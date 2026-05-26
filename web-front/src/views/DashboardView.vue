<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import api from '../plugins/api'

const loading = ref(false)
const error = ref('')
const goalQuery = ref('')
const dashboard = ref(null)
const weather = ref(null)
const today = ref(new Date().toISOString().slice(0, 10))
const selectedGoalId = ref(null)
const selectedGoalTitle = ref('')
const selectedGoalTasks = ref([])
const tasksLoading = ref(false)
const tasksError = ref('')
const goalQueryTimer = ref(null)
const scheduleDate = ref('')

function fmt(dt) {
  if (!dt) return '-'
  return String(dt).replace('T', ' ').slice(0, 16)
}

function dateKey(dt) {
  if (!dt) return ''
  return String(dt).slice(0, 10)
}

function fmtHm(dt) {
  if (!dt) return '--:--'
  const s = String(dt)
  if (s.includes('T')) return s.slice(11, 16)
  return s.slice(0, 5)
}

function timeToMinutes(dt) {
  if (!dt) return null
  const s = String(dt)
  const t = s.includes('T') ? s.split('T')[1] : s
  const hhmm = t.slice(0, 5)
  const [h, m] = hhmm.split(':')
  const hh = Number(h)
  const mm = Number(m)
  if (!Number.isFinite(hh) || !Number.isFinite(mm)) return null
  return hh * 60 + mm
}

function humanMinutes(total) {
  const m = Number(total)
  if (!Number.isFinite(m) || m <= 0) return '0 分'
  const h = Math.floor(m / 60)
  const mm = Math.round(m % 60)
  if (h <= 0) return `${mm} 分`
  if (mm <= 0) return `${h} 小时`
  return `${h} 小时 ${mm} 分`
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [res, w] = await Promise.all([
      api.get('/user/dashboard'),
      api.get('/user/weather'),
    ])
    dashboard.value = res?.data?.data ?? null
    weather.value = w?.data?.data ?? null
  } catch (e) {
    error.value = '加载失败'
  } finally {
    loading.value = false
  }
}

const filteredGoals = computed(() => {
  const q = String(goalQuery.value || '').trim()
  const list = dashboard.value?.goals ?? []
  if (!q) return list
  return list.filter((g) => String(g?.title || '').includes(q) || String(g?.description || '').includes(q))
})

const freeTimeSlots = computed(() => dashboard.value?.freeTimeSlots ?? [])
const freeTotalMinutes = computed(() => {
  let sum = 0
  for (const s of freeTimeSlots.value ?? []) {
    const start = timeToMinutes(s?.start)
    const end = timeToMinutes(s?.end)
    if (start == null || end == null) continue
    const d = end - start
    if (d > 0) sum += d
  }
  return sum
})

const allTaskSchedules = computed(() => dashboard.value?.taskSchedules ?? [])
const availableScheduleDates = computed(() => {
  const set = new Set()
  for (const s of allTaskSchedules.value ?? []) {
    const k = dateKey(s?.startTime)
    if (k) set.add(k)
  }
  const arr = Array.from(set).sort((a, b) => String(a).localeCompare(String(b)))
  const t = String(today.value || '')
  if (t && !set.has(t)) arr.push(t)
  return arr
})
const displayTaskSchedules = computed(() => {
  const d = String(scheduleDate.value || '').trim()
  if (!d) return []
  return (allTaskSchedules.value ?? []).filter((s) => dateKey(s?.startTime) === d)
})

watch(
  () => [today.value, availableScheduleDates.value.join('|')].join('::'),
  () => {
    const dates = availableScheduleDates.value
    const t = String(today.value || '')
    if (scheduleDate.value) {
      const ok = dates.some((x) => String(x) === String(scheduleDate.value))
      if (ok) return
    }
    scheduleDate.value = dates.includes(t) ? t : (dates[0] || t)
  },
  { immediate: true },
)

function resetGoalTasks() {
  selectedGoalId.value = null
  selectedGoalTitle.value = ''
  selectedGoalTasks.value = []
  tasksLoading.value = false
  tasksError.value = ''
}

async function loadGoalTasks(goalId) {
  const id = Number(goalId)
  if (!Number.isFinite(id) || id <= 0) return
  tasksLoading.value = true
  tasksError.value = ''
  try {
    const res = await api.get(`/user/goals/${id}/tasks`)
    selectedGoalTasks.value = res?.data?.data ?? []
  } catch (e) {
    tasksError.value = e?.response?.data?.message || '加载目标任务失败'
    selectedGoalTasks.value = []
  } finally {
    tasksLoading.value = false
  }
}

async function selectGoal(g) {
  const id = Number(g?.id)
  if (!Number.isFinite(id) || id <= 0) return
  selectedGoalId.value = id
  selectedGoalTitle.value = g?.title || `目标 ${id}`
  await loadGoalTasks(id)
}

watch(
  () => goalQuery.value,
  (v) => {
    const q = String(v || '').trim()
    if (goalQueryTimer.value) clearTimeout(goalQueryTimer.value)
    if (!q) {
      resetGoalTasks()
      return
    }
    goalQueryTimer.value = setTimeout(async () => {
      const list = filteredGoals.value ?? []
      if (!list.length) {
        resetGoalTasks()
        return
      }
      const stillValid = list.some((g) => Number(g?.id) === Number(selectedGoalId.value))
      if (stillValid) return
      await selectGoal(list[0])
    }, 300)
  },
  { flush: 'post' },
)

onBeforeUnmount(() => {
  if (goalQueryTimer.value) clearTimeout(goalQueryTimer.value)
})

const timelineStartHour = 8
const timelineEndHour = 22
const blockGapPx = 8
const minBlockHeightPx = 44

function clampedDurationMinutes(start, end) {
  const s = timeToMinutes(start)
  const e = timeToMinutes(end)
  if (s == null || e == null) return null
  const startMin = timelineStartHour * 60
  const endMin = timelineEndHour * 60
  const topMin = Math.max(s, startMin)
  const bottomMin = Math.min(e, endMin)
  const d = bottomMin - topMin
  return d > 0 ? d : null
}

const pxPerMinute = computed(() => {
  const mins = []
  for (const s of displayTaskSchedules.value ?? []) {
    const d = clampedDurationMinutes(s?.startTime, s?.endTime)
    if (d != null) mins.push(d)
  }
  for (const f of freeTimeSlots.value ?? []) {
    const d = clampedDurationMinutes(f?.start, f?.end)
    if (d != null) mins.push(d)
  }
  const minDur = mins.length ? Math.min(...mins) : null
  if (!minDur) return 1.6
  const target = (minBlockHeightPx + blockGapPx) / minDur
  return Math.max(1.1, Math.min(3.2, target))
})

const timelineHeightPx = computed(() => (timelineEndHour - timelineStartHour) * 60 * pxPerMinute.value)

function blockStyle(start, end) {
  const s = timeToMinutes(start)
  const e = timeToMinutes(end)
  if (s == null || e == null) return null
  const startMin = timelineStartHour * 60
  const endMin = timelineEndHour * 60
  const topMin = Math.max(s, startMin) - startMin
  const bottomMin = Math.min(e, endMin) - startMin
  const hMin = bottomMin - topMin
  if (hMin <= 0) return null
  const ppm = pxPerMinute.value
  const topPx = Math.round(topMin * ppm)
  const heightPx = Math.round(hMin * ppm)
  const finalHeight = Math.max(minBlockHeightPx, heightPx - blockGapPx)
  return { top: `${topPx}px`, height: `${finalHeight}px` }
}

const freeBlocks = computed(() =>
  (freeTimeSlots.value ?? []).map((f) => ({ ...f, style: blockStyle(f?.start, f?.end) })).filter((f) => f.style),
)

const scheduleBlocks = computed(() =>
  (displayTaskSchedules.value ?? [])
    .map((s, idx) => ({
      ...s,
      _idx: idx + 1,
      style: blockStyle(s?.startTime, s?.endTime),
    }))
    .filter((s) => s.style),
)

function tempText() {
  const t = weather.value?.temperature
  if (t === null || t === undefined) return '-'
  return `${t}`
}

function windText() {
  const w = weather.value?.windspeed
  if (w === null || w === undefined) return '-'
  return `${w}`
}

onMounted(load)
</script>

<template>
  <v-row class="mb-2" align="center">
    <v-col cols="12" md="6">
      <v-text-field v-model="goalQuery" label="目标查询" density="comfortable" variant="outlined" />
    </v-col>
    <v-col cols="12" md="3">
      <v-btn color="primary" :loading="loading" @click="load">刷新</v-btn>
    </v-col>
  </v-row>

  <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

  <v-row v-if="dashboard">
    <v-col cols="12" md="3">
      <v-card class="pa-2">
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-bullseye-arrow" class="mr-2" />
          目标
        </v-card-title>
        <v-card-text class="text-h4 font-weight-bold">{{ dashboard.goals?.length ?? 0 }}</v-card-text>
      </v-card>
    </v-col>
    <v-col cols="12" md="3">
      <v-card class="pa-2">
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-fire" class="mr-2" />
          连续打卡
        </v-card-title>
        <v-card-text class="text-h4 font-weight-bold">{{ dashboard.streak ?? 0 }}</v-card-text>
      </v-card>
    </v-col>
    <v-col cols="12" md="6">
      <v-card class="pa-2">
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-weather-partly-cloudy" class="mr-2" />
          今日 {{ today }}
        </v-card-title>
        <v-card-text>
          <div v-if="weather">
            <div class="text-h5 font-weight-bold">{{ weather.summary || '天气' }} {{ tempText() }}°C</div>
            <div class="text-body-2" style="opacity:0.75">风速 {{ windText() }} km/h</div>
          </div>
          <div v-else class="text-body-2" style="opacity:0.7">天气加载中…</div>
        </v-card-text>
      </v-card>
    </v-col>
    <v-col cols="12" md="6">
      <v-card class="pa-2">
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-calendar-clock" class="mr-2" />
          今日空闲时间
        </v-card-title>
        <v-card-text>
          <v-alert v-if="!(freeTimeSlots?.length)" type="info" variant="tonal">暂无空闲时间（或课表未导入）</v-alert>
          <div v-else>
            <div class="d-flex align-center justify-space-between mb-2">
              <div class="text-body-2" style="opacity:0.75">合计 {{ humanMinutes(freeTotalMinutes) }}</div>
              <v-chip size="small" variant="tonal" color="primary">{{ today }}</v-chip>
            </div>
            <div class="d-flex flex-wrap" style="gap:8px">
              <v-chip v-for="(slot, i) in freeTimeSlots" :key="i" size="small" variant="outlined">
                {{ fmtHm(slot.start) }} - {{ fmtHm(slot.end) }}
              </v-chip>
            </div>
          </div>
        </v-card-text>
      </v-card>
    </v-col>

    <v-col cols="12" md="6">
      <v-card class="pa-2">
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-timetable" class="mr-2" />
          任务日程
        </v-card-title>
        <v-card-text>
          <div class="mb-2">
            <div class="text-caption mb-1" style="opacity:0.75">选择日期</div>
            <v-chip-group column>
              <v-chip
                v-for="d in availableScheduleDates"
                :key="d"
                :color="String(d) === String(scheduleDate) ? 'primary' : undefined"
                :variant="String(d) === String(scheduleDate) ? 'flat' : 'outlined'"
                size="small"
                @click="scheduleDate = d"
              >
                {{ d }}
              </v-chip>
            </v-chip-group>
          </div>

          <v-alert v-if="!(displayTaskSchedules?.length)" type="info" variant="tonal" class="mb-2">该日暂无任务排程</v-alert>

          <div v-else class="dash-timeline-shell mb-2">
            <div class="dash-timeline-axis" :style="{ height: timelineHeightPx + 'px' }">
              <div
                v-for="h in (timelineEndHour - timelineStartHour + 1)"
                :key="h"
                class="dash-timeline-axis-row"
                :style="{ height: (60 * pxPerMinute) + 'px' }"
              >
                {{ String(timelineStartHour + h - 1).padStart(2, '0') }}:00
              </div>
            </div>
            <div class="dash-timeline-canvas" :style="{ height: timelineHeightPx + 'px' }">
              <div
                v-for="h in (timelineEndHour - timelineStartHour + 1)"
                :key="h"
                class="dash-timeline-gridline"
                :style="{ top: ((h - 1) * 60 * pxPerMinute) + 'px' }"
              />

              <div v-for="(b, i) in freeBlocks" :key="'free-' + i" class="dash-timeline-free" :style="b.style" />

              <div
                v-for="b in scheduleBlocks"
                :key="b.id || `${b.taskId}-${b.startTime}`"
                class="dash-timeline-block dash-timeline-task"
                :style="b.style"
              >
                <div class="dash-timeline-title">{{ b.taskTitle || `任务 ${b._idx}` }}</div>
                <div class="dash-timeline-sub">{{ fmtHm(b.startTime) }} - {{ fmtHm(b.endTime) }}</div>
              </div>
            </div>
          </div>
        </v-card-text>
      </v-card>
    </v-col>

    <v-col cols="12">
      <v-card class="pa-2">
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-chart-arc" class="mr-2" />
          目标进度
        </v-card-title>
        <v-card-text>
          <v-alert v-if="!(dashboard.goalProgress?.length)" type="info" variant="tonal">暂无目标进度</v-alert>
          <div v-for="g in dashboard.goalProgress ?? []" :key="g.goalId" class="mb-4">
            <div class="d-flex justify-space-between mb-1">
              <div class="text-subtitle-2 font-weight-semibold">{{ g.title }}</div>
              <div class="text-caption" style="opacity:0.75">{{ g.doneTasks }}/{{ g.totalTasks }}（{{ g.percent }}%）</div>
            </div>
            <v-progress-linear :model-value="g.percent" height="10" rounded color="primary" />
          </div>
        </v-card-text>
      </v-card>
    </v-col>

    <v-col cols="12">
      <v-card class="pa-2">
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-magnify" class="mr-2" />
          目标任务查询
        </v-card-title>
        <v-card-text>
          <v-alert v-if="!String(goalQuery || '').trim()" type="info" variant="tonal">输入目标关键词后，自动展示匹配目标的任务列表</v-alert>

          <v-alert v-else-if="!(filteredGoals?.length)" type="info" variant="tonal">暂无匹配目标</v-alert>

          <div v-else>
            <div v-if="(filteredGoals?.length ?? 0) > 1" class="mb-2">
              <div class="text-caption mb-1" style="opacity:0.75">匹配到多个目标，点击切换要查看的目标</div>
              <v-chip-group column>
                <v-chip
                  v-for="g in filteredGoals"
                  :key="g.id"
                  :color="Number(g.id) === Number(selectedGoalId) ? 'primary' : undefined"
                  :variant="Number(g.id) === Number(selectedGoalId) ? 'flat' : 'outlined'"
                  @click="selectGoal(g)"
                >
                  {{ g.title }}
                </v-chip>
              </v-chip-group>
            </div>

            <v-alert v-if="tasksError" type="error" variant="tonal" class="mb-2">{{ tasksError }}</v-alert>
            <v-alert v-else-if="tasksLoading" type="info" variant="tonal" class="mb-2">正在加载任务…</v-alert>
            <v-alert v-else-if="selectedGoalId && !(selectedGoalTasks?.length)" type="info" variant="tonal" class="mb-2">
              {{ selectedGoalTitle }} 暂无任务
            </v-alert>

            <div v-else-if="selectedGoalId">
              <div class="text-subtitle-2 font-weight-semibold mb-2">{{ selectedGoalTitle }} 的任务</div>
              <v-list density="compact">
                <v-list-item
                  v-for="t in (selectedGoalTasks ?? [])"
                  :key="t.id"
                  :title="t.title"
                  :subtitle="t.description"
                />
              </v-list>
            </div>
          </div>
        </v-card-text>
      </v-card>
    </v-col>
  </v-row>
</template>

<style scoped>
.dash-timeline-shell {
  display: grid;
  grid-template-columns: 64px 1fr;
  gap: 10px;
  max-height: 360px;
  overflow: auto;
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.08);
}

.dash-timeline-axis {
  position: relative;
  background: rgba(0, 0, 0, 0.02);
  padding-top: 6px;
}

.dash-timeline-axis-row {
  position: relative;
  padding-right: 8px;
  text-align: right;
  font-size: 12px;
  opacity: 0.75;
}

.dash-timeline-canvas {
  position: relative;
  padding: 6px 10px 6px 10px;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.015), rgba(0, 0, 0, 0.01));
}

.dash-timeline-gridline {
  position: absolute;
  left: 0;
  right: 0;
  height: 1px;
  background: rgba(0, 0, 0, 0.06);
}

.dash-timeline-free {
  position: absolute;
  left: 0;
  right: 0;
  border-radius: 10px;
  background: rgba(var(--v-theme-primary), 0.06);
  outline: 1px dashed rgba(var(--v-theme-primary), 0.25);
}

.dash-timeline-block {
  position: absolute;
  left: 10px;
  right: 10px;
  border-radius: 12px;
  padding: 8px 10px;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.08);
  overflow: hidden;
  box-sizing: border-box;
}

.dash-timeline-task {
  background: rgba(var(--v-theme-primary), 0.12);
  border: 1px solid rgba(var(--v-theme-primary), 0.25);
}

.dash-timeline-title {
  font-weight: 600;
  font-size: 13px;
  line-height: 1.2;
}

.dash-timeline-sub {
  margin-top: 4px;
  font-size: 12px;
  opacity: 0.8;
  line-height: 1.2;
}
</style>
