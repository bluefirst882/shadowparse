#!/usr/bin/env python3
"""Local Whisper worker. Emits timestamped segments as JSON; never sends media remotely."""
import argparse
import json
import sys

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("audio")
    parser.add_argument("--model", default="turbo")
    args = parser.parse_args()
    try:
        import whisper
        result = whisper.load_model(args.model).transcribe(args.audio)
        print(json.dumps({"segments": [{"start": s["start"], "end": s["end"], "text": s["text"].strip()} for s in result["segments"]]}, ensure_ascii=False))
    except Exception as error:
        print(f"Whisper 执行失败: {error}", file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())

