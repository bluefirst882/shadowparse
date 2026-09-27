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
视频导入 → FFmpeg 音频抽取 → Whisper 语音转写 → 自动生成摘要/章节 → 结果校验 → 多格式导出
              │                    │                    │                │
          16kHz 单声道        turbo 模型（GPU）     JSON 结构化输出   章节时间轴 + 引文校验
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
当前生产构建产物为约 361 KB JavaScript 和 97 KB CSS（未压缩），用于降低本地工作台首次加载体积。

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

- **一次提交自动跑完**：上传后自动完成音频提取、转写与内容生成，转写结束即串联摘要与章节，无需手动触发；摘要阶段校验不通过会自动重新生成，最多 3 次。
- **重试边界**：已保留转写的任务可单独重试摘要；其他失败任务从本地处理重新开始。
- **重启恢复**：服务启动时将残留的 `PROCESSING` 任务改回 `QUEUED` 并尝试入队；队列满时任务保留为 `QUEUED`，可在队列排空后重试。
- **产物一致性**：任务记录写入失败时回收刚上传的视频；删除任务时若本地视频或音频清理失败，则保留任务记录并返回错误，避免产生不可追踪的本地文件。
- **转写与摘要解耦**：未配置云端密钥时任务以「本地转写已完成」状态保留，
  配置后可单独重试内容生成，不丢失已完成的转写结果

### 2. 外部进程与服务托管

FFmpeg 由 `ProcessBuilder` 托管：

- **独立线程异步消费 stdout / stderr**：避免管道缓冲区写满导致子进程阻塞死锁
- **超时强杀**：超过配置时限自动 `destroyForcibly`
- **退出码诊断**：失败时截取 stderr 尾部作为错误信息回传前端

Whisper 推理改为独立运行的常驻 HTTP 服务（`workers/whisper_worker.py --serve`，
默认跑在宿主机 GPU 上），后端只通过 HTTP 调用它：

- **调用鉴权**：请求头携带 `X-Whisper-Token`，与后端共享密钥；服务启动时强制要求该密钥
  至少 32 字符，避免本机其他进程随意调用
- **设备自述**：`GET /health` 返回当前推理设备（`cuda` / `cpu`），便于确认是否退化到 CPU
- **输入边界**：按 `Content-Length` 分块落盘并限制体积，超限返回 `413`；转写结束后删除临时音频
- 当前实现**每次请求都会重新加载模型**，常驻服务省掉的是进程启动开销而非模型加载开销，
  这是已知的优化点

### 3. LLM 结构化输出与可信校验

大模型的输出不可全信，尤其是时间轴，以及「看起来像原文」的引文。因此：

- 约束模型返回 JSON：`{summary, keyPoints[], chapters[{startMs, endMs, title, sourceSegmentId, sourceEndSegmentId, quote}]}`。章节可覆盖连续转写片段，首尾片段 ID 用于服务端时间范围校验；`quote` 是该章节所引用片段的**转写原文原句**。
- 每个章节**必须声明其起始和结束来源转写片段 id**；单片段章节的两个 id 相同
- 服务端逐条校验：
  - **时间轴**：章节时间必须分别落在首、尾来源片段的起止范围内，尾片段不得早于首片段，章节时间不得与上一章节重叠、标题非空
  - **反幻觉闸门（quote）**：`quote` 非空，且**归一化后**（去掉空白与中英文标点）必须连续出现在 `sourceSegmentId..sourceEndSegmentId` 覆盖片段的转写原文里；不匹配即判定为模型编造，抛错并触发重试。比较的是完整引文的连续包含，不做前缀 / 关键字 / 编辑距离等宽松匹配
- **分块与合并**：转写按 `LLM_MAX_INPUT_CHARS` 分批。**分块数 > 1 时**，summary 与 keyPoints 额外发起一次「合并去重」调用（要求模型只返回 `{summary,keyPoints[]}`）；chapters 由各块结果**确定性合并排序**，不交给模型重写，以免破坏 `sourceSegmentId` 引用与时间校验。
- **要点不截断**：keyPoints 按「去首尾空白 + 去尾部标点」归一化后去重；数量超过 12 条（继承自旧截断值的宽松告警阈值）只打日志告警，**绝不静默丢弃**。
- 校验失败则整体失败并可重试，且**把上一次的失败原因回灌到提示词**（同一个提示词重试大概率仍是同样的幻觉，带上原因才有意义），最多 3 次；**本地转写结果始终保留**。
- 非中文视频在转写后自动生成中文译文，便于与摘要对照阅读

这样既获得了大模型的表达能力，又不把时间轴与引文的正确性交给模型。

### 4. 视频流式播放

手写 HTTP Range 处理，基于 `FilterInputStream` 对响应体精确限流，
返回 `206 Partial Content` 与 `Content-Range` 头，
支撑前端播放器拖拽跳转，并与转写片段的时间轴双向联动定位。

### 5. 结果导出

支持三种格式导出：**Markdown**（摘要 + 要点 + 带时间戳全文）、
**JSON**（完整任务数据）、**SRT**（标准字幕格式，可直接用于视频压制）。

### 6. 账号鉴权与任务归属

自建注册/登录（BCrypt 存密码）+ 无状态 JWT，所有 `/api/tasks/**` 接口都需要令牌：

- **身份来源**：`Authorization: Bearer <token>`；视频流与导出由浏览器直接打开，
  无法附加请求头，因此这两类接口额外接受 `?access_token=` 查询参数
- **归属强约束**：`tasks.owner_id` 非空并建索引，列表查询在 SQL 层按 `owner_id` 过滤，
  按 id 的读写统一走 `where id=? and owner_id=?`，不匹配一律返回 `403`（不区分“不存在”与“不属于你”，避免探测）
- **状态码语义**：未携带/携带无效令牌返回 `401`，跨账号访问他人任务返回 `403`
- **前端行为**：令牌存 `localStorage`，401 时自动清除并回到登录页
- **历史数据**：鉴权上线前的无主任务在迁移中回填给种子账号 `demo`，已有转写与摘要数据保留

### 7. 列表游标分页

任务列表按 `created_at desc, id desc` 稳定排序，用 `(created_at, id)` 元组做**键集比较**（keyset，不用 OFFSET），
避免大列表下翻页随深度变慢，也避免翻页途中有新任务插入时出现重复或漏项：

- `GET /api/tasks?cursor=<opaque>&limit=<n>`：`limit` 可选，默认 20、上限 100；`cursor` 省略表示第一页。
- 响应体为 `{"items":[...],"nextCursor":"..."}`；`nextCursor` 为 `null` 表示没有更多数据，前端拿它请求下一页。
- 游标对客户端不透明，内部是 `base64url(毫秒时间戳|任务 id)`；时间戳固定毫秒精度，与
  `tasks.created_at timestamp(3)` 一致，避免读出的时间被截断后跳过同一时间戳内的任务。
- `limit` 非数字、≤0 或 >100，以及 `cursor` 非法（base64 无法解析、缺少字段、时间戳不合法）都返回 `400`，
  不会静默当成第一页；游标仍在 SQL 层叠加 `owner_id` 过滤，无法借此读到其他账号的任务。

---

## 项目结构

```
.
├── backend/                         # Spring Boot 后端
│   └── src/main/
│       ├── java/ai/toni/videoworkbench/
│       │   ├── TaskController.java       # REST 接口（鉴权后按账号隔离）
│       │   ├── TaskService.java          # 任务编排与外部进程托管
│       │   ├── TaskRepository.java       # JDBC 数据访问（读写强制带 owner）
│       │   ├── TaskCursor.java           # 列表游标编解码（不透明 keyset 游标）
│       │   ├── TaskPage.java             # 任务列表分页响应
│       │   ├── SecurityConfig.java       # Spring Security 过滤链与密码编码器
│       │   ├── JwtService.java           # JWT 签发与校验
│       │   ├── JwtAuthenticationFilter.java # 从请求中解析令牌并建立身份
│       │   ├── AuthController.java       # 注册 / 登录接口
│       │   ├── UserRepository.java       # 用户数据访问
│       │   ├── LlmClient.java            # LLM 调用（摘要 / 翻译，解析 usage 并打点）
│       │   ├── WorkbenchMetrics.java     # 指标埋点集中入口（Micrometer）
│       │   ├── ResultValidator.java      # 章节时间轴可信校验
│       │   ├── ExportService.java        # Markdown / JSON / SRT 导出
│       │   └── TaskRecovery.java         # 重启后任务恢复
│       └── resources/
│           ├── application.yml
│           └── db/migration/             # Flyway 迁移脚本
├── frontend/                        # Vue 3 + TypeScript 工作台界面
├── workers/whisper_worker.py        # Whisper 推理服务（HTTP，/transcribe 与 /health）
├── ops/                             # 可观测性栈配置
│   ├── prometheus/prometheus.yml         # 抓取 backend:8080/actuator/prometheus
│   └── grafana/                          # 数据源与面板 provisioning（含面板 JSON）
├── docs/                            # 需求说明、编码规范、验收记录、面板截图
└── compose.yaml                     # MySQL / 后端 / 前端 / Prometheus / Grafana
```

---

## REST 接口

除 `/api/auth/**` 外，所有接口都需要 `Authorization: Bearer <token>`；
未携带或令牌无效返回 `401`，访问他人任务返回 `403`。

| 方法     | 路径                              | 说明                           |
| -------- | --------------------------------- | ------------------------------ |
| `POST`   | `/api/auth/register`              | 注册并返回令牌                 |
| `POST`   | `/api/auth/login`                 | 登录并返回令牌                 |
| `GET`    | `/api/tasks?cursor=&limit=`       | 当前账号的任务列表（游标分页） |
| `POST`   | `/api/tasks`                      | 导入视频（multipart）          |
| `GET`    | `/api/tasks/{id}/details`         | 任务详情（含转写与摘要结果）   |
| `POST`   | `/api/tasks/{id}/cancel`          | 取消任务                       |
| `POST`   | `/api/tasks/{id}/retry`           | 重试摘要或重新执行本地处理     |
| `POST`   | `/api/tasks/{id}/retranscribe`    | 重新转写                       |
| `GET`    | `/api/tasks/{id}/video`           | 视频流（支持 Range）           |
| `GET`    | `/api/tasks/{id}/export/{format}` | 导出 md / json / srt           |
| `DELETE` | `/api/tasks/{id}`                 | 删除任务、视频及提取的音频     |

`GET /api/tasks` 支持 `cursor`（不透明游标，省略即第一页）与 `limit`（默认 20、上限 100），
响应体为 `{"items":[...],"nextCursor":"..."}`，`nextCursor` 为 `null` 表示已到最后一页；详见上文「列表游标分页」。

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

- `code`：错误码枚举名（`INVALID_REQUEST` / `UNAUTHORIZED` / `FORBIDDEN` / `NOT_FOUND` / `METHOD_NOT_ALLOWED` / `CONFLICT` / `PAYLOAD_TOO_LARGE` / `QUEUE_FULL` / `INTERNAL_ERROR`），仅作粗分类，精确语义以 HTTP 状态码为准。
- `message`：面向用户的中文文案，前端直接展示。
- `traceId`：32 位十六进制追踪号。请求可携带 `X-Trace-Id`（`[A-Za-z0-9-]`，1-64 位）透传，否则由服务端生成；结果会回写到响应头 `X-Trace-Id`（成功响应也带）。

服务端内部异常只返回固定文案，不会把异常信息或堆栈泄漏到响应体，完整堆栈仅记录到后端日志。排查问题时，把响应里的 `traceId` 直接检索后端日志即可定位该次请求；日志格式已通过 `logging.pattern.console`（`%X{traceId:-}`）带上该追踪号。

---

## 可观测性

后端通过 Spring Boot Actuator + Micrometer 暴露运行指标，由 Prometheus 抓取、Grafana 看图。

### 端点

| 端点                       | 说明                                                          |
| -------------------------- | ------------------------------------------------------------- |
| `GET /actuator/health`     | 存活探针，容器 healthcheck 使用；`{"status":"UP"}`            |
| `GET /actuator/prometheus` | Prometheus 文本格式指标（JVM/HTTP 默认指标 + 下列自定义指标） |

两个端点都在 `SecurityConfig` 中放行，**无需令牌**（取舍见本节末尾）。

### 自定义指标

埋点集中在 `WorkbenchMetrics`，业务代码只调它的方法、不直接依赖 `MeterRegistry`；标签刻意避开任务 id、文件名等高基数维度。

| 类别         | Micrometer 名                      | Prometheus 名                              | 含义                                                            | 标签                                       | 打点位置                           |
| ------------ | ---------------------------------- | ------------------------------------------ | --------------------------------------------------------------- | ------------------------------------------ | ---------------------------------- |
| 转写耗时     | `workbench.transcription.duration` | `workbench_transcription_duration_seconds` | 单条任务 Whisper 转写（含片段落库）耗时直方图                   | `outcome=success/failure`                  | `TaskService.transcribe`           |
| LLM 调用结果 | `workbench.llm.requests`           | `workbench_llm_requests_total`             | 每次 LLM HTTP 调用按结果计数                                    | `operation=summarize/translate`、`outcome` | `LlmClient.send`（`finally` 记账） |
| LLM 重试次数 | `workbench.llm.retries`            | `workbench_llm_retries_total`              | 摘要生成在单条任务内首次之外的重试次数（最多 3 次尝试）         | `operation`                                | `TaskService.generateContent`      |
| 校验拦截次数 | `workbench.llm.validation_failures` | `workbench_llm_validation_failures_total` | 结果校验拦下未通过模型输出的次数（反幻觉闸门命中）             | `reason=quote_mismatch` / `quote_missing` / `invalid_chapter_*` 等 | `TaskService.generateContent`      |
| token 用量   | `workbench.llm.tokens`             | `workbench_llm_tokens_total`               | 从 LLM 响应 `usage` 字段累加的 token 数（仅运行指标，未持久化） | `type=prompt/completion`、`model`          | `LlmClient.recordUsage`            |
| 队列深度     | `workbench.queue.depth`            | `workbench_queue_depth`                    | 当前排队等待处理的任务数（Gauge）                               | 无                                         | `TaskService` 构造函数绑定         |
| 队列等待时长 | `workbench.queue.wait`             | `workbench_queue_wait_seconds`             | 任务从入队到真正开始执行的等待时长直方图                        | 无                                         | `TaskService.tryEnqueue`           |

- **usage 解析**：`LlmClient` 原实现只取 `choices[0].message.content`，**并不解析 `usage`**；本次补上 `usage.prompt_tokens` / `usage.completion_tokens` 的读取并累加到指标（按要求不做持久化）。
- **队列指标的可替换性**：`WorkbenchMetrics.bindQueueDepth(Supplier<Number>)` 由 `TaskService` 注入数据源（当前是有界单线程队列的等待任务数）。P2-1 把进程内队列换成 RabbitMQ 时，只改这一处绑定与调用点，指标名与语义保持不变。

### 启动与看图

```bash
docker compose up -d --wait      # backend / frontend / mysql / prometheus / grafana 全部 healthy
```

- Prometheus：<http://localhost:9090>，抓取任务 `video-workbench-backend`，目标 `backend:8080/actuator/prometheus`（容器网络内，无需令牌）。
- Grafana：<http://localhost:3000>，默认账号 `admin` / `workbench`（可用 `GRAFANA_ADMIN_USER`、`GRAFANA_ADMIN_PASSWORD` 覆盖）。数据源与面板均由 `ops/grafana/provisioning` 自动配置，面板 UID 为 `workbench-observability`。

面板覆盖上述主要指标：LLM 调用成功/失败/重试计数、转写完成次数与平均耗时、当前队列深度，以及 token 用量、LLM 调用次数、转写耗时 P50/P95、队列等待时长四条曲线（新增的校验拦截计数尚未加入面板，可在 Prometheus 直接查询）。

![可观测性面板](docs/可观测性面板.png)

上图来自一次**真实业务路径**：对已有任务触发 `retranscribe` 与 `retry` 后，`/actuator/prometheus` 中 `workbench_transcription_duration_seconds_count{outcome="success"}=2`、`workbench_llm_requests_total{operation="summarize",outcome="success"}=3`、`workbench_llm_tokens_total{type="prompt"}=12690`、`{type="completion"}=29117`，抓取瞬间 `workbench_queue_depth=2`。图中「LLM 调用失败次数」「LLM 重试次数」显示 `No data`，是因为这次运行确实没有失败或重试（该 Counter 只在首次发生时才注册）。为验证这两条路径，另做了一次**故障注入**：把 `LLM_API_KEY` 临时置为无效值后重试任务，得到 `workbench_llm_requests_total{outcome="failure"}=3`、`workbench_llm_retries_total{operation="summarize"}=2`，随后已恢复真实密钥：

![故障注入下的 LLM 失败/重试指标](docs/可观测性面板-故障注入.png)

复现截图：`npm run screenshot:grafana`（默认写入 `docs/可观测性面板.png`，可传文件名参数）。

### 鉴权取舍（如实说明）

`/actuator/health` 与 `/actuator/prometheus` 当前**未做鉴权**，任何能访问后端端口的人都能读取指标。
这是为了让 compose 网络内的 Prometheus 直接抓取而做的取舍：指标不包含视频内容或密钥，
但包含调用量、耗时、token 用量与队列状态等运营信息。**真实部署应把这两个端点限制在内网**，
或用反向代理 / 抓取侧鉴权（如只允许 Prometheus 网段访问、加 Basic Auth），不要直接暴露到公网。

---

## 数据库结构

Flyway 自动迁移，共 4 张表：

- `users` —— 账号（用户名唯一、BCrypt 密码哈希）
- `tasks` —— 任务主表（状态、阶段、进度、错误信息、取消标记、归属账号 `owner_id`）
- `transcript_segments` —— 带起止毫秒的转写片段，含译文列
- `task_results` —— 摘要、要点 JSON、章节 JSON

关联数据外键均为 `ON DELETE CASCADE`，删除任务时自动清理关联数据；
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

工作台访问 `http://localhost:5174`，后端访问 `http://localhost:8081`，Prometheus 访问 `http://localhost:9090`，Grafana 访问 `http://localhost:3000`。Compose 会启动 Nginx 前端、Spring Boot 后端、MySQL 8.4 以及 Prometheus / Grafana 可观测性栈；Windows 上的 Whisper worker 由 `start-all.ps1` 在宿主机运行，使 PyTorch 能直接使用宿主机 NVIDIA GPU。音视频保存在 `video-storage` 命名卷，MySQL 数据保存在 `mysql-data` 命名卷，Prometheus / Grafana 数据分别保存在 `prometheus-data`、`grafana-data` 命名卷；模型缓存在 `WHISPER_MODEL_DIR` 指定的宿主机目录。端口可通过 `BACKEND_HOST_PORT`、`FRONTEND_HOST_PORT`、`PROMETHEUS_HOST_PORT` 和 `GRAFANA_HOST_PORT` 修改。

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

- Java 21、Node.js 20+、Python 3.11+
- MySQL 8.0+
- FFmpeg（需在 `PATH` 中，或通过 `FFMPEG_PATH` 指定）
- 本地 Whisper：`pip install openai-whisper`

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

Windows 宿主机首次配置独立的 Python 3.11 GPU 环境：

```powershell
py -3.11 -m venv E:\model\toni-whisper-venv
E:\model\toni-whisper-venv\Scripts\python.exe -m pip install --upgrade pip
E:\model\toni-whisper-venv\Scripts\pip.exe install torch==2.5.1+cu124 --index-url https://download.pytorch.org/whl/cu124
E:\model\toni-whisper-venv\Scripts\pip.exe install -r workers\requirements.txt
```

默认模型目录为 `E:\model\whisper`。可用 `WHISPER_VENV`、
`WHISPER_MODEL_DIR` 修改位置，并用 `nvidia-smi` 检查驱动与显卡。

### 2. 启动全部服务

```powershell
./start-all.ps1
```

脚本启动宿主机 Whisper worker 并等待健康检查，再构建、启动 Compose 中的
Java 后端、前端和 MySQL。前端地址为 `http://localhost:5174`，后端为
`http://localhost:8081`。停止使用 `./stop-all.ps1`；不会删除模型或数据卷。

### 5. 运行测试与校验

```bash
./mvnw -f backend/pom.xml test          # JUnit
./mvnw -f backend/pom.xml verify        # 含 Spotless 代码风格校验
npm --prefix frontend run build         # vue-tsc 类型检查 + 生产构建
```

---

## 配置项

| 变量                                | 默认值                             | 说明                                               |
| ----------------------------------- | ---------------------------------- | -------------------------------------------------- |
| `WORKBENCH_STORAGE_DIR`             | `./storage`                        | 视频、音频与模型文件的本机存放目录                 |
| `WORKBENCH_MAX_UPLOAD_BYTES`        | 20GB                               | 单个视频大小上限                                   |
| `WORKBENCH_PROCESS_TIMEOUT_MINUTES` | 180                                | 外部进程超时时间；首次下载模型时应保留充足时间     |
| `FFMPEG_PATH`                       | `ffmpeg`                           | FFmpeg 可执行文件路径                              |
| `WHISPER_MODEL`                     | `turbo`                            | Whisper 模型规格                                   |
| `WHISPER_SERVICE_URL`               | `http://host.docker.internal:8090` | 宿主机 Whisper 服务地址                            |
| `WHISPER_MODEL_DIR`                 | `E:/model/whisper`                 | 宿主机模型缓存目录                                 |
| `WORKBENCH_JWT_SECRET`              | 无（必填）                         | JWT 签名密钥，至少 32 字符，需自行随机生成         |
| `WORKBENCH_TOKEN_TTL_HOURS`         | 24                                 | 令牌有效期（小时）                                 |
| `LLM_API_KEY`                       | 空                                 | 云端 LLM 密钥，留空则跳过摘要阶段                  |
| `LLM_MAX_INPUT_CHARS`               | 60000                              | 单次摘要请求的字符上限，按完整转写片段分批         |
| `PROMETHEUS_HOST_PORT`              | 9090                               | Prometheus 映射到宿主机的端口                      |
| `GRAFANA_HOST_PORT`                 | 3000                               | Grafana 映射到宿主机的端口                         |
| `GRAFANA_ADMIN_USER`                | `admin`                            | Grafana 管理员账号                                 |
| `GRAFANA_ADMIN_PASSWORD`            | `workbench`                        | Grafana 管理员密码（仅本地开发默认值，请按需修改） |

> 密钥仅由后端读取，不会写入日志、前端响应或导出文件。

Docker 不包含 Python、PyTorch、Whisper，也不申请容器 GPU。宿主机 worker
在 CUDA 可用时使用 NVIDIA GPU，否则回退 CPU。后端通过
`host.docker.internal:8090` 上传提取后的 WAV 音频，接口由
`WHISPER_SERVICE_TOKEN` 保护。模型保存在宿主机 `WHISPER_MODEL_DIR`。
若首次下载因网络中断留下不完整的 `.pt` 文件，重试会重新校验并下载该模型；不要将未完成的文件当作已缓存模型。

---

## 验收情况

已通过真实验收：Maven 编译与 JUnit、前端 `vue-tsc` 与生产构建、
MySQL 8.4 隔离容器中 Flyway 迁移执行，以及 1 个真实视频的完整导入与 Whisper 转写
（持久化 42 个时间戳片段）、Range 请求返回 `206`、三种格式导出成功。

已验证边界场景：损坏视频、无音轨视频、取消与重启恢复；长文本已通过按完整转写片段分批和单片段超限拒绝校验。
鉴权与归属已在容器环境实测：无令牌与伪造令牌均返回 `401`；新注册账号调用任务列表、详情、视频流、导出、取消、删除访问种子账号的历史任务全部返回 `403`；迁移将原有 3 条无主任务回填给 `demo` 且 `owner_id` 已收紧为非空（`0` 条空值）；
前端从登录页到列表、注册新账号后列表为空的完整路径已通过浏览器验证。
中文真实视频已补充：输入 `E:\Downloads\Video\27210678708-1-192.mp4`，媒体时长 355.947 秒，H.264/AAC；本地 Whisper `turbo`、RTX 4060 Laptop GPU，上传到任务完成约 58 秒，持久化 250 个片段，任务保留为 `COMPLETED/SUMMARY` 并提示摘要可单独重试。检查发现尾部若干片段超出媒体时长，未将该样本表述为时间轴校验通过。以上数据仅对应样本，不能外推为通用成功率或准确率。

可观测性已在容器环境实测：`docker compose build backend` + `docker compose up -d --wait` 后五个服务全部 healthy；
对 `demo` 的已有任务触发 `retranscribe` / `retry`，`/actuator/prometheus` 出现自定义指标的非零样本
（转写耗时 `count=2`、LLM 调用成功、token `prompt=12690 / completion=29117`、队列深度抓取到 `2`、队列等待时长有样本），
Prometheus 目标 `video-workbench-backend` 为 `up`，Grafana 面板与数据源由 provisioning 自动加载；
额外用一次故障注入（临时失效 `LLM_API_KEY`）验证了 LLM 失败与重试路径。详见 [docs/端到端验收记录.md](docs/端到端验收记录.md)。

---

## 更多文档

- [需求说明](docs/需求说明.md) —— 首版范围与非目标
- [编码规范](docs/编码规范.md) —— 代码风格约定
- [端到端验收记录](docs/端到端验收记录.md) —— 已验收项与待验证项
- [项目数据记录模板](docs/项目数据记录模板.md) —— 记录可回填简历的测试规模与结果
- [MySQL 部署说明](docs/MySQL-部署说明.md)
