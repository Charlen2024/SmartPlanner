<script setup>
import { computed, onActivated, onMounted, onUnmounted, ref, watch } from 'vue'
import api from '../plugins/api'
import { useNotifyStore } from '../stores/notify'

const notify = useNotifyStore()

const loading = ref(false)
const error = ref('')

const records = ref([])
const streak = ref(0)
const schedules = ref([])
const taskMap = ref(new Map())
const taskResources = ref({})
const adviceMap = ref(new Map())
const adviceLoading = ref(false)

const activeScheduleId = ref(null)
const running = ref(false)
const remainingSec = ref(0)
const totalSec = ref(0)
const startedAt = ref(null)
const pausedAt = ref(null)
const timer = ref(null)
const completing = ref(false)
const submitting = ref(false)

const activeSchedule = computed(() => (schedules.value ?? []).find((s) => Number(s.id) === Number(activeScheduleId.value)) || null)
const progressPercent = computed(() => {
  if (!totalSec.value) return 0
  const done = totalSec.value - remainingSec.value
  return Math.max(0, Math.min(100, Math.round((done / totalSec.value) * 100)))
})

const dayTimeRange = computed(() => {
  const list = schedules.value ?? []
  if (!list.length) return { min: 8 * 60, max: 22 * 60 }
  const mins = list.map((s) => {
    const st = timeLabel(s.startTime)
    const et = timeLabel(s.endTime)
    const sh = parseInt(st?.split(':')[0]) || 0
    const sm = parseInt(st?.split(':')[1]) || 0
    const eh = parseInt(et?.split(':')[0]) || 0
    const em = parseInt(et?.split(':')[1]) || 0
    return { start: sh * 60 + sm, end: eh * 60 + em }
  })
  const minStart = Math.min(...mins.map((m) => m.start))
  const maxEnd = Math.max(...mins.map((m) => m.end))
  return { min: minStart, max: maxEnd }
})

function hourLabel(mins) {
  return `${Math.floor(mins / 60)}:${String(mins % 60).padStart(2, '0')}`
}

function timeLabel(dt) {
  if (!dt) return ''
  const t = String(dt).split('T')[1]
  return t ? t.slice(0, 5) : ''
}

function timelineBarSegments() {
  const list = schedules.value ?? []
  if (!list.length) return []
  const { min, max } = dayTimeRange.value
  const total = (max - min) || 1
  return list.map((s) => {
    const st = timeLabel(s.startTime)
    const et = timeLabel(s.endTime)
    const sh = parseInt(st?.split(':')[0]) || 0
    const sm = parseInt(st?.split(':')[1]) || 0
    const eh = parseInt(et?.split(':')[0]) || 0
    const em = parseInt(et?.split(':')[1]) || 0
    const start = sh * 60 + sm
    const end = eh * 60 + em
    const left = ((start - min) / total) * 100
    const width = Math.max(2, ((end - start) / total) * 100)
    return { left, width, taskTitle: s.taskTitle, status: s.status, id: s.id }
  })
}

function fmtTime(sec) {
  const s = Math.max(0, Math.floor(sec))
  const mm = String(Math.floor(s / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return `${mm}:${ss}`
}

function fmt(dt) {
  if (!dt) return '-'
  return String(dt).replace('T', ' ').slice(0, 16)
}

async function loadTaskResourcesForSchedules(list) {
  const taskIds = Array.from(new Set((list ?? []).map((x) => Number(x?.taskId)).filter((x) => Number.isFinite(x) && x > 0)))
  if (!taskIds.length) {
    taskResources.value = {}
    return
  }
  try {
    const res = await api.post('/user/tasks/resources', { taskIds, topK: 3 })
    const body = res?.data ?? null
    taskResources.value = body?.code === 200 ? body?.data ?? {} : {}
  } catch (e) {
    taskResources.value = {}
  }
}

function resourcesForTask(taskId) {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) return []
  const map = taskResources.value ?? {}
  return map?.[id] ?? map?.[String(id)] ?? []
}

function adviceForTask(taskId) {
  return adviceMap.value.get(Number(taskId)) || ''
}

async function loadTaskAdvice() {
  const taskIds = Array.from(new Set((schedules.value ?? []).map((x) => Number(x?.taskId)).filter((x) => Number.isFinite(x) && x > 0)))
  if (!taskIds.length) {
    adviceMap.value = new Map()
    return
  }
  adviceLoading.value = true
  try {
    const res = await api.post('/user/tasks/advice', taskIds)
    const body = res?.data ?? null
    if (body?.code !== 200) {
      throw new Error(body?.message || '加载 AI 建议失败')
    }
    const data = body?.data ?? {}
    const next = new Map()
    for (const k of Object.keys(data)) {
      next.set(Number(k), data[k])
    }
    adviceMap.value = next
  } catch (e) {
    adviceMap.value = new Map()
  } finally {
    adviceLoading.value = false
  }
}

function platformIcon(platform) {
  const p = (platform || '').toLowerCase()
  if (p.includes('bilibili') || p.includes('b站')) return 'mdi-video-box'
  if (p.includes('zhihu') || p.includes('知乎')) return 'mdi-forum'
  if (p.includes('github')) return 'mdi-github'
  if (p.includes('course') || p.includes('mooc') || p.includes('慕课')) return 'mdi-school'
  return 'mdi-open-in-new'
}

function openUrl(url) {
  const u = String(url || '').trim()
  if (!u) return
  window.open(u, '_blank')
}

function fmtDuration(sec) {
  const s = Math.max(0, Math.floor(Number(sec) || 0))
  const hh = Math.floor(s / 3600)
  const mm = Math.floor((s % 3600) / 60)
  const ss = s % 60
  if (hh > 0) {
    return `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}`
  }
  return `${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}`
}

const totalStudySec = computed(() =>
  (records.value ?? []).reduce((sum, r) => sum + Math.max(0, Number(r?.durationSeconds) || 0), 0)
)

function punchTimeLabel(dt) {
  if (!dt) return ''
  const s = String(dt)
  const t = s.includes('T') ? s.split('T')[1] : (s.includes(' ') ? s.split(' ')[1] : s)
  return (t || '').slice(0, 5)
}

const TIMER_STATE_KEY = 'sp:timer:state'

function saveTimerState() {
  if (!activeScheduleId.value) { clearTimerState(); return }
  const state = {
    activeScheduleId: activeScheduleId.value,
    totalSec: totalSec.value,
    remainingSec: remainingSec.value,
    startedAt: startedAt.value,
    running: running.value,
    pausedAt: pausedAt.value,
    lastSavedAt: Date.now(),
  }
  localStorage.setItem(TIMER_STATE_KEY, JSON.stringify(state))
}

function clearTimerState() {
  localStorage.removeItem(TIMER_STATE_KEY)
}

function restoreTimerState(schedulesList) {
  try {
    const raw = localStorage.getItem(TIMER_STATE_KEY)
    if (!raw) return false
    const state = JSON.parse(raw)
    if (!state || !state.activeScheduleId || !state.totalSec) return false
    // verify the schedule still exists in today's list
    const s = (schedulesList ?? []).find((x) => Number(x.id) === Number(state.activeScheduleId))
    if (!s) { clearTimerState(); return false }
    activeScheduleId.value = state.activeScheduleId
    totalSec.value = state.totalSec
    startedAt.value = state.startedAt || Date.now()
    if (state.running) {
      const elapsed = Math.floor((Date.now() - (state.lastSavedAt || Date.now())) / 1000)
      remainingSec.value = Math.max(0, (state.remainingSec || 0) - elapsed)
      running.value = true
      pausedAt.value = null
    } else {
      remainingSec.value = state.remainingSec || 0
      running.value = false
      pausedAt.value = state.pausedAt || null
    }
    if (remainingSec.value <= 0) {
      clearTimerState()
      activeScheduleId.value = null
      running.value = false
      return false
    }
    clearTimer()
    timer.value = setInterval(() => {
      if (!running.value) return
      remainingSec.value = Math.max(0, remainingSec.value - 1)
      saveTimerState()
      if (remainingSec.value <= 0) {
        running.value = false
        clearTimer()
        clearTimerState()
        setTimeout(() => {
          if (activeSchedule.value) {
            completeNow()
          }
        }, 0)
      }
    }, 1000)
    return true
  } catch (e) {
    clearTimerState()
    return false
  }
}

function clearTimer() {
  if (timer.value) {
    clearInterval(timer.value)
    timer.value = null
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const t = new Date()
    const d = `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`
    const [r1, r2, r3] = await Promise.all([
      api.get('/user/punch/records'),
      api.get('/user/punch/streak'),
      api.get('/user/schedule/task-schedules', { params: { from: `${d}T00:00:00`, to: `${d}T23:59:59` } }),
    ])
    records.value = r1?.data?.data ?? []
    streak.value = r2?.data?.data ?? 0
    schedules.value = r3?.data?.data ?? []
    await Promise.all([
      loadTaskResourcesForSchedules(schedules.value),
      loadTaskAdvice(),
    ])

    const scheduleTaskIds = (schedules.value ?? []).map((x) => x?.taskId).filter(Boolean)
    const recordTaskIds = (records.value ?? []).map((x) => x?.taskId).filter(Boolean)
    const ids = Array.from(new Set([...scheduleTaskIds, ...recordTaskIds])).slice(0, 200)
    if (ids.length) {
      const tasksRes = await api.post('/user/tasks/by-ids', ids)
      const list = tasksRes?.data?.data ?? []
      const map = new Map()
      for (const t of list) {
        if (t?.id) map.set(Number(t.id), t)
      }
      taskMap.value = map
    } else {
      taskMap.value = new Map()
    }
  } catch (e) {
    error.value = e?.response?.data?.message || e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function remove(id) {
  await api.delete(`/user/punch/records/${id}`)
  await load()
}

function startSchedule(s) {
  if (!s?.startTime || !s?.endTime) return
  activeScheduleId.value = s.id
  const start = new Date(s.startTime)
  const end = new Date(s.endTime)
  const mins = Math.max(1, Math.round((end.getTime() - start.getTime()) / 60000))
  totalSec.value = mins * 60
  remainingSec.value = totalSec.value
  startedAt.value = Date.now()
  pausedAt.value = null
  running.value = true
  clearTimer()
  saveTimerState()
  timer.value = setInterval(() => {
    if (!running.value) return
    remainingSec.value = Math.max(0, remainingSec.value - 1)
    saveTimerState()
    if (remainingSec.value <= 0) {
      running.value = false
      clearTimer()
      clearTimerState()
      setTimeout(() => {
        if (activeSchedule.value) {
          completeNow()
        }
      }, 0)
    }
  }, 1000)
}

function togglePause() {
  if (!activeSchedule.value) return
  running.value = !running.value
  if (!running.value) pausedAt.value = Date.now()
  else pausedAt.value = null
  saveTimerState()
}

async function completeNow() {
  if (!activeSchedule.value) return
  if (completing.value) return
  completing.value = true
  const s = activeSchedule.value
  try {
    submitting.value = true
    error.value = ''
    const [r1, r2] = await Promise.all([
      api.patch(`/user/schedule/task-schedules/${s.id}/status`, null, { params: { status: 1 } }),
      api.patch(`/user/tasks/${s.taskId}/status`, null, { params: { status: 1 } }),
    ])
    const durationSeconds = totalSec.value ? Math.max(0, totalSec.value - remainingSec.value) : null
    const startMs = startedAt.value || (durationSeconds != null ? Date.now() - durationSeconds * 1000 : null)
    const endMs = Date.now()
    const fd = new FormData()
    fd.append('taskId', String(s.taskId))
    fd.append('type', String(1))
    if (s.taskTitle) fd.append('taskTitle', String(s.taskTitle))
    if (durationSeconds != null) fd.append('durationSeconds', String(durationSeconds))
    if (startMs != null) fd.append('startedAtMs', String(startMs))
    if (endMs != null) fd.append('endedAtMs', String(endMs))
    await api.post('/user/punch/submit', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
    activeScheduleId.value = null
    running.value = false
    remainingSec.value = 0
    totalSec.value = 0
    clearTimer()
    clearTimerState()
    await load()
  } catch (e) {
    error.value = e?.response?.data?.message || e?.message || '完成失败'
  } finally {
    submitting.value = false
    completing.value = false
  }
}

watch(
  () => notify.signalSeq?.RESOURCE_ADVICE_DONE,
  async () => {
    await loadTaskResourcesForSchedules(schedules.value)
  },
)

let _ready4 = false
onMounted(async () => {
  await load()
  restoreTimerState(schedules.value)
})
onActivated(async () => { if (_ready4) await load(); _ready4 = true })
onUnmounted(() => {
  clearTimer()
  // timer state is already in localStorage, no need to save again on unmount
})
</script>

<template>
  <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

  <v-card class="mb-4">
    <v-card-title class="d-flex align-center">
      <v-icon icon="mdi-fire" class="mr-2" />
      连续打卡：{{ streak }} 次
    </v-card-title>
  </v-card>

  <v-row>
    <v-col cols="12" md="8">
      <!-- ====== 今日任务日程（时间线 + AI 深度融合）====== -->
      <v-card>
        <v-card-title class="d-flex align-center justify-space-between">
          <div class="d-flex align-center">
            <v-icon icon="mdi-timetable" class="mr-2" />
            今日任务日程
          </div>
          <div class="d-flex align-center ga-1">
            <v-btn size="small" variant="text" :icon="true" :loading="adviceLoading" @click="loadTaskAdvice" aria-label="刷新 AI 建议" title="刷新 AI 建议">
              <v-icon size="18">mdi-refresh</v-icon>
            </v-btn>
          </div>
        </v-card-title>
        <v-divider />

        <!-- 日时间线概览条 -->
        <div v-if="schedules?.length" class="px-4 pt-4">
          <div class="d-flex align-center mb-1">
            <span class="text-caption" style="opacity:0.6">{{ hourLabel(dayTimeRange.min) }}</span>
            <div class="flex-grow-1 mx-2" style="position:relative; height:20px; background:rgba(var(--v-theme-surface-variant), 0.4); border-radius:4px; overflow:hidden">
              <div
                v-for="seg in timelineBarSegments()"
                :key="seg.id"
                :style="{ position:'absolute', left:seg.left+'%', width:seg.width+'%', height:'100%', top:0 }"
                :class="Number(seg.status) === 1 ? 'bg-success' : 'bg-primary'"
                style="opacity:0.5; border-radius:2px; min-width:4px"
                :title="seg.taskTitle"
              />
            </div>
            <span class="text-caption" style="opacity:0.6">{{ hourLabel(dayTimeRange.max) }}</span>
          </div>
        </div>

        <v-card-text>
          <v-alert v-if="!(schedules?.length)" type="info" variant="tonal">今天暂无排程任务，前往「目标」页生成排程。</v-alert>

          <!-- 任务卡片列表 -->
          <div v-else>
            <div
              v-for="(s, i) in schedules"
              :key="s.id"
              class="task-card mb-3"
              :class="{ 'task-active': Number(activeScheduleId) === Number(s.id) && running }"
            >
              <!-- 时间列 -->
              <div class="task-time-col" :class="Number(s.status) === 1 ? 'done' : 'pending'">
                <div class="task-time-start">{{ timeLabel(s.startTime) }}</div>
                <div class="task-time-line">
                  <div class="task-time-dot" :class="Number(s.status) === 1 ? 'bg-success' : running && Number(activeScheduleId) === Number(s.id) ? 'bg-error pulse' : 'bg-primary'" />
                </div>
                <div class="task-time-end">{{ timeLabel(s.endTime) }}</div>
              </div>
              <!-- 内容列 -->
              <div class="task-content-col">
                <div class="d-flex align-center justify-space-between mb-1">
                  <div class="font-weight-semibold">{{ i + 1 }}. {{ s.taskTitle || ('任务 ' + (i + 1)) }}</div>
                  <div class="d-flex align-center">
                    <v-chip size="x-small" variant="tonal" :color="Number(s.status) === 1 ? 'success' : undefined" class="mr-1">
                      {{ Number(s.status) === 1 ? '已完成' : '未完成' }}
                    </v-chip>
                    <v-btn
                      v-if="Number(s.status) !== 1"
                      size="x-small"
                      variant="tonal"
                      color="primary"
                      @click="startSchedule(s)"
                    >
                      <v-icon size="16" class="mr-1">mdi-play</v-icon>
                      计时
                    </v-btn>
                  </div>
                </div>

                <!-- AI 建议内嵌 -->
                <div v-if="adviceForTask(s.taskId)" class="task-advice">
                  <v-icon size="14" color="primary" class="mr-1">mdi-lightbulb-outline</v-icon>
                  <span>{{ adviceForTask(s.taskId) }}</span>
                </div>

                <!-- 资源推荐 -->
                <div v-if="resourcesForTask(s.taskId)?.length" class="task-resources">
                  <v-chip
                    v-for="r in resourcesForTask(s.taskId)"
                    :key="(r?.sourceUrl || r?.title) + String(s.taskId)"
                    size="x-small"
                    variant="tonal"
                    :prepend-icon="platformIcon(r?.platform)"
                    class="mr-2 mb-1"
                    @click.stop="openUrl(r?.sourceUrl)"
                  >
                    {{ (r?.platform ? r.platform + '：' : '') + (r?.title || '课程资源') }}
                  </v-chip>
                </div>
              </div>
            </div>
          </div>
        </v-card-text>
      </v-card>
    </v-col>

    <v-col cols="12" md="4">
      <!-- ====== 专注计时 ====== -->
      <v-card class="mb-4" :class="{ 'timer-active': activeSchedule }">
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-timer-outline" class="mr-2" />
          专注计时
        </v-card-title>
        <v-divider />
        <v-card-text>
          <v-alert v-if="!activeSchedule" type="info" variant="tonal" class="text-caption">
            从左侧任务日程点击「计时」开始专注
          </v-alert>
          <div v-else class="text-center">
            <div class="text-subtitle-1 font-weight-bold mb-1">{{ activeSchedule.taskTitle || `任务 ${activeSchedule.taskId}` }}</div>
            <div class="text-caption mb-4" style="opacity:0.65">
              {{ timeLabel(activeSchedule.startTime) }} — {{ timeLabel(activeSchedule.endTime) }}
            </div>
            <div class="timer-display mb-2" :class="{ 'text-error': remainingSec <= 60 && remainingSec > 0 }">
              {{ fmtTime(remainingSec) }}
            </div>
            <v-progress-linear :model-value="progressPercent" height="8" rounded :color="remainingSec <= 60 ? 'error' : 'primary'" class="mb-4" />
            <div class="d-flex justify-center">
              <v-btn variant="tonal" class="mr-2" :disabled="!activeSchedule" @click="togglePause">
                <v-icon size="18" class="mr-1">{{ running ? 'mdi-pause' : 'mdi-play' }}</v-icon>
                {{ running ? '暂停' : '继续' }}
              </v-btn>
              <v-btn color="success" variant="tonal" :loading="submitting" :disabled="!activeSchedule" @click="completeNow">
                <v-icon size="18" class="mr-1">mdi-check</v-icon>
                完成打卡
              </v-btn>
            </div>
          </div>
        </v-card-text>
      </v-card>

      <!-- ====== 打卡记录 ====== -->
      <v-card>
        <v-card-title class="d-flex align-center justify-space-between">
          <div class="d-flex align-center">
            <v-icon icon="mdi-checkbox-multiple-marked" class="mr-2" />
            打卡记录
          </div>
          <span v-if="records?.length" class="text-caption" style="opacity:0.7">
            累计 {{ fmtDuration(totalStudySec) }} · {{ records.length }} 次
          </span>
        </v-card-title>
        <v-divider />
        <v-card-text>
          <v-alert v-if="!records?.length" type="info" variant="tonal" class="text-caption">
            暂无打卡记录，完成计时后自动记录。
          </v-alert>
          <div v-else>
            <div
              v-for="r in records"
              :key="r.id"
              class="punch-record-card mb-2"
            >
              <div class="punch-record-timebar">
                <div class="punch-record-range">
                  <span>{{ punchTimeLabel(r.startedAt) }}</span>
                  <span class="punch-record-arrow">→</span>
                  <span>{{ punchTimeLabel(r.endedAt) }}</span>
                </div>
                <div class="punch-record-dur">
                  <v-icon size="14" class="mr-1">mdi-timer-outline</v-icon>
                  {{ fmtDuration(r.durationSeconds) }}
                </div>
              </div>
              <div class="punch-record-body">
                <div class="d-flex align-center justify-space-between">
                  <div class="font-weight-semibold text-body-2">
                    {{ r.taskTitle || taskMap.get(Number(r.taskId))?.title || '未知任务' }}
                  </div>
                  <v-btn size="x-small" variant="text" color="error" icon="mdi-delete-outline" aria-label="删除打卡记录" @click="remove(r.id)" />
                </div>
                <div class="d-flex align-center mt-1">
                  <v-icon size="12" class="mr-1" style="opacity:0.4">mdi-clock-outline</v-icon>
                  <span class="text-caption" style="opacity:0.5">
                    {{ punchTimeLabel(r.createdAt) }} 打卡
                  </span>
                </div>
              </div>
            </div>
          </div>
        </v-card-text>
      </v-card>
    </v-col>
  </v-row>
</template>

<style scoped>
.task-card {
  display: flex;
  gap: 12px;
  padding: 8px 4px;
  border-radius: 8px;
  transition: background 0.2s;
}
.task-card:hover {
  background: rgba(var(--v-theme-surface-variant), 0.15);
}
.task-active {
  background: rgba(var(--v-theme-primary), 0.06);
  box-shadow: inset 3px 0 0 rgb(var(--v-theme-primary));
  border-radius: 8px;
}

.task-time-col {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 44px;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}
.task-time-col.done .task-time-start,
.task-time-col.done .task-time-end {
  opacity: 0.5;
}
.task-time-start,
.task-time-end {
  line-height: 1;
  opacity: 0.85;
}
.task-time-line {
  flex: 1;
  width: 2px;
  min-height: 20px;
  margin: 4px 0;
  position: relative;
}
.task-time-col.pending .task-time-line {
  background: rgba(var(--v-theme-primary), 0.25);
}
.task-time-col.done .task-time-line {
  background: rgba(var(--v-theme-success), 0.3);
}
.task-time-dot {
  position: absolute;
  top: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.task-time-dot.pulse {
  animation: task-pulse 1.2s ease-in-out infinite;
}

.task-content-col {
  flex: 1;
  min-width: 0;
}
.task-advice {
  font-size: 12px;
  padding: 6px 8px;
  border-radius: 6px;
  background: rgba(var(--v-theme-primary), 0.05);
  border-left: 3px solid rgb(var(--v-theme-primary));
  opacity: 0.9;
  margin-top: 6px;
  display: flex;
  align-items: flex-start;
  gap: 4px;
}
.task-resources {
  margin-top: 6px;
}

.timer-active {
  box-shadow: 0 0 20px rgba(var(--v-theme-primary), 0.15);
}
.timer-display {
  font-size: 3.5rem;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  letter-spacing: 2px;
  line-height: 1;
}

@keyframes task-pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(var(--v-theme-error), 0.5); }
  50% { box-shadow: 0 0 0 6px rgba(var(--v-theme-error), 0); }
}

/* ── Punch records ── */

.punch-record-card {
  border: 1px solid rgba(var(--v-theme-on-surface), 0.08);
  border-radius: 10px;
  overflow: hidden;
  transition: background 0.15s;
}
.punch-record-card:hover {
  background: rgba(var(--v-theme-on-surface), 0.03);
}

.punch-record-timebar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px;
  background: rgba(var(--v-theme-primary), 0.05);
  border-bottom: 1px solid rgba(var(--v-theme-on-surface), 0.05);
}
.punch-record-range {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.punch-record-arrow {
  opacity: 0.35;
  font-size: 11px;
}
.punch-record-dur {
  display: flex;
  align-items: center;
  font-size: 12px;
  font-weight: 700;
  opacity: 0.7;
}
.punch-record-body {
  padding: 8px 12px;
}
</style>
