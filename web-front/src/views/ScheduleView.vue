<script setup>
import { ref, onActivated, onMounted, computed, watch } from 'vue'
import api from '../plugins/api'
import { useAuthStore } from '../stores/auth'
import { useRoute, useRouter } from 'vue-router'
import { timeToMinutes, dateDowValue, computeWeekNumber, matchesWeekFn, clampedDurationMinutes, blockStyle, layoutOverlappingBlocks } from '../composables/useTimeline'
import { useNotifyStore } from '../stores/notify'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const notify = useNotifyStore()

const loading = ref(false)
const error = ref('')
const busy = ref(false)
const date = ref('')
const free = ref([])
const schedules = ref([])
const classes = ref([])
const file = ref(null)
const importResult = ref(null)
const classPanelOpen = ref(false)

const needsImport = computed(() => auth.me?.scheduleImported === false)

const dateChips = computed(() => {
  const out = []
  const base = new Date()
  const todayStr = base.toISOString().slice(0, 10)
  for (let i = -1; i < 14; i++) {
    const d = new Date(base.getTime() + i * 86400000)
    const value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
    out.push({ title: value === todayStr ? '今天' : `${d.getMonth() + 1}/${d.getDate()}`, subtitle: `周${week}`, value })
  }
  return out
})

const filteredSchedules = computed(() => {
  if (!date.value) return schedules.value ?? []
  const d = String(date.value)
  return (schedules.value ?? []).filter((s) => String(s?.startTime || '').startsWith(d))
})

const timelineStartHour = 8
const timelineEndHour = 22
const blockGapPx = 10
const minBlockHeightPx = 56

const pxPerMinute = computed(() => {
  const mins = []
  for (const s of filteredSchedules.value ?? []) {
    const d = clampedDurationMinutes(s?.startTime, s?.endTime, timelineStartHour, timelineEndHour)
    if (d != null) mins.push(d)
  }
  for (const c of dayClasses.value ?? []) {
    const start = `${date.value}T${String(c?.startTime).slice(0, 5)}:00`
    const end = `${date.value}T${String(c?.endTime).slice(0, 5)}:00`
    const d = clampedDurationMinutes(start, end, timelineStartHour, timelineEndHour)
    if (d != null) mins.push(d)
  }
  const minDur = mins.length ? Math.min(...mins) : null
  if (!minDur) return 1.5
  const target = (minBlockHeightPx + blockGapPx) / minDur
  return Math.max(1.2, Math.min(4.0, target))
})

const timelineHeightPx = computed(() => (timelineEndHour - timelineStartHour) * 60 * pxPerMinute.value)

const blockStyleW = (start, end) => blockStyle(start, end, pxPerMinute.value, timelineStartHour, timelineEndHour, minBlockHeightPx, blockGapPx)

function blockStyleCompact(start, end) {
  const s = timeToMinutes(start)
  const e = timeToMinutes(end)
  if (s == null || e == null) return null
  const totalMin = (timelineEndHour - timelineStartHour) * 60
  const startMin = Math.max(s, timelineStartHour * 60) - timelineStartHour * 60
  const endMin = Math.min(e, timelineEndHour * 60) - timelineStartHour * 60
  const hMin = endMin - startMin
  if (hMin <= 0) return null
  const topPct = (startMin / totalMin) * 100
  const hPct = (hMin / totalMin) * 100
  return { top: `${topPct}%`, height: `${Math.max(3, hPct)}%` }
}

const dayDow = computed(() => dateDowValue(date.value))
const dayWeekNumber = computed(() => computeWeekNumber(date.value, firstWeekMonday.value))
const dayClasses = computed(() => {
  const dow = dayDow.value
  if (!dow) return []
  const source = dayClassesFiltered.value != null ? dayClassesFiltered.value : classes.value
  return source.filter((c) => Number(c?.dayOfWeek) === Number(dow))
})

const classDowSet = computed(() => {
  const allClasses = classes.value ?? []
  return new Set(allClasses.map((c) => Number(c?.dayOfWeek)).filter((n) => Number.isFinite(n)))
})
const dateHasClasses = computed(() => {
  const dow = dayDow.value
  if (!dow) return false
  const source = dayClassesFiltered.value != null ? dayClassesFiltered.value : classes.value
  const dowClasses = source.filter((c) => Number(c?.dayOfWeek) === Number(dow))
  return dowClasses.length > 0
})

function dowText(n) {
  const map = { 1: '一', 2: '二', 3: '三', 4: '四', 5: '五', 6: '六', 7: '日' }
  return map[n] || String(n)
}

const importedDowText = computed(() => {
  const set = classDowSet.value
  if (!set || set.size === 0) return ''
  const arr = Array.from(set).sort((a, b) => a - b)
  return arr.map((n) => `周${dowText(n)}`).join('、')
})

const freeBarItems = computed(() => {
  const totalMin = (timelineEndHour - timelineStartHour) * 60
  return (free.value ?? []).map((f) => {
    const s = timeToMinutes(f.start)
    const e = timeToMinutes(f.end)
    if (s == null || e == null) return null
    const leftPct = ((s - timelineStartHour * 60) / totalMin) * 100
    const widthPct = ((e - s) / totalMin) * 100
    const startLabel = String(f.start).includes('T') ? String(f.start).slice(11, 16) : String(f.start).slice(0, 5)
    const endLabel = String(f.end).includes('T') ? String(f.end).slice(11, 16) : String(f.end).slice(0, 5)
    return { ...f, style: { left: `${leftPct}%`, width: `${widthPct}%` }, startLabel, endLabel }
  }).filter(Boolean)
})

const freeBlocks = computed(() =>
  (free.value ?? []).map((f) => ({ ...f, style: blockStyleW(f.start, f.end) })).filter((f) => f.style),
)

const timelineBlocks = computed(() => {
  const classList = (dayClasses.value ?? [])
    .map((c) => {
      const start = `${date.value}T${String(c.startTime).slice(0, 5)}:00`
      const end = `${date.value}T${String(c.endTime).slice(0, 5)}:00`
      return { ...c, _kind: 'class', _start: start, _end: end, style: blockStyleW(start, end) }
    })
    .filter((c) => c.style)

  const taskList = (filteredSchedules.value ?? [])
    .map((s) => ({ ...s, _kind: 'schedule', style: blockStyleW(s.startTime, s.endTime) }))
    .filter((s) => s.style)

  return layoutOverlappingBlocks([...classList, ...taskList])
})

// Compact timeline for the mini view alongside task cards
const compactTimeline = computed(() => {
  const classList = (dayClasses.value ?? []).map((c) => {
    const start = `${date.value}T${String(c.startTime).slice(0, 5)}:00`
    const end = `${date.value}T${String(c.endTime).slice(0, 5)}:00`
    return { ...c, _kind: 'class', style: blockStyleCompact(start, end) }
  }).filter((c) => c.style)

  const taskList = (filteredSchedules.value ?? []).map((s) => ({
    ...s, _kind: 'schedule', style: blockStyleCompact(s.startTime, s.endTime),
  })).filter((s) => s.style)

  return [...classList, ...taskList].sort((a, b) => {
    const sa = timeToMinutes(a._kind === 'class' ? a._start : a.startTime) ?? 0
    const sb = timeToMinutes(b._kind === 'class' ? b._start : b.startTime) ?? 0
    return sa - sb
  })
})

const doneCount = computed(() => (filteredSchedules.value ?? []).filter((s) => Number(s?.status) === 1).length)
const totalCount = computed(() => (filteredSchedules.value ?? []).length)
const donePercent = computed(() => {
  const t = totalCount.value
  if (!t) return 0
  return Math.round((doneCount.value / t) * 100)
})

const hasSchedulesOrClasses = computed(() => filteredSchedules.value.length > 0 || dayClasses.value.length > 0)

function fmt(dt) {
  if (!dt) return '-'
  return String(dt).replace('T', ' ').slice(0, 16)
}

function statusText(s) {
  if (s === 1) return '已完成'
  if (s === 2) return '已取消'
  return '未完成'
}

async function loadFree() {
  if (!date.value) return
  loading.value = true
  error.value = ''
  try {
    const res = await api.get('/user/schedule/free-time', { params: { date: date.value } })
    free.value = res?.data?.data ?? []
  } catch (e) {
    error.value = '加载失败'
  } finally {
    loading.value = false
  }
}

async function loadSchedules() {
  try {
    const params = {}
    if (date.value) { params.from = `${date.value}T00:00:00`; params.to = `${date.value}T23:59:59` }
    const res = await api.get('/user/schedule/task-schedules', { params })
    schedules.value = res?.data?.data ?? []
  } catch (e) {
    const status = e?.response?.status
    if (status === 401) { error.value = '登录已失效，请重新登录' }
    else { error.value = e?.response?.data?.message || e?.message || '加载任务排程失败' }
    schedules.value = []
  }
}


async function importSchedule() {
  if (!file.value) return
  busy.value = true
  const fd = new FormData()
  fd.append('file', file.value)
  if (firstWeekMonday.value) fd.append('firstWeekMonday', firstWeekMonday.value)
  importResult.value = null
  error.value = ''
  try {
    const res = await api.post('/user/schedule/import', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
    importResult.value = res?.data?.data ?? null
    notify.success('课表已上传，正在刷新')
    await auth.fetchMe()
    await loadClasses()
    await Promise.all([loadFree(), loadSchedules()])
    if (classes.value?.length) {
      const set = classDowSet.value
      let chosen = null
      if (set && set.size) {
        const t = new Date()
        for (let i = 0; i < 14; i++) {
          const d = new Date(t.getTime() + i * 86400000)
          const js = d.getDay(); const dow = ((js + 6) % 7) + 1
          if (set.has(dow)) { chosen = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; break }
        }
      }
      if (chosen) { date.value = chosen; await Promise.all([loadFree(), loadSchedules()]) }
    }
    const next = route.query.next
    if (next) router.push(String(next))
  } catch (e) {
    error.value = e?.response?.data?.message || '导入失败（支持 .ics / .xlsx / .csv）'
  } finally { busy.value = false }
}

const dayClassesFiltered = ref(null)

async function loadClasses(dateParam) {
  const params = {}
  if (dateParam) params.date = dateParam
  const res = await api.get('/user/schedule/classes', { params })
  const list = res?.data?.data ?? []
  if (dateParam) { dayClassesFiltered.value = list }
  else { classes.value = list }
}

async function clearClasses() {
  busy.value = true
  try {
    await api.delete('/user/schedule/classes')
    dayClassesFiltered.value = null
    await loadClasses()
  } finally { busy.value = false }
}

onMounted(() => {
  if (!date.value) {
    const t = new Date()
    date.value = `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`
  }
  Promise.all([loadSchedules(), loadClasses()]).then(() => {
    if (date.value) loadClasses(date.value)
  })
})

let _ready3 = false
onActivated(() => {
  if (_ready3) {
    Promise.all([loadSchedules(), loadClasses()]).then(() => {
      if (date.value) loadClasses(date.value)
    })
  }
  _ready3 = true
})

watch(() => date.value, async () => {
  dayClassesFiltered.value = null
  await Promise.all([loadSchedules(), loadFree(), loadClasses(date.value)])
})

watch(() => notify.signalSeq?.GOAL_TASK_READY, async () => {
  await Promise.all([loadSchedules(), loadFree()])
  if (date.value) await loadClasses(date.value)
})

watch(() => notify.signalSeq?.SCHEDULE_DONE, async () => {
  await Promise.all([loadSchedules(), loadFree()])
  if (date.value) await loadClasses(date.value)
})

// ── Weekly class schedule grid ──
const firstWeekMonday = ref(auth.me?.firstWeekMonday || localStorage.getItem('firstWeekMonday') || '')
watch(() => auth.me?.firstWeekMonday, (v) => { if (v) { firstWeekMonday.value = v; localStorage.setItem('firstWeekMonday', v) } })
const firstWeekMondayDate = computed({
  get: () => firstWeekMonday.value ? new Date(firstWeekMonday.value + 'T00:00:00') : undefined,
  set: (v) => { setFirstWeekMonday(v ? `${v.getFullYear()}-${String(v.getMonth()+1).padStart(2,'0')}-${String(v.getDate()).padStart(2,'0')}` : '') }
})
async function setFirstWeekMonday(val) {
  firstWeekMonday.value = val
  localStorage.setItem('firstWeekMonday', val)
  try {
    await api.put('/user/schedule/first-week-monday', null, { params: { firstWeekMonday: val || '' } })
    await auth.fetchMe()
    await loadFree()
    if (date.value) await loadClasses(date.value)
  } catch (e) { /* ignore */ }
}
const weekOffset = ref(0)
const showAllWeeks = ref(false)

const todayWeekNumber = computed(() => {
  const today = new Date().toISOString().slice(0, 10)
  return computeWeekNumber(today, firstWeekMonday.value)
})

const viewWeek = computed(() => {
  const base = todayWeekNumber.value
  if (base == null) return null
  return base + weekOffset.value
})

const weekGridClasses = computed(() => {
  const list = classes.value ?? []
  if (showAllWeeks.value) return list
  const wn = viewWeek.value
  if (wn == null) return list
  return list.filter((c) => matchesWeekFn(c, wn))
})

const periodSlots = computed(() => {
  const map = new Map()
  for (const c of classes.value ?? []) {
    const key = `${c.startTime}_${c.endTime}`
    if (!map.has(key)) map.set(key, { start: c.startTime, end: c.endTime })
  }
  return Array.from(map.values()).sort((a, b) => String(a.start || '').localeCompare(String(b.start || '')))
})

function periodPairIndex(c) {
  if (!c) return -1
  const start = String(c.startTime || ''), end = String(c.endTime || '')
  return periodSlots.value.findIndex((s) => String(s.start || '') === start && String(s.end || '') === end)
}

const weekGridMap = computed(() => {
  const map = {}
  for (const c of weekGridClasses.value ?? []) {
    const dow = Number(c.dayOfWeek), ppi = periodPairIndex(c)
    if (!Number.isFinite(dow) || dow < 1 || dow > 7 || ppi < 0) continue
    const key = `${dow}_${ppi}`
    if (!map[key]) map[key] = []
    map[key].push(c)
  }
  return map
})

const viewWeekMonday = computed(() => {
  const fwm = firstWeekMonday.value, vw = viewWeek.value
  if (!fwm || vw == null) return ''
  const d = new Date(`${fwm}T00:00:00`)
  d.setDate(d.getDate() + (vw - 1) * 7)
  return d.toISOString().slice(0, 10)
})

const weekDays = computed(() => {
  const labels = ['一', '二', '三', '四', '五', '六', '日']
  const monday = viewWeekMonday.value
  if (!monday) return labels.map((label, i) => ({ dow: i + 1, label, date: '' }))
  const d = new Date(`${monday}T00:00:00`)
  return labels.map((label, i) => {
    const date = new Date(d.getTime() + i * 86400000)
    return { dow: i + 1, label, date: `${date.getMonth() + 1}/${date.getDate()}` }
  })
})

const weekLabel = computed(() => {
  const vw = viewWeek.value
  return vw != null ? `第 ${vw} 周` : ''
})

const hasWeekInfo = computed(() => {
  return (classes.value ?? []).some((c) => c.weekStart != null || c.weekEnd != null)
})

function goPrevWeek() { weekOffset.value-- }
function goNextWeek() { weekOffset.value++ }
function resetWeek() { weekOffset.value = 0 }

function fmtHm(dt) {
  if (!dt) return '--:--'
  const s = String(dt)
  if (s.includes('T')) return s.slice(11, 16)
  return s.slice(0, 5)
}
</script>

<template>
  <!-- ====== Import reminder ====== -->
  <v-alert v-if="needsImport" type="info" variant="tonal" class="mb-4" density="compact">
    首次使用，请先导入课表以便系统识别课余时间。排程任务请在「目标」页生成。
  </v-alert>

  <v-alert v-if="error" type="error" variant="tonal" class="mb-4" density="compact">{{ error }}</v-alert>
  <v-alert v-if="importResult" type="success" variant="tonal" class="mb-4" density="compact">
    已导入 {{ importResult.inserted }}/{{ importResult.total }} 门课程
    <span v-if="importResult.warnings?.length">（{{ importResult.warnings.join('；') }}）</span>
  </v-alert>
  <v-alert v-if="date && classes?.length && !dateHasClasses" type="info" variant="tonal" class="mb-4" density="compact">
    {{ date }}（周{{ dowText(dayDow) }}）当天无课程；已导入课程分布在 {{ importedDowText }}。
  </v-alert>

  <!-- ====== Date Selector + Progress ====== -->
  <v-card class="mb-4 pa-3">
    <div class="d-flex align-center flex-wrap ga-2">
      <v-icon icon="mdi-calendar-month" class="mr-1" color="primary" />
      <v-chip-group column>
        <v-chip
          v-for="d in dateChips"
          :key="d.value"
          size="small"
          :variant="date === d.value ? 'tonal' : 'text'"
          :color="date === d.value ? 'primary' : undefined"
          class="date-chip"
          @click="date = d.value"
        >
          <span class="font-weight-semibold">{{ d.title }}</span>
          <span class="ml-1 text-caption date-chip-sub">{{ d.subtitle }}</span>
        </v-chip>
      </v-chip-group>
      <v-spacer />
      <template v-if="totalCount">
        <v-progress-circular :model-value="donePercent" size="40" width="5" :color="donePercent >= 100 ? 'success' : 'primary'">
          <span style="font-size:11px">{{ donePercent }}%</span>
        </v-progress-circular>
        <span class="text-caption ml-2" style="opacity:0.75">完成 {{ doneCount }}/{{ totalCount }}</span>
      </template>
    </div>
  </v-card>

  <!-- ====== Daily View ====== -->
  <v-row>
    <!-- Left: Free time + Task schedule -->
    <v-col cols="12" md="5">
      <!-- Free time bar -->
      <v-card class="mb-4">
        <v-card-title class="d-flex align-center py-2 px-3">
          <v-icon icon="mdi-timer-outline" size="20" class="mr-2" />
          <span class="text-body-2 font-weight-semibold">课余时间</span>
        </v-card-title>
        <v-divider />
        <v-card-text class="pa-3">
          <div v-if="!free?.length" class="text-center py-3">
            <div class="text-caption text-medium-emphasis">当天无课余时间</div>
            <div class="text-caption mt-1" style="opacity:0.5">（课表未导入或当天无课程）</div>
          </div>
          <div v-else class="free-bar-shell">
            <div class="free-bar-axis">
              <span v-for="h in (timelineEndHour - timelineStartHour + 1)" :key="h" class="free-bar-tick">{{ String(timelineStartHour + h - 1).padStart(2, '0') }}:00</span>
            </div>
            <div class="free-bar-track">
              <div v-for="(f, i) in freeBarItems" :key="i" class="free-bar-block" :style="f.style">
                <span class="free-bar-label">{{ f.startLabel }} - {{ f.endLabel }}</span>
              </div>
            </div>
          </div>
        </v-card-text>
      </v-card>

      <!-- Task schedule cards -->
      <v-card>
        <v-card-title class="d-flex align-center justify-space-between py-2 px-3">
          <div class="d-flex align-center">
            <v-icon icon="mdi-timetable" size="20" class="mr-2" />
            <span class="text-body-2 font-weight-semibold">任务排程</span>
          </div>
          <v-chip v-if="totalCount" size="x-small" variant="tonal" :color="donePercent >= 100 ? 'success' : 'primary'">
            {{ doneCount }}/{{ totalCount }}
          </v-chip>
        </v-card-title>
        <v-divider />
        <v-card-text class="pa-3">
          <v-alert v-if="!filteredSchedules?.length" type="info" variant="tonal" density="compact">
            暂无排程任务，前往「目标」页生成。
          </v-alert>
          <div v-else>
            <v-progress-linear
              v-if="totalCount"
              :model-value="donePercent"
              height="4"
              rounded
              :color="donePercent >= 100 ? 'success' : 'primary'"
              class="mb-3"
            />
            <div
              v-for="(s, i) in filteredSchedules"
              :key="s.id"
              class="sched-task-card mb-2"
              :class="{ 'sched-task-done': Number(s.status) === 1 }"
            >
              <div class="sched-task-time">
                <span class="sched-task-time-val">{{ String(s.startTime || '').slice(11, 16) }}</span>
                <span class="sched-task-time-sep">-</span>
                <span class="sched-task-time-val">{{ String(s.endTime || '').slice(11, 16) }}</span>
              </div>
              <div class="sched-task-body">
                <div class="d-flex align-center justify-space-between">
                  <div class="font-weight-semibold text-body-2">{{ s.taskTitle || `任务 ${s.taskId}` }}</div>
                  <v-chip size="x-small" variant="tonal" :color="Number(s.status) === 1 ? 'success' : Number(s.status) === 2 ? undefined : 'primary'">
                    {{ statusText(s.status) }}
                  </v-chip>
                </div>
                <div class="d-flex align-center mt-1">
                  <v-icon size="14" class="mr-1" :color="Number(s.status) === 1 ? 'success' : 'primary'" style="opacity:0.6">
                    {{ Number(s.status) === 1 ? 'mdi-check-circle' : 'mdi-clock-outline' }}
                  </v-icon>
                  <span class="text-caption" style="opacity:0.6">{{ fmt(s.startTime).slice(11, 16) }} — {{ fmt(s.endTime).slice(11, 16) }}</span>
                </div>
              </div>
            </div>
          </div>
        </v-card-text>
      </v-card>
    </v-col>

    <!-- Right: Timeline visualization -->
    <v-col cols="12" md="7">
      <v-card style="height:100%">
        <v-card-title class="d-flex align-center py-2 px-3">
          <v-icon icon="mdi-chart-timeline-variant" size="20" class="mr-2" />
          <span class="text-body-2 font-weight-semibold">日程可视化</span>
          <v-spacer />
          <span class="text-caption text-medium-emphasis">{{ date }}</span>
        </v-card-title>
        <v-divider />
        <v-card-text class="pa-3" style="overflow:auto; max-height:520px">
          <div v-if="!hasSchedulesOrClasses" class="text-center py-10">
            <v-icon icon="mdi-calendar-blank" size="48" class="mb-2 text-medium-emphasis" style="opacity:0.3" />
            <div class="text-caption text-medium-emphasis">当天暂无日程</div>
          </div>
          <div v-else class="timeline-shell">
            <div class="timeline-axis" :style="{ height: timelineHeightPx + 'px' }">
              <div v-for="h in (timelineEndHour - timelineStartHour + 1)" :key="h" class="timeline-axis-row" :style="{ height: (60 * pxPerMinute) + 'px' }">
                {{ String(timelineStartHour + h - 1).padStart(2, '0') }}:00
              </div>
            </div>
            <div class="timeline-canvas" :style="{ height: timelineHeightPx + 'px' }">
              <div v-for="h in (timelineEndHour - timelineStartHour + 1)" :key="h" class="timeline-gridline" :style="{ top: ((h - 1) * 60 * pxPerMinute) + 'px' }" />
              <div v-for="(b, i) in freeBlocks" :key="'free-' + i" class="timeline-free" :style="b.style" />
              <div v-for="(b, i) in timelineBlocks" :key="(b._kind || 'block') + '-' + (b.id ?? i)" class="timeline-block" :class="[b._kind === 'class' ? 'timeline-class' : 'timeline-task', Number(b.status) === 1 ? 'timeline-task-done' : '']" :style="b.style">
                <div class="timeline-title">{{ b._kind === 'class' ? b.courseName : (b.taskTitle || '任务 ' + b.taskId) }}</div>
                <div class="timeline-sub">{{ b._kind === 'class' ? (String(b.startTime).slice(0, 5) + ' - ' + String(b.endTime).slice(0, 5) + ' ' + (b.location || '')) : (fmt(b.startTime).slice(11, 16) + ' - ' + fmt(b.endTime).slice(11, 16)) }}</div>
              </div>
            </div>
          </div>
        </v-card-text>
      </v-card>
    </v-col>
  </v-row>

  <!-- ====== Class Management (collapsible) ====== -->
  <v-card class="mt-4">
    <v-card-title class="d-flex align-center py-2 px-3 cursor-pointer" @click="classPanelOpen = !classPanelOpen" style="user-select:none">
      <v-icon icon="mdi-book-education" size="20" class="mr-2" />
      <span class="text-body-2 font-weight-semibold">课表管理</span>
      <v-spacer />
      <v-chip v-if="classes?.length" size="x-small" variant="tonal" class="mr-2">{{ classes.length }} 门课程</v-chip>
      <v-icon :icon="classPanelOpen ? 'mdi-chevron-up' : 'mdi-chevron-down'" size="20" />
    </v-card-title>

    <v-expand-transition>
      <div v-show="classPanelOpen">
        <v-divider />
        <v-card-text class="pa-3">
          <v-row>
            <!-- Import form -->
            <v-col cols="12" md="5">
              <div class="text-subtitle-2 font-weight-semibold mb-3">{{ needsImport ? '导入课表' : '更新课表' }}</div>

              <div class="upload-zone mb-3" :class="{ 'upload-zone--has-file': file }" @click="$refs.scheduleFileInput?.click()" @dragover.prevent @drop.prevent="file = $event.dataTransfer?.files?.[0]">
                <input ref="scheduleFileInput" type="file" accept=".csv,.xlsx,.ics" style="display:none" @change="file = $event.target.files?.[0]" />
                <v-icon :icon="file ? 'mdi-file-check-outline' : 'mdi-cloud-upload-outline'" size="32" :color="file ? 'success' : undefined" class="mb-1" />
                <div v-if="!file" class="text-body-2 font-weight-medium">点击或拖拽文件</div>
                <div v-else class="text-body-2 font-weight-medium text-success">{{ file?.name }}</div>
                <div class="text-caption mt-1" style="opacity:0.45">.ics / .xlsx / .csv</div>
              </div>

              <v-row dense class="mb-3">
                <v-col cols="12" sm="6">
                  <v-date-input v-model="firstWeekMondayDate" label="第一周周一" variant="outlined" density="comfortable" hide-details hint="选学期第一个周一" persistent-hint />
                </v-col>
                <v-col cols="12" sm="6" class="d-flex align-center">
                  <v-alert v-if="todayWeekNumber != null" type="info" variant="tonal" density="compact" class="mb-0 w-100 text-caption">
                    当前为 <strong>第 {{ todayWeekNumber }} 周</strong>
                  </v-alert>
                  <div v-else class="text-caption" style="opacity:0.4">填写第一周周一后自动计算</div>
                </v-col>
              </v-row>

              <div class="format-hints mb-3">
                <div class="text-caption" style="opacity:0.5">CSV 表头：课程名称 / 星期(1=周一) / 开始节数 / 结束节数 / 地点 / 周数</div>
                <div class="text-caption mt-1" style="opacity:0.5">周数例：<code>1-16</code> <code>1-16双</code></div>
              </div>

              <div class="d-flex ga-2">
                <v-btn size="small" variant="tonal" prepend-icon="mdi-download" href="/schedule_template.csv" download>下载模板</v-btn>
                <v-spacer />
                <v-btn color="primary" :loading="busy" :disabled="!file" @click="importSchedule">{{ needsImport ? '导入' : '更新' }}</v-btn>
              </div>
            </v-col>

            <!-- Weekly grid -->
            <v-col cols="12" md="7">
              <div class="d-flex align-center flex-wrap ga-2 mb-3">
                <span class="text-subtitle-2 font-weight-semibold">课表（周视图）</span>
                <v-spacer />
                <template v-if="hasWeekInfo && firstWeekMonday">
                  <v-btn size="x-small" variant="text" icon="mdi-chevron-left" aria-label="上一周" @click="goPrevWeek" />
                  <v-chip size="x-small" variant="tonal" color="primary">{{ weekLabel }}</v-chip>
                  <v-btn size="x-small" variant="text" icon="mdi-chevron-right" aria-label="下一周" @click="goNextWeek" />
                  <v-btn size="x-small" variant="text" @click="resetWeek">今天</v-btn>
                  <v-switch v-model="showAllWeeks" label="全部周" density="compact" hide-details class="ml-1" />
                </template>
                <v-btn size="x-small" variant="text" @click="loadClasses()">刷新</v-btn>
                <v-btn size="x-small" variant="text" color="error" @click="clearClasses">清空</v-btn>
              </div>

              <v-alert v-if="!classes?.length" type="info" variant="tonal" density="compact">暂无课表数据</v-alert>
              <div v-else-if="!periodSlots.length" class="mt-2">
                <v-alert type="info" variant="tonal" density="compact">暂无课表时段数据</v-alert>
              </div>
              <div v-else class="week-grid-wrapper">
                <table class="week-grid-table">
                  <colgroup>
                    <col class="week-grid-col-period">
                    <col v-for="d in 7" :key="d" class="week-grid-col-day">
                  </colgroup>
                  <thead>
                    <tr>
                      <th class="week-grid-th">节次</th>
                      <th v-for="d in weekDays" :key="d.dow" class="week-grid-th">
                        <div>周{{ d.label }}</div>
                        <div v-if="d.date" class="week-grid-date">{{ d.date }}</div>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(slot, si) in periodSlots" :key="si">
                      <td class="week-grid-period">
                        <div>{{ fmtHm(slot.start) }}</div>
                        <div class="week-grid-period-end">{{ fmtHm(slot.end) }}</div>
                      </td>
                      <td v-for="dow in 7" :key="dow" class="week-grid-cell" :class="{ 'week-grid-cell-filled': (weekGridMap[`${dow}_${si}`] ?? []).length }">
                        <div v-for="c in (weekGridMap[`${dow}_${si}`] ?? [])" :key="c.id" class="week-grid-class">
                          <div class="week-grid-course">{{ c.courseName }}</div>
                          <div v-if="c.location" class="week-grid-loc">{{ c.location }}</div>
                          <div v-if="showAllWeeks && (c.weekStart != null || c.weekEnd != null)" class="week-grid-weeks">{{ c.weekStart }}-{{ c.weekEnd }}周{{ c.weekType ? ' ' + c.weekType : '' }}</div>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </v-col>
          </v-row>
        </v-card-text>
      </div>
    </v-expand-transition>
  </v-card>
</template>

<style scoped>
.upload-zone {
  border: 2px dashed rgba(var(--v-theme-on-surface), 0.15);
  border-radius: 10px;
  padding: 20px 16px;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}
.upload-zone:hover {
  border-color: rgba(var(--v-theme-primary), 0.45);
  background: rgba(var(--v-theme-primary), 0.03);
}
.upload-zone--has-file {
  border-style: solid;
  border-color: rgba(var(--v-theme-success), 0.35);
  background: rgba(var(--v-theme-success), 0.03);
}

.format-hints {
  padding: 8px 12px;
  border-radius: 8px;
  background: rgba(var(--v-theme-on-surface), 0.025);
}

.cursor-pointer { cursor: pointer; }

/* Timeline */
.timeline-shell {
  display: grid;
  grid-template-columns: 68px 1fr;
  gap: 10px;
  border-radius: 10px;
  border: 1px solid rgba(0, 0, 0, 0.06);
}
.timeline-axis {
  position: relative;
  background: rgba(0, 0, 0, 0.015);
  padding-top: 6px;
}
.timeline-axis-row {
  position: relative;
  padding-right: 8px;
  text-align: right;
  font-size: 11px;
  opacity: 0.7;
}
.timeline-canvas {
  position: relative;
  padding: 6px 8px;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.01), rgba(0, 0, 0, 0.005));
}
.timeline-gridline {
  position: absolute;
  left: 0;
  right: 0;
  height: 1px;
  background: rgba(0, 0, 0, 0.05);
}
.timeline-free {
  position: absolute;
  left: 2px;
  right: 2px;
  border-radius: 8px;
  background: rgba(var(--v-theme-primary), 0.05);
  outline: 1px dashed rgba(var(--v-theme-primary), 0.2);
}
.timeline-block {
  position: absolute;
  left: 8px;
  right: 8px;
  border-radius: 10px;
  padding: 6px 8px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.06);
  overflow: hidden;
  box-sizing: border-box;
}
.timeline-class {
  background: rgba(0, 0, 0, 0.06);
  border: 1px solid rgba(0, 0, 0, 0.1);
}
.timeline-task {
  background: rgba(var(--v-theme-primary), 0.1);
  border: 1px solid rgba(var(--v-theme-primary), 0.22);
}
.timeline-task-done {
  background: rgba(var(--v-theme-success), 0.12);
  border: 1px solid rgba(var(--v-theme-success), 0.25);
}
.timeline-title {
  font-weight: 600;
  font-size: 12px;
  line-height: 1.2;
}
.timeline-sub {
  margin-top: 3px;
  font-size: 11px;
  opacity: 0.75;
  line-height: 1.2;
}

/* Free time bar */
.free-bar-shell {
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 8px;
  padding: 4px 4px;
}
.free-bar-axis {
  display: flex;
  justify-content: space-between;
  margin-bottom: 2px;
  padding: 0 2px;
}
.free-bar-tick {
  font-size: 10px;
  opacity: 0.45;
  text-align: center;
  flex: 1;
}
.free-bar-track {
  position: relative;
  height: 28px;
  background: rgba(0, 0, 0, 0.025);
  border-radius: 6px;
  overflow: visible;
}
.free-bar-block {
  position: absolute;
  top: 3px;
  bottom: 3px;
  border-radius: 4px;
  background: rgba(var(--v-theme-success), 0.16);
  border-left: 3px solid rgba(var(--v-theme-success), 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 600;
  white-space: nowrap;
  overflow: visible;
  padding: 0 2px;
  box-sizing: border-box;
  min-width: 0;
  z-index: 1;
  color: transparent;
}
.free-bar-block:hover { z-index: 2; }
.free-bar-label {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  bottom: calc(100% + 4px);
  background: rgba(0, 0, 0, 0.78);
  color: #fff;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.15s;
  line-height: 1.4;
}
.free-bar-block:hover .free-bar-label { opacity: 1; }

/* Schedule task cards */
.sched-task-card {
  display: flex;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 10px;
  border: 1px solid rgba(var(--v-theme-primary), 0.1);
  background: rgba(var(--v-theme-primary), 0.025);
  transition: background 0.2s, border-color 0.2s;
}
.sched-task-card:hover {
  background: rgba(var(--v-theme-primary), 0.05);
  border-color: rgba(var(--v-theme-primary), 0.18);
}
.sched-task-done {
  background: rgba(var(--v-theme-success), 0.03);
  border-color: rgba(var(--v-theme-success), 0.1);
}
.sched-task-done:hover {
  background: rgba(var(--v-theme-success), 0.06);
  border-color: rgba(var(--v-theme-success), 0.18);
}
.sched-task-time {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 40px;
  flex-shrink: 0;
  padding-top: 1px;
}
.sched-task-time-val {
  font-size: 11px;
  font-weight: 700;
  opacity: 0.8;
  line-height: 1;
}
.sched-task-done .sched-task-time-val { opacity: 0.45; }
.sched-task-time-sep {
  font-size: 10px;
  opacity: 0.3;
  margin: 1px 0;
  line-height: 1;
}
.sched-task-body {
  flex: 1;
  min-width: 0;
}

/* Date chip */
.date-chip {
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1) !important;
}
.date-chip-sub {
  opacity: 0.55;
  transition: opacity 0.25s;
}
.date-chip.text-primary .date-chip-sub {
  opacity: 0.75;
}

/* Weekly grid */
.week-grid-wrapper {
  overflow-x: auto;
  max-height: 560px;
  overflow-y: auto;
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 8px;
}
.week-grid-table {
  width: 100%;
  table-layout: fixed;
  border-collapse: collapse;
  font-size: 13px;
}
.week-grid-col-period { width: 72px; }
.week-grid-th {
  position: sticky;
  top: 0;
  background: rgba(var(--v-theme-surface), 0.72);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  padding: 10px 6px;
  text-align: center;
  font-weight: 600;
  border-bottom: 2px solid rgba(0, 0, 0, 0.1);
  z-index: 2;
}
.week-grid-date { font-weight: 400; font-size: 11px; opacity: 0.65; }
.week-grid-period {
  text-align: center;
  padding: 8px 6px;
  font-weight: 500;
  font-size: 12px;
  background: rgba(0, 0, 0, 0.015);
  border-right: 1px solid rgba(0, 0, 0, 0.06);
  vertical-align: middle;
}
.week-grid-period-end { font-size: 11px; opacity: 0.55; }
.week-grid-cell {
  padding: 6px;
  border: 1px solid rgba(0, 0, 0, 0.05);
  vertical-align: top;
}
.week-grid-cell-filled { background: rgba(var(--v-theme-primary), 0.03); }
.week-grid-class {
  padding: 6px 8px;
  border-radius: 6px;
  background: rgba(var(--v-theme-primary), 0.08);
  border-left: 3px solid rgba(var(--v-theme-primary), 0.35);
  margin-bottom: 4px;
}
.week-grid-class:last-child { margin-bottom: 0; }
.week-grid-course { font-weight: 600; font-size: 13px; line-height: 1.4; word-break: break-word; }
.week-grid-loc { margin-top: 2px; font-size: 11px; opacity: 0.65; }
.week-grid-weeks { margin-top: 2px; font-size: 10px; opacity: 0.55; }
</style>
