# Whisper 推理服务镜像：把原来跑在宿主机 Python venv 里的 worker 收进容器，
# 由 compose 负责健康检查与重启（见 README「Whisper 容器化」）。
#
# 默认装 CPU 版 PyTorch：镜像不依赖 NVIDIA 容器运行时，任何机器都能起来，
# /health 会如实报告 device=cpu（模型权重靠挂载复用，不需要重新下载）。
# 需要 GPU 时用 build args 换成 CUDA 版 torch，再给服务加 GPU 预留（需 nvidia-container-toolkit）：
#   docker compose build whisper --build-arg TORCH_INDEX=https://download.pytorch.org/whl/cu124 \
#                                --build-arg TORCH_VERSION=2.5.1+cu124
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
COPY workers/whisper_worker.py /app/whisper_worker.py
COPY workers/runtime-requirements.txt /tmp/runtime-requirements.txt

# 分步安装，避免在 Debian 上把整坨 CUDA 运行库和 triton 拉进来（镜像体积差一个量级）：
# 1) torch 走 PyTorch 官方索引（cpu 或 cu124，由 build args 决定）；
# 2) torch 与 numba 的纯 Python 依赖（runtime-requirements.txt）+ numba/numpy 本身；
# 3) whisper 依赖里剩下几个；
# 4) openai-whisper 本体用 --no-deps 装入：它的依赖上面都齐了，只跳过 Linux x86_64 上的 triton
#    （triton 只在 whisper 的 DTW 加速里被可选导入，缺失会走 PyTorch 实现）。
RUN pip install --index-url ${TORCH_INDEX} torch==${TORCH_VERSION} \
    && pip install -r /tmp/runtime-requirements.txt numba==0.61.2 numpy==2.1.3 \
    && pip install tiktoken==0.9.0 tqdm==4.67.1 more-itertools==10.8.0 regex==2024.11.6 \
    && pip install --no-deps openai-whisper==20250625 \
    && python -c "import torch, whisper; print('torch', torch.__version__, 'cuda', torch.cuda.is_available())"

HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=20 \
    CMD curl -fsS http://127.0.0.1:8090/health || exit 1

EXPOSE 8090
ENTRYPOINT ["python", "-u", "/app/whisper_worker.py", "--serve"]
