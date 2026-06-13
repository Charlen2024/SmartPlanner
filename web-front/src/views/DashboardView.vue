<script setup>
import { computed, onActivated, onMounted, ref, watch } from 'vue'
import api from '../plugins/api'
import { useAuthStore } from '../stores/auth'
import { timeToMinutes, dateDowValue, computeWeekNumber, matchesWeekFn, clampedDurationMinutes, blockStyle, layoutOverlappingBlocks } from '../composables/useTimeline'

const loading = ref(false)
const error = ref('')
const dashboard = ref(null)
const weather = ref(null)
const weatherLocation = ref(localStorage.getItem('weatherLocation') || '深圳')
const weatherLocations = ['深圳', '北京', '上海', '广州', '杭州', '成都', '武汉', '南京']
const weatherLocationCoords = {
  '深圳': { lat: 22.5431, lon: 114.0579 },
  '北京': { lat: 39.9042, lon: 116.4074 },
  '上海': { lat: 31.2304, lon: 121.4737 },
  '广州': { lat: 23.1291, lon: 113.2644 },
  '杭州': { lat: 30.2741, lon: 120.1551 },
  '成都': { lat: 30.5728, lon: 104.0668 },
  '武汉': { lat: 30.5928, lon: 114.3055 },
  '南京': { lat: 32.0603, lon: 118.7969 },
}
const today = ref(new Date().toISOString().slice(0, 10))
const scheduleDate = ref('')

// ── Helpers ──
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

const auth = useAuthStore()
const firstWeekMonday = computed(() => auth.me?.firstWeekMonday || localStorage.getItem('firstWeekMonday') || '')

const weekNumber = computed(() => computeWeekNumber(scheduleDate.value, firstWeekMonday.value))

const allClasses = computed(() => dashboard.value?.classes ?? [])
const dayDow = computed(() => dateDowValue(scheduleDate.value))
const dayClasses = computed(() => {
  const dow = dayDow.value
  if (!dow) return []
  const dowClasses = (allClasses.value ?? []).filter((c) => Number(c?.dayOfWeek) === Number(dow))
  const wn = weekNumber.value
  if (wn != null) {
    return dowClasses.filter((c) => matchesWeekFn(c, wn))
  }
  return dowClasses
})

function humanMinutes(total) {
  const m = Number(total)
  if (!Number.isFinite(m) || m <= 0) return '0 分'
  const h = Math.floor(m / 60)
  const mm = Math.round(m % 60)
  if (h <= 0) return `${mm} 分`
  if (mm <= 0) return `${h} 小时`
  return `${h} 小时 ${mm} 分`
}

// ── API ──
async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.get('/user/dashboard')
    dashboard.value = res?.data?.data ?? null
  } catch (e) {
    error.value = '加载失败'
  } finally {
    loading.value = false
  }
  loadWeather()
}

async function loadWeather() {
  try {
    let params = {}
    let gotCoords = false
    let lat, lon
    // Try browser geolocation first
    if (navigator.geolocation) {
      try {
        const pos = await new Promise((resolve, reject) => {
          navigator.geolocation.getCurrentPosition(resolve, reject, { timeout: 5000, maximumAge: 600000 })
        })
        lat = pos.coords.latitude
        lon = pos.coords.longitude
        params = { lat, lon }
        gotCoords = true
      } catch (geoErr) {
        // Fall back to saved/default location
        params = { location: weatherLocation.value }
      }
    } else {
      params = { location: weatherLocation.value }
    }
    const w = await api.get('/user/weather', { params })
    weather.value = w?.data?.data ?? null
    if (weather.value?.location) {
      weatherLocation.value = weather.value.location
    }
    // Cache coordinates to Redis on first successful geolocation
    if (gotCoords) {
      api.put('/user/weather-location', null, {
        params: { location: weatherLocation.value, lat, lon }
      }).catch(() => {})
    }
  } catch (e) {
    // silently ignore
  }
}

function selectWeatherLocation(loc) {
  weatherLocation.value = loc
  localStorage.setItem('weatherLocation', loc)
  const coords = weatherLocationCoords[loc]
  const fetchParams = coords ? { lat: coords.lat, lon: coords.lon } : { location: loc }
  const saveParams = coords ? { location: loc, lat: coords.lat, lon: coords.lon } : { location: loc }
  api.get('/user/weather', { params: fetchParams }).then(r => {
    weather.value = r?.data?.data ?? null
  }).catch(() => {})
  api.put('/user/weather-location', null, { params: saveParams }).catch(() => {})
}

// ── Computed ──
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

const todayScheduleCount = computed(() => {
  const d = String(today.value || '')
  return (allTaskSchedules.value ?? []).filter((s) => dateKey(s?.startTime) === d).length
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

// ── Timeline ──
const timelineStartHour = 8
const timelineEndHour = 22
const blockGapPx = 8
const minBlockHeightPx = 44

const pxPerMinute = computed(() => {
  const mins = []
  for (const s of displayTaskSchedules.value ?? []) {
    const d = clampedDurationMinutes(s?.startTime, s?.endTime, timelineStartHour, timelineEndHour)
    if (d != null) mins.push(d)
  }
  for (const f of freeTimeSlots.value ?? []) {
    const d = clampedDurationMinutes(f?.start, f?.end, timelineStartHour, timelineEndHour)
    if (d != null) mins.push(d)
  }
  for (const c of dayClasses.value ?? []) {
    const start = `${scheduleDate.value}T${String(c?.startTime).slice(0, 5)}:00`
    const end = `${scheduleDate.value}T${String(c?.endTime).slice(0, 5)}:00`
    const d = clampedDurationMinutes(start, end, timelineStartHour, timelineEndHour)
    if (d != null) mins.push(d)
  }
  const minDur = mins.length ? Math.min(...mins) : null
  if (!minDur) return 1.6
  const target = (minBlockHeightPx + blockGapPx) / minDur
  return Math.max(1.1, Math.min(3.2, target))
})

const timelineHeightPx = computed(() => (timelineEndHour - timelineStartHour) * 60 * pxPerMinute.value)


const blockStyleW = (start, end) => blockStyle(start, end, pxPerMinute.value, timelineStartHour, timelineEndHour, minBlockHeightPx, blockGapPx)


const freeBlocks = computed(() =>
  (freeTimeSlots.value ?? []).map((f) => ({ ...f, _start: f.start, _end: f.end, style: blockStyleW(f?.start, f?.end) })).filter((f) => f.style),
)

const timelineBlocks = computed(() => {
  const classList = (dayClasses.value ?? [])
    .map((c) => {
      const start = `${scheduleDate.value}T${String(c.startTime).slice(0, 5)}:00`
      const end = `${scheduleDate.value}T${String(c.endTime).slice(0, 5)}:00`
      return { ...c, _kind: 'class', _start: start, _end: end, style: blockStyleW(start, end) }
    })
    .filter((c) => c.style)

  const taskList = (displayTaskSchedules.value ?? [])
    .map((s, idx) => ({
      ...s,
      _kind: 'schedule',
      _idx: idx + 1,
      _start: s?.startTime,
      _end: s?.endTime,
      style: blockStyleW(s?.startTime, s?.endTime),
    }))
    .filter((s) => s.style)

  return layoutOverlappingBlocks([...classList, ...taskList])
})

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

let _ready = false
onMounted(load)
onActivated(() => { if (_ready) load(); _ready = true })
</script>

<template>
  <!-- ====== Page Header ====== -->
  <div class="d-flex align-center flex-wrap ga-3 mb-4">
    <div>
      <div class="text-h5 font-weight-bold">仪表盘</div>
      <div class="text-body-2 text-medium-emphasis">{{ today }} · 今日概览</div>
    </div>
    <v-spacer />
    <v-btn variant="tonal" size="small" :loading="loading" @click="load">
      <v-icon icon="mdi-refresh" size="18" class="mr-1" />刷新
    </v-btn>
  </div>

  <v-alert v-if="error" type="error" variant="tonal" class="mb-4" density="compact">{{ error }}</v-alert>

  <!-- ====== Loading Skeleton ====== -->
  <template v-if="loading && !dashboard">
    <v-row class="mb-4">
      <v-col v-for="n in 4" :key="n" cols="6" md="3">
        <v-skeleton-loader type="card" />
      </v-col>
    </v-row>
    <v-row class="mb-4">
      <v-col cols="12" md="5"><v-skeleton-loader type="card" /></v-col>
      <v-col cols="12" md="7"><v-skeleton-loader type="card" /></v-col>
    </v-row>
    <v-row>
      <v-col cols="12"><v-skeleton-loader type="card" /></v-col>
    </v-row>
  </template>

  <template v-if="dashboard">
    <!-- ====== Top Metric Cards ====== -->
    <v-row class="mb-4">
      <v-col cols="6" md="3">
        <v-card class="dash-metric-card" color="primary" variant="tonal">
          <div class="d-flex align-center ga-2 mb-2">
            <v-icon icon="mdi-bullseye-arrow" size="18" />
            <span class="text-caption font-weight-medium">目标数</span>
          </div>
          <div class="text-h4 font-weight-bold">{{ dashboard.goals?.length ?? 0 }}</div>
        </v-card>
      </v-col>
      <v-col cols="6" md="3">
        <v-card class="dash-metric-card" color="warning" variant="tonal">
          <div class="d-flex align-center ga-2 mb-2">
            <v-icon icon="mdi-fire" size="18" />
            <span class="text-caption font-weight-medium">连续打卡</span>
          </div>
          <div class="text-h4 font-weight-bold">{{ dashboard.streak ?? 0 }} <span class="text-body-2 font-weight-regular">天</span></div>
        </v-card>
      </v-col>
      <v-col cols="6" md="3">
        <v-card class="dash-metric-card" color="success" variant="tonal">
          <div class="d-flex align-center ga-2 mb-2">
            <v-icon icon="mdi-calendar-check" size="18" />
            <span class="text-caption font-weight-medium">今日排程</span>
          </div>
          <div class="text-h4 font-weight-bold">{{ todayScheduleCount }} <span class="text-body-2 font-weight-regular">项</span></div>
        </v-card>
      </v-col>
      <v-col cols="6" md="3">
        <v-card class="dash-metric-card" color="info" variant="tonal">
          <div class="d-flex align-center ga-2 mb-2">
            <v-icon icon="mdi-clock-outline" size="18" />
            <span class="text-caption font-weight-medium">课余时长</span>
          </div>
          <div class="text-h4 font-weight-bold">{{ humanMinutes(freeTotalMinutes) }}</div>
        </v-card>
      </v-col>
    </v-row>

    <!-- ====== Weather + Free Time ====== -->
    <v-row class="mb-4">
      <v-col cols="12" md="5">
        <v-card class="h-100">
          <v-card-title class="d-flex align-center pb-1">
            <v-icon icon="mdi-weather-partly-cloudy" class="mr-2" />
            天气
            <v-spacer />
            <v-menu>
              <template #activator="{ props: menuProps }">
                <v-btn v-bind="menuProps" size="x-small" variant="text" class="text-caption mr-1">
                  <v-icon icon="mdi-map-marker" size="14" class="mr-1" />{{ weatherLocation }}
                </v-btn>
              </template>
              <v-list density="compact">
                <v-list-item
                  v-for="loc in weatherLocations"
                  :key="loc"
                  :title="loc"
                  :active="loc === weatherLocation"
                  @click="selectWeatherLocation(loc)"
                />
              </v-list>
            </v-menu>
            <v-btn size="x-small" variant="text" icon="mdi-refresh" aria-label="刷新天气" @click="loadWeather" />
          </v-card-title>
          <v-divider />
          <v-card-text>
            <div v-if="weather && weather.summary !== '天气服务不可用'" class="d-flex align-center ga-4">
              <div class="text-h3 font-weight-bold">{{ tempText() }}°</div>
              <div>
                <div class="text-body-1 font-weight-medium">{{ weather.summary || '天气' }}</div>
                <div class="text-caption" style="opacity:0.7">
                  风速 {{ windText() }} km/h
                  <span v-if="weather.feelsLike != null"> · 体感 {{ weather.feelsLike }}°C</span>
                  <span v-if="weather.humidity"> · 湿度 {{ weather.humidity }}%</span>
                </div>
              </div>
            </div>
            <div v-else-if="weather && weather.summary === '天气服务不可用'" class="text-body-2" style="opacity:0.5">
              天气服务暂不可用
              <v-btn size="x-small" variant="text" class="ml-1" @click="loadWeather">重试</v-btn>
            </div>
            <div v-else class="text-body-2" style="opacity:0.5">加载中…</div>
          </v-card-text>
        </v-card>
      </v-col>
      <v-col cols="12" md="7">
        <v-card class="h-100">
          <v-card-title class="d-flex align-center pb-1">
            <v-icon icon="mdi-calendar-clock" class="mr-2" />
            今日课余时间
            <v-spacer />
            <v-chip size="small" variant="tonal" color="primary">{{ today }}</v-chip>
          </v-card-title>
          <v-divider />
          <v-card-text>
            <div v-if="freeTotalMinutes" class="text-h5 font-weight-bold mb-3">{{ humanMinutes(freeTotalMinutes) }}</div>
            <v-alert v-if="!(freeTimeSlots?.length)" type="info" variant="tonal" density="compact" class="mb-0">
              暂无课余时段，请导入课表
            </v-alert>
            <div v-else class="d-flex flex-wrap" style="gap:8px">
              <v-chip v-for="(slot, i) in freeTimeSlots" :key="i" size="small" variant="outlined">
                {{ fmtHm(slot.start) }} - {{ fmtHm(slot.end) }}
              </v-chip>
            </div>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <!-- ====== Task Schedule Timeline ====== -->
    <v-row class="mb-4">
      <v-col cols="12">
        <v-card>
          <v-card-title class="d-flex align-center pb-1">
            <v-icon icon="mdi-timetable" class="mr-2" />
            任务日程
            <v-spacer />
            <span class="text-caption text-medium-emphasis mr-2">{{ scheduleDate }}</span>
          </v-card-title>
          <v-divider />
          <v-card-text>
            <div class="mb-3">
              <v-chip-group column>
                <v-chip
                  v-for="d in availableScheduleDates"
                  :key="d"
                  :color="String(d) === String(scheduleDate) ? 'primary' : undefined"
                  :variant="String(d) === String(scheduleDate) ? 'tonal' : 'outlined'"
                  size="small"
                  @click="scheduleDate = d"
                >
                  {{ d }}
                </v-chip>
              </v-chip-group>
            </div>

            <v-alert v-if="!(displayTaskSchedules?.length) && !(dayClasses?.length)" type="info" variant="tonal" density="compact">
              该日暂无日程
            </v-alert>

            <div v-else class="dash-timeline-shell">
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
                  v-for="(b, i) in timelineBlocks"
                  :key="(b._kind || 'block') + '-' + (b.id ?? i)"
                  class="dash-timeline-block"
                  :class="b._kind === 'class' ? 'dash-timeline-class' : 'dash-timeline-task'"
                  :style="b.style"
                >
                  <div class="dash-timeline-title">{{ b._kind === 'class' ? b.courseName : (b.taskTitle || '任务 ' + b._idx) }}</div>
                  <div class="dash-timeline-sub">
                    {{ b._kind === 'class' ? (String(b.startTime).slice(0, 5) + ' - ' + String(b.endTime).slice(0, 5) + ' ' + (b.location || '')) : (fmtHm(b.startTime) + ' - ' + fmtHm(b.endTime)) }}
                  </div>
                </div>
              </div>
            </div>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <!-- ====== Goal Progress ====== -->
    <v-row>
      <v-col cols="12">
        <v-card>
          <v-card-title class="d-flex align-center pb-1">
            <v-icon icon="mdi-chart-arc" class="mr-2" />
            目标进度
          </v-card-title>
          <v-divider />
          <v-card-text>
            <v-alert v-if="!(dashboard.goalProgress?.length)" type="info" variant="tonal" density="compact">
              暂无目标进度
            </v-alert>
            <div v-for="g in dashboard.goalProgress ?? []" :key="g.goalId" class="goal-progress-item">
              <div class="d-flex justify-space-between align-center mb-1">
                <div class="d-flex align-center ga-2">
                  <span class="text-body-2 font-weight-semibold">{{ g.title }}</span>
                  <v-chip size="x-small" :color="g.percent >= 100 ? 'success' : 'primary'" variant="tonal">
                    {{ g.percent }}%
                  </v-chip>
                </div>
                <span class="text-caption" style="opacity:0.65">{{ g.doneTasks }}/{{ g.totalTasks }}</span>
              </div>
              <v-progress-linear :model-value="g.percent" height="8" rounded :color="g.percent >= 100 ? 'success' : 'primary'" />
            </div>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>
  </template>
</template>

<style scoped>
/* ── Metric Cards ── */
.dash-metric-card {
  padding: 16px;
  border-radius: 12px;
  transition: transform 0.2s, box-shadow 0.2s;
}
.dash-metric-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 16px rgba(var(--v-theme-on-surface), 0.1);
}

/* ── Goal Progress ── */
.goal-progress-item {
  padding: 8px 12px;
  border-radius: 10px;
  transition: background 0.15s;
  margin-bottom: 8px;
}
.goal-progress-item:last-child { margin-bottom: 0; }
.goal-progress-item:hover {
  background: rgba(var(--v-theme-on-surface), 0.025);
}

/* ── Timeline ── */
.dash-timeline-shell {
  display: grid;
  grid-template-columns: 64px 1fr;
  gap: 10px;
  max-height: 360px;
  overflow: auto;
  border-radius: 12px;
  border: 1px solid rgba(var(--v-theme-on-surface), 0.08);
}

.dash-timeline-axis {
  position: relative;
  background: rgba(var(--v-theme-on-surface), 0.02);
  padding-top: 6px;
}

.dash-timeline-axis-row {
  position: relative;
  padding-right: 8px;
  text-align: right;
  font-size: 12px;
  opacity: 0.65;
}

.dash-timeline-canvas {
  position: relative;
  padding: 6px 10px;
  background: linear-gradient(180deg, rgba(var(--v-theme-on-surface), 0.01), rgba(var(--v-theme-on-surface), 0.02));
}

.dash-timeline-gridline {
  position: absolute;
  left: 0;
  right: 0;
  height: 1px;
  background: rgba(var(--v-theme-on-surface), 0.06);
}

.dash-timeline-free {
  position: absolute;
  left: 4px;
  right: 4px;
  border-radius: 8px;
  background: rgba(var(--v-theme-primary), 0.06);
  border: 1px dashed rgba(var(--v-theme-primary), 0.2);
  z-index: 0;
}

.dash-timeline-block {
  position: absolute;
  left: 10px;
  right: 10px;
  border-radius: 10px;
  padding: 6px 10px;
  box-shadow: 0 2px 8px rgba(var(--v-theme-on-surface), 0.06);
  overflow: hidden;
  box-sizing: border-box;
  z-index: 1;
  border-left: 4px solid;
}

.dash-timeline-class {
  background: rgba(var(--v-theme-secondary), 0.1);
  border-color: rgba(var(--v-theme-secondary), 0.2);
  border-left-color: rgb(var(--v-theme-secondary));
}

.dash-timeline-task {
  background: rgba(var(--v-theme-primary), 0.1);
  border-color: rgba(var(--v-theme-primary), 0.2);
  border-left-color: rgb(var(--v-theme-primary));
}

.dash-timeline-title {
  font-weight: 600;
  font-size: 13px;
  line-height: 1.2;
}

.dash-timeline-sub {
  margin-top: 2px;
  font-size: 11px;
  opacity: 0.7;
  line-height: 1.2;
}
</style>
