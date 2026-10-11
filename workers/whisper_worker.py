#!/usr/bin/env python3
"""Local Whisper worker. Emits timestamped segments as JSON; never sends media remotely."""
import argparse
import contextlib
import hmac
import json
import os
import sys
import tempfile
import threading

# numpy 是 openai-whisper 的硬依赖，容器里必然存在；这里直接顶层导入，
# VAD 的能量计算（vad_regions）要用它。
import numpy as np
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

# 一次只跑一个转写。GPU 上并发转写会互相抢显存（实测两个实例同时调用直接 CUDA out of memory），
# CPU 上并发也只会互相抢核，串行反而总吞吐更高；排队等锁比直接失败更符合「任务会重试」的语义。
_TRANSCRIBE_LOCK = threading.Lock()
# 已加载的模型按 (模型名, 设备) 缓存，避免每个请求都重新加载一遍权重。
_MODELS = {}
_MODELS_LOCK = threading.Lock()


def resolve_device():
    """按 WHISPER_DEVICE 决定推理设备：auto（默认）= 有 CUDA 就用；cuda / cpu = 强制指定。

    显式要求 cuda 但 torch 看不到 GPU 时直接抛错而不是悄悄回落 CPU——
    配了 GPU 的机器静默降级，会让人误以为已经在用 GPU（/health 与转写结果里的 device 字段
    都如实上报，但没人会主动去看）。"""
    import torch

    requested = os.environ.get("WHISPER_DEVICE", "auto").lower()
    if requested == "cpu":
        return "cpu"
    if requested == "cuda":
        if not torch.cuda.is_available():
            raise RuntimeError(
                "WHISPER_DEVICE=cuda 但 torch.cuda.is_available() 为 False："
                "检查容器是否拿到 GPU（需要宿主机 nvidia-container-toolkit 与 compose.yaml 里 "
                "whisper 服务的设备预留）"
            )
        return "cuda"
    if requested == "auto":
        return "cuda" if torch.cuda.is_available() else "cpu"
    raise RuntimeError(f"WHISPER_DEVICE 只支持 auto / cuda / cpu，实际为 {requested!r}")


def load_model(model_name, model_dir, device):
    with _MODELS_LOCK:
        key = (model_name, device)
        if key not in _MODELS:
            from faster_whisper import WhisperModel

            # 精度按设备选默认：GPU 用 int8_float16（省显存且几乎不掉精度），
            # CPU 用 int8；可用 WHISPER_COMPUTE_TYPE 覆盖（如 float16 / int8_float16 / int8）。
            default_ct = "int8_float16" if device == "cuda" else "int8"
            compute_type = os.environ.get("WHISPER_COMPUTE_TYPE") or default_ct
            with contextlib.redirect_stdout(sys.stderr):
                _MODELS[key] = WhisperModel(
                    model_name,
                    device=device,
                    compute_type=compute_type,
                    download_root=model_dir,
                )
        return _MODELS[key]


SR = 16000


def _env_float(name, default):
    try:
        return float(os.environ.get(name, default))
    except ValueError:
        return float(default)


def vad_regions(audio):
    """按能量找出「有声音」的时间区间，返回 [(start_sec, end_sec)]。

    Whisper 遇到「样本值恒为 0」的音频段（录屏软件在无声音时普遍这么写，ffmpeg 抽出来的
    wav 里就是长串纯零）会当成病态输入：mel 被 clamp 成常数，no_speech_prob 反而输出 0
    （等于「100% 有语音」），于是解码出一串幻觉文本，再被 condition_on_previous_text
    复制到后面每一个窗口——整段后半部分变成同一个词就是这个链路。这种输入靠 Whisper 自带的
    no_speech_threshold / hallucination_silence_threshold 拦不住，只能在送进模型前先按能量切掉。
    """
    frame = int(0.02 * SR)  # 20ms 一帧
    usable = len(audio) // frame * frame
    if usable == 0:
        return []
    frames = audio[:usable].reshape(-1, frame)
    rms = (frames.astype("float64") ** 2).mean(axis=1) ** 0.5
    db = 20 * np.log10(rms + 1e-10)
    voiced = db > _env_float("WHISPER_VAD_THRESHOLD_DB", -45)

    regions = []
    start = None
    for index, is_voiced in enumerate(voiced):
        at = index * frame / SR
        if is_voiced and start is None:
            start = at
        elif not is_voiced and start is not None:
            regions.append([start, at])
            start = None
    if start is not None:
        regions.append([start, usable / SR])

    gap = _env_float("WHISPER_VAD_MERGE_GAP", 1.0)
    merged = []
    for region in regions:
        # 相邻两句话之间的短暂停顿不该拆成两次转写：拆开会让模型丢掉上下文。
        if merged and region[0] - merged[-1][1] < gap:
            merged[-1][1] = region[1]
        else:
            merged.append(list(region))

    pad = _env_float("WHISPER_VAD_PAD", 0.3)
    min_speech = _env_float("WHISPER_VAD_MIN_SPEECH", 0.5)
    total = len(audio) / SR
    kept = []
    for start, end in merged:
        start = max(0.0, start - pad)
        end = min(total, end + pad)
        if end - start >= min_speech:
            kept.append((start, end))
    return kept


def _split_long(regions):
    """把超过 max_chunk 的区间切开，避免单个区间太长时模型注意力被稀释。"""
    limit = _env_float("WHISPER_VAD_MAX_CHUNK", 30)
    out = []
    for start, end in regions:
        while end - start > limit * 1.5:
            out.append((start, start + limit))
            start += limit
        out.append((start, end))
    return out


def _is_hallucination(segment, duration):
    """用模型自己给出的置信度指标兜底：压缩比过高 / 平均对数概率过低 = 模型在瞎编。"""
    compression = segment.get("compression_ratio")
    logprob = segment.get("avg_logprob")
    if compression is not None and compression > _env_float("WHISPER_MAX_COMPRESSION_RATIO", 2.4):
        return True
    if logprob is not None and logprob < _env_float("WHISPER_MIN_AVG_LOGPROB", -1.0):
        return True
    # 一句话横跨半分钟还不断句，正常语音几乎不会这样。
    return duration > _env_float("WHISPER_MAX_SEGMENT_SECONDS", 30)


def _drop_repeat_loops(segments):
    """丢掉「同一个句子连续重复」的尾巴——这是幻觉最典型的外形。

    静音窗口被解码成一句话后，condition_on_previous_text 会把它原样复制到后面每个窗口，
    于是出现十几段一字不差的文本。真实对话里同一个长句连说三遍以上基本不存在，
    所以只按「连续」计数、且要求重复次数达标才动手，短句（嗯、好的）不受影响。
    """
    if len(segments) < 3:
        return segments, 0
    kept = [segments[0]]
    run = 1
    dropped = 0
    for current in segments[1:]:
        if current["text"] == kept[-1]["text"]:
            run += 1
            if run >= 3:
                dropped += 1
                continue
        else:
            run = 1
        kept.append(current)
    return kept, dropped


def _word_dicts(words, offset, span, lo=None, hi=None):
    """把 faster-whisper 的词级结果平移到绝对时间轴；无词级时返回空列表。

    lo/hi 给定时再把词钳进所属段的 [lo, hi] 区间：
    faster-whisper 的段边界来自时间戳 token，而词级时间来自 cross-attention 对齐，
    两者会差出几十到几百毫秒，首词有时会落在所属段开始之前。下游（前端词高亮与
    点击定位、以及任何按「段内的词」做切分的地方）都依赖
    「词一定落在所属段的时间区间内」这个不变量，所以在这里兜住。
    """
    out = []
    if not words:
        return out
    for w in words:
        ws = offset + min(float(w.start), span)
        we = offset + min(float(w.end), span)
        if lo is not None and ws < lo:
            ws = lo
        if hi is not None and we > hi:
            we = hi
        # 钳到区间外导致时间塌缩的词（起止相等）直接丢掉，避免出现零长度词。
        if we > ws:
            out.append({"start": round(ws, 3), "end": round(we, 3), "text": w.word})
    return out


def _segment_dict(item, offset, span):
    """把 faster-whisper 的 Segment 转成内部统一格式（含词级时间戳），时间已平移到绝对轴。"""
    seg_start = offset + min(float(item.start), span)
    seg_end = offset + min(float(item.end), span)
    return {
        "start": seg_start,
        "end": seg_end,
        "text": item.text.strip(),
        # 词级时间钳到所属段内，保证下游「词 ∈ 段」的不变量成立（见 _word_dicts 注释）。
        "words": _word_dicts(
            getattr(item, "words", None), offset, span, lo=seg_start, hi=seg_end
        ),
        "compression_ratio": getattr(item, "compression_ratio", None),
        "avg_logprob": getattr(item, "avg_logprob", None),
    }


def _public_segment(seg):
    """对外输出的段格式：段级时间戳 + 词级时间戳（speaker 由说话人分离步骤补）。"""
    return {
        "start": seg["start"],
        "end": seg["end"],
        "text": seg["text"],
        "words": seg["words"],
    }


def transcribe_regions(model, audio, regions, language, device, word_ts):
    """只把有声区间送进模型，时间戳按区间起点平移回去；可选输出词级时间戳。

    每个区间都短于 30s 时 Whisper 会静默补零到 30s，并把时间戳标到补出来的静音上，
    所以要把每段结果裁回区间的实际长度，否则相邻区间的时间戳会互相重叠。
    """
    segments = []
    detected = None
    for start, end in regions:
        chunk = audio[int(start * SR) : int(end * SR)]
        if len(chunk) < SR // 10:
            continue
        with contextlib.redirect_stdout(sys.stderr):
            # 注意：faster-whisper 的 transcribe 没有 openai-whisper 的 verbose 参数
            # （对应的是 log_progress），传了会直接 TypeError。
            seg_gen, info = model.transcribe(
                chunk,
                language=language,
                word_timestamps=word_ts,
            )
        detected = detected or (info.language if info else None)
        span = len(chunk) / SR
        for item in seg_gen:
            if not item.text.strip():
                continue
            seg = _segment_dict(item, start, span)
            if seg["end"] <= seg["start"]:
                continue
            if _is_hallucination(seg, seg["end"] - seg["start"]):
                continue
            segments.append(_public_segment(seg))
    return segments, detected


# ---- 说话人分离（可选，pyannote；需 HF token + 接受 pyannote/speaker-diarization-3.1 许可）----
_DIARIZATION_MODEL = "pyannote/speaker-diarization-3.1"
_diarization_pipeline = None
_diarization_lock = threading.Lock()


def _diarize(audio_path, hf_token):
    """用 pyannote 跑说话人分离，返回 [(start_sec, end_sec, speaker)]；失败抛异常由调用方降级。"""
    global _diarization_pipeline
    if _diarization_pipeline is None:
        with _diarization_lock:
            if _diarization_pipeline is None:
                from pyannote.audio import Pipeline

                model = os.environ.get("WHISPER_DIARIZATION_MODEL", _DIARIZATION_MODEL)
                _diarization_pipeline = Pipeline.from_pretrained(model, use_auth_token=hf_token)
    diarization = _diarization_pipeline(audio_path)
    turns = []
    for turn, _, speaker in diarization.itertracks(yield_label=True):
        turns.append((float(turn.start), float(turn.end), speaker))
    return turns


def _speaker_for(turns, start, end):
    best, best_overlap = None, 0.0
    for ts, te, spk in turns:
        overlap = min(end, te) - max(start, ts)
        if overlap > best_overlap:
            best_overlap, best = overlap, spk
    return best


def _assign_speakers(segments, turns):
    out = []
    for seg in segments:
        seg = dict(seg)
        seg["speaker"] = _speaker_for(turns, seg["start"], seg["end"])
        if seg.get("words"):
            seg["words"] = [
                {**w, "speaker": _speaker_for(turns, w["start"], w["end"])} for w in seg["words"]
            ]
        out.append(seg)
    return out


def _finalize(audio_path, segments, language, vad_info):
    """收尾：可选说话人分离（失败降级为无说话人）+ 组装返回体。"""
    hf_token = os.environ.get("WHISPER_HF_TOKEN")
    diarize_on = os.environ.get("WHISPER_DIARIZE", "1") not in ("0", "false", "False")
    if diarize_on and hf_token and segments:
        try:
            turns = _diarize(audio_path, hf_token)
            segments = _assign_speakers(segments, turns)
        except Exception as ex:  # 分离失败不影响转写主产物
            print(f"whisper: 说话人分离失败，产物不含说话人：{ex}", file=sys.stderr)
    result = {"language": language, "segments": segments, "device": resolve_device()}
    if vad_info is not None:
        result["vad"] = vad_info
    return result


def transcribe(audio_path, model_name, model_dir, language):
    os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")
    device = resolve_device()
    model = load_model(model_name, model_dir, device)
    use_vad = os.environ.get("WHISPER_VAD", "1") not in ("0", "false", "False")
    word_ts = os.environ.get("WHISPER_WORD_TIMESTAMPS", "1") not in ("0", "false", "False")
    vad_info = None
    detected = None
    segments = []

    if not use_vad:
        with _TRANSCRIBE_LOCK:
            with contextlib.redirect_stdout(sys.stderr):
                seg_gen, info = model.transcribe(
                    audio_path, language=language, word_timestamps=word_ts
                )
                for item in seg_gen:
                    if not item.text.strip():
                        continue
                    seg = _segment_dict(item, 0, float("inf"))
                    if seg["end"] <= seg["start"]:
                        continue
                    segments.append(_public_segment(seg))
        detected = (info.language if info else None) or language
    else:
        from faster_whisper.audio import decode_audio  # noqa: PLC0415

        audio = decode_audio(audio_path)
        with _TRANSCRIBE_LOCK:
            regions = _split_long(vad_regions(audio))
            if not regions:
                return _finalize(
                    audio_path, [], language, {"regions": 0, "speech_ratio": 0.0}
                )
            if language is None:
                # 只在有声区间上做语言检测：拿整段（含大片纯零）去检测会被带偏成 en，
                # 这是「中文视频后半段整段变成 Thank you.」的另一半原因。
                # faster-whisper 的 detect_language 直接吃原始音频数组，内部完成 mel 计算。
                probe = np.concatenate(
                    [audio[int(s * SR) : int(e * SR)] for s, e in regions[:8]]
                )
                # faster-whisper 的 detect_language 返回
                # (language, language_probability, all_language_probs) 三元组，
                # 与 openai-whisper 的二元组不同；这里按索引取语言，
                # 避免元组长度变化再次触发 "too many values to unpack"。
                detected_lang = model.detect_language(probe)[0]
                language = detected_lang
            segments, detected = transcribe_regions(
                model, audio, regions, language, device, word_ts
            )
            speech = sum(e - s for s, e in regions)
            vad_info = {
                "regions": len(regions),
                "speech_seconds": round(speech, 2),
                "speech_ratio": round(speech / (len(audio) / SR), 4),
            }
    segments, dropped = _drop_repeat_loops(segments)
    if vad_info is not None:
        vad_info["dropped_repeats"] = dropped
    return _finalize(audio_path, segments, detected or language, vad_info)


class WorkerHandler(BaseHTTPRequestHandler):
    server_version = "ToniWhisper/1.0"

    def do_GET(self):
        if self.path != "/health":
            self.send_error(404)
            return
        # 模型在启动时已加载完成，所以 /health 返回 ok 即代表「设备可用且模型已就绪」，
        # 而不只是「HTTP 端口还活着」。
        self.respond(
            200,
            {"status": "ok", "device": self.server.device, "model": self.server.model_name},
        )

    def do_POST(self):
        if self.path != "/transcribe":
            self.send_error(404)
            return
        supplied = self.headers.get("X-Whisper-Token", "").encode()
        if not hmac.compare_digest(supplied, self.server.token.encode()):
            self.send_error(401)
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size <= 0 or size > self.server.max_upload_bytes:
                self.send_error(413, "invalid audio size")
                return
            remaining = size
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as audio:
                while remaining:
                    chunk = self.rfile.read(min(1024 * 1024, remaining))
                    if not chunk:
                        raise ConnectionError("request body ended before Content-Length")
                    audio.write(chunk)
                    remaining -= len(chunk)
                audio_path = audio.name
            try:
                payload = transcribe(
                    audio_path,
                    self.headers.get("X-Whisper-Model") or self.server.model_name,
                    self.server.model_dir,
                    self.headers.get("X-Whisper-Language") or self.server.default_language,
                )
            finally:
                Path(audio_path).unlink(missing_ok=True)
            self.respond(200, payload)
        except Exception as error:
            self.respond(500, {"error": str(error)})

    def respond(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print("whisper: " + fmt % args, file=sys.stderr)


def serve():
    token = os.environ.get("WHISPER_SERVICE_TOKEN", "")
    if len(token) < 32:
        raise RuntimeError("WHISPER_SERVICE_TOKEN must contain at least 32 characters")
    model_name = os.environ.get("WHISPER_MODEL", "turbo")
    model_dir = os.environ.get("WHISPER_MODEL_DIR", "models/whisper")
    import torch

    device = resolve_device()
    # 绑定端口前先加载模型：加载失败就让进程直接退出（容器随后按 restart 策略重启并留下明确报错），
    # 而不是端口「健康」、每次转写却都 500；也顺带省掉「每个请求重新加载模型」的开销。
    load_model(model_name, model_dir, device)
    gpu_name = f" ({torch.cuda.get_device_name(0)})" if device == "cuda" else ""
    print(f"Whisper model ready: {model_name} on {device}{gpu_name}", file=sys.stderr)

    server = ThreadingHTTPServer(
        (os.environ.get("WHISPER_HOST", "0.0.0.0"), int(os.environ.get("WHISPER_PORT", "8090"))),
        WorkerHandler,
    )
    server.daemon_threads = True
    server.token = token
    server.model_name = model_name
    server.model_dir = model_dir
    server.max_upload_bytes = int(os.environ.get("WHISPER_MAX_UPLOAD_BYTES", str(2 * 1024**3)))
    server.device = device
    # 全局默认语言：留空（默认）= 每次转写在有声区间上自动检测。
    # 设成 zh / en 会钉死解码语言——素材语种单一时能避免检测被带偏，但混语种视频会被按一种语言硬解，
    # 所以只在确实需要时才配。单个请求仍可用 X-Whisper-Language 覆盖。
    server.default_language = os.environ.get("WHISPER_LANGUAGE") or None
    print(f"Whisper service listening on {server.server_address}; device={device}", file=sys.stderr)
    server.serve_forever()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serve", action="store_true")
    parser.add_argument("audio", nargs="?")
    parser.add_argument("--model", default=os.environ.get("WHISPER_MODEL", "turbo"))
    parser.add_argument("--model-dir", default=os.environ.get("WHISPER_MODEL_DIR", "models/whisper"))
    parser.add_argument("--language", default=os.environ.get("WHISPER_LANGUAGE"))
    args = parser.parse_args()
    try:
        if args.serve:
            serve()
        elif args.audio:
            print(json.dumps(transcribe(args.audio, args.model, args.model_dir, args.language), ensure_ascii=False))
        else:
            parser.error("provide --serve or an audio file")
    except Exception as error:
        print(f"Whisper 执行失败: {error}", file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
