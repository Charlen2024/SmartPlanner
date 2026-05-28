<script setup>
import { ref, onMounted, computed, watch } from 'vue'
import api from '../plugins/api'
import { useAuthStore } from '../stores/auth'
import { useRoute, useRouter } from 'vue-router'
import { useNotifyStore } from '../stores/notify'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const notify = useNotifyStore()

const loading = ref(false)
const error = ref('')
const date = ref('')
const free = ref([])
const schedules = ref([])
const classes = ref([])
const taskResources = ref({})
const file = ref(null)
const importResult = ref(null)

const needsImport = computed(() => auth.me?.scheduleImported === false)
const dateOptions = computed(() => {
  const out = []
  const base = new Date()
  for (let i = 0; i < 14; i++) {
    const d = new Date(base.getTime() + i * 86400000)
    const value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
    out.push({ title: `${value}（周${week}）`, value })
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

  for (const s of filteredSchedules.value ?? []) {
    const d = clampedDurationMinutes(s?.startTime, s?.endTime)
    if (d != null) mins.push(d)
  }

  for (const c of dayClasses.value ?? []) {
    const start = `${date.value}T${String(c?.startTime).slice(0, 5)}:00`
    const end = `${date.value}T${String(c?.endTime).slice(0, 5)}:00`
    const d = clampedDurationMinutes(start, end)
    if (d != null) mins.push(d)
  }

  const minDur = mins.length ? Math.min(...mins) : null
  if (!minDur) return 1.5

  const target = (minBlockHeightPx + blockGapPx) / minDur
  return Math.max(1.2, Math.min(4.0, target))
})

const timelineHeightPx = computed(() => (timelineEndHour - timelineStartHour) * 60 * pxPerMinute.value)

function dateDowValue(dateStr) {
  if (!dateStr) return null
  const d = new Date(`${dateStr}T00:00:00`)
  const js = d.getDay()
  return ((js + 6) % 7) + 1
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
  return {
    top: `${topPx}px`,
    height: `${finalHeight}px`,
  }
}

const dayDow = computed(() => dateDowValue(date.value))
const dayClasses = computed(() => {
  const dow = dayDow.value
  if (!dow) return []
  return (classes.value ?? []).filter((c) => Number(c?.dayOfWeek) === Number(dow))
})

const classDowSet = computed(() => new Set((classes.value ?? []).map((c) => Number(c?.dayOfWeek)).filter((n) => Number.isFinite(n))))
const dateHasClasses = computed(() => {
  const dow = dayDow.value
  if (!dow) return false
  const set = classDowSet.value
  return set.size > 0 && set.has(Number(dow))
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
    return {
      ...f,
      style: { left: `${leftPct}%`, width: `${widthPct}%` },
      startLabel,
      endLabel,
    }
  }).filter(Boolean)
})

const freeBlocks = computed(() =>
  (free.value ?? [])
    .map((f) => ({ ...f, style: blockStyle(f.start, f.end) }))
    .filter((f) => f.style),
)

const classBlocks = computed(() =>
  (dayClasses.value ?? [])
    .map((c) => {
      const start = `${date.value}T${String(c.startTime).slice(0, 5)}:00`
      const end = `${date.value}T${String(c.endTime).slice(0, 5)}:00`
      return { ...c, _start: start, _end: end, style: blockStyle(start, end) }
    })
    .filter((c) => c.style),
)

const scheduleBlocks = computed(() =>
  (filteredSchedules.value ?? [])
    .map((s) => ({ ...s, style: blockStyle(s.startTime, s.endTime) }))
    .filter((s) => s.style),
)

const doneCount = computed(() => (filteredSchedules.value ?? []).filter((s) => Number(s?.status) === 1).length)
const totalCount = computed(() => (filteredSchedules.value ?? []).length)
const donePercent = computed(() => {
  const t = totalCount.value
  if (!t) return 0
  return Math.round((doneCount.value / t) * 100)
})

function fmt(dt) {
  if (!dt) return '-'
  return String(dt).replace('T', ' ').slice(0, 16)
}

function statusText(s) {
  if (s === 1) return '已完成'
  if (s === 2) return '已取消'
  return '未完成'
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

function openUrl(url) {
  const u = String(url || '').trim()
  if (!u) return
  window.open(u, '_blank')
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

function buildTaskGenPromptFromClasses(list) {
  const names = []
  for (const c of list ?? []) {
    const title = String(c?.courseName || c?.course || c?.title || c?.name || '').trim()
    if (!title) continue
    if (!names.includes(title)) names.push(title)
    if (names.length >= 12) break
  }
  if (!names.length) return ''
  const lines = names.map((x, i) => `${i + 1}. ${x}`).join('\n')
  return `我刚导入了课表。课表中的课程如下：\n${lines}\n\n请为我生成可执行的学习任务（task），任务要具体、可打卡、可在 30-60 分钟内完成。先生成任务，不要生成排程。`
}

async function loadSchedules() {
  try {
    const params = {}
    if (date.value) {
      params.from = `${date.value}T00:00:00`
      params.to = `${date.value}T23:59:59`
    }
    const res = await api.get('/user/schedule/task-schedules', { params })
    schedules.value = res?.data?.data ?? []
    await loadTaskResourcesForSchedules(schedules.value)
  } catch (e) {
    const status = e?.response?.status
    if (status === 401) {
      error.value = '登录已失效，请重新登录'
    } else {
      error.value = e?.response?.data?.message || e?.message || '加载任务排程失败'
    }
    schedules.value = []
    taskResources.value = {}
  }
}

async function updateScheduleStatus(id, status) {
  await api.patch(`/user/schedule/task-schedules/${id}/status`, null, { params: { status } })
  await loadSchedules()
}

async function importSchedule() {
  if (!file.value) return
  const fd = new FormData()
  fd.append('file', file.value)
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
          const js = d.getDay()
          const dow = ((js + 6) % 7) + 1
          if (set.has(dow)) {
            chosen = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
            break
          }
        }
      }
      if (chosen) {
        date.value = chosen
      }
      await Promise.all([loadFree(), loadSchedules()])
      const prompt = buildTaskGenPromptFromClasses(classes.value)
      if (prompt) {
        try {
          await api.post('/user/goals/ai', prompt, { headers: { 'Content-Type': 'text/plain' }, timeout: 15000 })
          notify.info('已根据课表提交任务生成，生成完成后会提示；排程请到「目标」页生成')
        } catch (e) {
          notify.error(e?.response?.data?.message || e?.message || '提交任务生成失败')
        }
      }
    }
    const next = route.query.next
    if (next) {
      router.push(String(next))
    }
  } catch (e) {
    error.value = e?.response?.data?.message || '导入失败（支持 .ics / .xlsx / .csv）'
  }
}

async function loadClasses() {
  const res = await api.get('/user/schedule/classes')
  classes.value = res?.data?.data ?? []
}

async function clearClasses() {
  await api.delete('/user/schedule/classes')
  await loadClasses()
}

onMounted(async () => {
  if (!date.value) {
    const t = new Date()
    date.value = `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`
  }
  await Promise.all([loadSchedules(), loadClasses()])
})

watch(
  () => date.value,
  async () => {
    await Promise.all([loadSchedules(), loadFree()])
  },
)

// ── Weekly class schedule grid ──

const firstWeekMonday = computed(() => auth.me?.firstWeekMonday || localStorage.getItem('firstWeekMonday') || '')
const weekOffset = ref(0)
const showAllWeeks = ref(false)

function computeWeekNumber(dateStr, fwmStr) {
  if (!dateStr || !fwmStr) return null
  const d = new Date(`${dateStr}T00:00:00`)
  const fwm = new Date(`${fwmStr}T00:00:00`)
  const diffDays = Math.floor((d - fwm) / (1000 * 60 * 60 * 24))
  return Math.floor(diffDays / 7) + 1
}

function matchesWeekFn(c, weekNumber) {
  if (!c) return true
  const ws = c.weekStart, we = c.weekEnd
  if (ws == null || we == null) return true
  if (weekNumber == null) return true
  if (weekNumber < ws || weekNumber > we) return false
  const wt = c.weekType
  if (!wt) return true
  if (wt === 'even' || wt === '双') return weekNumber % 2 === 0
  if (wt === 'odd' || wt === '单') return weekNumber % 2 === 1
  return true
}

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
    if (!map.has(key)) {
      map.set(key, { start: c.startTime, end: c.endTime })
    }
  }
  return Array.from(map.values()).sort((a, b) =>
    String(a.start || '').localeCompare(String(b.start || '')),
  )
})

function periodPairIndex(c) {
  if (!c) return -1
  const start = String(c.startTime || '')
  const end = String(c.endTime || '')
  return periodSlots.value.findIndex(
    (s) => String(s.start || '') === start && String(s.end || '') === end,
  )
}

const weekGridMap = computed(() => {
  const map = {}
  for (const c of weekGridClasses.value ?? []) {
    const dow = Number(c.dayOfWeek)
    const ppi = periodPairIndex(c)
    if (!Number.isFinite(dow) || dow < 1 || dow > 7 || ppi < 0) continue
    const key = `${dow}_${ppi}`
    if (!map[key]) map[key] = []
    map[key].push(c)
  }
  return map
})

const viewWeekMonday = computed(() => {
  const fwm = firstWeekMonday.value
  const vw = viewWeek.value
  if (!fwm || vw == null) return ''
  const d = new Date(`${fwm}T00:00:00`)
  d.setDate(d.getDate() + (vw - 1) * 7)
  return d.toISOString().slice(0, 10)
})

const weekDays = computed(() => {
  const labels = ['一', '二', '三', '四', '五', '六', '日']
  const monday = viewWeekMonday.value
  if (!monday) {
    return labels.map((label, i) => ({ dow: i + 1, label, date: '' }))
  }
  const d = new Date(`${monday}T00:00:00`)
  return labels.map((label, i) => {
    const date = new Date(d.getTime() + i * 86400000)
    return {
      dow: i + 1,
      label,
      date: `${date.getMonth() + 1}/${date.getDate()}`,
    }
  })
})

const weekLabel = computed(() => {
  const vw = viewWeek.value
  return vw != null ? `第 ${vw} 周` : ''
})

const hasWeekInfo = computed(() => {
  return (classes.value ?? []).some(
    (c) => c.weekStart != null || c.weekEnd != null,
  )
})

function goPrevWeek() {
  weekOffset.value--
}
function goNextWeek() {
  weekOffset.value++
}
function resetWeek() {
  weekOffset.value = 0
}

function fmtHm(dt) {
  if (!dt) return '--:--'
  const s = String(dt)
  if (s.includes('T')) return s.slice(11, 16)
  return s.slice(0, 5)
}
</script>

<template>
  
    <v-alert v-if="needsImport" type="info" variant="tonal" class="mb-4">
      这是你首次使用，需要先导入课表，系统才能识别空闲时间并生成任务。排程请到「目标」页生成。
    </v-alert>

    <v-row class="mb-2" align="center">
      <v-col cols="12" md="5">
        <v-select v-model="date" :items="dateOptions" label="日期" variant="outlined" density="comfortable" />
      </v-col>
      <v-col cols="12" md="7" class="d-flex align-center justify-end">
        <v-progress-circular v-if="totalCount" :model-value="donePercent" size="44" width="6" color="primary" class="mr-3">
          <span style="font-size:12px">{{ donePercent }}%</span>
        </v-progress-circular>
        <div v-if="totalCount" class="text-caption" style="opacity:0.8">今日完成 {{ doneCount }}/{{ totalCount }}</div>
      </v-col>
    </v-row>

    <v-alert v-if="date && classes?.length && !dateHasClasses" type="info" variant="tonal" class="mb-4">
      当前选择 {{ date }}（周{{ dowText(dayDow) }}）当天无课程；已导入课程分布在：{{ importedDowText }}。如需验证课间空闲，请切换到有课的日期（例如下一个周一/周三）。
    </v-alert>

    <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="importResult" type="success" variant="tonal" class="mb-4">
      已导入：{{ importResult.inserted }} / {{ importResult.total }}（跳过 {{ importResult.skipped }}）
      <div v-for="(w, i) in importResult.warnings ?? []" :key="i" class="text-body-2">{{ w }}</div>
    </v-alert>

    <v-row>
      <v-col cols="12" md="6">
        <v-card>
          <v-card-title class="d-flex align-center">
            <v-icon icon="mdi-timer-outline" class="mr-2" />
            空闲时间（来自课表）
          </v-card-title>
          <v-card-text>
            <v-alert v-if="!free?.length" type="info" variant="tonal">当天无空闲时间（或课表未导入/未匹配到当天课程）</v-alert>
            <div v-else>
              <div class="text-caption mb-2" style="opacity:0.75">可用空闲时段（严禁与课表冲突）</div>
              <div class="free-bar-shell">
                <div class="free-bar-axis">
                  <span v-for="h in (timelineEndHour - timelineStartHour + 1)" :key="h" class="free-bar-tick">{{ String(timelineStartHour + h - 1).padStart(2, '0') }}:00</span>
                </div>
                <div class="free-bar-track">
                  <div
                    v-for="(f, i) in freeBarItems"
                    :key="i"
                    class="free-bar-block"
                    :style="f.style"
                  >
                    {{ f.startLabel }} - {{ f.endLabel }}
                  </div>
                </div>
              </div>
            </div>
          </v-card-text>
        </v-card>
      </v-col>
      <v-col cols="12" md="6">
        <v-card>
          <v-card-title class="d-flex align-center">
            <v-icon icon="mdi-timetable" class="mr-2" />
            任务排程
          </v-card-title>
          <v-card-text>
            <v-alert type="info" variant="tonal" class="mb-3">排程统一在「目标」页生成；本页仅用于查看空闲时间与已有排程。</v-alert>
            <v-alert v-if="!filteredSchedules?.length" type="info" variant="tonal">暂无任务排程</v-alert>
            <div v-for="(s, i) in filteredSchedules" :key="s.id" class="mb-2">
              <div class="d-flex align-center">
                <div class="mr-3">{{ i + 1 }}. {{ s.taskTitle || `任务 ${s.taskId}` }}：{{ fmt(s.startTime) }} - {{ fmt(s.endTime) }}</div>
                <v-chip size="small" class="mr-2" variant="tonal" :color="Number(s.status) === 1 ? 'success' : undefined">
                  {{ statusText(s.status) }}
                </v-chip>
              </div>
              <div v-if="resourcesForTask(s.taskId)?.length" class="mt-1">
                <v-chip
                  v-for="r in resourcesForTask(s.taskId)"
                  :key="(r?.sourceUrl || r?.title) + String(s.taskId)"
                  size="x-small"
                  variant="outlined"
                  class="mr-2 mb-1"
                  @click.stop="openUrl(r?.sourceUrl)"
                >
                  {{ (r?.platform ? r.platform + '：' : '') + (r?.title || '课程资源') }}
                </v-chip>
              </div>
            </div>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <v-row class="mt-4">
      <v-col cols="12">
        <v-card>
          <v-card-title class="d-flex align-center">
            <v-icon icon="mdi-calendar-clock" class="mr-2" />
            日程可视化（{{ date }}）
          </v-card-title>
          <v-card-text>
            <div class="timeline-shell">
              <div class="timeline-axis" :style="{ height: timelineHeightPx + 'px' }">
                <div
                  v-for="h in (timelineEndHour - timelineStartHour + 1)"
                  :key="h"
                  class="timeline-axis-row"
                  :style="{ height: (60 * pxPerMinute) + 'px' }"
                >
                  {{ String(timelineStartHour + h - 1).padStart(2, '0') }}:00
                </div>
              </div>
              <div class="timeline-canvas" :style="{ height: timelineHeightPx + 'px' }">
                <div v-for="h in (timelineEndHour - timelineStartHour + 1)" :key="h" class="timeline-gridline" :style="{ top: ((h - 1) * 60 * pxPerMinute) + 'px' }" />

                <div v-for="(b, i) in freeBlocks" :key="'free-' + i" class="timeline-free" :style="b.style" />

                <div
                  v-for="(b, i) in classBlocks"
                  :key="'class-' + (b.id ?? i)"
                  class="timeline-block timeline-class"
                  :style="b.style"
                >
                  <div class="timeline-title">{{ b.courseName }}</div>
                  <div class="timeline-sub">{{ String(b.startTime).slice(0, 5) }} - {{ String(b.endTime).slice(0, 5) }} {{ b.location || '' }}</div>
                </div>

                <div
                  v-for="(b, i) in scheduleBlocks"
                  :key="'task-' + (b.id ?? i)"
                  class="timeline-block timeline-task"
                  :class="{ 'timeline-task-done': Number(b.status) === 1 }"
                  :style="b.style"
                >
                  <div class="timeline-title">{{ b.taskTitle || `任务 ${b.taskId}` }}</div>
                  <div class="timeline-sub">{{ fmt(b.startTime).slice(11, 16) }} - {{ fmt(b.endTime).slice(11, 16) }}</div>
                </div>
              </div>
            </div>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <v-row class="mt-2" align="center">
      <v-col cols="12" md="6">
        <v-card>
          <v-card-title>{{ needsImport ? '导入课表' : '更新课表' }}</v-card-title>
          <v-card-text>
            <v-file-input v-model="file" label="选择文件" prepend-icon="mdi-upload" variant="outlined" />
            <v-alert type="info" variant="tonal" class="mt-2">
              支持 .ics（教务系统日历导出）/.xlsx/.csv（表头：课程/星期/开始时间/结束时间/地点）
              <div class="mt-2">
                <v-btn
                  size="small"
                  variant="tonal"
                  color="primary"
                  href="/schedule_template.csv"
                  download="schedule_template.csv"
                  target="_blank"
                >
                  下载 CSV 模板
                </v-btn>
              </div>
            </v-alert>
            <div class="d-flex justify-end">
              <v-btn color="primary" @click="importSchedule">{{ needsImport ? '导入' : '更新' }}</v-btn>
            </div>
          </v-card-text>
        </v-card>
      </v-col>
      <v-col cols="12" md="6">
        <v-card>
          <v-card-title class="d-flex align-center">
            <v-icon icon="mdi-calendar" class="mr-2" />
            课表（周视图）
          </v-card-title>
          <v-card-text>
            <div class="d-flex align-center flex-wrap mb-2" style="gap:8px">
              <v-btn size="small" @click="loadClasses">刷新</v-btn>
              <v-btn size="small" color="error" @click="clearClasses">清空</v-btn>
              <v-spacer v-if="hasWeekInfo && firstWeekMonday" />
              <template v-if="hasWeekInfo && firstWeekMonday">
                <v-btn size="small" variant="text" icon="mdi-chevron-left" @click="goPrevWeek" />
                <v-chip size="small" variant="tonal" color="primary">{{ weekLabel }}</v-chip>
                <v-btn size="small" variant="text" icon="mdi-chevron-right" @click="goNextWeek" />
                <v-btn size="small" variant="text" @click="resetWeek">今天</v-btn>
                <v-switch v-model="showAllWeeks" label="全部周" density="compact" hide-details class="ml-2" />
              </template>
            </div>

            <v-alert v-if="!classes?.length" type="info" variant="tonal" class="mt-2">暂无课表数据</v-alert>

            <div v-else-if="!periodSlots.length" class="mt-2">
              <v-alert type="info" variant="tonal">暂无课表时段数据</v-alert>
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
                    <td
                      v-for="dow in 7"
                      :key="dow"
                      class="week-grid-cell"
                      :class="{ 'week-grid-cell-filled': (weekGridMap[`${dow}_${si}`] ?? []).length }"
                    >
                      <div
                        v-for="c in (weekGridMap[`${dow}_${si}`] ?? [])"
                        :key="c.id"
                        class="week-grid-class"
                      >
                        <div class="week-grid-course">{{ c.courseName }}</div>
                        <div v-if="c.location" class="week-grid-loc">{{ c.location }}</div>
                        <div
                          v-if="showAllWeeks && (c.weekStart != null || c.weekEnd != null)"
                          class="week-grid-weeks"
                        >
                          {{ c.weekStart }}-{{ c.weekEnd }}周{{ c.weekType ? ' ' + c.weekType : '' }}
                        </div>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>

  
</template>

<style scoped>
.timeline-shell {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 12px;
  max-height: 560px;
  overflow: auto;
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.08);
}

.timeline-axis {
  position: relative;
  background: rgba(0, 0, 0, 0.02);
  padding-top: 6px;
}

.timeline-axis-row {
  position: relative;
  padding-right: 8px;
  text-align: right;
  font-size: 12px;
  opacity: 0.75;
}

.timeline-canvas {
  position: relative;
  padding: 6px 10px 6px 10px;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.015), rgba(0, 0, 0, 0.01));
}

.timeline-gridline {
  position: absolute;
  left: 0;
  right: 0;
  height: 1px;
  background: rgba(0, 0, 0, 0.06);
}

.timeline-free {
  position: absolute;
  left: 0;
  right: 0;
  border-radius: 10px;
  background: rgba(var(--v-theme-primary), 0.06);
  outline: 1px dashed rgba(var(--v-theme-primary), 0.25);
}

.timeline-block {
  position: absolute;
  left: 10px;
  right: 10px;
  border-radius: 12px;
  padding: 8px 10px;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.08);
  overflow: hidden;
  box-sizing: border-box;
}

.timeline-class {
  background: rgba(0, 0, 0, 0.07);
  border: 1px solid rgba(0, 0, 0, 0.12);
}

.timeline-task {
  background: rgba(var(--v-theme-primary), 0.12);
  border: 1px solid rgba(var(--v-theme-primary), 0.25);
}

.timeline-task-done {
  background: rgba(var(--v-theme-success), 0.14);
  border: 1px solid rgba(var(--v-theme-success), 0.28);
}

.timeline-title {
  font-weight: 600;
  font-size: 13px;
  line-height: 1.2;
}

.timeline-sub {
  margin-top: 4px;
  font-size: 12px;
  opacity: 0.8;
  line-height: 1.2;
}

/* ── Weekly grid table ── */

.week-grid-wrapper {
  overflow-x: auto;
  max-height: 520px;
  overflow-y: auto;
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 8px;
}

.week-grid-table {
  width: 100%;
  table-layout: fixed;
  border-collapse: collapse;
  font-size: 13px;
}

.week-grid-col-period {
  width: 72px;
}

.week-grid-col-day {
  /* remaining width distributed equally by table-layout:fixed */;
}

.week-grid-th {
  position: sticky;
  top: 0;
  background: rgba(0, 0, 0, 0.04);
  padding: 8px 4px;
  text-align: center;
  font-weight: 600;
  border-bottom: 2px solid rgba(0, 0, 0, 0.12);
  z-index: 1;
}

.week-grid-date {
  font-weight: 400;
  font-size: 11px;
  opacity: 0.7;
}

.week-grid-period {
  text-align: center;
  padding: 6px 4px;
  font-weight: 500;
  background: rgba(0, 0, 0, 0.02);
  border-right: 1px solid rgba(0, 0, 0, 0.08);
  vertical-align: middle;
}

.week-grid-period-end {
  font-size: 11px;
  opacity: 0.6;
}

.week-grid-cell {
  padding: 4px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  vertical-align: top;
  min-height: 48px;
}

.week-grid-cell-filled {
  background: rgba(var(--v-theme-primary), 0.04);
}

.week-grid-class {
  padding: 4px 6px;
  border-radius: 6px;
  background: rgba(var(--v-theme-primary), 0.1);
  border-left: 3px solid rgba(var(--v-theme-primary), 0.4);
  margin-bottom: 3px;
}

.week-grid-class:last-child {
  margin-bottom: 0;
}

.week-grid-course {
  font-weight: 600;
  font-size: 13px;
  line-height: 1.2;
  word-break: break-word;
}

.week-grid-loc {
  margin-top: 2px;
  font-size: 11px;
  opacity: 0.7;
}

.week-grid-weeks {
  margin-top: 2px;
  font-size: 10px;
  opacity: 0.6;
}

/* ── Free time bar chart ── */

.free-bar-shell {
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 8px;
  padding: 6px 4px;
}

.free-bar-axis {
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
  padding: 0 2px;
}

.free-bar-tick {
  font-size: 10px;
  opacity: 0.5;
  text-align: center;
  flex: 1;
}

.free-bar-track {
  position: relative;
  height: 32px;
  background: rgba(0, 0, 0, 0.03);
  border-radius: 6px;
  overflow: hidden;
}

.free-bar-block {
  position: absolute;
  top: 2px;
  bottom: 2px;
  border-radius: 4px;
  background: rgba(var(--v-theme-success), 0.18);
  border-left: 3px solid rgba(var(--v-theme-success), 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  padding: 0 4px;
  box-sizing: border-box;
  min-width: 52px;
}
</style>
