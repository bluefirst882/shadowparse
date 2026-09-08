import { chromium } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import path from "node:path";

const viewport = { width: 1440, height: 1000 };
const pageUrl = pathToFileURL(path.resolve("video-workbench-prototype.html")).href;
const outputDir = path.resolve("artifacts");
const outputPath = path.join(outputDir, "video-workbench-prototype.png");

await mkdir(outputDir, { recursive: true });

const browser = await chromium.launch({ channel: "chrome" });
try {
  const page = await browser.newPage({ viewport });
  await page.goto(pageUrl, { waitUntil: "networkidle" });
  await page.screenshot({ path: outputPath, fullPage: true });
  console.log(`Screenshot written to ${outputPath}`);
} finally {
  await browser.close();
}
