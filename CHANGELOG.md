# Changelog / 发布说明

本文件汇总近期三个特性提交与一轮「验证 + 修复」的变更内容。版本口径以 Git tag 为准，未打 tag 时按提交顺序阅读。

---

## 1. 视觉分支 P1 — 关键帧抽取、本地 Tesseract OCR 与 pHash 去重（`8d78fa0`）

本地优先的视频理解能力，画面帧不出本机。

- **关键帧抽取**：FFmpeg 场景检测 `select='gt(scene,0.3)'` 抓镜头切换点，再用 ffprobe 逐点 `-ss` seek 做保底均匀采样，避免纯场景检测漏掉长镜头里的信息。
- **画面去重**：手写 DCT pHash，相邻帧汉明距离 ≤ 10 视为重复；单视频超过 200 帧时再做均匀抽稀，控制进入 OCR / 摘要的帧数。
- **本地 OCR**：`OcrEngine` 接口 + `TesseractOcrEngine`（容器内 `tesseract -l chi_sim+eng` 命令行，实测 4.1.1）。抽出的画面文字并入 v2 摘要提示词作为 `{{visualText}}`。
- **降级策略**：抽帧失败、OCR 失败均降级为「无画面文本」，不阻断音频转写与摘要主链路。
- **前端**：关键帧时间条可点击跳转播放。

## 2. 转写引擎升级 — faster-whisper + 词级时间戳 + 说话人分离（`1fe91c0`）

- **推理后端替换**：`openai-whisper` → `faster-whisper`（CTranslate2 后端）。本地推理、无需 API Key，相同精度下约 4× 提速、显存更省；保留自研 VAD、语言检测、反幻觉闸门与时间戳平移。
- **词级时间戳**：`word_timestamps=True`，新增 `TranscriptWord(startMs,endMs,text,speaker)` 记录；数据库 `transcript_segments` 增加 `words json` 列（V9 迁移）。
- **说话人分离（可选）**：`pyannote/speaker-diarization-3.1` 懒加载，按时间重叠最大匹配把说话人补到段级与词级；`WHISPER_DIARIZE="0"` 或无 `WHISPER_HF_TOKEN` 时优雅降级为「无说话人」。
- **导出**：SRT / Markdown 导出自动带 `[说话人]` 标签；JSON 经 Jackson 自动带新字段。
- **前端**：逐字稿按说话人着色，词级时间戳可点击定位（`@click.stop="seek(w.startMs)"`）。
- **配置**：`.env.example` 与 README 增补 `WHISPER_WORD_TIMESTAMPS` / `WHISPER_DIARIZE` / `WHISPER_HF_TOKEN` / `WHISPER_COMPUTE_TYPE` / `WHISPER_DIARIZATION_MODEL`。
  - 说话人分离需先接受 HF 许可、配置 token，并取消 `requirements.txt` 中 pyannote 行的注释后重建镜像。

## 3. LLM 成本护栏 + v2 摘要提示词 + 下游容错；CI 工作流与文档（`ddca951`）

- **成本护栏**：`LlmBudgetGuard` 按 prompt 版本记账并拦截超限调用；`LlmUsageRepository` 落库用量。
- **提示词升级**：`summarize.v2.*` 提示词，引入画面文本与更严格的摘要约束。
- **下游容错**：`DownstreamResilience` + `TransientFailure` 实现重试 / 熔断 / 降级；`WorkbenchMetrics`（Micrometer）暴露关键指标。
- **CI**：`verify.yml` 增补 backend / frontend 镜像 `docker build-push-action` 校验（不推送，仅验证 Dockerfile 不被悄悄改坏）；新增 `release.yml`，打 `v*` tag 时构建并推送镜像到 GHCR。
- **迁移**：V7 新增 LLM 调用预算 / 降级相关表。

---

## 4. 验证轮修复（本回合）

### 4.1 修复 `verify` CI 的 Prettier 格式检查失败

- 原因：`npm run format:check`（`prettier --check .`）对 `README.md`、`frontend/src/App.vue`、`docs/改造方案与优先级.md` 报格式问题，导致验证工作流红。
- 处理：运行 `prettier --write .` 规范化上述文件，CI 恢复全绿。

### 4.2 修复 whisper 镜像依赖与 worker 不一致

- 原因：`workers/Dockerfile` 仍安装 `openai-whisper==20250625`，但 `whisper_worker.py` 已改用 `faster-whisper`，镜像启动会因 `import faster_whisper` 失败。CI 不构建 whisper 镜像，故此前未被发现。
- 处理：`Dockerfile` 改为安装 `faster-whisper==1.2.1`，更新启动自检 `import faster_whisper` 与相关注释。

### 4.3 修复 faster-whisper API 兼容性（端到端验证才发现）

以下三处**只有真正跑起来才会触发**（`py_compile`、Java 单测、CI 都拦不住），是靠 whisper 容器端到端跑出来的：

- **av 依赖漂移**：`faster_whisper/audio.py` 里调用 `av.open(..., metadata_errors="ignore")`，而 pip 默认解析到最新的 av（≥17 已移除该参数），转写第一步 `decode_audio` 就抛
  `TypeError: open() got an unexpected keyword argument 'metadata_errors'`。
  → `Dockerfile` 显式 pin `av==16.0.0`（实测仍支持该签名），与 `faster-whisper==1.2.1` 一起安装。
- **detect_language 返回值元数**：faster-whisper 返回 `(language, language_probability, all_language_probs)` **三元组**，而 worker 沿用 openai-whisper 的二元组写法解包，抛 `too many values to unpack (expected 2)`。
  → 改为按索引取 `[0]`，不再依赖元组长度。
- **不支持的 verbose 参数**：worker 在两处传了 `model.transcribe(..., verbose=False)`；`verbose` 属于 openai-whisper，faster-whisper 没有该参数，抛
  `TypeError: WhisperModel.transcribe() got an unexpected keyword argument 'verbose'`。
  → 移除两处该参数（faster-whisper 对应的是 `log_progress`，默认已是 False）。

### 4.4 修复词级时间戳越界（业务逻辑不变量）

- **现象**：端到端产物中 `seg[4]` 的首词 `' But'` 起始 **21.560s**，早于所属段起点 **21.820s**（差约 260ms）。
- **原因**：段边界来自 Whisper 的时间戳 token，而词级时间来自 cross-attention 对齐，两者天然会差出几十~几百毫秒，首词偶尔落在所属段之前。
- **影响**：前端词高亮与「点击词跳转播放」依赖「词一定落在所属段的时间区间内」这个不变量，越界会让词看起来跑到段外。
- **处理**：`_word_dicts` 增加 `lo` / `hi` 参数，把每个词钳进所属段的 `[seg_start, seg_end]` 区间；钳完塌缩为零长度的词丢弃。

### 4.5 端到端验证结果

在 RTX 4060（CUDA）+ 30 秒英文音视频片段上跑通整条链路：

| 项目       | 结果                                                                 |
| ---------- | -------------------------------------------------------------------- |
| 推理设备   | `device=cuda`（GPU 生效）                                            |
| 语言检测   | `language=en`（在有声区间上自动检测）                                |
| VAD        | 2 个有声区间，语音占比 0.9647，丢弃重复 0 段                         |
| 产物       | 6 段 / 78 个词，全部段均带词级时间戳                                 |
| 一致性断言 | 修复前 2 项失败 → 修复后 **0 失败**（词⊆段、无零长度词、词时间单调） |

---

## 启用说明（速查）

| 能力         | 默认 | 开启方式                                                                                                      |
| ------------ | ---- | ------------------------------------------------------------------------------------------------------------- |
| 词级时间戳   | 开   | `WHISPER_WORD_TIMESTAMPS=1`（默认即开）                                                                       |
| 说话人分离   | 关   | 接受 HF 许可 → 设 `WHISPER_HF_TOKEN` → `WHISPER_DIARIZE=1` → 取消 `requirements.txt` pyannote 注释 → 重建镜像 |
| 视觉 OCR     | 开   | 容器内已带 `tesseract`（chi_sim+eng）                                                                         |
| LLM 成本护栏 | 开   | 由 `LlmBudgetGuard` 自动生效，见 `application.yml`                                                            |

## 已知限制

- 说话人分离依赖 pyannote 权重与 HF token，未配置时转写产物不含说话人（不影响主链路）。
- faster-whisper 模型默认从 HuggingFace Hub 拉取并缓存到 `WHISPER_MODEL_DIR`；存量 `openai-whisper` 格式的 `large-v3-turbo.pt` 不会被直接复用，首跑会重新下载并转换为 CTranslate2 格式缓存。
- whisper 仅用 GPU：容器未拿到 GPU 时启动即失败并明确报错，不静默回落 CPU。
