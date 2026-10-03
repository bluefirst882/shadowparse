export type Stage =
  'IMPORT' | 'AUDIO_EXTRACTION' | 'TRANSCRIPTION' | 'SUMMARY' | 'COMPLETED'
export type Task = {
  id: string
  fileName: string
  // 自定义任务名：为空时前端回退展示 fileName。
  displayName?: string | null
  sizeBytes: number
  status: 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  stage: Stage
  progress: number
  errorMessage?: string
  updatedAt: string
}
export type Segment = {
  id: number
  startMs: number
  endMs: number
  text: string
  translation?: string
}
export type Chapter = {
  startMs: number
  endMs: number
  title: string
  sourceSegmentId: number
  sourceEndSegmentId?: number
  // quote 为该章节引用的转写原文原句，服务端已核验其逐字出自来源片段。
  quote: string
}
export type Details = {
  task: Task
  transcript: Segment[]
  result?: { summary: string; keyPoints: string[]; chapters: Chapter[] }
}
// 列表接口按游标分页：nextCursor 为 null 表示没有更多数据。
export type TaskPage = { items: Task[]; nextCursor?: string | null }
// 分片上传会话：receivedChunks 是服务端已落盘的分片序号，续传时只补缺的那些。
export type UploadSession = {
  uploadId: string
  fileName: string
  sizeBytes: number
  chunkSize: number
  receivedChunks: number[]
}
const errorFrom = (status: number, body: string) => {
  let message = '请求失败，请稍后重试'
  let code: string | undefined
  let traceId: string | undefined
  try {
    const payload = JSON.parse(body) as {
      message?: string
      code?: string
      traceId?: string
    }
    if (payload.message) message = payload.message
    code = payload.code
    traceId = payload.traceId
  } catch {
    // Keep the stable fallback for non-JSON error responses.
  }
  return new ApiError(message, status, code, traceId)
}
const request = async <T>(path: string, init?: RequestInit): Promise<T> => {
  const headers = new Headers(init?.headers)
  const current = token.get()
  if (current) headers.set('Authorization', `Bearer ${current}`)
  const response = await fetch('/api' + path, { ...init, headers })
  const body = await response.text()
  if (!response.ok) {
    if (response.status === 401) token.clear()
    throw errorFrom(response.status, body)
  }
  return body.trim() ? (JSON.parse(body) as T) : (undefined as T)
}
export const TOKEN_KEY = 'video-workbench-token'
export const token = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (value: string) => localStorage.setItem(TOKEN_KEY, value),
  clear: () => localStorage.removeItem(TOKEN_KEY)
}
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
    readonly traceId?: string
  ) {
    super(message)
  }
}
export type AuthResult = { token: string; username: string }
const credentials = (username: string, password: string) => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username, password })
})
/**
 * 单片的提交走 XHR 而不是 fetch：上传方向的进度事件目前只有 XHR 提供，进度条靠它驱动。
 * 请求体是原始字节（不是 multipart），服务端按偏移直接写入文件。
 */
const putChunk = (
  uploadId: string,
  index: number,
  data: Blob,
  onProgress: (loadedBytes: number) => void
) =>
  new Promise<UploadSession>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', `/api/tasks/uploads/${uploadId}/chunks/${index}`)
    xhr.setRequestHeader('Content-Type', 'application/octet-stream')
    const current = token.get()
    if (current) xhr.setRequestHeader('Authorization', `Bearer ${current}`)
    xhr.upload.addEventListener('progress', (event) => {
      if (event.lengthComputable) onProgress(event.loaded)
    })
    xhr.addEventListener('load', () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(
          xhr.responseText.trim()
            ? (JSON.parse(xhr.responseText) as UploadSession)
            : (undefined as unknown as UploadSession)
        )
        return
      }
      if (xhr.status === 401) token.clear()
      reject(errorFrom(xhr.status, xhr.responseText))
    })
    xhr.addEventListener('error', () => {
      reject(new ApiError('上传分片失败，请检查网络后重试', 0))
    })
    xhr.send(data)
  })
export const api = {
  list: (cursor?: string | null) =>
    request<TaskPage>(
      `/tasks${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`
    ),
  details: (id: string) => request<Details>(`/tasks/${id}/details`),
  // 分片上传：先按「文件名 + 大小」拿到会话，再只补缺失的分片，最后登记成任务。
  upload: async (file: File, onProgress?: (sentBytes: number) => void) => {
    const session = await request<UploadSession>('/tasks/uploads', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fileName: file.name, sizeBytes: file.size })
    })
    const chunkSize = session.chunkSize
    const chunkCount = Math.ceil(session.sizeBytes / chunkSize)
    const sizeOf = (index: number) =>
      Math.min(chunkSize, session.sizeBytes - index * chunkSize)
    const done = new Set(session.receivedChunks)
    const sentSoFar = () =>
      Array.from(done).reduce((sum, index) => sum + sizeOf(index), 0)
    onProgress?.(sentSoFar())
    for (let index = 0; index < chunkCount; index++) {
      if (done.has(index)) continue
      const start = index * chunkSize
      await putChunk(
        session.uploadId,
        index,
        file.slice(start, start + sizeOf(index)),
        (loaded) => onProgress?.(sentSoFar() + loaded)
      )
      done.add(index)
      onProgress?.(sentSoFar())
    }
    return request<Task>(`/tasks/uploads/${session.uploadId}/complete`, {
      method: 'POST'
    })
  },
  login: (username: string, password: string) =>
    request<AuthResult>('/auth/login', credentials(username, password)),
  register: (username: string, password: string) =>
    request<AuthResult>('/auth/register', credentials(username, password)),
  cancel: (id: string) =>
    request<void>(`/tasks/${id}/cancel`, { method: 'POST' }),
  retry: (id: string) =>
    request<void>(`/tasks/${id}/retry`, { method: 'POST' }),
  retranscribe: (id: string) =>
    request<void>(`/tasks/${id}/retranscribe`, { method: 'POST' }),
  remove: (id: string) => request<void>(`/tasks/${id}`, { method: 'DELETE' }),
  // 任务改名：只改展示名，原文件名保留（导出、溯源仍用它）。
  rename: (id: string, name: string) =>
    request<Task>(`/tasks/${id}/name`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name })
    }),
  // 推送通道与视频流同理：EventSource 不能自定义请求头，因此以查询参数携带令牌。
  streamUrl: () =>
    `/api/tasks/stream?access_token=${encodeURIComponent(token.get() ?? '')}`,
  // 视频与导出由浏览器直接发起，无法附加请求头，因此以查询参数携带令牌。
  videoUrl: (id: string) =>
    `/api/tasks/${id}/video?access_token=${encodeURIComponent(token.get() ?? '')}`,
  exportUrl: (id: string, format: 'md' | 'json' | 'srt') =>
    `/api/tasks/${id}/export/${format}?access_token=${encodeURIComponent(token.get() ?? '')}`
}
