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
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

# 一次只跑一个转写。GPU 上并发转写会互相抢显存（实测两个实例同时调用直接 CUDA out of memory），
# CPU 上并发也只会互相抢核，串行反而总吞吐更高；排队等锁比直接失败更符合「任务会重试」的语义。
_TRANSCRIBE_LOCK = threading.Lock()
# 已加载的模型按 (模型名, 设备) 缓存，避免每个请求都重新加载一遍权重。
_MODELS = {}
_MODELS_LOCK = threading.Lock()


def load_model(model_name, model_dir, device):
    with _MODELS_LOCK:
        key = (model_name, device)
        if key not in _MODELS:
            import whisper

            with contextlib.redirect_stdout(sys.stderr):
                _MODELS[key] = whisper.load_model(
                    model_name, download_root=model_dir, device=device
                )
        return _MODELS[key]


def transcribe(audio, model_name, model_dir, language):
    os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")
    import torch

    device = "cuda" if torch.cuda.is_available() else "cpu"
    model = load_model(model_name, model_dir, device)
    with _TRANSCRIBE_LOCK:
        with contextlib.redirect_stdout(sys.stderr):
            result = model.transcribe(
                audio,
                language=language,
                fp16=device == "cuda",
                verbose=False,
            )
    segments = [
        {"start": item["start"], "end": item["end"], "text": item["text"].strip()}
        for item in result["segments"]
        if item["text"].strip()
    ]
    return {"language": result.get("language"), "segments": segments, "device": device}


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
                    self.headers.get("X-Whisper-Language") or None,
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
    import torch

    model_name = os.environ.get("WHISPER_MODEL", "turbo")
    model_dir = os.environ.get("WHISPER_MODEL_DIR", "models/whisper")
    device = "cuda" if torch.cuda.is_available() else "cpu"
    # 绑定端口前先加载模型：加载失败就让进程直接退出（容器随后按 restart 策略重启并留下明确报错），
    # 而不是端口「健康」、每次转写却都 500；也顺带省掉「每个请求重新加载模型」的开销。
    load_model(model_name, model_dir, device)
    print(f"Whisper model ready: {model_name} on {device}", file=sys.stderr)

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
