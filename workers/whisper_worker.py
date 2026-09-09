#!/usr/bin/env python3
"""Local Whisper worker. Emits timestamped segments as JSON; never sends media remotely."""
import argparse
import contextlib
import json
import os
import sys

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("audio")
    parser.add_argument("--model", default="turbo")
    parser.add_argument("--model-dir", default=os.environ.get("WHISPER_MODEL_DIR", "models/whisper"))
    parser.add_argument("--language", default=None)
    args = parser.parse_args()
    try:
        # Anaconda's NumPy and PyTorch can load separate OpenMP runtimes on Windows.
        os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")
        import whisper
        import torch

        print(f"正在加载 Whisper {args.model} 模型", file=sys.stderr)
        device = "cuda" if torch.cuda.is_available() else "cpu"
        print(f"使用推理设备 {device}", file=sys.stderr)
        # Keep stdout reserved for the final JSON payload consumed by the backend.
        with contextlib.redirect_stdout(sys.stderr):
            model = whisper.load_model(args.model, download_root=args.model_dir, device=device)
            result = model.transcribe(
                args.audio,
                language=args.language,
                fp16=device == "cuda",
                verbose=False,
            )
        segments = [
            {"start": segment["start"], "end": segment["end"], "text": segment["text"].strip()}
            for segment in result["segments"]
            if segment["text"].strip()
        ]
        print(
            json.dumps(
                {"language": result.get("language"), "segments": segments},
                ensure_ascii=False,
            )
        )
    except Exception as error:
        print(f"Whisper 执行失败: {error}", file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
