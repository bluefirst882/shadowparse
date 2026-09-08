# coderplan.ai 协议核查

核查日期：2026-09-08。

已于 2026-09-08 使用本地 `.env` 中的密钥验证 OpenAI 兼容接口：`GET https://api.coderplan.ai/v1/models` 返回 `gpt-5.6-terra`，`POST https://api.coderplan.ai/v1/chat/completions` 能以 `reasoning_effort: low` 和 `response_format: {"type":"json_object"}` 返回摘要与章节 JSON。

保留以下后端环境变量，待从 coderplan.ai 官方控制台或文档确认后再接入：

- `CODERPLAN_BASE_URL`
- `CODERPLAN_API_KEY`
- `CODERPLAN_MODEL`，固定目标值为 `gpt-5.6-terra`
- `CODERPLAN_REASONING_EFFORT`，固定目标值为 `low`

请求使用 `Authorization: Bearer <CODERPLAN_API_KEY>`，消息格式为 OpenAI Chat Completions `messages`。后端仅发送带时间戳的必要转写文本，不会向任何服务发送视频、音频或画面。

本地导入、音频提取与 Whisper 转写可独立完成；无密钥或云端失败时保留转写并提示用户配置后重试。模型和推理强度保持固定，不自动切换。
