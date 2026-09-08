import { chromium } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'

const outputDir = path.resolve('artifacts')
await mkdir(outputDir, { recursive: true })
const browser = await chromium.launch({ channel: 'chrome' })
try {
  for (const [name, viewport] of Object.entries({
    desktop: { width: 1440, height: 1000 },
    mobile: { width: 390, height: 844 }
  })) {
    const page = await browser.newPage({ viewport })
    await page.goto('http://127.0.0.1:5173', { waitUntil: 'networkidle' })
    await page.locator('button').filter({ hasText: '导入视频' }).waitFor()
    await page.getByText('还没有视频任务').waitFor()
    await page.screenshot({
      path: path.join(outputDir, `workbench-${name}.png`),
      fullPage: true
    })
    if (name === 'desktop') {
      await page.getByRole('button', { name: '切换为暗色模式' }).click()
      await page.locator('html.dark').waitFor()
      await page.screenshot({
        path: path.join(outputDir, 'workbench-dark.png'),
        fullPage: true
      })
    }
    await page.close()
  }
  console.log('Workbench desktop and mobile screenshots written to artifacts/')
} finally {
  await browser.close()
}
