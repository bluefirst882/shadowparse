# 本地视频 AI 解析工作台

面向内容创作者的**本地化**视频 AI 解析工作台。导入视频后自动完成音频抽取和语音转写；
转写完成后可手动触发摘要与章节生成，输出可跳转的带时间戳文稿。

设计上**视频与音频全程保留在本机**，仅将纯文本转写结果发送至云端内容服务，
兼顾隐私与成本；未配置云端密钥时，本地转写能力仍然完整可用。

## 项目职责与已验证成果

个人主要负责视频导入、任务编排、FFmpeg 音频抽取、Whisper 转写、LLM 结果校验、
结果导出及 Vue 前端接口联调，完成从上传到转写、播放定位和导出的后端核心流程与持久化设计。

- 设计 `IMPORT → AUDIO_EXTRACTION → TRANSCRIPTION → SUMMARY → COMPLETED` 分阶段任务流程，使用 MySQL 持久化任务状态和转写片段，使处理进度、失败信息和已有产物可被查询。
- 使用 `ProcessBuilder` 托管 FFmpeg 与 Whisper，分别异步消费 stdout/stderr，并加入超时终止和退出码诊断，避免外部进程因管道缓冲区写满而阻塞。
- 对 LLM 返回的摘要、要点和章节执行服务端业务校验，检查章节来源片段、时间范围、顺序和重叠，避免直接信任模型生成的时间轴。
- 在 2 个真实视频样本上完成本地转写验证：英文样本完整转写持久化 42 个带时间戳片段；中文样本（355.947 秒）持久化 250 个带时间戳片段，实测从上传到完成约 58 秒。英文样本的 Range 请求返回 `206 Partial Content`，Markdown、JSON、SRT 三种导出均成功。

上述耗时和片段数是样本结果，不代表通用准确率或并发性能；中文样本还发现 Whisper 尾部输出存在超出媒体时长的片段，当前记录为待进一步收敛的验证发现。

---

## 核心流程

```
视频导入 → FFmpeg 音频抽取 → Whisper 语音转写 → 手动生成摘要/章节 → 结果校验 → 多格式导出
              │                    │                    │                │
          16kHz 单声道        turbo 模型（GPU）     JSON 结构化输出   章节时间轴校验
```

---

## 技术栈

| 层次        | 技术                                                 |
| ----------- | ---------------------------------------------------- |
| 后端        | Java 21、Spring Boot 3.5、Spring JDBC、Maven         |
| 数据库      | MySQL 8 + Flyway 版本迁移                            |
| AI / 音视频 | Whisper（turbo）、FFmpeg、LLM API（结构化输出）      |
| 前端        | Vue 3、TypeScript、Vite、Element Plus                |
| 工程化      | JUnit、Spotless + google-java-format、Docker Compose |

---

## 技术要点

### 0. 前端按需加载

仅注册工作台实际使用的 Element Plus 组件、指令及样式，避免全量 UI 库进入首屏包。
当前生产构建产物为约 356 KB JavaScript 和 96 KB CSS（未压缩），用于降低本地工作台首次加载体积。

### 1. 异步任务编排与故障恢复

以**有界单线程队列**串行推进任务，用 `ConcurrentHashMap` 持有运行中的外部进程句柄，
以保护本地推理资源；队列满时新任务仍保存为 `QUEUED` 并返回可重试提示。任务通过数据库条件更新原子领取，
避免重复入队导致同一视频被并行处理。

任务按阶段推进，每个阶段独立可观测：

| 阶段               | 进度 | 说明                           |
| ------------------ | ---- | ------------------------------ |
| `IMPORT`           | 0%   | 视频导入与校验                 |
| `AUDIO_EXTRACTION` | 10%  | FFmpeg 抽取 16kHz 单声道音频   |
| `TRANSCRIPTION`    | 45%  | Whisper 转写并落库带时间戳片段 |
| `SUMMARY`          | 85%  | LLM 生成摘要、要点与章节       |
| `COMPLETED`        | 100% | 完成                           |

关键设计：

- **重试边界**：已保留转写的任务可单独重试摘要；其他失败任务从本地处理重新开始。
- **重启恢复**：服务启动时将残留的 `PROCESSING` 任务改回 `QUEUED` 并尝试入队；队列满时任务保留为 `QUEUED`，可在队列排空后重试。
- **产物一致性**：任务记录写入失败时回收刚上传的视频；删除任务时若本地视频或音频清理失败，则保留任务记录并返回错误，避免产生不可追踪的本地文件。
- **转写与摘要解耦**：未配置云端密钥时任务以「本地转写已完成」状态保留，
  配置后可单独重试内容生成，不丢失已完成的转写结果

### 2. 外部进程托管

FFmpeg 与 Whisper 推理均为外部进程，通过 `ProcessBuilder` 托管：

- **独立线程异步消费 stdout / stderr**：避免管道缓冲区写满导致子进程阻塞死锁
- **超时强杀**：超过配置时限自动 `destroyForcibly`
- **退出码诊断**：失败时截取 stderr 尾部作为错误信息回传前端
- stdout 专门用于承载结构化 JSON，推理过程中的日志统一重定向到 stderr，
  保证父进程解析协议不被污染

### 3. LLM 结构化输出与可信校验

大模型的输出不可全信，尤其是时间轴。因此：

- 约束模型返回 JSON：`{summary, keyPoints[], chapters[{startMs, endMs, title, sourceSegmentId, sourceEndSegmentId}]}`。章节可覆盖连续转写片段，首尾片段 ID 用于服务端时间范围校验。
- 每个章节**必须声明其起始和结束来源转写片段 id**；单片段章节的两个 id 相同
- 服务端逐条校验：章节时间必须分别落在首、尾来源片段的起止范围内，尾片段不得早于首片段，章节时间不得与上一章节重叠、标题非空
- 校验失败则整体失败并可重试，**本地转写结果始终保留**
- 非中文视频在转写后自动生成中文译文，便于与摘要对照阅读

这样既获得了大模型的表达能力，又不把时间轴的正确性交给模型。

### 4. 视频流式播放

手写 HTTP Range 处理，基于 `FilterInputStream` 对响应体精确限流，
返回 `206 Partial Content` 与 `Content-Range` 头，
支撑前端播放器拖拽跳转，并与转写片段的时间轴双向联动定位。

### 5. 结果导出

支持三种格式导出：**Markdown**（摘要 + 要点 + 带时间戳全文）、
**JSON**（完整任务数据）、**SRT**（标准字幕格式，可直接用于视频压制）。

---

## 项目结构

```
.
├── backend/                         # Spring Boot 后端
│   └── src/main/
│       ├── java/ai/toni/videoworkbench/
│       │   ├── TaskController.java       # REST 接口
│       │   ├── TaskService.java          # 任务编排与外部进程托管
│       │   ├── TaskRepository.java       # JDBC 数据访问
│       │   ├── CoderplanClient.java      # LLM 调用（摘要 / 翻译）
│       │   ├── ResultValidator.java      # 章节时间轴可信校验
│       │   ├── ExportService.java        # Markdown / JSON / SRT 导出
│       │   └── TaskRecovery.java         # 重启后任务恢复
│       └── resources/
│           ├── application.yml
│           └── db/migration/             # Flyway 迁移脚本
├── frontend/                        # Vue 3 + TypeScript 工作台界面
├── workers/whisper_worker.py        # Whisper 推理进程（输出 JSON）
├── docs/                            # 需求说明、编码规范、验收记录
└── compose.yaml                     # MySQL 容器
```

---

## REST 接口

| 方法     | 路径                              | 说明                         |
| -------- | --------------------------------- | ---------------------------- |
| `GET`    | `/api/tasks`                      | 任务列表                     |
| `POST`   | `/api/tasks`                      | 导入视频（multipart）        |
| `GET`    | `/api/tasks/{id}/details`         | 任务详情（含转写与摘要结果） |
| `POST`   | `/api/tasks/{id}/cancel`          | 取消任务                     |
| `POST`   | `/api/tasks/{id}/retry`           | 重试摘要或重新执行本地处理   |
| `POST`   | `/api/tasks/{id}/retranscribe`    | 重新转写                     |
| `GET`    | `/api/tasks/{id}/video`           | 视频流（支持 Range）         |
| `GET`    | `/api/tasks/{id}/export/{format}` | 导出 md / json / srt         |
| `DELETE` | `/api/tasks/{id}`                 | 删除任务、视频及提取的音频   |

---

## 数据库结构

Flyway 自动迁移，共 3 张表：

- `tasks` —— 任务主表（状态、阶段、进度、错误信息、取消标记）
- `transcript_segments` —— 带起止毫秒的转写片段，含译文列
- `task_results` —— 摘要、要点 JSON、章节 JSON

外键均为 `ON DELETE CASCADE`，删除任务时自动清理关联数据。

---

## 快速开始

### 环境要求

- Java 21、Node.js 20+、Python 3.11+
- MySQL 8.0+
- FFmpeg（需在 `PATH` 中，或通过 `FFMPEG_PATH` 指定）
- 本地 Whisper：`pip install openai-whisper`

### 1. 配置

```bash
cp .env.example .env
```

填写 `MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`；如需云端摘要能力，
再填 `CODERPLAN_API_KEY`（**不填也可正常完成本地转写**）。

### 2. 数据库

```bash
docker compose up -d mysql
```

或使用本地 MySQL 手动创建 `video_workbench` 库与账号。
Flyway 会在后端首次连接时自动执行迁移。

### 3. 启动后端

```bash
./mvnw -f backend/pom.xml spring-boot:run     # Windows: mvnw.cmd
```

后端监听 `http://localhost:8080`。

### 4. 启动前端

```bash
npm --prefix frontend install
npm --prefix frontend run dev
```

前端监听 `http://localhost:5173`。

### 5. 运行测试与校验

```bash
./mvnw -f backend/pom.xml test          # JUnit
./mvnw -f backend/pom.xml verify        # 含 Spotless 代码风格校验
npm --prefix frontend run build         # vue-tsc 类型检查 + 生产构建
```

---

## 配置项

| 变量                                | 默认值      | 说明                                       |
| ----------------------------------- | ----------- | ------------------------------------------ |
| `WORKBENCH_STORAGE_DIR`             | `./storage` | 视频、音频与模型文件的本机存放目录         |
| `WORKBENCH_MAX_UPLOAD_BYTES`        | 20GB        | 单个视频大小上限                           |
| `WORKBENCH_PROCESS_TIMEOUT_MINUTES` | 30          | 外部进程超时时间                           |
| `FFMPEG_PATH`                       | `ffmpeg`    | FFmpeg 可执行文件路径                      |
| `WHISPER_MODEL`                     | `turbo`     | Whisper 模型规格                           |
| `CODERPLAN_API_KEY`                 | 空          | 云端内容服务密钥，留空则跳过摘要阶段       |
| `CODERPLAN_MAX_INPUT_CHARS`         | 60000       | 单次摘要请求的字符上限，按完整转写片段分批 |

> 密钥仅由后端读取，不会写入日志、前端响应或导出文件。

---

## 验收情况

已通过真实验收：Maven 编译与 JUnit、前端 `vue-tsc` 与生产构建、
MySQL 8.4 隔离容器中 Flyway 迁移执行，以及 1 个真实视频的完整导入与 Whisper 转写
（持久化 42 个时间戳片段）、Range 请求返回 `206`、三种格式导出成功。

已验证边界场景：损坏视频、无音轨视频、取消与重启恢复；长文本已通过按完整转写片段分批和单片段超限拒绝校验。
中文真实视频已补充：输入 `E:\Downloads\Video\27210678708-1-192.mp4`，媒体时长 355.947 秒，H.264/AAC；本地 Whisper `turbo`、RTX 4060 Laptop GPU，上传到任务完成约 58 秒，持久化 250 个片段，任务保留为 `COMPLETED/SUMMARY` 并提示摘要可单独重试。检查发现尾部若干片段超出媒体时长，未将该样本表述为时间轴校验通过。以上数据仅对应样本，不能外推为通用成功率或准确率。
详见 [docs/端到端验收记录.md](docs/端到端验收记录.md)。

---

## 更多文档

- [需求说明](docs/需求说明.md) —— 首版范围与非目标
- [编码规范](docs/编码规范.md) —— 代码风格约定
- [端到端验收记录](docs/端到端验收记录.md) —— 已验收项与待验证项
- [项目数据记录模板](docs/项目数据记录模板.md) —— 记录可回填简历的测试规模与结果
- [MySQL 部署说明](docs/MySQL-部署说明.md)
