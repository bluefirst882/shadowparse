# coderplan.ai 协议核查

核查日期：2026-09-08。

当前公开网络环境无法获得可引用的 coderplan.ai 官方 API 文档。因此项目**尚未**硬编码请求路径、认证头或响应格式，也不会把 `gpt-5.6-terra` 替换为其他模型。

保留以下后端环境变量，待从 coderplan.ai 官方控制台或文档确认后再接入：

- `CODERPLAN_BASE_URL`
- `CODERPLAN_API_KEY`
- `CODERPLAN_MODEL`，固定目标值为 `gpt-5.6-terra`
- `CODERPLAN_REASONING_EFFORT`，固定目标值为 `low`

在确认前，本地导入、音频提取与 Whisper 转写可独立完成；内容生成阶段会保留转写并提示用户配置后重试，不会向任何服务发送视频、音频或画面。
