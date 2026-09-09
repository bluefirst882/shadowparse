# 本地视频解析工作台

单机、单用户的视频解析工作台。视频仅保留在本机；可选的内容生成只发送转写文本。

## 开发环境

- Java 21、MySQL 8.0+、Python 3.11+、FFmpeg、Node.js 20+
- 将 `FFMPEG_PATH` 指向 FFmpeg 可执行文件（默认 `ffmpeg`）；可用 `MYSQL_*` 与 `WORKBENCH_STORAGE_DIR` 配置数据存储和本地文件位置。
- Windows Anaconda 环境中，`workers/whisper_worker.py` 会处理 NumPy/PyTorch 的 OpenMP 兼容设置；仍需要通过 `FFMPEG_PATH` 提供 FFmpeg。
- 安装本地 Whisper：`pip install openai-whisper`

## 启动

1. 复制 `.env.example` 为 `.env`，填写 `MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD` 和摘要服务配置。
2. 使用本地 MySQL 创建 `video_workbench` 数据库及账号，或执行 `docker compose up -d mysql`。
3. `./mvnw -f backend/pom.xml spring-boot:run`
4. `npm --prefix frontend install && npm --prefix frontend run dev`

前端开发服务器默认位于 `http://localhost:5173`，后端位于 `http://localhost:8080`。Flyway 会在后端首次连接 MySQL 时自动执行表迁移。

## 原型截图

`npm run screenshot` 会用系统 Chrome 截取 `video-workbench-prototype.html`，产物写入 `artifacts/`。

详细范围见 [docs/需求说明.md](docs/需求说明.md)，编码规则见 [docs/编码规范.md](docs/编码规范.md)。
