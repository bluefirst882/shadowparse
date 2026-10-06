// P3-3 推送通道（SSE）的实测脚本：回答三个问题——未登录能不能订阅、别的账号能不能收到我的推送、
// 任务变化到界面收到信号要多久。
//
// 用法：node tools/measure/sse-latency.mjs          （需要 backend 已启动，默认 http://localhost:8081，可用 BASE 覆盖）
//
// 做法：真实上传一个 4 秒样本，任务会依次走「本地转写 → 生成内容 → 已完成」，每次状态写入都会触发一次推送。
// 每收到一次推送，立即回查任务的 updated_at，用它作为服务端写入时刻来算端到端延迟；跑完把这次上传的任务删掉。
// 同时用第二个账号订阅同一个后端，确认它在整个过程中收不到任何关于该任务的推送。

import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const baseUrl = (process.env.BASE || 'http://localhost:8081').replace(
  /\/+$/,
  ''
)
const account = { username: 'demo', password: 'demo1234' }
// 用户名只允许字母、数字、下划线，因此每次跑用一个新账号，避免与历史遗留账号的密码对不上。
const probeAccount = {
  username: `sse_probe_${Date.now() % 100000000}`,
  password: 'sse-probe-1234'
}

const json = async (path, init = {}) => {
  const response = await fetch(baseUrl + path, init)
  const body = await response.text()
  return {
    status: response.status,
    body: body.trim() ? JSON.parse(body) : undefined
  }
}

const bearer = (token) => ({ Authorization: `Bearer ${token}` })

async function authenticate({ username, password }) {
  const credentials = { 'Content-Type': 'application/json' }
  const registered = await json('/api/auth/register', {
    method: 'POST',
    headers: credentials,
    body: JSON.stringify({ username, password })
  })
  if (registered.status < 300) return registered.body.token
  const loggedIn = await json('/api/auth/login', {
    method: 'POST',
    headers: credentials,
    body: JSON.stringify({ username, password })
  })
  if (loggedIn.status >= 300)
    throw new Error(`无法登录 ${username}：HTTP ${loggedIn.status}`)
  return loggedIn.body.token
}

/** 按 SSE 协议逐块解析：事件之间以空行分隔，这里只关心事件名与数据。 */
function openStream(token, onEvent) {
  const controller = new AbortController()
  const query = token ? `?access_token=${encodeURIComponent(token)}` : ''
  const started = (async () => {
    const response = await fetch(`${baseUrl}/api/tasks/stream${query}`, {
      headers: { Accept: 'text/event-stream' },
      signal: controller.signal
    })
    if (response.status !== 200) return response.status
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let edge = buffer.indexOf('\n\n')
      while (edge >= 0) {
        const block = buffer.slice(0, edge)
        buffer = buffer.slice(edge + 2)
        onEvent(
          /^event:\s*(.*)$/m.exec(block)?.[1]?.trim(),
          /^data:\s*(.*)$/m.exec(block)?.[1]?.trim(),
          Date.now()
        )
        edge = buffer.indexOf('\n\n')
      }
    }
    return 200
  })().catch(() => 0)
  return { close: () => controller.abort(), started }
}

const anonymous = await fetch(`${baseUrl}/api/tasks/stream`)
console.log(`无令牌订阅 -> HTTP ${anonymous.status}（应为 401）`)
await anonymous.body?.cancel()

const demo = await authenticate(account)
const probe = await authenticate(probeAccount)
const probeEvents = []
const probeStream = openStream(probe, (name, data) =>
  probeEvents.push(`${name}:${data}`)
)
const latencies = []
let pushCount = 0
let demoReady = false
const connectStarted = Date.now()
const demoStream = openStream(demo, (name, data, at) => {
  if (name === 'ready') {
    demoReady = true
    console.log(`[ready] 订阅确认，用时 ${at - connectStarted}ms`)
    return
  }
  if (name !== 'tasks') return
  pushCount += 1
  // 收到推送立刻回查任务，把服务端的 updated_at 当作这次写入的时刻：差值就是「状态变化 → 界面收到信号」的端到端延迟。
  // 同一个上传会连续写好几次（落库、认领、阶段推进），若回查时任务已经又变了，这个差值就不对应同一次写入，
  // 直接丢弃而不是报一个假数。
  void json(`/api/tasks/${data}`, { headers: bearer(demo) }).then((detail) => {
    const updatedAt = Date.parse(detail.body?.updatedAt ?? '')
    const delta = at - updatedAt
    if (Number.isFinite(delta) && delta >= 0 && delta < 5000)
      latencies.push({ taskId: data, delta })
  })
})

// 真实产生一串状态变化：上传 4 秒样本，任务会被转写并生成内容。
const samplePath = join(ROOT, 'ops', 'k6', 'fixtures', 'sample-4s.mp4')
const fileName = `sse-latency-${Date.now()}.mp4`
const form = new FormData()
form.append('file', new Blob([readFileSync(samplePath)]), fileName)
const created = await json('/api/tasks', {
  method: 'POST',
  headers: bearer(demo),
  body: form
})
if (created.status >= 300) throw new Error(`上传失败：HTTP ${created.status}`)
console.log(
  `已上传 ${fileName}（taskId=${created.body.id}），等待任务走完整个流程`
)

const deadline = Date.now() + 180000
let finished = false
while (Date.now() < deadline) {
  const detail = await json(`/api/tasks/${created.body.id}/details`, {
    headers: bearer(demo)
  })
  if (
    detail.body?.task?.status === 'COMPLETED' ||
    detail.body?.task?.status === 'FAILED'
  ) {
    console.log(`任务终态 ${detail.body.task.status}/${detail.body.task.stage}`)
    finished = true
    break
  }
  await new Promise((resolve) => setTimeout(resolve, 1000))
}
console.log(`任务走完流程：${finished}`)
await new Promise((resolve) => setTimeout(resolve, 500))

const taskLatencies = latencies
  .filter((item) => item.taskId === created.body.id)
  .map((i) => i.delta)
const sorted = [...taskLatencies].sort((a, b) => a - b)
console.log(
  `本任务收到推送 ${pushCount} 次，其中 ${taskLatencies.length} 次可与同一次写入对上账：` +
    `${taskLatencies.join('ms, ')}ms`
)
if (sorted.length) {
  console.log(
    `推送延迟 min=${sorted[0]}ms 中位=${sorted[Math.floor(sorted.length / 2)]}ms max=${sorted.at(-1)}ms`
  )
}
console.log(`SSE 订阅已建立：${demoReady}`)
console.log(
  `另一账号（${probeAccount.username}）收到的推送：${JSON.stringify(probeEvents)}（应为空）`
)

const removed = await json(`/api/tasks/${created.body.id}`, {
  method: 'DELETE',
  headers: bearer(demo)
})
console.log(`清理本次任务 -> HTTP ${removed.status}`)
demoStream.close()
probeStream.close()
