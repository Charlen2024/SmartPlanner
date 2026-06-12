<script setup>
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import api from '../plugins/api'
import { useNotifyStore } from '../stores/notify'
import { useDecomposeStore } from '../stores/decompose'

const loading = ref(false)
const initialLoad = ref(true)
const error = ref('')
const goals = ref([])
const schedules = ref([])
const taskGoalMap = ref(new Map())
const expanded = ref([])
const pendingTasks = ref([])
const planningBusy = ref(false)
const notify = useNotifyStore()
const decompose = useDecomposeStore()
const planDialogOpen = ref(false)
const regenerateBusy = ref(null)
const planGoalId = ref(null)
const planGoalTasks = ref([])
const planTaskIds = ref([])
const planDate = ref(new Date())
const scheduleWaiting = ref(false)
const scheduleWaitingGoalId = ref(null)
let scheduleWaitingTimer = null
const deleteGoalOpen = ref(false)
const deleteGoalBusy = ref(false)
const deleteGoalUnfinishedCount = ref(0)
const deletingGoal = ref(null)
const deletingGoalId = ref(null)
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
  if (body?.code !== 200) throw new Error(body?.message || '加载任务排程失败')
  schedules.value = body?.data ?? []
}

async function rebuildTaskGoalMap() {
  const taskIds = Array.from(new Set((schedules.value ?? []).map((x) => x?.taskId).filter(Boolean)))
  if (!taskIds.length) { taskGoalMap.value = new Map(); return }
  const tasksRes = await api.post('/user/tasks/by-ids', taskIds)
  const body = tasksRes?.data ?? null
  if (body?.code !== 200) throw new Error(body?.message || '加载任务详情失败')
  const tasks = body?.data ?? []
  const map = new Map()
  for (const t of tasks) { if (t?.id && t?.goalId) map.set(Number(t.id), Number(t.goalId)) }
  taskGoalMap.value = map
}

async function load(opts = {}) {
  const { showLoading = true } = opts
  if (showLoading) loading.value = true
  error.value = ''
  try {
    const [g, p] = await Promise.all([api.get('/user/goals'), api.get('/user/tasks/pending')])
    const gb = g?.data ?? null; const pb = p?.data ?? null
    if (gb?.code !== 200) throw new Error(gb?.message || '加载目标失败')
    if (pb?.code !== 200) throw new Error(pb?.message || '加载待办任务失败')
    goals.value = gb?.data ?? []
    pendingTasks.value = pb?.data ?? []
    await loadSchedulesRange(365, 30)
    await rebuildTaskGoalMap()
  } catch (e) {
    error.value = e?.response?.data?.message || e?.message || '加载失败'
  } finally {
    if (showLoading) loading.value = false
  }
}

async function startPlanning() {
  if (!goals.value?.length) { notify.error('暂无目标，请先创建目标后再生成排程'); return }
  planGoalId.value = Number(goals.value?.[0]?.id) || null
  planDialogOpen.value = true
}

async function startPlanningForGoal(goalId) {
  const gid = Number(goalId)
  if (!Number.isFinite(gid) || gid <= 0) { await startPlanning(); return }
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
  } catch (e) { deletingDate.value = null; notify.error(e?.response?.data?.message || e?.message || '删除排程失败') }
}

async function askDeleteGoal(goal) {
  const gid = Number(goal?.id)
  if (!Number.isFinite(gid) || gid <= 0) return
  try {
    const res = await api.get(`/user/goals/${gid}/unfinished-count`)
    const count = Number(res?.data?.data?.unfinished) || 0
    if (count > 0) {
      deleteGoalUnfinishedCount.value = count
      deletingGoal.value = goal
      deleteGoalOpen.value = true
    } else {
      deletingGoalId.value = gid
      await api.delete(`/user/goals/${gid}`)
      notify.success('目标已完成')
      expanded.value = (expanded.value || []).filter((x) => Number(x) !== gid)
      await load()
      deletingGoalId.value = null
    }
  } catch (e) {
    deletingGoalId.value = null
    notify.error(e?.response?.data?.message || e?.message || '操作失败')
  }
}

async function confirmDeleteGoal() {
  const gid = Number(deletingGoal.value?.id)
  if (!Number.isFinite(gid) || gid <= 0) return
  deleteGoalBusy.value = true
  try {
    await api.delete(`/user/goals/${gid}`)
    notify.success('目标已完成')
    deleteGoalOpen.value = false; deletingGoal.value = null; deleteGoalUnfinishedCount.value = 0
    expanded.value = (expanded.value || []).filter((x) => Number(x) !== gid)
    await load()
  } catch (e) { notify.error(e?.response?.data?.message || e?.message || '删除失败') }
  finally { deleteGoalBusy.value = false }
}

async function cleanOrphanSchedules(x) {
  if (!x?.days?.length) return
  const ids = Array.from(new Set(x.days.flatMap((d) => (d?.items || []).map((s) => Number(s?.taskId)).filter((v) => Number.isFinite(v) && v > 0))))
  if (!ids.length) { notify.info('暂无可清理的排程'); return }
  cleanOrphanBusy.value = true
  try {
    await api.post('/user/schedule/task-schedules/delete-by-task-ids', ids, { headers: { 'Content-Type': 'application/json' } })
    notify.success('已清理未归属排程')
    await load()
  } catch (e) { notify.error(e?.response?.data?.message || e?.message || '清理失败') }
  finally { cleanOrphanBusy.value = false }
}

async function loadGoalTasksForPlan(goalId) {
  const gid = Number(goalId)
  if (!Number.isFinite(gid) || gid <= 0) { planGoalTasks.value = []; planTaskIds.value = []; return }
  const res = await api.get(`/user/goals/${gid}/tasks`)
  const list = res?.data?.data ?? []
  planGoalTasks.value = (list ?? []).filter((t) => t?.id).filter((t) => Number(t?.status) === 0).filter((t) => !String(t?.title || '').startsWith('[AI降级]'))
  planTaskIds.value = []
}

const regenerateWaiting = ref(false)
const regenerateWaitingGoalId = ref(null)

async function regenerateTasksForGoal(goalId) {
  const gid = Number(goalId)
  if (!Number.isFinite(gid) || gid <= 0) return
  regenerateBusy.value = gid
  try {
    const goal = goals.value.find(g => Number(g?.id) === gid)
    decompose.start(goal?.title || '目标')
    await api.post(`/user/goals/${gid}/tasks/regenerate`, {}, { timeout: 30000 })
    regenerateWaiting.value = true
    regenerateWaitingGoalId.value = gid
    notify.success('已触发AI进阶学习任务生成，完成后自动刷新')
  } catch (e) { decompose.dismiss(); notify.error(e?.response?.data?.message || e?.message || '触发失败') }
  finally { regenerateBusy.value = null }
}

let schedulePollingTimer = null

function stopSchedulePolling() {
  if (schedulePollingTimer) { clearTimeout(schedulePollingTimer); schedulePollingTimer = null }
}

async function pollScheduleJob(jobId, goalId) {
  stopSchedulePolling()
  try {
    const res = await api.get(`/user/schedule/daily-plan/jobs/${jobId}`, { timeout: 10000 })
    const st = res?.data?.data
    if (st?.status === 'DONE') {
      scheduleWaiting.value = false
      scheduleWaitingGoalId.value = null
      if (scheduleWaitingTimer) { clearTimeout(scheduleWaitingTimer); scheduleWaitingTimer = null }
      await load()
      const gid = Number(goalId)
      if (Number.isFinite(gid) && gid > 0) {
        const curr = Array.isArray(expanded.value) ? expanded.value : []
        if (!curr.some((x) => Number(x) === gid)) expanded.value = [...curr, gid]
      }
      notify.success('排程完成，页面已刷新')
      return
    }
    if (st?.status === 'FAILED') {
      scheduleWaiting.value = false
      scheduleWaitingGoalId.value = null
      if (scheduleWaitingTimer) { clearTimeout(scheduleWaitingTimer); scheduleWaitingTimer = null }
      notify.error(st?.error || st?.message || '排程失败')
      return
    }
  } catch (e) { /* ignore polling errors, keep trying */ }
  schedulePollingTimer = setTimeout(() => pollScheduleJob(jobId, goalId), 3000)
}

async function confirmPlanning() {
  planningBusy.value = true
  try {
    const goalId = Number(planGoalId.value)
    if (!Number.isFinite(goalId) || goalId <= 0) throw new Error('请选择要生成排程的目标')
    const d = planDate.value ? dateStr(planDate.value) : dateStr(new Date())
    const taskIds = Array.isArray(planTaskIds.value) ? planTaskIds.value.map((x) => Number(x)).filter((x) => Number.isFinite(x) && x > 0) : []
    const payload = { date: d, mode: 'merge', goalId, taskIds: taskIds.length ? taskIds : null, days: 1 }
    const startRes = await api.post('/user/schedule/daily-plan/jobs', payload, { timeout: 15000 })
    const jobId = startRes?.data?.data?.jobId
    if (!jobId) throw new Error('启动排程任务失败')
    planDialogOpen.value = false
    scheduleWaiting.value = true; scheduleWaitingGoalId.value = goalId
    if (scheduleWaitingTimer) clearTimeout(scheduleWaitingTimer)
    scheduleWaitingTimer = setTimeout(() => { if (scheduleWaiting.value) { scheduleWaiting.value = false; notify.info('排程任务仍在后台运行，请稍后手动刷新查看结果', 6000) } }, 5 * 60 * 1000)
    pollScheduleJob(jobId, goalId)
    notify.info('已启动后台智能排程，完成后自动刷新')
  } catch (e) { notify.error(e?.response?.data?.message || e?.message || '排程失败') }
  finally { planningBusy.value = false }
}

const grouped = computed(() => {
  const goalList = goals.value ?? []
  const goalById = new Map(goalList.map((g) => [Number(g.id), g]))
  const groups = new Map()
  for (const s of schedules.value ?? []) {
    const taskId = Number(s?.taskId)
    let goalId = taskGoalMap.value.get(taskId)
    let g = null
    if (goalId) { g = goalById.get(goalId) }
    if (!g) { goalId = 0; g = { id: 0, title: '未归属任务', description: '这些排程未绑定到具体目标' } }
    const day = String(s?.startTime || '').slice(0, 10)
    if (!day) continue
    if (!groups.has(goalId)) groups.set(goalId, { goal: g, days: new Map(), tasks: [] })
    const grp = groups.get(goalId)
    if (!grp.days.has(day)) grp.days.set(day, [])
    grp.days.get(day).push(s)
    grp.tasks.push(s)
  }
  const out = []
  const orphan = groups.get(0)
  if (orphan) {
    const days = Array.from(orphan.days.entries()).sort((a, b) => a[0].localeCompare(b[0])).map(([date, items]) => ({ date, items: items.sort((x, y) => String(x.startTime).localeCompare(String(y.startTime))) }))
    out.push({ goal: orphan.goal, days })
  }
  for (const g of goalList) {
    const gid = Number(g.id); const grp = groups.get(gid)
    const days = grp ? Array.from(grp.days.entries()).sort((a, b) => a[0].localeCompare(b[0])).map(([date, items]) => ({ date, items: items.sort((x, y) => String(x.startTime).localeCompare(String(y.startTime))) })) : []
    out.push({ goal: goalById.get(gid) || g, days })
  }
  return out
})

const scheduledGoalsHint = computed(() => {
  const goalList = goals.value ?? []; const goalById = new Map(goalList.map((g) => [Number(g.id), g]))
  const counts = new Map()
  for (const s of schedules.value ?? []) {
    const taskId = Number(s?.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) continue
    const gid = taskGoalMap.value.get(taskId)
    if (!gid) continue
    counts.set(Number(gid), (counts.get(Number(gid)) || 0) + 1)
  }
  return Array.from(counts.entries()).map(([goalId, count]) => { const g = goalById.get(Number(goalId)); return { goalId: Number(goalId), title: g?.title || `目标 ${goalId}`, count } }).sort((a, b) => b.count - a.count).slice(0, 4)
})

function openGoalPanel(goalId) {
  const gid = Number(goalId)
  if (!Number.isFinite(gid) || gid <= 0) return
  const curr = Array.isArray(expanded.value) ? expanded.value : []
  if (curr.some((x) => Number(x) === gid)) return
  expanded.value = [...curr, gid]
}

function fmt(dt) {
  if (!dt) return '-'
  return String(dt).replace('T', ' ').slice(0, 16)
}

function panelColor(idx) {
  const colors = ['primary', 'secondary', 'success', 'warning', 'error', '#7c4dff', '#00bcd4', '#ff6e40']
  return colors[idx % colors.length]
}

function doneCount(items) {
  return (items ?? []).filter((s) => Number(s?.status) === 1).length
}

watch(planGoalId, async (v) => {
  try { await loadGoalTasksForPlan(v) } catch (e) { planGoalTasks.value = []; planTaskIds.value = [] }
}, { immediate: true })

watch(() => notify.signalSeq?.SCHEDULE_DONE, async () => {
  if (!scheduleWaiting.value) return
  if (scheduleWaitingTimer) { clearTimeout(scheduleWaitingTimer); scheduleWaitingTimer = null }
  scheduleWaiting.value = false
  await load()
  const gid = Number(scheduleWaitingGoalId.value)
  if (Number.isFinite(gid) && gid > 0) {
    const curr = Array.isArray(expanded.value) ? expanded.value : []
    if (!curr.some((x) => Number(x) === gid)) expanded.value = [...curr, gid]
  }
})

watch(() => notify.signalSeq?.SCHEDULE_FAILED, () => {
  if (!scheduleWaiting.value) return
  if (scheduleWaitingTimer) { clearTimeout(scheduleWaitingTimer); scheduleWaitingTimer = null }
  scheduleWaiting.value = false
})

watch(() => notify.signalSeq?.GOAL_TASK_READY, async () => {
  if (!regenerateWaiting.value) return
  regenerateWaiting.value = false
  const gid = Number(regenerateWaitingGoalId.value)
  regenerateWaitingGoalId.value = null
  await load({ showLoading: false })
  if (Number.isFinite(gid) && gid > 0) {
    await loadGoalTasksForPlan(gid)
    const curr = Array.isArray(expanded.value) ? expanded.value : []
    if (!curr.some((x) => Number(x) === gid)) expanded.value = [...curr, gid]
  }
  notify.success('进阶任务生成完成')
})

watch(() => notify.signalSeq?.GOAL_DECOMPOSE_FAILED, () => {
  if (!regenerateWaiting.value) return
  regenerateWaiting.value = false
  regenerateWaitingGoalId.value = null
})

onBeforeUnmount(() => { if (scheduleWaitingTimer) clearTimeout(scheduleWaitingTimer); stopSchedulePolling() })
onDeactivated(() => { if (scheduleWaitingTimer) clearTimeout(scheduleWaitingTimer); stopSchedulePolling() })

let _ready2 = false
onMounted(async () => { await load({ showLoading: false }); initialLoad.value = false })
onActivated(async () => { if (_ready2) await load({ showLoading: false }); _ready2 = true })
</script>

<template>
  <!-- ====== Page Header ====== -->
  <div class="d-flex align-center flex-wrap ga-3 mb-4">
    <div>
      <div class="text-h5 font-weight-bold">目标与排程</div>
      <div class="text-body-2 text-medium-emphasis">管理学习目标，查看与生成任务排程</div>
    </div>
    <v-spacer />
    <v-btn variant="tonal" size="small" :loading="loading" @click="load()">
      <v-icon icon="mdi-refresh" size="18" class="mr-1" />刷新
    </v-btn>
    <v-btn color="primary" size="small" variant="elevated" :loading="planningBusy" @click="startPlanning">
      <v-icon icon="mdi-calendar-clock" size="18" class="mr-1" />生成排程
    </v-btn>
  </div>

  <v-alert v-if="scheduleWaiting" type="info" variant="tonal" class="mb-4" density="compact">
    排程任务已在后台启动，完成后会自动刷新本页。
  </v-alert>

  <v-alert v-if="error" type="error" variant="tonal" class="mb-4" density="compact">{{ error }}</v-alert>

  <!-- ====== Planning Dialog ====== -->
  <v-dialog v-model="planDialogOpen" max-width="720">
    <v-card>
      <v-card-title class="d-flex align-center">
        <v-icon icon="mdi-calendar-clock" class="mr-2" />生成排程
      </v-card-title>
      <v-divider />
      <v-card-text>
        <v-select v-model="planGoalId" :items="goals" item-title="title" item-value="id" label="请选择目标" variant="outlined" density="comfortable" />
        <v-select v-if="planGoalId" v-model="planTaskIds" :items="planGoalTasks" item-title="title" item-value="id" label="选择任务（不选则全部未完成任务）" variant="outlined" density="comfortable" multiple class="mt-3" />
        <div v-if="planGoalId" class="d-flex justify-end mt-1">
          <v-btn variant="text" size="small" color="primary" :loading="regenerateBusy === planGoalId" @click="regenerateTasksForGoal(planGoalId)">
            <v-icon icon="mdi-refresh" size="small" class="mr-1" />进阶生成任务
          </v-btn>
        </div>
        <v-date-input v-model="planDate" label="排程日期" variant="outlined" density="comfortable" class="mt-1" />
      </v-card-text>
      <v-divider />
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="planDialogOpen = false">取消</v-btn>
        <v-btn color="primary" :loading="planningBusy" @click="confirmPlanning">开始生成</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>

  <!-- ====== Delete Dialog ====== -->
  <v-dialog v-model="deleteGoalOpen" max-width="480">
    <v-card>
      <v-card-title class="text-subtitle-1 font-weight-semibold">提前完成目标</v-card-title>
      <v-divider />
      <v-card-text class="pt-4">目标「{{ deletingGoal?.title || '-' }}」还有 <strong>{{ deleteGoalUnfinishedCount }}</strong> 个任务未完成，确定提前完成吗？完成后目标及其任务将被移除。</v-card-text>
      <v-divider />
      <v-card-actions class="d-flex justify-end ga-2">
        <v-btn variant="text" @click="deleteGoalOpen = false">取消</v-btn>
        <v-btn color="success" variant="tonal" :loading="deleteGoalBusy" @click="confirmDeleteGoal">确认完成</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>

  <!-- ====== Content ====== -->
  <v-card>
    <v-progress-linear v-if="initialLoad" indeterminate color="primary" />

    <!-- Empty state -->
    <div v-if="!initialLoad && !grouped.length" class="text-center py-10">
      <v-icon icon="mdi-bullseye-arrow" size="64" class="mb-3 text-medium-emphasis" style="opacity:0.3" />
      <div class="text-h6 font-weight-medium text-medium-emphasis mb-1">暂无目标与排程</div>
      <div class="text-body-2 text-medium-emphasis mb-4" style="opacity:0.6">创建学习目标后，AI 将为你拆解任务并自动排程</div>
      <v-btn color="primary" variant="tonal" :loading="planningBusy" @click="startPlanning">开始生成排程</v-btn>
    </div>

    <!-- Goals accordion -->
    <v-expansion-panels v-else v-model="expanded" multiple>
      <v-expansion-panel
        v-for="(x, idx) in grouped"
        :key="x.goal.id"
        :value="x.goal.id"
        class="goal-panel"
      >
        <v-expansion-panel-title class="py-2">
          <div class="d-flex align-center ga-3" style="width:100%">
            <div class="goal-dot" :style="{ background: panelColor(idx) }" />
            <div class="flex-grow-1" style="min-width:0">
              <div class="d-flex align-center ga-2">
                <span class="font-weight-semibold text-body-1 goal-panel-title">{{ x.goal.title }}</span>
                <v-chip v-if="Number(x.goal?.id) === 0" size="x-small" variant="tonal" color="warning">未归属</v-chip>
              </div>
              <div class="text-caption text-medium-emphasis text-truncate">{{ x.goal.description }}</div>
            </div>
            <v-chip size="x-small" variant="tonal">{{ x.days.length }} 天</v-chip>
            <template v-if="Number(x.goal?.id) !== 0">
              <v-btn size="small" variant="tonal" color="primary" :loading="regenerateBusy === Number(x.goal?.id)" @click.stop="regenerateTasksForGoal(x.goal.id)">
                <v-icon icon="mdi-refresh" size="16" class="mr-1" />进阶生成
              </v-btn>
              <v-btn size="small" variant="tonal" color="error" :loading="deletingGoalId === Number(x.goal?.id)" @click.stop="askDeleteGoal(x.goal)">
                <v-icon icon="mdi-check-circle-outline" size="16" class="mr-1" />完成
              </v-btn>
            </template>
          </div>
        </v-expansion-panel-title>

        <v-expansion-panel-text>
          <!-- Orphan warning -->
          <v-alert v-if="Number(x.goal?.id) === 0" type="warning" variant="tonal" density="compact" class="mb-3">
            这些排程未关联任何目标，可一键清理。
            <v-btn size="x-small" color="warning" variant="tonal" class="ml-2" :loading="cleanOrphanBusy" @click="cleanOrphanSchedules(x)">清理</v-btn>
          </v-alert>

          <!-- No schedules for this goal -->
          <v-alert v-if="!x.days.length && Number(x.goal?.id) !== 0" type="info" variant="tonal" density="compact" class="mb-3">
            暂无排程。
            <v-btn size="x-small" variant="tonal" color="primary" class="ml-2" :loading="planningBusy" @click="startPlanningForGoal(x.goal.id)">生成排程</v-btn>
            <template v-if="scheduledGoalsHint?.length">
              <div class="mt-2 d-flex flex-wrap align-center ga-1">
                <span class="text-caption">已有排程的目标：</span>
                <v-chip v-for="g in scheduledGoalsHint" :key="g.goalId" size="x-small" variant="outlined" @click="openGoalPanel(g.goalId)">{{ g.title }}（{{ g.count }}）</v-chip>
              </div>
            </template>
          </v-alert>

          <!-- Schedule days -->
          <div v-for="d in x.days" :key="d.date" class="goal-day mb-3">
            <div class="d-flex align-center ga-2 mb-2">
              <v-icon icon="mdi-calendar-blank" size="14" color="primary" />
              <span class="text-body-2 font-weight-semibold">{{ d.date }}</span>
              <span class="text-caption text-medium-emphasis">{{ d.items.length }} 项 · 完成 {{ doneCount(d.items) }}/{{ d.items.length }}</span>
              <v-spacer />
              <v-btn icon="mdi-delete-outline" size="x-small" variant="text" color="error" density="compact" :loading="deletingDate === d.date" @click.stop="deleteSchedulesForDate(d.date)" aria-label="删除该日排程" title="删除该日排程" />
            </div>
            <div class="goal-sched-list">
              <div
                v-for="(s, i) in d.items"
                :key="s.id"
                class="goal-sched-item"
                :class="{ 'goal-sched-done': Number(s.status) === 1 }"
              >
                <span class="goal-sched-num">{{ i + 1 }}</span>
                <div class="flex-grow-1" style="min-width:0">
                  <div class="d-flex align-center justify-space-between ga-2">
                    <span class="text-body-2 font-weight-medium text-truncate">{{ s.taskTitle || ('任务 ' + s.taskId) }}</span>
                    <v-chip size="x-small" variant="tonal" :color="Number(s.status) === 1 ? 'success' : undefined">
                      {{ Number(s.status) === 1 ? '已完成' : '未完成' }}
                    </v-chip>
                  </div>
                  <div class="text-caption text-medium-emphasis mt-1">
                    <v-icon icon="mdi-clock-outline" size="12" class="mr-1" />{{ fmt(s.startTime).slice(11, 16) }} — {{ fmt(s.endTime).slice(11, 16) }}
                  </div>
                </div>
              </div>
            </div>
          </div>
        </v-expansion-panel-text>
      </v-expansion-panel>
    </v-expansion-panels>
  </v-card>
</template>

<style scoped>
.goal-panel :deep(.v-expansion-panel-title) {
  padding: 8px 16px;
}

.goal-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
  opacity: 0.7;
}

.goal-panel-title {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.goal-day {
  padding: 8px 12px;
  border-radius: 10px;
  background: rgba(var(--v-theme-on-surface), 0.02);
}

.goal-sched-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.goal-sched-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid rgba(var(--v-theme-primary), 0.08);
  background: rgba(var(--v-theme-primary), 0.02);
  transition: border-color 0.2s, background 0.2s;
}
.goal-sched-item:hover {
  border-color: rgba(var(--v-theme-primary), 0.18);
  background: rgba(var(--v-theme-primary), 0.04);
}
.goal-sched-done {
  border-color: rgba(var(--v-theme-success), 0.12);
  background: rgba(var(--v-theme-success), 0.03);
}

.goal-sched-num {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: rgba(var(--v-theme-primary), 0.08);
  color: rgb(var(--v-theme-primary));
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  flex-shrink: 0;
}
.goal-sched-done .goal-sched-num {
  background: rgba(var(--v-theme-success), 0.12);
  color: rgb(var(--v-theme-success));
}
</style>
