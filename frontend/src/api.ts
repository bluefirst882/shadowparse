export type Stage =
  'IMPORT' | 'AUDIO_EXTRACTION' | 'TRANSCRIPTION' | 'SUMMARY' | 'COMPLETED'
export type Task = {
  id: string
  fileName: string
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
}
export type Details = {
  task: Task
  transcript: Segment[]
  result?: { summary: string; keyPoints: string[]; chapters: Chapter[] }
}
const request = async <T>(path: string, init?: RequestInit): Promise<T> => {
  const headers = new Headers(init?.headers)
  const current = token.get()
  if (current) headers.set('Authorization', `Bearer ${current}`)
  const response = await fetch('/api' + path, { ...init, headers })
  const body = await response.text()
  if (!response.ok) {
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
    if (response.status === 401) token.clear()
    throw new ApiError(message, response.status, code, traceId)
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
export const api = {
  list: () => request<Task[]>('/tasks'),
  details: (id: string) => request<Details>(`/tasks/${id}/details`),
  upload: (file: File) => {
    const data = new FormData()
    data.append('file', file)
    return request<Task>('/tasks', { method: 'POST', body: data })
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
  // 视频与导出由浏览器直接发起，无法附加请求头，因此以查询参数携带令牌。
  videoUrl: (id: string) =>
    `/api/tasks/${id}/video?access_token=${encodeURIComponent(token.get() ?? '')}`,
  exportUrl: (id: string, format: 'md' | 'json' | 'srt') =>
    `/api/tasks/${id}/export/${format}?access_token=${encodeURIComponent(token.get() ?? '')}`
}
