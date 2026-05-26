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
const taskResources = ref({})
const expanded = ref([])
const pendingTasks = ref([])
const planningBusy = ref(false)
const notify = useNotifyStore()
const planDialogOpen = ref(false)
const regenerateBusy = ref(null)
const planGoalId = ref(null)
const planGoalTasks = ref([])
const planTaskIds = ref([])
const planDate = ref(dateStr(new Date()))
const planDays = ref(1)
const scheduleWaiting = ref(false)
const scheduleWaitingGoalId = ref(null)
const deleteGoalOpen = ref(false)
const deleteGoalBusy = ref(false)
const deletingGoal = ref(null)
const cleanOrphanBusy = ref(false)
const deletingDate = ref(null)

function dateStr(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function buildRange(pastDays, futureDays) {
  const today = new Date()
  const fromDate = new Date(today.getTime() - Math.max(0, Number(pastDays) || 0) * 86400000)
  const from = dateStr(fromDate)
  const toDate = new Date(today.getTime() + Math.max(0, Number(futureDays) || 0) * 86400000)
  const to = dateStr(toDate)
  return { from, to }
}

async function loadSchedulesRange(pastDays = 365, futureDays = 30) {
  const { from, to } = buildRange(pastDays, futureDays)
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

async function loadTaskResourcesForSchedules() {
  const taskIds = Array.from(new Set((schedules.value ?? []).map((x) => Number(x?.taskId)).filter((x) => Number.isFinite(x) && x > 0)))
  if (!taskIds.length) {
    taskResources.value = {}
    return
  }
  const res = await api.post('/user/tasks/resources', { taskIds, topK: 3 })
  const body = res?.data ?? null
  if (body?.code !== 200) {
    throw new Error(body?.message || '加载课程资源失败')
  }
  taskResources.value = body?.data ?? {}
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

    await loadSchedulesRange(365, 30)
    await rebuildTaskGoalMap()
    await loadTaskResourcesForSchedules()
  } catch (e) {
    error.value = e?.response?.data?.message || e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function startPlanning() {
  try {
    if (!goals.value?.length) {
      notify.error('暂无目标，请先创建目标后再生成排程')
      return
    }
    planGoalId.value = Number(goals.value?.[0]?.id) || null
    planDialogOpen.value = true
  } catch (e) {
    notify.error(e?.response?.data?.message || e?.message || '排程失败')
  }
}

async function startPlanningForGoal(goalId) {
  const gid = Number(goalId)
  if (!Number.isFinite(gid) || gid <= 0) {
    await startPlanning()
    return
  }
  planGoalId.value = gid
  planDialogOpen.value = true
}

async function deleteSchedulesForDate(dateStr) {
  if (!dateStr) return
  deletingDate.value = dateStr
  try {
    await api.delete('/user/schedule/task-schedules/by-date', { params: { date: dateStr } })
    notify.success('已删除 ' + dateStr + ' 的排程')
    deletingDate.value = null
    await load()
  } catch (e) {
    deletingDate.value = null
    notify.error(e?.response?.data?.message || e?.message || '删除排程失败')
  }
}
function askDeleteGoal(goal) {
  const gid = Number(goal?.id)
  if (!Number.isFinite(gid) || gid <= 0) return
  deletingGoal.value = goal
  deleteGoalOpen.value = true
}

async function confirmDeleteGoal() {
  const gid = Number(deletingGoal.value?.id)
  if (!Number.isFinite(gid) || gid <= 0) return
  deleteGoalBusy.value = true
  try {
    await api.delete(`/user/goals/${gid}`)
    notify.success('已删除目标')
    deleteGoalOpen.value = false
    deletingGoal.value = null
    expanded.value = (expanded.value || []).filter((x) => Number(x) !== gid)
    await load()
  } catch (e) {
    notify.error(e?.response?.data?.message || e?.message || '删除失败')
  } finally {
    deleteGoalBusy.value = false
  }
}

async function cleanOrphanSchedules(x) {
  if (!x?.days?.length) return
  const ids = Array.from(new Set(
    x.days.flatMap((d) => (d?.items || []).map((s) => Number(s?.taskId)).filter((v) => Number.isFinite(v) && v > 0)),
  ))
  if (!ids.length) {
    notify.info('暂无可清理的排程')
    return
  }
  cleanOrphanBusy.value = true
  try {
    await api.post('/user/schedule/task-schedules/delete-by-task-ids', ids, { headers: { 'Content-Type': 'application/json' } })
    notify.success('已清理未归属排程')
    await load()
  } catch (e) {
    notify.error(e?.response?.data?.message || e?.message || '清理失败')
  } finally {
    cleanOrphanBusy.value = false
  }
}

async function loadGoalTasksForPlan(goalId) {
  const gid = Number(goalId)
  if (!Number.isFinite(gid) || gid <= 0) {
    planGoalTasks.value = []
    planTaskIds.value = []
    return
  }
  const res = await api.get(`/user/goals/${gid}/tasks`)
  const list = res?.data?.data ?? []
  const filtered = (list ?? [])
    .filter((t) => t?.id)
    .filter((t) => Number(t?.status) === 0)
    .filter((t) => !String(t?.title || '').startsWith('[AI降级]'))
  planGoalTasks.value = filtered
  planTaskIds.value = []
}

async function regenerateTasksForGoal(goalId) {
  const gid = Number(goalId)
  if (!Number.isFinite(gid) || gid <= 0) return
  regenerateBusy.value = gid
  try {
    await api.post(`/user/goals/${gid}/tasks/regenerate`, {}, { timeout: 30000 })
    notify.success('已触发AI重新生成任务，稍后刷新查看')
    setTimeout(() => loadGoalTasksForPlan(gid), 2000)
  } catch (e) {
    notify.error(e?.response?.data?.message || e?.message || '触发失败')
  } finally {
    regenerateBusy.value = null
  }
}

async function confirmPlanning() {
  planningBusy.value = true
  try {
    const goalId = Number(planGoalId.value)
    if (!Number.isFinite(goalId) || goalId <= 0) throw new Error('请选择要生成排程的目标')
    const d = planDate.value || dateStr(new Date())
    const taskIds = Array.isArray(planTaskIds.value) ? planTaskIds.value.map((x) => Number(x)).filter((x) => Number.isFinite(x) && x > 0) : []
    const payload = {
      date: d,
      mode: 'merge',
      goalId,
      taskIds: taskIds.length ? taskIds : null,
      days: planDays.value || 1,
    }
    const startRes = await api.post('/user/schedule/daily-plan/jobs', payload, { timeout: 15000 })
    const jobId = startRes?.data?.data?.jobId
    if (!jobId) throw new Error('启动排程任务失败')

    planDialogOpen.value = false
    scheduleWaiting.value = true
    scheduleWaitingGoalId.value = goalId
    notify.info('已启动后台智能排程，你可以先去看别的页面；完成后会自动刷新')
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
    const days = grp
      ? Array.from(grp.days.entries())
          .sort((a, b) => a[0].localeCompare(b[0]))
          .map(([date, items]) => ({ date, items: items.sort((x, y) => String(x.startTime).localeCompare(String(y.startTime))) }))
      : []
    out.push({ goal: goalById.get(gid) || g, days })
  }
  return out
})

const scheduledGoalsHint = computed(() => {
  const goalList = goals.value ?? []
  const goalById = new Map(goalList.map((g) => [Number(g.id), g]))

  const counts = new Map()
  for (const s of schedules.value ?? []) {
    const taskId = Number(s?.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) continue
    const gid = taskGoalMap.value.get(taskId)
    if (!gid) continue
    counts.set(Number(gid), (counts.get(Number(gid)) || 0) + 1)
  }

  const list = Array.from(counts.entries())
    .map(([goalId, count]) => {
      const g = goalById.get(Number(goalId))
      return { goalId: Number(goalId), title: g?.title || `目标 ${goalId}`, count }
    })
    .sort((a, b) => b.count - a.count)

  return list.slice(0, 4)
})

function openGoalPanel(goalId) {
  const gid = Number(goalId)
  if (!Number.isFinite(gid) || gid <= 0) return
  const curr = Array.isArray(expanded.value) ? expanded.value : []
  if (curr.some((x) => Number(x) === gid)) return
  expanded.value = [...curr, gid]
}

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

watch(
  planGoalId,
  async (v) => {
    try {
      await loadGoalTasksForPlan(v)
    } catch (e) {
      planGoalTasks.value = []
      planTaskIds.value = []
    }
  },
  { immediate: true },
)

watch(
  () => notify.signalSeq?.SCHEDULE_DONE,
  async () => {
    if (!scheduleWaiting.value) return
    scheduleWaiting.value = false
    await load()
    const gid = Number(scheduleWaitingGoalId.value)
    if (Number.isFinite(gid) && gid > 0) {
      const curr = Array.isArray(expanded.value) ? expanded.value : []
      if (!curr.some((x) => Number(x) === gid)) {
        expanded.value = [...curr, gid]
      }
    }
  },
)

watch(
  () => notify.signalSeq?.SCHEDULE_FAILED,
  () => {
    if (!scheduleWaiting.value) return
    scheduleWaiting.value = false
  },
)

watch(
  () => notify.signalSeq?.RESOURCE_ADVICE_DONE,
  async () => {
    await loadTaskResourcesForSchedules()
    notify.success('课程资源推荐已更新！', 6000)
  },
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

  <v-alert v-if="scheduleWaiting" type="info" variant="tonal" class="mb-4">
    已启动后台排程。你可以先去看别的页面；排程完成后会自动刷新本页。
  </v-alert>

  <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

  <v-dialog v-model="planDialogOpen" max-width="720">
    <v-card>
      <v-card-title class="d-flex align-center">
        <v-icon icon="mdi-calendar-clock" class="mr-2" />
        生成排程
      </v-card-title>
      <v-divider />
      <v-card-text>
        <v-select
          v-model="planGoalId"
          :items="goals"
          item-title="title"
          item-value="id"
          label="请选择目标"
          variant="outlined"
          density="comfortable"
        />
        <v-select v-if="planGoalId" v-model="planTaskIds" :items="planGoalTasks" item-title="title" item-value="id" label="请选择任务（可多选；不选则为该目标全部未完成任务）" variant="outlined" density="comfortable" multiple :no-data-text="planGoalTasks.length ? `暂无未完成任务` : `加载中...`" class="mt-3" />
        <div v-if="planGoalId" class="d-flex justify-end mt-1"><v-btn variant="text" size="small" color="primary" :loading="regenerateBusy === planGoalId" @click="regenerateTasksForGoal(planGoalId)"><v-icon icon="mdi-refresh" size="small" class="mr-1" />重新生成该目标的学习任务</v-btn></div>
        <v-text-field v-model="planDate" type="date" label="排程日期" variant="outlined" density="comfortable" class="mt-1" />
        <v-select v-model="planDays" :items="[{title:'仅当天 (1天)',value:1},{title:'未来3天',value:3},{title:'未来1周 (7天)',value:7}]" item-title="title" item-value="value" label="排程天数" variant="outlined" density="comfortable" class="mt-1" />
      </v-card-text>
      <v-divider />
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="planDialogOpen = false">取消</v-btn>
        <v-btn color="primary" :loading="planningBusy" @click="confirmPlanning">开始生成</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>

  <v-dialog v-model="deleteGoalOpen" max-width="560">
    <v-card>
      <v-card-title class="d-flex align-center">
        <div class="text-subtitle-1 font-weight-semibold">删除目标</div>
        <v-spacer />
        <v-btn size="small" variant="text" @click="deleteGoalOpen = false">关闭</v-btn>
      </v-card-title>
      <v-divider />
      <v-card-text>
        确认删除目标「{{ deletingGoal?.title || '-' }}」吗？删除后不可恢复。
      </v-card-text>
      <v-divider />
      <v-card-actions class="d-flex justify-end">
        <v-btn variant="text" @click="deleteGoalOpen = false">取消</v-btn>
        <v-btn color="error" variant="tonal" :loading="deleteGoalBusy" @click="confirmDeleteGoal">删除</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>

  <v-card>
    <v-card-title class="d-flex align-center">
      <v-icon icon="mdi-bullseye-arrow" class="mr-2" />
      目标与排程
    </v-card-title>
    <v-divider />
    <v-card-text v-if="!grouped.length" class="text-body-2" style="opacity:0.75">
      <div v-if="pendingTasks?.length">暂无已排程任务（可能排程尚未写入）。可点击上方“生成排程”直接触发排程写库。</div>
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
            <v-btn
              v-if="Number(x.goal?.id) !== 0"
              class="ml-1"
              icon="mdi-refresh"
              size="small"
              variant="text"
              color="primary"
              :loading="regenerateBusy === Number(x.goal?.id)"
              :title="'为 ' + x.goal.title + ' 重新生成学习任务'"
              @click.stop="regenerateTasksForGoal(x.goal.id)"
            />
            <v-btn
              v-if="Number(x.goal?.id) !== 0"
              class="ml-1"
              icon="mdi-delete-outline"
              size="small"
              variant="text"
              color="error"
              @click.stop="askDeleteGoal(x.goal)"
            />
          </div>
        </v-expansion-panel-title>
        <v-expansion-panel-text>
          <v-alert v-if="Number(x.goal?.id) === 0" type="warning" variant="tonal" class="mb-3">
            这些排程任务没有关联到任何目标（常见于删除目标/降级临时任务后）。可一键清理避免干扰。
            <div class="mt-2">
              <v-btn size="small" color="warning" variant="tonal" :loading="cleanOrphanBusy" @click="cleanOrphanSchedules(x)">清理这些排程</v-btn>
            </div>
          </v-alert>
          <v-alert v-if="!x.days.length && Number(x.goal?.id) !== 0" type="info" variant="tonal" class="mb-3">
            该目标暂无排程任务。你可以为它生成排程，或先补充/调整任务后再排程。
            <div class="mt-2">
              <v-btn size="small" variant="tonal" color="primary" :loading="planningBusy" @click="startPlanningForGoal(x.goal.id)">为该目标生成排程</v-btn>
            </div>
            <div v-if="scheduledGoalsHint?.length" class="mt-2">
              <div class="text-caption" style="opacity:0.75">当前已有排程的目标：</div>
              <div class="d-flex flex-wrap ga-2 mt-1">
                <v-chip
                  v-for="g in scheduledGoalsHint"
                  :key="g.goalId"
                  size="small"
                  variant="outlined"
                  @click="openGoalPanel(g.goalId)"
                >{{ g.title }}（{{ g.count }}）</v-chip>
              </div>
            </div>
          </v-alert>
          <div v-for="d in x.days" :key="d.date" class="mb-4">
            <div class="d-flex align-center mb-2">
              <div class="text-subtitle-2 font-weight-semibold">排程日期：{{ d.date }}</div>
              <v-btn icon="mdi-delete-outline" size="x-small" variant="text" color="error" :loading="deletingDate === d.date" @click.stop="deleteSchedulesForDate(d.date)" class="ml-2" title="删除该日全部排程" />
            </div>
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
              </v-list-item>
            </v-list>
          </div>
        </v-expansion-panel-text>
      </v-expansion-panel>
    </v-expansion-panels>
  </v-card>
</template>
