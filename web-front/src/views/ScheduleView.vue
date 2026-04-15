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
const file = ref(null)
const importResult = ref(null)
const planNote = ref('')
const scheduling = ref(false)

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

async function startPlanningJob(planDate) {
  error.value = ''
  try {
    const d = planDate || date.value
    if (!d) {
      error.value = '请先选择日期'
      return
    }
    scheduling.value = true
    const startRes = await api.post('/user/schedule/daily-plan/jobs', { date: d, mode: 'replace' }, { timeout: 15000 })
    const jobId = startRes?.data?.data?.jobId
    if (!jobId) {
      throw new Error('启动排程任务失败')
    }

    notify.info('已启动后台智能排程，可继续其他操作')
    
    // Polling in background without blocking
    setTimeout(async () => {
      try {
        for (let i = 0; i < 180; i++) {
          await new Promise((r) => setTimeout(r, 2000))
          const st = await api.get(`/user/schedule/daily-plan/jobs/${jobId}`)
          const data = st?.data?.data
          if (data?.status === 'DONE') {
            planNote.value = data?.result?.note ?? ''
            schedules.value = data?.result?.schedules ?? []
            free.value = data?.result?.freeSlots ?? free.value
            notify.success('后台智能排程已完成')
            await loadSchedules()
            break
          }
          if (data?.status === 'FAILED') {
            notify.error(`后台智能排程失败：${data?.error || '未知错误'}`)
            break
          }
        }
      } catch(e) {}
    }, 1000)

  } catch (e) {
    const status = e?.response?.status
    if (e?.code === 'ECONNABORTED') {
      error.value = '智能排程请求超时，请稍后重试'
    } else if (status === 401) {
      error.value = '登录已失效，请重新登录'
    } else if (status === 404) {
      error.value = '接口不存在，请确认后端已更新部署'
    } else {
      error.value = e?.response?.data?.message || e?.message || '智能排程失败'
    }
    notify.error(error.value)
  } finally {
    scheduling.value = false
  }
}

async function autoSchedule() {
  await startPlanningJob(date.value)
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
  } catch (e) {
    const status = e?.response?.status
    if (status === 401) {
      error.value = '登录已失效，请重新登录'
    } else {
      error.value = e?.response?.data?.message || e?.message || '加载任务排程失败'
    }
    schedules.value = []
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
    notify.success('课表已上传，正在刷新并尝试后台排程')
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
      await startPlanningJob(chosen || date.value)
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
</script>

<template>
  
    <v-alert v-if="needsImport" type="info" variant="tonal" class="mb-4">
      这是你首次使用，需要先导入课表，系统才能识别空闲时间并生成学习计划。
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
              <div class="text-caption mb-1" style="opacity:0.75">可用空闲时段（严禁与课表冲突）</div>
              <div v-for="(f, i) in free" :key="i" class="d-flex align-center mb-1">
                <div>{{ fmt(f.start) }} - {{ fmt(f.end) }}</div>
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
            <v-alert v-if="planNote" type="info" variant="tonal" class="mb-3">{{ planNote }}</v-alert>
            <v-alert v-if="!filteredSchedules?.length" type="info" variant="tonal">暂无任务排程</v-alert>
            <div v-for="(s, i) in filteredSchedules" :key="s.id" class="d-flex align-center mb-2">
              <div class="mr-3">{{ i + 1 }}. {{ s.taskTitle || `任务 ${s.taskId}` }}：{{ fmt(s.startTime) }} - {{ fmt(s.endTime) }}</div>
              <v-chip size="small" class="mr-2" variant="tonal" :color="Number(s.status) === 1 ? 'success' : undefined">
                {{ statusText(s.status) }}
              </v-chip>
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
            <v-btn size="small" class="mb-2" @click="loadClasses">刷新</v-btn>
            <v-btn size="small" class="mb-2 ml-2" color="error" @click="clearClasses">清空</v-btn>
            <v-alert v-if="!classes?.length" type="info" variant="tonal" class="mt-2">暂无课表数据</v-alert>
            <v-table v-else density="compact">
              <thead>
                <tr>
                  <th>星期</th>
                  <th>课程</th>
                  <th>时间</th>
                  <th>地点</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(c, i) in classes" :key="i">
                  <td>周{{ c.dayOfWeek }}</td>
                  <td>{{ c.courseName }}</td>
                  <td>{{ c.startTime }} - {{ c.endTime }}</td>
                  <td>{{ c.location || '-' }}</td>
                </tr>
              </tbody>
            </v-table>
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
</style>
