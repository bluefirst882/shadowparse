# 本地视频 AI 解析工作台

面向内容创作者的**本地化**视频 AI 解析工作台。导入视频后自动完成音频抽取、语音转写与摘要/章节生成；
所有任务按账号隔离，登录后只能看到自己的任务。输出可跳转的带时间戳文稿。

设计上**视频与音频全程保留在本机**，仅将纯文本转写结果发送至云端内容服务，
兼顾隐私与成本；未配置云端密钥时，本地转写能力仍然完整可用。

## 项目职责与已验证成果

个人主要负责视频导入、任务编排、FFmpeg 音频抽取、Whisper 转写、LLM 结果校验、
结果导出及 Vue 前端接口联调，完成从上传到转写、播放定位和导出的后端核心流程与持久化设计。

- 设计 `IMPORT → AUDIO_EXTRACTION → TRANSCRIPTION → SUMMARY → COMPLETED` 分阶段任务流程，使用 MySQL 持久化任务状态和转写片段，使处理进度、失败信息和已有产物可被查询。
- 使用 `ProcessBuilder` 托管 FFmpeg，异步消费 stdout/stderr，并加入超时终止和退出码诊断，避免外部进程因管道缓冲区写满而阻塞；Whisper 改为常驻 HTTP 服务单独托管。
- 对 LLM 返回的摘要、要点和章节执行服务端业务校验，检查章节来源片段、时间范围、顺序与重叠，并用「引文逐字出自来源片段」的反幻觉闸门（`quote`）拦截编造内容，避免直接信任模型的输出。
- 在 2 个真实视频样本上完成本地转写验证：英文样本完整转写持久化 42 个带时间戳片段；中文样本（355.947 秒）持久化 250 个带时间戳片段，实测从上传到完成约 58 秒。英文样本的 Range 请求返回 `206 Partial Content`，Markdown、JSON、SRT 三种导出均成功。

上述耗时和片段数是样本结果，不代表通用准确率或并发性能；中文样本还发现 Whisper 尾部输出存在超出媒体时长的片段，当前记录为待进一步收敛的验证发现。

---

## 核心流程

```
视频导入 → 落库 QUEUED → 投递队列 → FFmpeg 音频抽取 → Whisper 语音转写 → 自动生成摘要/章节 → 结果校验 → 多格式导出
                                        │                    │                │
                                  16kHz 单声道      turbo 模型（容器内 CPU）  JSON 结构化输出   章节时间轴 + 引文校验
```

全链路图与关键取舍（ADR）见 [docs/架构与取舍.md](docs/架构与取舍.md)。

---

## 技术栈

| 层次        | 技术                                                                         |
| ----------- | ---------------------------------------------------------------------------- |
| 后端        | Java 21、Spring Boot 3.5、Spring JDBC、Maven                                 |
| 数据库      | MySQL 8 + Flyway 版本迁移                                                    |
| 消息队列    | RabbitMQ 4（持久化队列 + 手动 ack，幂等由数据库条件更新保证）                |
| AI / 音视频 | Whisper（turbo）、FFmpeg、LLM API（结构化输出）                              |
| 容错        | Resilience4j 2.3（并发隔板 + 熔断器，熔断状态可从 Actuator/Prometheus 观测） |
| 前端        | Vue 3、TypeScript、Vite、Element Plus                                        |
| 工程化      | JUnit、Spotless + google-java-format、Docker Compose                         |

---

## 项目结构

```text
.
├── backend/                 # Spring Boot 后端（任务编排、鉴权、队列、LLM、导出）
│   └── src/main/resources/
│       ├── prompts/         # 带版本号的提示词：<名称>.<版本>.<system|user>.txt
│       ├── schemas/         # JSON Schema 正文（Java 服务端与 Node 评测脚本共用）
│       └── db/migration/    # Flyway 迁移脚本
├── frontend/                # Vue 3 + TypeScript 工作台界面
├── workers/                 # Whisper 推理服务（HTTP 接口，容器内 GPU 推理）
├── docs/                    # 说明、验收记录与证据
│   ├── images/              # 面板与界面截图
│   └── prototype/           # 早期界面原型 HTML
├── tools/                   # 辅助脚本：capture/ 截图、verify/ 验收、measure/ 度量
├── ops/                     # 可观测性栈配置与 k6 压测脚本
└── compose.yaml             # 全栈一键编排
```

后端类的完整清单与职责见 [docs/技术要点.md](docs/技术要点.md) 与 [docs/架构与取舍.md](docs/架构与取舍.md)。

---

## REST 接口

除 `/api/auth/**` 外，所有接口都需要 `Authorization: Bearer <token>`；
未携带或令牌无效返回 `401`，访问他人任务返回 `403`。

| 方法     | 路径                                           | 说明                                                |
| -------- | ---------------------------------------------- | --------------------------------------------------- |
| `POST`   | `/api/auth/register`                           | 注册并返回令牌                                      |
| `POST`   | `/api/auth/login`                              | 登录并返回令牌                                      |
| `GET`    | `/api/tasks?cursor=&limit=`                    | 当前账号的任务列表（游标分页）                      |
| `POST`   | `/api/tasks`                                   | 导入视频（multipart 一次性上传）                    |
| `GET`    | `/api/tasks/stream`                            | 任务变化推送（SSE，令牌走 `access_token` 查询参数） |
| `POST`   | `/api/tasks/uploads`                           | 建立或继续分片上传会话（声明文件名与大小）          |
| `GET`    | `/api/tasks/uploads/{uploadId}`                | 查询会话已收到的分片                                |
| `PUT`    | `/api/tasks/uploads/{uploadId}/chunks/{index}` | 提交一片（`application/octet-stream`）              |
| `POST`   | `/api/tasks/uploads/{uploadId}/complete`       | 收齐后登记成任务                                    |
| `GET`    | `/api/tasks/{id}/details`                      | 任务详情（含转写与摘要结果）                        |
| `GET`    | `/api/tasks/{id}/cost`                         | 单视频 LLM 用量与成本（按提示词版本分行）           |
| `POST`   | `/api/tasks/{id}/cancel`                       | 取消任务                                            |
| `POST`   | `/api/tasks/{id}/retry`                        | 重试摘要或重新执行本地处理                          |
| `POST`   | `/api/tasks/{id}/retranscribe`                 | 重新转写                                            |
| `PATCH`  | `/api/tasks/{id}/name`                         | 任务改名（只改展示名，原文件名保留）                |
| `GET`    | `/api/tasks/{id}/video`                        | 视频流（支持 Range）                                |
| `GET`    | `/api/tasks/{id}/export/{format}`              | 导出 md / json / srt                                |
| `DELETE` | `/api/tasks/{id}`                              | 删除任务、视频及提取的音频                          |

`GET /api/tasks` 支持 `cursor`（不透明游标，省略即第一页）与 `limit`（默认 20、上限 100），
响应体为 `{"items":[...],"nextCursor":"..."}`，`nextCursor` 为 `null` 表示已到最后一页；详见上文「列表游标分页」。

`GET /api/tasks/stream` 是一次订阅、持续推送的 SSE 连接，推送内容只有任务号（`event: tasks` / `data: <taskId>`），
客户端据此重新取数；浏览器 `EventSource` 不能自定义请求头，所以这个端点额外接受 `access_token` 查询参数带令牌
（与 `/{id}/video`、`/{id}/export/{format}` 同一约定，其余接口仍只认 `Authorization` 头）。
分片上传的四个端点与 `POST /api/tasks` 是两条并存的路径：前者供前端大文件续传，后者保留给脚本与压测的单次提交。

---

## 统一错误响应与 traceId

所有接口的错误响应结构统一为 `{code, message, traceId}`，HTTP 状态码保持语义不变（400/401/403/405/409/413/429/500 等）：

```json
{
  "code": "FORBIDDEN",
  "message": "无权访问该任务",
  "traceId": "8f3c1a2b9d4e4f7799aabbccddeeff00"
}
```

- `code`：错误码枚举名（`INVALID_REQUEST` / `UNAUTHORIZED` / `FORBIDDEN` / `NOT_FOUND` / `METHOD_NOT_ALLOWED` / `CONFLICT` / `PAYLOAD_TOO_LARGE` / `INTERNAL_ERROR`），仅作粗分类，精确语义以 HTTP 状态码为准（例如队列不可用返回 `503`，`code` 落到 `INTERNAL_ERROR`）。
- `message`：面向用户的中文文案，前端直接展示。
- `traceId`：32 位十六进制追踪号。请求可携带 `X-Trace-Id`（`[A-Za-z0-9-]`，1-64 位）透传，否则由服务端生成；结果会回写到响应头 `X-Trace-Id`（成功响应也带）。

服务端内部异常只返回固定文案，不会把异常信息或堆栈泄漏到响应体，完整堆栈仅记录到后端日志。排查问题时，把响应里的 `traceId` 直接检索后端日志即可定位该次请求；日志格式已通过 `logging.pattern.console`（`%X{traceId:-}`）带上该追踪号。

---

## 数据库结构

Flyway 自动迁移，共 5 张表：

- `users` —— 账号（用户名唯一、BCrypt 密码哈希）
- `tasks` —— 任务主表（状态、阶段、进度、错误信息、取消标记、归属账号 `owner_id`）
- `transcript_segments` —— 带起止毫秒的转写片段，含译文列
- `task_results` —— 摘要、要点 JSON、章节 JSON
- `llm_calls` —— LLM 调用账目（任务、提示词版本 `prompt_id`、实际模型 `model`、token 数），
  供单视频成本导出与按版本对比；建 `(task_id, created_at)` 与 `(prompt_id, model, created_at)` 两个索引

关联数据外键均为 `ON DELETE CASCADE`，删除任务时自动清理关联数据（含 `llm_calls`）；
`tasks.owner_id` 指向 `users(id)`，非空并建有 `(owner_id, created_at)` 索引。

---

## 快速开始

### Docker Compose 全容器启动

复制 `.env.example` 为 `.env`，至少设置 `MYSQL_USER`、`MYSQL_PASSWORD` 和 `MYSQL_ROOT_PASSWORD`，然后执行：

```bash
docker compose up -d --build
```

Compose 默认将容器 MySQL 映射到宿主机 `3307`，避免与其他项目占用 `3306` 冲突；后端容器内部仍通过服务名 `mysql:3306` 连接。

MySQL 用户和密码只会在空数据卷首次初始化时创建。如果已有旧卷是用其他账号初始化的，请先备份数据，再执行 `docker compose down --volumes` 后重新初始化，或进入 MySQL 手动创建 `.env` 中的 `MYSQL_USER` 并授权；不要为了测试随意删除包含重要视频/数据库的卷。

工作台访问 `http://localhost:5174`，后端访问 `http://localhost:8081`，Prometheus 访问 `http://localhost:9090`，Grafana 访问 `http://localhost:3000`，RabbitMQ 管理台访问 `http://localhost:15672`。Compose 会启动 Nginx 前端、Spring Boot 后端、MySQL 8.4、RabbitMQ 4、Whisper 转写服务以及 Prometheus / Grafana 可观测性栈；Whisper 也在容器里（默认 CPU 推理），端口映射到宿主机 `8090`。音视频保存在 `video-storage` 命名卷，MySQL 数据保存在 `mysql-data` 命名卷，队列消息保存在 `rabbitmq-data` 命名卷，Prometheus / Grafana 数据分别保存在 `prometheus-data`、`grafana-data` 命名卷；模型缓存在 `WHISPER_MODELS_DIR` 指定的宿主机目录（挂载进容器，不打进镜像）。端口可通过 `BACKEND_HOST_PORT`、`FRONTEND_HOST_PORT`、`WHISPER_HOST_PORT`、`PROMETHEUS_HOST_PORT`、`GRAFANA_HOST_PORT`、`RABBITMQ_HOST_PORT` 和 `RABBITMQ_MANAGEMENT_HOST_PORT` 修改。

停止容器但保留视频、模型和数据库：

```bash
docker compose down
```

结束本次测试并清空本项目数据库、视频、模型卷：

```bash
docker compose down --volumes --remove-orphans
```

仅清理本项目构建产生的悬空镜像和构建缓存：

```bash
docker image prune -f
docker builder prune -f
```

不要在日常停止服务时使用 `docker system prune --volumes`，它可能删除其他项目的未使用数据卷。

### 环境要求

- Docker（含 Compose v2）：Whisper 转写服务也在 compose 里，全栈一条命令起完
- **NVIDIA GPU + nvidia-container-toolkit**：whisper 容器只用 GPU 推理，启动时预留一块显卡
  （验证：`docker run --rm --gpus all <任意镜像> nvidia-smi` 能列出显卡）；没有 GPU 的机器
  起 whisper 会直接失败，这是设计行为而不是缺陷
- Java 21、Node.js 20+（本机构建或直接跑后端/前端时需要）
- MySQL 8.0+、RabbitMQ 4（或直接用 `compose.yaml` 起这两个依赖）
- FFmpeg（容器内已装；仅在本机直接跑后端时才需要，可用 `FFMPEG_PATH` 指定）

### 1. 配置

```bash
cp .env.example .env
```

填写 `MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`、至少 32 字符的随机
`WHISPER_SERVICE_TOKEN` 与 `WORKBENCH_JWT_SECRET`；如需云端摘要能力，再填
`LLM_API_KEY`（不填也可完成本地转写）。

首次打开 `http://localhost:5174` 会进入登录页，可直接注册账号；迁移同时创建了
种子账号 `demo`（密码 `demo1234`，仅本地开发用），用于查看鉴权上线前的历史任务，
登录后请按需修改或改用自建账号。

Whisper 权重不打进镜像，挂载一个模型目录即可（已有缓存就直接指过去，省掉 1.6GB 重复下载）：

```properties
WHISPER_MODELS_DIR=E:/model/whisper
```

不设置时默认用仓库内的 `models/whisper`。目录里没有 `large-v3-turbo.pt` 时，
`whisper` 容器会在首次启动时把权重下载到该目录，`docker compose logs -f whisper` 可以看到进度。

### 2. 启动全部服务

```powershell
./tools/start-all.ps1
```

脚本构建并启动 Compose 中的全部服务（Whisper、MySQL、RabbitMQ、Java 后端、前端、Prometheus、Grafana）。
后端会等到 Whisper 的 `/health` 通过（即模型加载完成）再启动，避免启动窗口里的任务白白消耗重试额度。
前端地址为 `http://localhost:5174`，后端为 `http://localhost:8081`，
Whisper 的设备信息可以用 `curl http://127.0.0.1:8090/health` 查看。
停止使用 `./tools/stop-all.ps1`；不会删除模型或数据卷。

### 3. 运行测试与校验

```bash
./mvnw -f backend/pom.xml test          # JUnit
./mvnw -f backend/pom.xml verify        # 含 Spotless 代码风格校验
npm --prefix frontend run build         # vue-tsc 类型检查 + 生产构建
```

---

## 更多文档

| 文档                                         | 内容                                                           |
| -------------------------------------------- | -------------------------------------------------------------- |
| [架构与取舍](docs/架构与取舍.md)             | 全链路图、ADR、当前不支持项与下一步                            |
| [技术要点](docs/技术要点.md)                 | 任务编排、外部进程托管、LLM 结构化输出校验、容错治理等设计细节 |
| [可观测性](docs/可观测性.md)                 | Actuator / Prometheus / Grafana 端点、自定义指标与鉴权取舍     |
| [配置说明](docs/配置说明.md)                 | 全部环境变量与默认值                                           |
| [验收情况](docs/验收情况.md)                 | 已通过验证的场景与样本数据                                     |
| [端到端验收记录](docs/端到端验收记录.md)     | 逐次实测的时间线记录                                           |
| [开发与验证](docs/开发验证.md)               | 提交检查、完整验证与历史复验结论                               |
| [改造方案与优先级](docs/改造方案与优先级.md) | 与云端多模态路线的对比，以及 P0/P1/P2 落地排序                 |
| [需求说明](docs/需求说明.md)                 | 首版范围、隐私约定与非目标                                     |
| [编码规范](docs/编码规范.md)                 | 代码风格约定                                                   |
| [MySQL 部署说明](docs/MySQL-部署说明.md)     | 本地数据库与迁移规则                                           |
| [项目数据记录模板](docs/项目数据记录模板.md) | 回填简历用的测试规模与结果模板                                 |
