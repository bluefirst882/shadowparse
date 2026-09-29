// 摘要结构化输出的真实调用回放（P1-1 的「真实调用脚本」部分）。
//
// 与离线回放（EvalReplayTest）互补：离线集验证「同一批原始输出过新旧口径的判定差异」，本脚本则用真实 API
// 采样，回答「线上模型在当前提示词下的结构非法率是多少」。两处共用同一份提示词资源与 schema 文件，不复制提示词。
//
// 用法：
//   node tools/eval-live.mjs             # 默认采样 3 次
//   node tools/eval-live.mjs --samples=5
//
// 产出：
//   backend/target/eval-live-report.md      采样统计（新口径 / 旧口径结构非法率、误收数）
//   backend/target/eval-live/sample-N.json  每次采样的原始返回与服务端判定（证据留档）
//
// 取值来源：优先读环境变量 LLM_BASE_URL / LLM_API_KEY / LLM_MODEL / LLM_REASONING_EFFORT，
// 缺失时回落到仓库根目录的 .env（与容器同源），不在此文件里硬编码任何密钥。

import { readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const REPORT_DIR = join(ROOT, 'backend', 'target', 'eval-live')
const REPORT_PATH = join(ROOT, 'backend', 'target', 'eval-live-report.md')

const sampled = Number(
  (process.argv.find((arg) => arg.startsWith('--samples=')) || '').split(
    '='
  )[1] || 3
)

function readEnvFile() {
  try {
    return Object.fromEntries(
      readFileSync(join(ROOT, '.env'), 'utf8')
        .split(/\r?\n/)
        .filter((line) => line.includes('=') && !line.trim().startsWith('#'))
        .map((line) => {
          const index = line.indexOf('=')
          return [line.slice(0, index).trim(), line.slice(index + 1).trim()]
        })
    )
  } catch {
    return {}
  }
}

const env = { ...readEnvFile(), ...process.env }
const baseUrl = (env.LLM_BASE_URL || 'https://api.deepseek.com/v1').replace(
  /\/+$/,
  ''
)
const apiKey = env.LLM_API_KEY || ''
const model = env.LLM_MODEL || 'deepseek-v4-flash'
const reasoningEffort = env.LLM_REASONING_EFFORT || 'low'

const read = (path) => readFileSync(join(ROOT, path), 'utf8')
const schemaJson = read(
  'backend/src/main/resources/schemas/summarize.json'
).trim()
const systemPrompt = read(
  'backend/src/main/resources/prompts/summarize.v1.system.txt'
).trim()
const userTemplate = read(
  'backend/src/main/resources/prompts/summarize.v1.user.txt'
)
const transcript = JSON.parse(
  read('backend/src/test/resources/eval/summarize-transcript.json')
).segments

// 与 LlmClient.sourceLine 一致，保证提示词输入形态相同。
const source = transcript
  .map((s) => `[id=${s.id}, startMs=${s.startMs}, endMs=${s.endMs}] ${s.text}`)
  .join('\n')
const userPrompt = userTemplate
  .replaceAll('{{schema}}', schemaJson)
  .replaceAll('{{retryHint}}', '')
  .replaceAll('{{input}}', source)

/** 与 LlmClient.extractJson 一致：剥掉 markdown 围栏，再取首个 { 到末个 } 之间的内容。 */
function extractJson(content) {
  let value = (content || '').trim()
  if (value.startsWith('```') && value.endsWith('```')) {
    const firstLine = value.indexOf('\n')
    value =
      firstLine >= 0
        ? value.slice(firstLine + 1, value.length - 3).trim()
        : value
  }
  const start = value.indexOf('{')
  const end = value.lastIndexOf('}')
  if (start < 0 || end < start) throw new Error('内容服务未返回有效 JSON')
  return value.slice(start, end + 1)
}

/** 与 LlmJsonSchema.validate 一致：只校验 type / required / properties / items 这一子集。 */
function validateSchema(value, schema, path = '$', violations = []) {
  const type = schema.type || ''
  const actual =
    value === null ? 'null' : Array.isArray(value) ? 'array' : typeof value
  const matches =
    !type ||
    (type === 'object' && actual === 'object' && !Array.isArray(value)) ||
    (type === 'array' && Array.isArray(value)) ||
    (type === 'string' && actual === 'string') ||
    (type === 'boolean' && actual === 'boolean') ||
    (type === 'number' && actual === 'number') ||
    (type === 'integer' && actual === 'number' && Number.isInteger(value))
  if (!matches) {
    violations.push(`${path} 期望 ${type}，实际为 ${actual}`)
    return violations
  }
  if (type === 'object' && actual === 'object' && !Array.isArray(value)) {
    for (const name of schema.required || [])
      if (!(name in value)) violations.push(`${path}.${name} 缺少必填字段`)
    for (const [name, child] of Object.entries(schema.properties || {}))
      if (name in value)
        validateSchema(value[name], child, `${path}.${name}`, violations)
  } else if (type === 'array' && Array.isArray(value) && schema.items) {
    value.forEach((item, index) =>
      validateSchema(item, schema.items, `${path}[${index}]`, violations)
    )
  }
  return violations
}

/** 新口径：空内容 / 非法 JSON / 不符 schema。与 LlmClient.parseStructured 的分类一致。 */
function currentGate(content) {
  if (!content || !content.trim())
    return { accepted: false, reason: 'empty_content' }
  let parsed
  try {
    parsed = JSON.parse(extractJson(content))
  } catch {
    return { accepted: false, reason: 'invalid_json' }
  }
  const violations = validateSchema(parsed, JSON.parse(schemaJson))
  if (violations.length > 0)
    return { accepted: false, reason: 'schema_violation', violations }
  return { accepted: true, reason: null }
}

/** 旧口径：能解析成对象 + summary / keyPoints / chapters 三个顶层字段非空。 */
function legacyGate(content) {
  let parsed
  try {
    parsed = JSON.parse(content)
  } catch {
    return { accepted: false, reason: 'legacy_parse_failure' }
  }
  const empty =
    typeof parsed?.summary !== 'string' ||
    !parsed.summary.trim() ||
    !Array.isArray(parsed?.keyPoints) ||
    parsed.keyPoints.length === 0 ||
    !Array.isArray(parsed?.chapters) ||
    parsed.chapters.length === 0
  return empty
    ? { accepted: false, reason: 'legacy_empty_field' }
    : { accepted: true, reason: null }
}

async function callOnce(sample) {
  const response = await fetch(`${baseUrl}/chat/completions`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiKey}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      model,
      reasoning_effort: reasoningEffort,
      messages: [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userPrompt }
      ],
      response_format: { type: 'json_object' }
    })
  })
  const body = await response.json()
  if (!response.ok)
    throw new Error(
      `HTTP ${response.status}: ${JSON.stringify(body).slice(0, 300)}`
    )
  const content = body?.choices?.[0]?.message?.content ?? ''
  const record = {
    sample,
    model,
    content,
    usage: body.usage ?? null,
    current: currentGate(content),
    legacy: legacyGate(content)
  }
  writeFileSync(
    join(REPORT_DIR, `sample-${sample}.json`),
    JSON.stringify(record, null, 2),
    'utf8'
  )
  return record
}

async function main() {
  if (!apiKey) throw new Error('缺少 LLM_API_KEY：请在环境变量或 .env 中配置')
  mkdirSync(REPORT_DIR, { recursive: true })
  console.log(
    `模型=${model} 采样=${sampled} 输入片段=${transcript.length} 提示词=${userPrompt.length} 字符`
  )

  const records = []
  for (let sample = 1; sample <= sampled; sample++) {
    const record = await callOnce(sample)
    records.push(record)
    console.log(
      `#${sample} 新口径=${record.current.accepted ? 'accept' : 'reject/' + record.current.reason}` +
        ` 旧口径=${record.legacy.accepted ? 'accept' : 'reject/' + record.legacy.reason}`
    )
  }

  const currentRejects = records.filter((r) => !r.current.accepted)
  const legacyRejects = records.filter((r) => !r.legacy.accepted)
  const legacyFalseAccepts = records.filter(
    (r) => r.legacy.accepted && !r.current.accepted
  )
  const rate = (count) => `${count}/${records.length}`

  const report = [
    '# 摘要结构化输出真实调用抽样报告',
    '',
    `> 由 \`tools/eval-live.mjs\` 生成：用 \`prompts/summarize.v1.*\` 与 \`schemas/summarize.json\` 对真实转写样本调用真实 API ${records.length} 次，`,
    '> 对每次原始返回同时按新口径（本文本 → JSON → schema）与旧口径（解析成功 + 三个顶层字段非空）判定。',
    '',
    `- 模型：\`${model}\`，reasoning_effort=\`${reasoningEffort}\``,
    `- 输入：\`backend/src/test/resources/eval/summarize-transcript.json\`（真实任务 6493bbed 的全部 ${transcript.length} 个片段）`,
    `- 样本量：${records.length}`,
    `- **新口径结构非法率：**${rate(currentRejects.length)}${currentRejects.length ? '（' + currentRejects.map((r) => `#${r.sample} ${r.current.reason}`).join('、') + '）' : ''}`,
    `- 旧口径结构非法率：${rate(legacyRejects.length)}${legacyRejects.length ? '（' + legacyRejects.map((r) => `#${r.sample} ${r.legacy.reason}`).join('、') + '）' : ''}`,
    `- 旧口径误收（旧口径通过、新口径拒绝）：${legacyFalseAccepts.length}`,
    '',
    '## 每次采样',
    '',
    '| 采样 | 新口径 | 旧口径 | 章节数 | prompt tokens | completion tokens |',
    '| --- | --- | --- | --- | --- | --- |',
    ...records.map((r) => {
      const chapters = (() => {
        try {
          return JSON.parse(extractJson(r.content)).chapters?.length ?? '-'
        } catch {
          return '-'
        }
      })()
      return `| #${r.sample} | ${r.current.accepted ? 'accept' : 'reject/' + r.current.reason} | ${r.legacy.accepted ? 'accept' : 'reject/' + r.legacy.reason} | ${chapters} | ${r.usage?.prompt_tokens ?? '-'} | ${r.usage?.completion_tokens ?? '-'} |`
    }),
    '',
    '- 原始返回与服务端判定留档在 `backend/target/eval-live/sample-*.json`。',
    '- 口径说明：本脚本只复现「结构化闸门」，不含 `ResultValidator` 的时间轴与引文核验（那部分由离线回放 `EvalReplayTest` 覆盖）。'
  ].join('\n')

  writeFileSync(REPORT_PATH, report + '\n', 'utf8')
  console.log(report)
}

main().catch((error) => {
  console.error(error.message)
  process.exit(1)
})
