<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  ArrowLeft,
  Delete,
  Download,
  Moon,
  RefreshRight,
  Sunny,
  UploadFilled,
  VideoPlay
} from '@element-plus/icons-vue'
import { api, type Details, type Task } from './api'
const tasks = ref<Task[]>([]),
  selected = ref<Details>(),
  loading = ref(false),
  uploading = ref(false),
  message = ref(''),
  query = ref(''),
  transcriptQuery = ref(''),
  currentMs = ref(0)
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
    tasks.value = await api.list()
  } catch {
    message.value = '无法连接本地服务，请确认后端已启动。'
  } finally {
    loading.value = false
  }
}
async function choose(file: File) {
  if (!file.type.startsWith('video/')) {
    message.value = '请选择视频文件。'
    return false
  }
  uploading.value = true
  try {
    await api.upload(file)
    message.value = '视频已加入本地处理队列。'
    await refresh()
  } catch {
    message.value = '导入失败，请检查文件后重试。'
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
  } catch {
    message.value = '操作未完成，请稍后重试。'
  }
}
async function openTask(task: Task) {
  try {
    selected.value = await api.details(task.id)
    transcriptQuery.value = ''
  } catch {
    message.value = '无法读取任务详情。'
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
let timer: number
onMounted(async () => {
  const saved = localStorage.getItem('video-workbench-theme')
  applyTheme(
    saved
      ? saved === 'dark'
      : window.matchMedia('(prefers-color-scheme: dark)').matches
  )
  await refresh()
  timer = window.setInterval(refresh, 3000)
})
onUnmounted(() => clearInterval(timer))
</script>
<template>
  <el-container class="shell"
    ><el-header class="header"
      ><div class="brand"><span></span>瞬析 VideoLab</div>
      <div class="context">本地视频解析工作台</div>
      <el-button
        circle
        :icon="dark ? Sunny : Moon"
        :aria-label="dark ? '切换为浅色模式' : '切换为暗色模式'"
        @click="applyTheme(!dark)" /></el-header
    ><el-main class="main"
      ><template v-if="!selected"
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
            accept="video/*"
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
          @close="message = ''" />
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
                    row.status === 'QUEUED' ||
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
                  @click="action(row, 'remove')" /></template></el-table-column
          ></el-table></section></template
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
              id="video-player"
              controls
              :src="api.videoUrl(selected.task.id)"
              @timeupdate="
                currentMs =
                  ($event.target as HTMLVideoElement).currentTime * 1000
              "
            />
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
              >
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
              ><b>{{ segment.text }}</b> ><small v-if="segment.translation">{{
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
              本地转写完成后，可在配置 coderplan.ai 后重试“生成内容”阶段。
            </p>
          </aside>
        </section></template
      ></el-main
    ></el-container
  >
</template>
