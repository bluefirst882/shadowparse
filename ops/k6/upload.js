// 上传接口基准压测：固定速率向 POST /api/tasks 投递同一个真实短视频（multipart）。
// 只压「接收路径」（落盘 + 入库 + 投递消息），不把下游 Whisper 串行处理算进这份延迟——
// 处理吞吐单独由队列深度增长与单任务耗时说明。
//
// 前置：先生成被压的文件（不入库，见 .gitignore）：
//   ffmpeg -y -ss 00:01:00 -t 4 -i <源视频> -vf scale=160:120 -c:v libx264 -preset ultrafast \
//     -crf 40 -c:a aac -b:a 48k ops/k6/fixtures/sample-4s.mp4
// 用法（容器内后端监听 8080，宿主映射是 8081）：
//   docker run --rm --network toni-2_default -v <repo>/ops/k6:/scripts -w /scripts \
//     -e BASE_URL=http://backend:8080 -e UPLOAD_RATE=3 -e UPLOAD_DURATION=40s -e RESULT_NAME=upload \
//     grafana/k6 run upload.js
import http from 'k6/http'
import { check } from 'k6'
import { Counter } from 'k6/metrics'
import { baseUrl, login, report, writeJson } from './lib.js'

const sample = open('./fixtures/sample-4s.mp4', 'b')
const created = new Counter('tasks_created')

export const options = {
  scenarios: {
    upload: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.UPLOAD_RATE || 3),
      timeUnit: '1s',
      duration: __ENV.UPLOAD_DURATION || '40s',
      preAllocatedVUs: 10,
      maxVUs: 60
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:upload}': ['p(95)<1000']
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
  return { token: login() }
}

export default function (data) {
  const response = http.post(
    `${baseUrl}/api/tasks`,
    { file: http.file(sample, 'sample-4s.mp4', 'video/mp4') },
    {
      headers: { Authorization: `Bearer ${data.token}` },
      tags: { name: 'upload' }
    }
  )
  const ok = check(response, {
    '上传 200 且返回任务 id': (r) => r.status === 200 && !!r.json('id')
  })
  if (ok) created.add(1)
}

export function handleSummary(data) {
  return Object.assign(writeJson(data), { stdout: report(data) })
}
