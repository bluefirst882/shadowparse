import { chromium } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'

// 打开 Grafana 中已通过 provisioning 配置的面板并截图，作为可观测性证据。
// 用法：node grafana-screenshot.mjs [输出文件名]
const baseUrl = process.env.GRAFANA_URL ?? 'http://127.0.0.1:3000'
const user = process.env.GRAFANA_ADMIN_USER ?? 'admin'
const password = process.env.GRAFANA_ADMIN_PASSWORD ?? 'workbench'
const outputDir = path.resolve('docs')
const outputPath = path.join(outputDir, process.argv[2] ?? '可观测性面板.png')

await mkdir(outputDir, { recursive: true })

const browser = await chromium.launch({ channel: 'chrome' })
try {
  // Grafana 的面板区域是内部滚动容器，fullPage 截不到视口以下的内容；
  // 面板变多后把视口调高（GRAFANA_VIEWPORT_HEIGHT）才能一次截全。
  const page = await browser.newPage({
    viewport: {
      width: Number(process.env.GRAFANA_VIEWPORT_WIDTH ?? 1600),
      height: Number(process.env.GRAFANA_VIEWPORT_HEIGHT ?? 660)
    }
  })
  await page.goto(`${baseUrl}/login`, { waitUntil: 'domcontentloaded' })
  await page.fill('input[name="user"]', user)
  await page.fill('input[name="password"]', password)
  await page.click('button[type="submit"]')
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), {
    timeout: 30000
  })
  await page.goto(
    `${baseUrl}/d/workbench-observability/?from=now-6h&to=now&kiosk`,
    { waitUntil: 'networkidle' }
  )
  // 等到面板标题渲染出来，再留一点时间给查询与绘制，避免截到空白图。
  await page
    .getByText('LLM Token 用量（累计）')
    .first()
    .waitFor({ timeout: 30000 })
  await page.waitForTimeout(6000)
  await page.screenshot({ path: outputPath, fullPage: true })
  console.log(`Grafana dashboard screenshot written to ${outputPath}`)
} finally {
  await browser.close()
}
