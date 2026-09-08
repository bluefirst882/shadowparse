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
}
export type Chapter = {
  startMs: number
  endMs: number
  title: string
  sourceSegmentId: number
}
export type Details = {
  task: Task
  transcript: Segment[]
  result?: { summary: string; keyPoints: string[]; chapters: Chapter[] }
}
const request = async <T>(path: string, init?: RequestInit): Promise<T> => {
  const response = await fetch('/api' + path, init)
  if (!response.ok) throw new Error('请求失败，请稍后重试')
  return response.status === 204 ? (undefined as T) : response.json()
}
export const api = {
  list: () => request<Task[]>('/tasks'),
  details: (id: string) => request<Details>(`/tasks/${id}/details`),
  upload: (file: File) => {
    const data = new FormData()
    data.append('file', file)
    return request<Task>('/tasks', { method: 'POST', body: data })
  },
  cancel: (id: string) =>
    request<void>(`/tasks/${id}/cancel`, { method: 'POST' }),
  retry: (id: string) =>
    request<void>(`/tasks/${id}/retry`, { method: 'POST' }),
  remove: (id: string) => request<void>(`/tasks/${id}`, { method: 'DELETE' }),
  videoUrl: (id: string) => `/api/tasks/${id}/video`,
  exportUrl: (id: string, format: 'md' | 'json' | 'srt') =>
    `/api/tasks/${id}/export/${format}`
}
