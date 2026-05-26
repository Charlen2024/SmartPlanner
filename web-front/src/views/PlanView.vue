<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import api from '../plugins/api'
import { useNotifyStore } from '../stores/notify'
import { useAuthStore } from '../stores/auth'
import { useRouter } from 'vue-router'

const step = ref(0)
const initializing = ref(true)
const busy = ref(false)
const error = ref('')
const importResult = ref(null)
const notify = useNotifyStore()
const auth = useAuthStore()
const router = useRouter()

const scheduleFile = ref(null)
const goalText = ref('学习分布式系统')
const topic = ref('分布式')

const dashboard = ref(null)
const tasks = ref([])
const currentGoalId = ref(null)
const currentGoalTitle = ref('')
const goalsList = ref([])
const feedbackOpen = ref(false)
const feedbackText = ref('')
const tasksPolling = ref(false)
const tasksPollingMessage = ref('')
const tasksPollTimer = ref(null)

const today = computed(() => new Date().toISOString().slice(0, 10))
const needsImport = computed(() => auth.me?.scheduleImported === false)
const showScheduleUpload = ref(false)
const step1Title = computed(() => (needsImport.value ? '导入课表' : '课表（可选）'))

const displayTasks = computed(() => tasks.value?.slice?.(0, 80) ?? [])

const WIZARD_KEY = 'smartplanner.planWizard.v1'

function safeLoadWizardState() {
  try {
    const raw = localStorage.getItem(WIZARD_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return null
    return parsed
  } catch (e) {
    return null
  }
}

function safeSaveWizardState(patch) {
  try {
    const prev = safeLoadWizardState() || {}
    const next = { ...prev, ...patch, updatedAt: Date.now() }
    localStorage.setItem(WIZARD_KEY, JSON.stringify(next))
  } catch (e) {}
}

function safeClearWizardState() {
  try {
    localStorage.removeItem(WIZARD_KEY)
  } catch (e) {}
}

async function refreshAll() {
  const d = await api.get('/user/dashboard', { params: { topic: topic.value } })
  dashboard.value = d?.data?.data ?? null
  await loadGoals(false)
}

async function loadGoals(selectLatest = true) {
  const res = await api.get('/user/goals')
  const list = res?.data?.data ?? []
  goalsList.value = [...list].filter((g) => g?.id).sort((a, b) => a.id - b.id)
  if (selectLatest && !currentGoalId.value) {
    const latest = goalsList.value.at(-1)
    currentGoalId.value = latest?.id ?? null
    currentGoalTitle.value = latest?.title ?? ''
  } else if (currentGoalId.value) {
    const g = goalsList.value.find((x) => x.id === currentGoalId.value)
    currentGoalTitle.value = g?.title ?? currentGoalTitle.value
  }
  if (currentGoalId.value) {
    await loadGoalTasks(currentGoalId.value)
  } else {
    tasks.value = []
  }
}

async function loadGoalTasks(goalId) {
  const res = await api.get(`/user/goals/${goalId}/tasks`)
  tasks.value = res?.data?.data ?? []
}

function stopTasksPolling() {
  tasksPolling.value = false
  tasksPollingMessage.value = ''
  if (tasksPollTimer.value) clearTimeout(tasksPollTimer.value)
  tasksPollTimer.value = null
}

function isTasksReady(list) {
  const arr = Array.isArray(list) ? list : []
  if (!arr.length) return false
  const hasReal = arr.some((t) => t?.id && !String(t?.title || '').startsWith('[AI降级]'))
  return hasReal
}

async function pollTasksUntilReady(goalId) {
  stopTasksPolling()
  const gid = Number(goalId)
  if (!Number.isFinite(gid) || gid <= 0) return
  tasksPolling.value = true
  tasksPollingMessage.value = '任务拆解进行中（你可以先去写随笔/看别的，稍后回来）'
  const startedAt = Date.now()
  const loop = async () => {
    if (!tasksPolling.value) return
    try {
      await loadGoalTasks(gid)
      if (isTasksReady(tasks.value)) {
        tasksPollingMessage.value = '任务已生成'
        tasksPollTimer.value = setTimeout(() => stopTasksPolling(), 800)
        return
      }
    } catch (e) {}
    const elapsed = Date.now() - startedAt
    if (elapsed > 120000) {
      tasksPollingMessage.value = '任务拆解耗时较长，可稍后点击刷新'
      tasksPollTimer.value = setTimeout(() => stopTasksPolling(), 1500)
      return
    }
    tasksPollTimer.value = setTimeout(loop, 2000)
  }
  await loop()
}

async function onSelectGoal(goalId) {
  currentGoalId.value = goalId
  const g = goalsList.value.find((x) => x.id === goalId)
  currentGoalTitle.value = g?.title ?? ''
  await loadGoalTasks(goalId)
}

function acceptTasks() {
  notify.success('已标记为满意')
  safeSaveWizardState({ tasksAcceptedGoalId: currentGoalId.value || null })
}

async function submitFeedback() {
  if (!currentGoalId.value) return
  busy.value = true
  error.value = ''
  try {
    await api.post(`/user/goals/${currentGoalId.value}/tasks/regenerate`, feedbackText.value, { headers: { 'Content-Type': 'text/plain' } })
    feedbackOpen.value = false
    feedbackText.value = ''
    notify.info('已提交意见，后台正在重新生成任务')
    await pollTasksUntilReady(currentGoalId.value)
  } catch (e) {
    error.value = e?.response?.data?.message || '提交失败'
  } finally {
    busy.value = false
  }
}

async function importSchedule() {
  if (!scheduleFile.value) return
  busy.value = true
  error.value = ''
  importResult.value = null
  try {
    const fd = new FormData()
    fd.append('file', Array.isArray(scheduleFile.value) ? scheduleFile.value[0] : scheduleFile.value)
    const res = await api.post('/user/schedule/import', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
    importResult.value = res?.data?.data ?? null
    notify.success(`课表导入成功：${importResult.value?.inserted ?? 0} 条`)
    try {
      await auth.fetchMe()
    } catch (e) {}
    step.value = 2
  } catch (e) {
    error.value = e?.response?.data?.message || '课表导入失败（请使用 .ics / .xlsx）'
  } finally {
    busy.value = false
  }
}

async function detectFreeTime() {
  busy.value = true
  error.value = ''
  try {
    const d = await api.get('/user/schedule/free-time', { params: { date: today.value } })
    dashboard.value = dashboard.value ?? {}
    dashboard.value.freeTimeSlots = d?.data?.data ?? []
    notify.success('已识别空闲时间')
    step.value = 3
  } catch (e) {
    error.value = '识别空闲时间失败'
  } finally {
    busy.value = false
  }
}

async function createGoalByAi() {
  if (!goalText.value) return
  busy.value = true
  error.value = ''
  try {
    const created = await api.post('/user/goals/ai', goalText.value, { headers: { 'Content-Type': 'text/plain' } })
    const goal = created?.data?.data ?? null
    currentGoalId.value = goal?.id ?? null
    currentGoalTitle.value = goal?.title ?? ''
    notify.info('目标已提交，后台正在拆解任务（完成后右上角会提示）')
    step.value = 4
    safeSaveWizardState({ step: 4, currentGoalId: currentGoalId.value, topic: topic.value, goalText: goalText.value })
    await pollTasksUntilReady(currentGoalId.value)
  } catch (e) {
    error.value = e?.response?.data?.message || '提交目标失败'
  } finally {
    busy.value = false
  }
}

function finishWizard() {
  stopTasksPolling()
  safeClearWizardState()
  notify.success('已完成本次向导')
  router.push('/')
}

function finishWizardAndGo(to) {
  stopTasksPolling()
  safeClearWizardState()
  notify.success('已结束本页流程，请在目标页完成排程')
  router.push(to)
}

async function initWizard() {
  initializing.value = true
  try {
    await auth.fetchMe()
  } catch (e) {}
  await refreshAll()

  const saved = safeLoadWizardState()
  const savedStep = Number(saved?.step)
  const savedGoalId = Number(saved?.currentGoalId)
  let desiredStep = 1
  if (Number.isFinite(savedStep) && savedStep >= 1 && savedStep <= 5) desiredStep = savedStep
  if (Number.isFinite(savedGoalId) && savedGoalId > 0) {
    currentGoalId.value = savedGoalId
    await loadGoals(false)
    if (desiredStep === 4 && !isTasksReady(tasks.value)) {
      await pollTasksUntilReady(savedGoalId)
    }
  }
  step.value = desiredStep
  initializing.value = false
}

onMounted(initWizard)

watch(
  () => step.value,
  (v) => {
    if (initializing.value) return
    const n = Number(v)
    if (Number.isFinite(n) && n >= 1 && n <= 5) safeSaveWizardState({ step: n })
    if (n !== 4) stopTasksPolling()
  },
)

watch(
  () => currentGoalId.value,
  (v) => {
    safeSaveWizardState({ currentGoalId: v || null })
  },
)

watch(
  () => topic.value,
  (v) => {
    safeSaveWizardState({ topic: v })
  },
)

onBeforeUnmount(() => {
  stopTasksPolling()
})
</script>

<template>
  
    <v-row class="mb-4" align="center">
      <v-col cols="12" md="7">
        <div class="text-h5 font-weight-bold">学习计划向导</div>
        <div class="text-body-2" style="opacity: 0.78">课表（一次导入即可）→ 识别空闲时间 → 添加新目标 → 拆解任务 → 去目标页排程 → 打卡</div>
      </v-col>
      <v-col cols="12" md="5" class="d-flex justify-end">
        <v-btn variant="tonal" :loading="busy" @click="refreshAll">刷新数据</v-btn>
      </v-col>
    </v-row>

    <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

    <div v-if="initializing" class="mb-4">
      <v-alert type="info" variant="tonal" class="mb-2">正在加载向导数据…</v-alert>
      <v-progress-linear indeterminate height="8" rounded color="primary" />
    </div>

    <v-stepper v-else v-model="step" elevation="0">
      <v-stepper-header>
        <v-stepper-item :value="1" :title="step1Title" />
        <v-divider />
        <v-stepper-item :value="2" title="识别空闲时间" />
        <v-divider />
        <v-stepper-item :value="3" title="添加新目标" />
        <v-divider />
        <v-stepper-item :value="4" title="任务拆解" />
        <v-divider />
        <v-stepper-item :value="5" title="去目标页排程/完成" />
      </v-stepper-header>

      <v-stepper-window>
        <v-stepper-window-item :value="1">
          <v-card v-if="needsImport || showScheduleUpload" class="pa-4">
            <div class="text-subtitle-1 font-weight-semibold mb-2">上传大学课表文件</div>
            <v-file-input v-model="scheduleFile" label="选择文件" prepend-icon="mdi-upload" variant="outlined" />
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
            <v-alert v-if="importResult" type="success" variant="tonal" class="mt-3">
              已导入：{{ importResult.inserted }} / {{ importResult.total }}（跳过 {{ importResult.skipped }}）
              <div v-for="(w, i) in importResult.warnings ?? []" :key="i" class="text-body-2">{{ w }}</div>
            </v-alert>
            <div class="d-flex justify-end mt-2">
              <v-btn v-if="!needsImport" variant="text" @click="showScheduleUpload = false">暂不更换</v-btn>
              <v-btn color="primary" :loading="busy" @click="importSchedule">导入</v-btn>
            </div>
          </v-card>
          <v-card v-else class="pa-4">
            <div class="text-subtitle-1 font-weight-semibold mb-2">课表已导入</div>
            <v-alert type="success" variant="tonal">
              已检测到你之前导入过课表。这里默认不再要求重复上传，直接进入“添加新目标”。
            </v-alert>
            <div class="d-flex justify-end mt-3">
              <v-btn variant="tonal" @click="router.push('/schedule')">查看/管理课表</v-btn>
              <v-btn class="ml-3" variant="tonal" @click="showScheduleUpload = true">更换课表</v-btn>
              <v-btn class="ml-3" color="primary" @click="step = 3">开始添加目标</v-btn>
            </div>
          </v-card>
        </v-stepper-window-item>

        <v-stepper-window-item :value="2">
          <v-card class="pa-4">
            <div class="text-subtitle-1 font-weight-semibold mb-2">自动识别空闲时间</div>
            <div class="text-body-2 mb-3" style="opacity: 0.78">默认识别日期：{{ today }}</div>
            <div class="d-flex justify-end">
              <v-btn color="primary" :loading="busy" @click="detectFreeTime">开始识别</v-btn>
            </div>
            <v-divider class="my-4" />
            <div v-if="dashboard?.freeTimeSlots?.length">
              <div v-for="(s, i) in dashboard.freeTimeSlots" :key="i">{{ s.start }} - {{ s.end }}</div>
            </div>
            <div v-else class="text-body-2" style="opacity: 0.7">暂无空闲时间数据</div>
          </v-card>
        </v-stepper-window-item>

        <v-stepper-window-item :value="3">
          <v-card class="pa-4">
            <div class="text-subtitle-1 font-weight-semibold mb-2">添加新目标</div>
            <v-textarea v-model="goalText" label="例如：学习分布式系统" variant="outlined" rows="3" auto-grow />
            <v-text-field v-model="topic" label="资源主题（用于推荐）" variant="outlined" />
            <div class="d-flex justify-end">
              <v-btn color="primary" :loading="busy" @click="createGoalByAi">提交目标并拆解</v-btn>
            </div>
          </v-card>
        </v-stepper-window-item>

        <v-stepper-window-item :value="4">
          <v-card class="pa-4">
            <v-alert v-if="tasksPolling" type="info" variant="tonal" class="mb-3">
              {{ tasksPollingMessage || '任务拆解进行中…' }}
            </v-alert>
            <v-alert v-else-if="!tasks?.length" type="info" variant="tonal" class="mb-3">暂无待办任务，稍后刷新重试</v-alert>
            <div v-else class="vibe-scroll">
              <div class="vibe-scroll-header">
                <div class="d-flex align-center">
                  <div class="text-subtitle-1 font-weight-semibold">任务列表</div>
                  <v-btn variant="tonal" class="mr-2" :disabled="!currentGoalId" @click="acceptTasks">满意</v-btn>
                  <v-btn variant="tonal" color="warning" class="mr-2" :disabled="!currentGoalId" @click="feedbackOpen = true">不满意</v-btn>
                  <v-btn variant="tonal" class="mr-2" :loading="busy" @click="refreshAll">刷新</v-btn>
                  <v-btn color="primary" @click="finishWizardAndGo('/goals')">去目标页排程</v-btn>
                </div>
                <div class="text-caption mt-1" style="opacity:0.75">
                  当前目标：{{ currentGoalTitle || '-' }}（ID {{ currentGoalId || '-' }}）｜当前显示 {{ displayTasks.length }} / {{ tasks.length }}（仅展示前 80 条）
                </div>
              </div>
              <v-list density="comfortable">
                <v-list-item v-for="t in displayTasks" :key="t.id" :title="t.title" :subtitle="t.description" />
              </v-list>
            </div>
            <div v-if="tasksPolling || !tasks?.length" class="d-flex justify-end mt-3">
              <v-btn variant="tonal" class="mr-2" @click="router.push('/journals')">去写随笔</v-btn>
              <v-btn variant="tonal" :loading="busy" @click="refreshAll">刷新</v-btn>
            </div>
          </v-card>
        </v-stepper-window-item>

        <v-stepper-window-item :value="5">
          <v-row>
            <v-col cols="12" md="6">
              <v-card class="pa-4">
                <div class="text-subtitle-1 font-weight-semibold mb-2">排程</div>
                <v-alert type="info" variant="tonal" class="mb-3">
                  课表提交统一在本页完成。智能排程统一在「目标」页生成（异步执行，完成后会通知）。
                </v-alert>
                <div class="d-flex justify-end">
                  <v-btn variant="tonal" class="mr-2" @click="finishWizardAndGo('/goals')">去目标页</v-btn>
                  <v-btn variant="tonal" @click="finishWizardAndGo('/schedule')">查看日程</v-btn>
                </div>
              </v-card>
            </v-col>
            <v-col cols="12" md="6">
              <v-card class="pa-4">
                <div class="text-subtitle-1 font-weight-semibold mb-2">完成</div>
                <div class="d-flex justify-end">
                  <v-btn color="primary" @click="finishWizard">完成</v-btn>
                </div>
              </v-card>
            </v-col>
          </v-row>
        </v-stepper-window-item>
      </v-stepper-window>
    </v-stepper>

    <v-dialog v-model="feedbackOpen" max-width="720">
      <v-card class="pa-2">
        <v-card-title class="text-h6">给出改进意见</v-card-title>
        <v-card-text>
          <v-textarea v-model="feedbackText" label="例如：任务太笼统/希望按章节拆分/每天控制 1 小时" variant="outlined" rows="4" auto-grow />
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="feedbackOpen = false">取消</v-btn>
          <v-btn color="primary" :loading="busy" @click="submitFeedback">提交并重新生成</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  
</template>

<style scoped>
.vibe-scroll {
  max-height: 64vh;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior: contain;
  border: 1px solid rgba(var(--v-theme-on-surface), 0.08);
  border-radius: 16px;
}

.vibe-scroll-header {
  position: sticky;
  top: 0;
  z-index: 2;
  padding: 12px 12px 10px 12px;
  background: rgba(var(--v-theme-surface), 0.92);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid rgba(var(--v-theme-on-surface), 0.08);
}
</style>
