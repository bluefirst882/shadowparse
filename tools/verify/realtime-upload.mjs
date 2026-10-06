// P3-3 浏览器端验收：任务变化靠 SSE 推送（不再轮询）+ 分片上传进度条。
// 用法：node tools/verify/realtime-upload.mjs   （需要 backend/frontend 容器已启动）
// 脚本自己准备测试文件（小文件取 k6 的 4 秒样本，大文件拼到 3 片），跑完把产生的任务删掉。
import { chromium } from '@playwright/test'
import { copyFile, mkdtemp, readFile, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

const BASE = process.env.BASE ?? 'http://127.0.0.1:5174'
const workspace = await mkdtemp(path.join(os.tmpdir(), 'p33-verify-'))
const stamp0 = Date.now()
const smallName = `verify-small-${stamp0}.mp4`
const bigName = `verify-big-${stamp0}.mp4`
const small = path.join(workspace, smallName)
const big = path.join(workspace, bigName)
const sample = await readFile(path.resolve('ops/k6/fixtures/sample-4s.mp4'))
await copyFile(path.resolve('ops/k6/fixtures/sample-4s.mp4'), small)
// 12MB 出头 = 3 个 5MiB 分片；内容只是同一段样本重复，够用来验证上传链路。
const copies = Math.ceil((12 * 1024 * 1024) / sample.length)
await writeFile(
  big,
  Buffer.concat(Array.from({ length: copies }, () => sample))
)

const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
const listCalls = []
page.on('request', (request) => {
  const url = new URL(request.url())
  if (url.pathname === '/api/tasks' && request.method() === 'GET')
    listCalls.push(Date.now())
})
page.on('response', async (response) => {
  const url = new URL(response.url())
  if (url.pathname.startsWith('/api/tasks')) {
    const body =
      response.status() >= 400
        ? ` body=${await response.text().catch(() => '')}`
        : ''
    console.log(
      `  <-- ${response.status()} ${response.request().method()} ${url.pathname}${url.search}${body}`
    )
  }
})
page.on('console', (message) =>
  console.log(`  [console:${message.type()}] ${message.text()}`)
)
const stamp = () =>
  `${new Date().toLocaleTimeString('zh-CN', { hour12: false })}.${String(Date.now() % 1000).padStart(3, '0')}`

// 计数必须写在浏览器侧：只有它才看得到 EventSource 实际建立的连接与收到的推送事件。
await page.addInitScript(() => {
  const Original = window.EventSource
  window.__sse = { connections: 0, events: 0 }
  window.EventSource = class extends Original {
    constructor(...args) {
      super(...args)
      window.__sse.connections += 1
      this.addEventListener('tasks', () => {
        window.__sse.events += 1
      })
    }
  }
})

try {
  await page.goto(BASE, { waitUntil: 'domcontentloaded' })
  await page.getByPlaceholder('用户名').fill('demo')
  await page.getByPlaceholder('密码').fill('demo1234')
  await page.getByRole('button', { name: '登录' }).click()
  await page.locator('.tasks tbody tr').first().waitFor()
  console.log(
    `登录完成，既有任务 ${await page.locator('.tasks tbody tr').count()} 行`
  )

  // 空闲窗口：先等 2s 让 EventSource 的 onopen 补刷（建连时的一次性刷新）落地，再开始计数。
  await page.waitForTimeout(2000)
  const idleStart = listCalls.length
  await page.waitForTimeout(8000)
  console.log(
    `空闲 8s 内 /api/tasks 列表请求数=${listCalls.length - idleStart}（轮询已移除，应为 0）`
  )
  console.log(
    `SSE：${JSON.stringify(await page.evaluate(() => window.__sse))}（连接应为 1）`
  )

  // 小文件：只看任务行的出现与阶段推进，不手动刷新。
  const row = page.locator('.tasks tbody tr').filter({ hasText: smallName })
  const before = listCalls.length
  await page.setInputFiles('input[type=file]', small)
  await page.waitForTimeout(4000)
  console.log(
    `上传后提示：${await page.locator('.el-alert__title').allInnerTexts()}`
  )
  await row.waitFor({ timeout: 15000 })
  console.log(`[${stamp()}] 新任务行出现（未手动刷新、未轮询）`)

  const stages = []
  const deadline = Date.now() + 120000
  let last = ''
  while (Date.now() < deadline) {
    const text = (await row.innerText()).replace(/\s+/g, ' ').trim()
    if (text !== last) {
      last = text
      stages.push(text)
      console.log(`[${stamp()}] ${text}`)
    }
    if (text.includes('已完成')) break
    await page.waitForTimeout(400)
  }
  const sse = await page.evaluate(() => window.__sse)
  console.log(
    `阶段变化次数=${stages.length}；推送事件=${sse.events}；由推送触发的列表刷新=${listCalls.length - before}`
  )

  // 真正限速上传（而不是在 Playwright 里拦下请求再放行）：只有数据真的在网上传，
  // 浏览器才会派发 XHR 的上传进度事件，进度条上的数字才有意义。
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('Network.enable')
  const throttle = (uploadThroughput) =>
    cdp.send('Network.emulateNetworkConditions', {
      offline: false,
      latency: 0,
      downloadThroughput: -1,
      uploadThroughput
    })
  await throttle(1536 * 1024)
  await page.setInputFiles('input[type=file]', big)
  await page
    .locator('.uploader .el-progress')
    .waitFor({ state: 'visible', timeout: 15000 })
  await page.waitForTimeout(1500)
  const percent = await page.locator('.uploader small').innerText()
  const bar = await page
    .locator('.uploader .el-progress')
    .getAttribute('aria-valuenow')
  console.log(
    `[${stamp()}] 上传进度 UI：${percent}（进度条 aria-valuenow=${bar}，可见）`
  )
  await page.screenshot({ path: 'docs/前端分片上传进度.png' })
  await throttle(-1)
  await page
    .locator('.tasks tbody tr')
    .filter({ hasText: bigName })
    .first()
    .waitFor({ timeout: 60000 })
  console.log(`[${stamp()}] 大文件分片上传完成并出现在列表中`)

  // 收尾：删掉本次验证产生的任务，多次运行不会在列表里堆垃圾。
  const token = await page.evaluate(() =>
    localStorage.getItem('video-workbench-token')
  )
  const call = async (apiPath, init) =>
    fetch(`${BASE}/api${apiPath}`, {
      ...init,
      headers: { Authorization: `Bearer ${token}`, ...init?.headers }
    })
  const created = await (await call('/tasks?limit=100')).json()
  for (const task of created.items.filter((item) =>
    [smallName, bigName].includes(item.fileName)
  )) {
    const response = await call(`/tasks/${task.id}`, { method: 'DELETE' })
    console.log(`清理任务 ${task.fileName} -> HTTP ${response.status}`)
  }
} finally {
  await browser.close()
}
