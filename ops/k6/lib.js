// k6 脚本共用部分：登录拿 JWT、取若干任务 id 供详情接口复用。
// 只依赖 k6 内置模块，不引外部 CDN，保证容器里离线也能跑。
import http from 'k6/http'
import { fail } from 'k6'

export const baseUrl = __ENV.BASE_URL || 'http://localhost:8081'

export function login() {
  const username = __ENV.WORKBENCH_USER || 'demo'
  const password = __ENV.WORKBENCH_PASSWORD || 'demo1234'
  const response = http.post(
    `${baseUrl}/api/auth/login`,
    JSON.stringify({ username, password }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } }
  )
  if (response.status !== 200) {
    fail(`登录失败：HTTP ${response.status} ${response.body}`)
  }
  return response.json('token')
}

// 详情类接口每次打同一个任务会命中数据库与文件系统的同一行，压不出真实分布，
// 所以先取一页 id 备随机挑选。
export function taskIds(token, size) {
  const response = http.get(`${baseUrl}/api/tasks?limit=${size}`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'setup-list' }
  })
  if (response.status !== 200) {
    fail(`取任务列表失败：HTTP ${response.status} ${response.body}`)
  }
  const ids = response.json('items').map((item) => item.id)
  if (ids.length === 0) {
    fail('任务列表为空，压测前需要至少一条已完成任务（读取场景要复用真实数据）')
  }
  return ids
}

export function pick(list) {
  return list[Math.floor(Math.random() * list.length)]
}

// 不引 jslib 的 textSummary：只打印本次关心的几个指标，自己拼更可控。
export function report(data, extraLines) {
  const lines = [
    `${'='.repeat(60)}`,
    `场景 ${data.state.testRunDurationMs} ms`,
    ''
  ]
  const wanted = [
    'http_reqs',
    'http_req_failed',
    'http_req_duration',
    'http_req_duration{name:list}',
    'http_req_duration{name:detail}',
    'http_req_duration{name:details}',
    'http_req_duration{name:upload}',
    'iterations',
    'vus_max'
  ]
  for (const name of wanted) {
    const metric = data.metrics[name]
    if (!metric) continue
    const values = metric.values
    lines.push(
      [
        name.padEnd(38),
        `count=${values.count ?? '-'}`,
        `avg=${fmt(values.avg)}ms`,
        `med=${fmt(values.med)}ms`,
        `p95=${fmt(values['p(95)'])}ms`,
        `p99=${fmt(values['p(99)'])}ms`,
        `max=${fmt(values.max)}ms`,
        values.rate === undefined ? '' : `rate=${values.rate.toFixed(4)}`
      ]
        .filter(Boolean)
        .join('  ')
    )
  }
  for (const line of extraLines || []) lines.push(line)
  lines.push('='.repeat(60), '')
  return lines.join('\n')
}

function fmt(value) {
  return value === undefined ? '-' : value.toFixed(2)
}

export function writeJson(data) {
  const dir = __ENV.RESULT_DIR || 'results'
  const name = __ENV.RESULT_NAME || 'summary'
  return { [`${dir}/${name}.json`]: JSON.stringify(data, null, 2) }
}
