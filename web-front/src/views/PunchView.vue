<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import api from '../plugins/api'

const loading = ref(false)
const error = ref('')

const records = ref([])
const streak = ref(0)
const schedules = ref([])
const taskMap = ref(new Map())

const activeScheduleId = ref(null)
const running = ref(false)
const remainingSec = ref(0)
const totalSec = ref(0)
const startedAt = ref(null)
const pausedAt = ref(null)
const timer = ref(null)
const completing = ref(false)

const activeSchedule = computed(() => (schedules.value ?? []).find((s) => Number(s.id) === Number(activeScheduleId.value)) || null)
const progressPercent = computed(() => {
  if (!totalSec.value) return 0
  const done = totalSec.value - remainingSec.value
  return Math.max(0, Math.min(100, Math.round((done / totalSec.value) * 100)))
})

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
  timer.value = setInterval(() => {
    if (!running.value) return
    remainingSec.value = Math.max(0, remainingSec.value - 1)
    if (remainingSec.value <= 0) {
      running.value = false
      clearTimer()
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
}

async function completeNow() {
  if (!activeSchedule.value) return
  if (completing.value) return
  completing.value = true
  const s = activeSchedule.value
  try {
    loading.value = true
    error.value = ''
    await api.patch(`/user/schedule/task-schedules/${s.id}/status`, null, { params: { status: 1 } })
    await api.patch(`/user/tasks/${s.taskId}/status`, null, { params: { status: 1 } })
    const durationSeconds = totalSec.value ? Math.max(0, totalSec.value - remainingSec.value) : null
    const startMs = startedAt.value || (durationSeconds != null ? Date.now() - durationSeconds * 1000 : null)
    const endMs = Date.now()
    const fd = new FormData()
    fd.append('taskId', String(s.taskId))
    fd.append('type', String(1))
    if (durationSeconds != null) fd.append('durationSeconds', String(durationSeconds))
    if (startMs != null) fd.append('startedAtMs', String(startMs))
    if (endMs != null) fd.append('endedAtMs', String(endMs))
    await api.post('/user/punch/submit', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
    activeScheduleId.value = null
    running.value = false
    remainingSec.value = 0
    totalSec.value = 0
    clearTimer()
    await load()
  } catch (e) {
    error.value = e?.response?.data?.message || e?.message || '完成失败'
  } finally {
    loading.value = false
    completing.value = false
  }
}

onMounted(load)
onUnmounted(clearTimer)
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
      <v-col cols="12" md="6">
        <v-card class="mb-4">
          <v-card-title class="d-flex align-center">
            <v-icon icon="mdi-timetable" class="mr-2" />
            今日任务日程
          </v-card-title>
          <v-divider />
          <v-card-text>
            <v-alert v-if="!(schedules?.length)" type="info" variant="tonal">今天暂无排程任务</v-alert>
            <v-list v-else density="compact">
              <v-list-item
                v-for="(s, i) in schedules"
                :key="s.id"
                :title="`${i + 1}. ${s.taskTitle || ('任务 ' + (i + 1))}`"
                :subtitle="`${fmt(s.startTime)} - ${fmt(s.endTime)}`"
              >
                <template #append>
                  <v-chip size="x-small" class="mr-2" variant="tonal" :color="Number(s.status) === 1 ? 'success' : undefined">
                    {{ Number(s.status) === 1 ? '已完成' : '未完成' }}
                  </v-chip>
                  <v-btn v-if="Number(s.status) !== 1" size="small" variant="tonal" :loading="loading" @click="startSchedule(s)">
                    计时
                  </v-btn>
                </template>
              </v-list-item>
            </v-list>
          </v-card-text>
        </v-card>

        <v-card>
          <v-card-title class="d-flex align-center">
            <v-icon icon="mdi-checkbox-multiple-marked" class="mr-2" />
            打卡记录
          </v-card-title>
          <v-divider />
          <v-list>
            <v-list-item v-for="(r, i) in records" :key="r.id">
              <template #title>
                <div class="d-flex align-center">
                  <div class="mr-2">{{ i + 1 }}.</div>
                  <div>
                    学习时长 {{ fmtDuration(r.durationSeconds) }}
                    <span v-if="taskMap.get(Number(r.taskId))?.title" style="opacity:0.85">（{{ taskMap.get(Number(r.taskId))?.title }}）</span>
                  </div>
                </div>
              </template>
              <template #subtitle>
                {{ fmt(r.createdAt) }}
              </template>
              <template #append>
                <v-btn size="small" variant="text" color="error" @click="remove(r.id)">删除</v-btn>
              </template>
            </v-list-item>
          </v-list>
        </v-card>
      </v-col>

      <v-col cols="12" md="6">
        <v-card class="mb-4">
          <v-card-title class="d-flex align-center">
            <v-icon icon="mdi-timer-outline" class="mr-2" />
            专注计时
          </v-card-title>
          <v-divider />
          <v-card-text>
            <v-alert v-if="!activeSchedule" type="info" variant="tonal">从“今日任务日程”选择一个任务开始计时</v-alert>
            <div v-else>
              <div class="text-subtitle-1 font-weight-semibold mb-1">{{ activeSchedule.taskTitle || `任务 ${activeSchedule.taskId}` }}</div>
              <div class="text-caption mb-3" style="opacity:0.75">{{ fmt(activeSchedule.startTime) }} - {{ fmt(activeSchedule.endTime) }}</div>
              <div class="text-h3 font-weight-bold mb-2">{{ fmtTime(remainingSec) }}</div>
              <v-progress-linear :model-value="progressPercent" height="10" rounded color="primary" class="mb-3" />
              <div class="d-flex">
                <v-btn variant="tonal" class="mr-2" :disabled="!activeSchedule" @click="togglePause">
                  {{ running ? '暂停' : '继续' }}
                </v-btn>
                <v-btn color="success" variant="tonal" :loading="loading" :disabled="!activeSchedule" @click="completeNow">
                  完成并打卡
                </v-btn>
              </div>
            </div>
          </v-card-text>
        </v-card>

        <v-card>
          <v-card-title class="d-flex align-center">
            <v-icon icon="mdi-robot-outline" class="mr-2" />
            Agent 学习建议
          </v-card-title>
          <v-divider />
          <v-card-text>
            <v-alert type="info" variant="tonal">学习建议已在右下角 Agent 面板展示（刷新页面会重新生成/加载）</v-alert>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>
</template>
