# Whisper 推理服务镜像：把原来跑在宿主机 Python venv 里的 worker 收进容器，
# 由 compose 负责健康检查与重启（见 README「Whisper 容器化」）。
#
# 镜像由 compose.yaml 用 CUDA build args 构建（cu124 版 torch + GPU 设备预留），
# 只用 GPU 推理：容器没拿到 GPU 时 whisper_worker.py 启动即失败，绝不静默回落 CPU。
# 此处的 CPU 默认值只是「直接 docker build 不带参数时」的兜底，正常入口是 docker compose build。
FROM python:3.12-slim

ARG TORCH_INDEX=https://download.pytorch.org/whl/cpu
ARG TORCH_VERSION=2.5.1+cpu

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    WHISPER_MODEL_DIR=/models \
    WHISPER_PORT=8090 \
    OMP_NUM_THREADS=4

# whisper 解码音频要调 ffmpeg；curl 供 HEALTHCHECK 与手工排查使用。
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
# 只先拷贝依赖清单：worker 源码放在 pip 之后拷贝，改一行 Python 不会让下面这层缓存失效
# （否则每次改代码都要重装一遍几个 GB 的 CUDA torch）。
COPY workers/runtime-requirements.txt /tmp/runtime-requirements.txt

# 分步安装，避免一次性把整坨 CUDA 运行库和可选项拉进来（镜像体积差一个量级）：
# 1) torch 走 PyTorch 官方索引（cpu 或 cu124，由 build args 决定）；
# 2) runtime-requirements.txt 里的纯 Python 依赖 + numpy；
# 3) faster-whisper 本体（CTranslate2 后端，自带 ctranslate2 / onnxruntime / av 等依赖，
#    不再依赖 openai-whisper 的 triton / DTW，装起来更轻也更稳）。
#    av 必须 pin：faster-whisper 1.2.1 的 faster_whisper/audio.py 里调用
#    av.open(..., metadata_errors="ignore")，而 av >= 17 移除了该参数；
#    不 pin 的话 pip 会解析到最新 av，转写时 decode_audio 直接抛
#    TypeError: open() got an unexpected keyword argument 'metadata_errors'。
#    实测 16.0.0 仍支持该签名，17+ 不支持。
RUN pip install --index-url ${TORCH_INDEX} torch==${TORCH_VERSION} \
    && pip install -r /tmp/runtime-requirements.txt numpy==2.1.3 \
    && pip install tiktoken==0.9.0 tqdm==4.67.1 more-itertools==10.8.0 regex==2024.11.6 \
    && pip install av==16.0.0 faster-whisper==1.2.1 \
    && python -c "import torch, faster_whisper; print('torch', torch.__version__, 'cuda', torch.cuda.is_available())"

COPY workers/whisper_worker.py /app/whisper_worker.py

HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=20 \
    CMD curl -fsS http://127.0.0.1:8090/health || exit 1

EXPOSE 8090
ENTRYPOINT ["python", "-u", "/app/whisper_worker.py", "--serve"]
