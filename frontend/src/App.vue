<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import {
  ArrowLeft,
  Delete,
  Download,
  Moon,
  RefreshRight,
  Sunny,
  SwitchButton,
  UploadFilled,
  VideoPlay
} from '@element-plus/icons-vue'
import { ApiError, api, token, type Details, type Task } from './api'
const tasks = ref<Task[]>([]),
  selected = ref<Details>(),
  loading = ref(false),
  uploading = ref(false),
  message = ref(''),
  query = ref(''),
  transcriptQuery = ref(''),
  currentMs = ref(0),
  videoError = ref(false),
  nextCursor = ref<string | null>(null),
  loadingMore = ref(false)
const authed = ref(Boolean(token.get())),
  registerMode = ref(false),
  authBusy = ref(false),
  authError = ref('')
const form = reactive({ username: '', password: '' })
const dark = ref(false)
const visible = computed(() =>
  tasks.value.filter((t) =>
    t.fileName.toLowerCase().includes(query.value.toLowerCase())
  )
)
const filteredTranscript = computed(
  () =>
    selected.value?.transcript.filter((s) =>
      s.text.includes(transcriptQuery.value)
    ) ?? []
)
const stageName: Record<string, string> = {
  IMPORT: '等待导入',
  AUDIO_EXTRACTION: '提取音频',
  TRANSCRIPTION: '本地转写',
  SUMMARY: '生成内容',
  COMPLETED: '已完成'
}
async function refresh() {
  loading.value = true
  try {
    const target = tasks.value.length
    const page = await api.list()
    const merged = [...page.items]
    let cursor = page.nextCursor ?? null
    let guard = 0
    // 轮询刷新时按最新游标补齐到已加载条数，避免把「加载更多」翻出的页回退掉。
    while (cursor && merged.length < target && guard < 50) {
      const next = await api.list(cursor)
      merged.push(...next.items)
      cursor = next.nextCursor ?? null
      guard++
    }
    tasks.value = merged
    nextCursor.value = cursor
    if (selected.value) {
      const current = merged.find((task) => task.id === selected.value?.task.id)
      if (current) selected.value = await api.details(current.id)
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      signOut('登录已过期，请重新登录。')
      return
    }
    message.value = describe(error, '无法连接本地服务，请确认后端已启动。')
  } finally {
    loading.value = false
  }
}
async function loadMore() {
  const cursor = nextCursor.value
  if (!cursor || loadingMore.value) return
  loadingMore.value = true
  try {
    const page = await api.list(cursor)
    tasks.value = [...tasks.value, ...page.items]
    nextCursor.value = page.nextCursor ?? null
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      signOut('登录已过期，请重新登录。')
      return
    }
    message.value = describe(error, '无法加载更多任务，请稍后重试。')
  } finally {
    loadingMore.value = false
  }
}
async function submitAuth() {
  authError.value = ''
  authBusy.value = true
  try {
    const result = registerMode.value
      ? await api.register(form.username, form.password)
      : await api.login(form.username, form.password)
    token.set(result.token)
    authed.value = true
    form.password = ''
    await refresh()
    startPolling()
  } catch (error) {
    authError.value = authMessage(error)
  } finally {
    authBusy.value = false
  }
}
function describe(error: unknown, fallback: string) {
  if (!(error instanceof Error)) return fallback
  return error instanceof ApiError && error.traceId
    ? `${error.message}（追踪号 ${error.traceId}）`
    : error.message
}
function authMessage(error: unknown) {
  if (!(error instanceof ApiError))
    return error instanceof Error
      ? error.message
      : '无法连接本地服务，请确认后端已启动。'
  if (error.status === 409) return '用户名已存在，请更换或直接登录。'
  if (error.status === 400)
    return '用户名需为 3-32 位字母、数字或下划线，密码至少 8 位。'
  if (error.status === 401) return '用户名或密码错误。'
  return describe(error, error.message)
}
function signOut(reason = '') {
  token.clear()
  authed.value = false
  tasks.value = []
  nextCursor.value = null
  selected.value = undefined
  authError.value = reason
  stopPolling()
}
function switchAuthMode() {
  registerMode.value = !registerMode.value
  authError.value = ''
}
async function choose(file: File) {
  const videoExtension = /\.(mp4|mov|mkv|webm|avi)$/i.test(file.name)
  if (!file.type.startsWith('video/') && !videoExtension) {
    message.value = '请选择视频文件。'
    return false
  }
  uploading.value = true
  try {
    await api.upload(file)
    message.value = '视频已加入本地处理队列。'
    await refresh()
  } catch (error) {
    message.value = describe(error, '导入失败，请检查文件后重试。')
  } finally {
    uploading.value = false
  }
  return false
}
async function action(
  task: Task,
  type: 'cancel' | 'retry' | 'retranscribe' | 'remove'
) {
  try {
    await api[type](task.id)
    if (selected.value?.task.id === task.id && type === 'remove')
      selected.value = undefined
    await refresh()
  } catch (error) {
    message.value = describe(error, '操作未完成，请稍后重试。')
  }
}
async function openTask(task: Task) {
  try {
    videoError.value = false
    selected.value = await api.details(task.id)
    transcriptQuery.value = ''
  } catch (error) {
    message.value = describe(error, '无法读取任务详情。')
  }
}
function seek(ms: number) {
  currentMs.value = ms
  const video = document.querySelector<HTMLVideoElement>('#video-player')
  if (video) {
    video.currentTime = ms / 1000
    video.play().catch(() => {})
  }
}
function stamp(ms: number) {
  const s = Math.floor(ms / 1000)
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}
function bytes(n: number) {
  return n > 1e9 ? (n / 1e9).toFixed(1) + ' GB' : (n / 1e6).toFixed(0) + ' MB'
}
function applyTheme(value: boolean) {
  dark.value = value
  document.documentElement.classList.toggle('dark', value)
  localStorage.setItem('video-workbench-theme', value ? 'dark' : 'light')
}
function removeTask(task: Task) {
  if (window.confirm(`确定删除“${task.fileName}”及其本地文件吗？`))
    return action(task, 'remove')
}
let timer: number | undefined
function startPolling() {
  stopPolling()
  timer = window.setInterval(refresh, 3000)
}
function stopPolling() {
  if (timer !== undefined) window.clearInterval(timer)
  timer = undefined
}
onMounted(async () => {
  const saved = localStorage.getItem('video-workbench-theme')
  applyTheme(
    saved
      ? saved === 'dark'
      : window.matchMedia('(prefers-color-scheme: dark)').matches
  )
  if (!authed.value) return
  await refresh()
  startPolling()
})
onUnmounted(stopPolling)
</script>
<template>
  <el-container class="shell"
    ><el-header class="header"
      ><div class="brand"><span></span>瞬析 VideoLab</div>
      <div class="context">本地视频解析工作台</div>
      <div class="account">
        <el-button v-if="authed" link :icon="SwitchButton" @click="signOut()"
          >退出登录</el-button
        ><el-button
          circle
          :icon="dark ? Sunny : Moon"
          :aria-label="dark ? '切换为浅色模式' : '切换为暗色模式'"
          @click="applyTheme(!dark)"
        /></div
    ></el-header>
    <el-main class="main"
      ><template v-if="!authed"
        ><section class="auth-panel">
          <h1>{{ registerMode ? '注册账号' : '登录' }}</h1>
          <p class="muted">
            任务与转写结果按账号隔离，登录后只能看到自己的任务。
          </p>
          <el-input
            v-model="form.username"
            placeholder="用户名"
            autocomplete="username"
            @keyup.enter="submitAuth"
          />
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="密码"
            autocomplete="current-password"
            @keyup.enter="submitAuth"
          />
          <p v-if="authError" class="auth-error">{{ authError }}</p>
          <el-button type="primary" :loading="authBusy" @click="submitAuth">{{
            registerMode ? '注册并登录' : '登录'
          }}</el-button>
          <el-button link type="primary" @click="switchAuthMode">{{
            registerMode ? '已有账号，去登录' : '还没有账号？注册一个'
          }}</el-button>
        </section></template
      ><template v-else-if="!selected"
        ><section class="heading">
          <div>
            <p>本地优先</p>
            <h1>视频解析任务</h1>
            <span
              >视频与音频仅保存在本机；内容生成阶段只发送必要的转写文本。</span
            >
          </div>
          <el-upload
            :show-file-list="false"
            :before-upload="choose"
            accept="video/*,.mp4,.mov,.mkv,.webm,.avi"
            ><el-button type="primary" :loading="uploading" :icon="UploadFilled"
              >导入视频</el-button
            ></el-upload
          >
        </section>
        <el-alert
          v-if="message"
          :title="message"
          type="info"
          show-icon
          :closable="true"
          @close="message = ''"
        />
        <section class="metrics">
          <div>
            <b>{{ tasks.length }}</b
            ><span>全部任务</span>
          </div>
          <div>
            <b>{{ tasks.filter((t) => t.status === 'PROCESSING').length }}</b
            ><span>正在处理</span>
          </div>
          <div>
            <b>{{ tasks.filter((t) => t.status === 'COMPLETED').length }}</b
            ><span>已完成</span>
          </div>
          <div>
            <b>{{ tasks.filter((t) => t.status === 'FAILED').length }}</b
            ><span>需要处理</span>
          </div>
        </section>
        <section class="task-panel">
          <div class="toolbar">
            <el-input
              v-model="query"
              placeholder="搜索文件名"
              clearable
            /><el-button
              :icon="RefreshRight"
              circle
              aria-label="刷新列表"
              @click="refresh"
            />
          </div>
          <el-table
            v-loading="loading"
            :data="visible"
            empty-text="还没有视频任务"
            class="tasks"
            ><el-table-column label="视频" min-width="260"
              ><template #default="{ row }"
                ><button class="file-link" @click="openTask(row)">
                  <VideoPlay />{{ row.fileName }}</button
                ><small
                  >{{ bytes(row.sizeBytes) }} ·
                  {{ new Date(row.updatedAt).toLocaleString('zh-CN') }}</small
                ></template
              ></el-table-column
            ><el-table-column label="阶段" width="130"
              ><template #default="{ row }"
                ><el-tag
                  :type="
                    row.status === 'FAILED'
                      ? 'danger'
                      : row.status === 'COMPLETED'
                        ? 'success'
                        : row.status === 'PROCESSING'
                          ? 'warning'
                          : 'info'
                  "
                  >{{ stageName[row.stage] }}</el-tag
                ></template
              ></el-table-column
            ><el-table-column label="进度" min-width="170"
              ><template #default="{ row }"
                ><el-progress
                  :percentage="row.progress"
                  :status="row.status === 'FAILED' ? 'exception' : undefined"
                /><small v-if="row.errorMessage">{{
                  row.errorMessage
                }}</small></template
              ></el-table-column
            ><el-table-column label="操作" width="150" fixed="right"
              ><template #default="{ row }"
                ><el-button
                  v-if="row.status === 'PROCESSING' || row.status === 'QUEUED'"
                  link
                  type="warning"
                  @click="action(row, 'cancel')"
                  >取消</el-button
                ><el-button
                  v-if="
                    row.status === 'FAILED' ||
                    row.status === 'CANCELLED' ||
                    row.stage === 'SUMMARY'
                  "
                  link
                  type="primary"
                  @click="action(row, 'retry')"
                  >重试</el-button
                ><el-button
                  v-if="row.status === 'COMPLETED'"
                  link
                  type="primary"
                  @click="action(row, 'retranscribe')"
                  >重新转写</el-button
                ><el-button
                  link
                  type="danger"
                  :icon="Delete"
                  aria-label="删除任务"
                  @click="removeTask(row)" /></template></el-table-column
          ></el-table>
          <div v-if="tasks.length" class="list-footer">
            <el-button
              v-if="nextCursor"
              :loading="loadingMore"
              @click="loadMore"
              >加载更多</el-button
            ><span v-else class="muted">没有更多了</span>
          </div>
        </section></template
      ><template v-else
        ><section class="detail-head">
          <div>
            <el-button :icon="ArrowLeft" text @click="selected = undefined"
              >返回任务</el-button
            >
            <h1>{{ selected.task.fileName }}</h1>
            <p>
              {{ bytes(selected.task.sizeBytes) }} ·
              {{ stageName[selected.task.stage] }} · 本地文件
            </p>
          </div>
          <div class="exports">
            <el-button
              v-for="format in ['md', 'json', 'srt']"
              :key="format"
              :icon="Download"
              tag="a"
              download
              :href="
                api.exportUrl(selected.task.id, format as 'md' | 'json' | 'srt')
              "
              >{{ format.toUpperCase() }}</el-button
            >
          </div>
        </section>
        <el-alert
          v-if="selected.task.errorMessage"
          :title="selected.task.errorMessage"
          type="warning"
          show-icon
          :closable="false"
        />
        <section class="watch-grid">
          <div class="video-wrap">
            <video
              v-if="!videoError"
              id="video-player"
              controls
              :src="api.videoUrl(selected.task.id)"
              @error="videoError = true"
              @timeupdate="
                currentMs =
                  ($event.target as HTMLVideoElement).currentTime * 1000
              "
            />
            <div v-else class="video-error">
              本地视频文件不可用，可能是部署前生成的旧任务路径，无法在容器中读取。
            </div>
            <div class="now">{{ stamp(currentMs) }}</div>
          </div>
          <aside class="chapter-panel">
            <h2>视频章节</h2>
            <p v-if="!selected.result" class="muted">
              完成内容生成后将显示章节。
            </p>
            <button
              v-for="chapter in selected.result?.chapters"
              :key="chapter.startMs"
              class="chapter"
              :class="{
                active:
                  currentMs >= chapter.startMs && currentMs < chapter.endMs
              }"
              @click="seek(chapter.startMs)"
            >
              <b>{{ stamp(chapter.startMs) }} · {{ chapter.title }}</b
              ><small
                >来源片段 #{{ chapter.sourceSegmentId
                }}<template
                  v-if="
                    chapter.sourceEndSegmentId &&
                    chapter.sourceEndSegmentId !== chapter.sourceSegmentId
                  "
                  >-#{{ chapter.sourceEndSegmentId }}</template
                ></small
              ><span class="chapter-quote">“{{ chapter.quote }}”</span>
            </button>
          </aside>
        </section>
        <section class="detail-grid">
          <article class="panel transcript">
            <div class="panel-title">
              <h2>逐字稿</h2>
              <el-input
                v-model="transcriptQuery"
                placeholder="搜索转写内容"
                clearable
              />
            </div>
            <p v-if="!filteredTranscript.length" class="muted">
              暂无转写结果或未匹配搜索条件。
            </p>
            <button
              v-for="segment in filteredTranscript"
              :key="segment.id"
              class="segment"
              :class="{
                active:
                  currentMs >= segment.startMs && currentMs < segment.endMs
              }"
              @click="seek(segment.startMs)"
            >
              <span>{{ stamp(segment.startMs) }}</span
              ><b>{{ segment.text }}</b
              ><small v-if="segment.translation">{{
                segment.translation
              }}</small>
            </button>
          </article>
          <aside class="panel result">
            <h2>摘要与要点</h2>
            <template v-if="selected.result"
              ><p class="summary">{{ selected.result.summary }}</p>
              <ul>
                <li v-for="point in selected.result.keyPoints" :key="point">
                  {{ point }}
                </li>
              </ul></template
            >
            <p v-else class="muted">
              转写完成后会自动生成摘要与章节；若生成失败，可在任务操作中点击“重试”。
            </p>
          </aside>
        </section></template
      ></el-main
    ></el-container
  >
</template>
