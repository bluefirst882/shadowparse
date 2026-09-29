// 查询接口基准压测：列表 / 详情 / 明细三种只读请求按 7:2:1 混合，阶梯加压。
// 用法（容器内后端监听 8080，宿主映射是 8081）：
//   docker run --rm --network toni-2_default -v <repo>/ops/k6:/scripts -w /scripts \
//     -e BASE_URL=http://backend:8080 -e RESULT_NAME=query \
//     grafana/k6 run query.js
import http from 'k6/http'
import { check } from 'k6'
import { baseUrl, login, pick, report, taskIds, writeJson } from './lib.js'

export const options = {
  scenarios: {
    read: {
      executor: 'ramping-arrival-rate',
      startRate: Number(__ENV.READ_START || 20),
      timeUnit: '1s',
      preAllocatedVUs: 40,
      maxVUs: 400,
      // 每段 30s 的到达速率，用 READ_TARGETS 覆盖即可换一档压力（如 200,300,400,400）。
      stages: (__ENV.READ_TARGETS || '40,80,120,120')
        .split(',')
        .map(Number)
        .map((target) => ({
          target,
          duration: __ENV.READ_STAGE_DURATION || '30s'
        }))
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:list}': ['p(95)<300'],
    'http_req_duration{name:detail}': ['p(95)<300'],
    'http_req_duration{name:details}': ['p(95)<800']
  },
  summaryTrendStats: [
    'avg',
    'min',
    'med',
    'p(90)',
    'p(95)',
    'p(99)',
    'max',
    'count'
  ]
}

export function setup() {
  const token = login()
  return { token, ids: taskIds(token, 20) }
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}` }
  const id = pick(data.ids)
  const roll = Math.random()
  if (roll < 0.7) {
    const response = http.get(`${baseUrl}/api/tasks?limit=20`, {
      headers,
      tags: { name: 'list' }
    })
    check(response, { '列表 200': (r) => r.status === 200 })
  } else if (roll < 0.9) {
    const response = http.get(`${baseUrl}/api/tasks/${id}`, {
      headers,
      tags: { name: 'detail' }
    })
    check(response, { '详情 200': (r) => r.status === 200 })
  } else {
    // 明细是整个任务的全部片段与摘要，是读放大最重的那个接口。
    const response = http.get(`${baseUrl}/api/tasks/${id}/details`, {
      headers,
      tags: { name: 'details' }
    })
    check(response, { '明细 200': (r) => r.status === 200 })
  }
}

export function handleSummary(data) {
  return Object.assign(writeJson(data), { stdout: report(data) })
}
