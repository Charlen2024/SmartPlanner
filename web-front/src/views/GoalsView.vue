<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import api from '../plugins/api'
import { useNotifyStore } from '../stores/notify'

const loading = ref(false)
const error = ref('')
const goals = ref([])
const schedules = ref([])
const taskGoalMap = ref(new Map())
const adviceMap = ref(new Map())
const expanded = ref([])
const pendingTasks = ref([])
const polling = ref(false)
const planningBusy = ref(false)
const notify = useNotifyStore()

function dateStr(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function buildRange(days) {
  const today = new Date()
  const from = dateStr(today)
  const toDate = new Date(today.getTime() + (days - 1) * 86400000)
  const to = dateStr(toDate)
  return { from, to }
}

async function loadSchedulesRange(days = 30) {
  const { from, to } = buildRange(days)
  const s = await api.get('/user/schedule/task-schedules', { params: { from: `${from}T00:00:00`, to: `${to}T23:59:59` } })
  const body = s?.data ?? null
  if (body?.code !== 200) {
    throw new Error(body?.message || '加载任务排程失败')
  }
  schedules.value = body?.data ?? []
}

async function rebuildTaskGoalMap() {
  const taskIds = Array.from(new Set((schedules.value ?? []).map((x) => x?.taskId).filter(Boolean)))
  if (!taskIds.length) {
    taskGoalMap.value = new Map()
    return
  }
  const tasksRes = await api.post('/user/tasks/by-ids', taskIds)
  const body = tasksRes?.data ?? null
  if (body?.code !== 200) {
    throw new Error(body?.message || '加载任务详情失败')
  }
  const tasks = body?.data ?? []
  const map = new Map()
  for (const t of tasks) {
    if (t?.id && t?.goalId) map.set(Number(t.id), Number(t.goalId))
  }
  taskGoalMap.value = map
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [g, p] = await Promise.all([
      api.get('/user/goals'),
      api.get('/user/tasks/pending'),
    ])
    const gb = g?.data ?? null
    const pb = p?.data ?? null
    if (gb?.code !== 200) throw new Error(gb?.message || '加载目标失败')
    if (pb?.code !== 200) throw new Error(pb?.message || '加载待办任务失败')
    goals.value = gb?.data ?? []
    pendingTasks.value = pb?.data ?? []

    await loadSchedulesRange(30)
    await rebuildTaskGoalMap()

    if ((schedules.value?.length ?? 0) === 0 && (goals.value?.length ?? 0) > 0) {
      await pollSchedules()
    }
  } catch (e) {
    error.value = e?.response?.data?.message || e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function pollSchedules() {
  if (polling.value) return
  polling.value = true
  try {
    for (let i = 0; i < 120; i++) {
      await new Promise((r) => setTimeout(r, 1000))
      await loadSchedulesRange(30)
      if ((schedules.value?.length ?? 0) > 0) {
        await rebuildTaskGoalMap()
        return
      }
    }
    error.value = '排程仍未写入，请先在“日程/学习计划”完成排程，或点击“生成排程”'
  } finally {
    polling.value = false
  }
}

async function startPlanning() {
  planningBusy.value = true
  try {
    const d = dateStr(new Date())
    const startRes = await api.post('/user/schedule/daily-plan/jobs', { date: d, mode: 'replace' }, { timeout: 15000 })
    const jobId = startRes?.data?.data?.jobId
    if (!jobId) {
      throw new Error('启动排程任务失败')
    }

    notify.info('已启动后台智能排程，可继续其他操作')
    setTimeout(async () => {
      try {
        for (let i = 0; i < 300; i++) {
          await new Promise((r) => setTimeout(r, 2000))
          const st = await api.get(`/user/schedule/daily-plan/jobs/${jobId}`)
          const data = st?.data?.data ?? null
          if (data?.status === 'DONE') {
            await load()
            notify.success('后台智能排程已完成')
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
    notify.error(e?.response?.data?.message || e?.message || '排程失败')
  } finally {
    planningBusy.value = false
  }
}

const grouped = computed(() => {
  const goalList = goals.value ?? []
  const goalById = new Map(goalList.map((g) => [Number(g.id), g]))
  const groups = new Map()

  for (const s of schedules.value ?? []) {
    const taskId = Number(s?.taskId)
    let goalId = taskGoalMap.value.get(taskId)
    let g = null
    if (goalId) {
      g = goalById.get(goalId)
    }
    if (!g) {
      goalId = 0
      g = { id: 0, title: '未归属目标', description: '这些排程任务未绑定到具体目标（可能是降级/临时任务）' }
    }
    const day = String(s?.startTime || '').slice(0, 10)
    if (!day) continue

    if (!groups.has(goalId)) {
      groups.set(goalId, { goal: g, days: new Map(), tasks: [] })
    }
    const grp = groups.get(goalId)
    if (!grp.days.has(day)) grp.days.set(day, [])
    grp.days.get(day).push(s)
    grp.tasks.push(s)
  }

  const out = []
  const orphan = groups.get(0)
  if (orphan) {
    const days = Array.from(orphan.days.entries())
      .sort((a, b) => a[0].localeCompare(b[0]))
      .map(([date, items]) => ({ date, items: items.sort((x, y) => String(x.startTime).localeCompare(String(y.startTime))) }))
    out.push({ goal: orphan.goal, days })
  }
  for (const g of goalList) {
    const gid = Number(g.id)
    const grp = groups.get(gid)
    if (!grp) continue
    const days = Array.from(grp.days.entries())
      .sort((a, b) => a[0].localeCompare(b[0]))
      .map(([date, items]) => ({ date, items: items.sort((x, y) => String(x.startTime).localeCompare(String(y.startTime))) }))
    out.push({ goal: grp.goal, days })
  }
  return out
})

async function ensureAdvice(goalId) {
  const grp = grouped.value.find((x) => Number(x.goal?.id) === Number(goalId))
  if (!grp) return
  const taskIds = Array.from(new Set(grp.days.flatMap((d) => d.items.map((x) => x.taskId)).filter(Boolean)))
  const missing = taskIds.filter((id) => !adviceMap.value.has(Number(id)))
  if (!missing.length) return
  const res = await api.post('/user/tasks/advice', missing)
  const body = res?.data ?? null
  if (body?.code !== 200) {
    throw new Error(body?.message || '加载 AI 建议失败')
  }
  const data = body?.data ?? {}
  const next = new Map(adviceMap.value)
  for (const k of Object.keys(data)) {
    next.set(Number(k), data[k])
  }
  adviceMap.value = next
}

function fmt(dt) {
  if (!dt) return '-'
  return String(dt).replace('T', ' ').slice(0, 16)
}

watch(
  expanded,
  async (v) => {
    const ids = Array.isArray(v) ? v : []
    for (const gid of ids) {
      await ensureAdvice(gid)
    }
  },
  { deep: true },
)

onMounted(load)
</script>

<template>
  <v-row class="mb-2" align="center">
    <v-col cols="12" md="6">
      <v-btn color="primary" :loading="loading" @click="load">刷新</v-btn>
      <v-btn class="ml-3" variant="tonal" :loading="planningBusy" @click="startPlanning">生成排程</v-btn>
    </v-col>
  </v-row>

  <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

  <v-card>
    <v-card-title class="d-flex align-center">
      <v-icon icon="mdi-bullseye-arrow" class="mr-2" />
      目标与排程
    </v-card-title>
    <v-divider />
    <v-card-text v-if="!grouped.length" class="text-body-2" style="opacity:0.75">
      <div v-if="polling">排程写库可能有延迟，正在刷新任务排程…</div>
      <div v-else-if="pendingTasks?.length">暂无已排程任务（可能排程尚未写入）。可点击上方“生成排程”直接触发排程写库。</div>
      <div v-else>暂无已排程任务。可点击上方“生成排程”自动生成任务并排程。</div>
    </v-card-text>
    <v-expansion-panels v-else v-model="expanded" multiple>
      <v-expansion-panel
        v-for="(x, idx) in grouped"
        :key="x.goal.id"
        :value="x.goal.id"
      >
        <v-expansion-panel-title>
          <div class="d-flex align-center" style="width:100%">
            <div class="mr-3" style="min-width: 24px">{{ idx + 1 }}</div>
            <div class="flex-grow-1">
              <div class="font-weight-semibold">{{ x.goal.title }}</div>
              <div class="text-caption" style="opacity:0.75">{{ x.goal.description }}</div>
            </div>
            <v-chip size="small" variant="tonal">{{ x.days.length }} 天</v-chip>
          </div>
        </v-expansion-panel-title>
        <v-expansion-panel-text>
          <div v-for="d in x.days" :key="d.date" class="mb-4">
            <div class="text-subtitle-2 font-weight-semibold mb-2">排程日期：{{ d.date }}</div>
            <v-list density="compact">
              <v-list-item v-for="(s, i) in d.items" :key="s.id" :title="`${i + 1}. ${s.taskTitle || ('任务 ' + s.taskId)}`" :subtitle="`${fmt(s.startTime)} - ${fmt(s.endTime)}`">
                <template #append>
                  <v-chip size="x-small" variant="tonal" :color="Number(s.status) === 1 ? 'success' : undefined">
                    {{ Number(s.status) === 1 ? '已完成' : '未完成' }}
                  </v-chip>
                </template>
                <div v-if="adviceMap.get(Number(s.taskId))" class="text-caption mt-1" style="opacity:0.85">
                  AI 建议：{{ adviceMap.get(Number(s.taskId)) }}
                </div>
              </v-list-item>
            </v-list>
          </div>
        </v-expansion-panel-text>
      </v-expansion-panel>
    </v-expansion-panels>
  </v-card>
</template>
