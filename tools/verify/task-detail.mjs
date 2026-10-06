import { chromium } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'

const taskName = process.env.TASK_NAME ?? '41611231579-1-192.mp4'
const outputDir = path.resolve('artifacts')
const outputPath = path.join(outputDir, 'task-detail-verified.png')

await mkdir(outputDir, { recursive: true })

const browser = await chromium.launch({ channel: 'chrome' })
try {
  const page = await browser.newPage({
    viewport: { width: 1440, height: 1000 }
  })
  await page.goto('http://127.0.0.1:5173', { waitUntil: 'domcontentloaded' })
  await page.getByRole('button', { name: '刷新列表' }).waitFor()
  await page.getByText(taskName, { exact: true }).click()
  await page.getByRole('heading', { name: '视频章节' }).waitFor()
  await page.getByRole('heading', { name: '摘要与要点' }).waitFor()
  await page.locator('.chapter').first().waitFor()
  await page.locator('.summary').waitFor()
  await page.locator('.segment small').first().waitFor()
  await page.screenshot({ path: outputPath, fullPage: true })

  const chapters = await page.locator('.chapter').count()
  const keyPoints = await page.locator('.result li').count()
  const translations = await page.locator('.segment small').count()
  console.log(
    `UI verified: ${chapters} chapters, ${keyPoints} key points, ${translations} translations`
  )
  console.log(`Screenshot: ${outputPath}`)
} finally {
  await browser.close()
}
