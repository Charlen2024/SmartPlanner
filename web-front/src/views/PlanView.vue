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
const firstWeekMonday = ref(
  auth.me?.firstWeekMonday || localStorage.getItem('firstWeekMonday') || ''
)
watch(() => auth.me?.firstWeekMonday, (v) => {
  if (v) {
    firstWeekMonday.value = v
    localStorage.setItem('firstWeekMonday', v)
  }
})
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
const currentWeekNum = computed(() => {
  if (!firstWeekMonday.value) return null
  const monday = new Date(firstWeekMonday.value + 'T00:00:00')
  const now = new Date(today.value + 'T00:00:00')
  if (isNaN(monday.getTime()) || now < monday) return null
  return Math.floor((now - monday) / (7 * 24 * 60 * 60 * 1000)) + 1
})
const needsImport = computed(() => auth.me?.scheduleImported === false)
const showScheduleUpload = ref(false)
const step1Title = computed(() => {
  if (needsImport.value) return '导入课表'
  if (auth.me?.scheduleImported) return '课表（已导入）'
  return '课表（可选）'
})
const wizardSubtitle = computed(() => {
  if (needsImport.value) return '导入课表 → 添加新目标 → 拆解任务 → 去目标页排程 → 打卡'
  return '课表已导入 → 添加新目标 → 拆解任务 → 去目标页排程 → 打卡'
})

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
    const file = scheduleFile.value?.[0] ?? scheduleFile.value
    fd.append('file', file)
    if (firstWeekMonday.value) fd.append('firstWeekMonday', firstWeekMonday.value)
    const res = await api.post('/user/schedule/import', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
    importResult.value = res?.data?.data ?? null
    const code = res?.data?.code
    if (code !== 200) {
      error.value = res?.data?.message || '课表导入失败'
      return
    }
    const inserted = importResult.value?.inserted ?? 0
    if (inserted === 0) {
      const warnings = importResult.value?.warnings
      const detail = warnings?.length ? `（${warnings.join('；')}）` : ''
      notify.error(`课表未导入任何课程${detail}`, 8000)
      return
    }
    notify.success(`课表导入成功：${inserted} 条`)
    try {
      await auth.fetchMe()
    } catch (e) {}
    safeClearWizardState()
    step.value = 2
  } catch (e) {
    error.value = e?.response?.data?.message || '课表导入失败（请使用 .ics / .xlsx / .csv）'
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
    step.value = 3
    safeSaveWizardState({ step: 3, currentGoalId: currentGoalId.value, topic: topic.value, goalText: goalText.value })
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

  // 新用户清除残留向导状态，从步骤1开始
  if (needsImport.value) {
    safeClearWizardState()
  }

  const saved = safeLoadWizardState()
  const savedStep = Number(saved?.step)
  const savedGoalId = Number(saved?.currentGoalId)
  let desiredStep = 1
  if (Number.isFinite(savedStep) && savedStep >= 1 && savedStep <= 4) desiredStep = savedStep
  if (Number.isFinite(savedGoalId) && savedGoalId > 0) {
    currentGoalId.value = savedGoalId
    await loadGoals(false)
    if (desiredStep === 3 && !isTasksReady(tasks.value)) {
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
    if (Number.isFinite(n) && n >= 1 && n <= 4) safeSaveWizardState({ step: n })
    if (n !== 3) stopTasksPolling()
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
        <div class="text-body-2" style="opacity: 0.78">{{ wizardSubtitle }}</div>
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
        <v-stepper-item :value="2" title="添加新目标" />
        <v-divider />
        <v-stepper-item :value="3" title="任务拆解" />
        <v-divider />
        <v-stepper-item :value="4" title="去目标页排程/完成" />
      </v-stepper-header>

      <v-stepper-window>
        <v-stepper-window-item :value="1">
          <v-card v-if="needsImport || showScheduleUpload" class="pa-6">
            <div class="text-h6 font-weight-bold mb-4">上传大学课表</div>

            <!-- 文件上传区域 -->
            <div
              class="upload-zone mb-4"
              :class="{ 'upload-zone--has-file': scheduleFile }"
              @click="$refs.fileInput?.click()"
              @dragover.prevent
              @drop.prevent="scheduleFile = $event.dataTransfer?.files"
            >
              <input
                ref="fileInput"
                type="file"
                accept=".csv,.xlsx,.ics"
                style="display:none"
                @change="scheduleFile = $event.target.files"
              />
              <v-icon
                :icon="scheduleFile ? 'mdi-file-check-outline' : 'mdi-cloud-upload-outline'"
                size="40"
                :color="scheduleFile ? 'success' : undefined"
                class="mb-2"
              />
              <div v-if="!scheduleFile" class="text-body-1 font-weight-medium">点击或拖拽文件到此处</div>
              <div v-else class="text-body-1 font-weight-medium text-success">
                {{ Array.isArray(scheduleFile) ? scheduleFile[0]?.name : scheduleFile?.name }}
              </div>
              <div class="text-caption mt-1" style="opacity:0.6">支持 .ics / .xlsx / .csv</div>
            </div>

            <!-- 日期选择 + 周数 -->
            <v-row dense class="mb-4">
              <v-col cols="12" sm="6">
                <v-text-field
                  v-model="firstWeekMonday"
                  label="第一周周一"
                  type="date"
                  variant="outlined"
                  density="comfortable"
                  hint="选学期第一个周一，如 2026-02-23"
                  persistent-hint
                />
              </v-col>
              <v-col cols="12" sm="6" class="d-flex align-center">
                <v-alert
                  v-if="currentWeekNum"
                  type="info"
                  variant="tonal"
                  density="compact"
                  class="mb-0 w-100"
                >
                  当前日期 {{ today }} 为 <strong>第 {{ currentWeekNum }} 周</strong>
                </v-alert>
                <div v-else class="text-caption" style="opacity:0.5">
                  填写第一周周一后自动计算当前周数
                </div>
              </v-col>
            </v-row>

            <!-- 格式说明 -->
            <div class="format-hints mb-4">
              <div class="text-caption font-weight-bold mb-2" style="opacity:0.6">CSV 表头格式</div>
              <div class="d-flex flex-wrap" style="gap:6px">
                <v-chip size="x-small" variant="tonal" label>课程名称</v-chip>
                <v-chip size="x-small" variant="tonal" label>星期(1=周一)</v-chip>
                <v-chip size="x-small" variant="tonal" label>开始节数</v-chip>
                <v-chip size="x-small" variant="tonal" label>结束节数</v-chip>
                <v-chip size="x-small" variant="tonal" label>地点</v-chip>
                <v-chip size="x-small" variant="tonal" label>周数</v-chip>
              </div>
              <div class="text-caption mt-2" style="opacity:0.5">
                第1节 08:00 ~ 第5节 14:45 · 第6节 14:55 ~ 第10节 20:40（午休 12:00-14:00）｜
                周数例：<code>1-16</code> <code>1-16双</code> <code>18</code>
              </div>
            </div>

            <!-- 操作按钮 -->
            <div class="d-flex align-center flex-wrap" style="gap:8px">
              <v-btn
                size="small"
                variant="tonal"
                prepend-icon="mdi-download"
                href="/schedule_template.csv"
                download
                target="_blank"
              >
                下载模板
              </v-btn>
              <v-spacer />
              <v-btn v-if="!needsImport" variant="text" size="small" @click="showScheduleUpload = false">取消</v-btn>
              <v-btn color="primary" size="large" :loading="busy" :disabled="!scheduleFile" @click="importSchedule">
                导入课表
              </v-btn>
            </div>

            <v-alert v-if="importResult" type="success" variant="tonal" class="mt-4" density="compact">
              已导入 {{ importResult.inserted }}/{{ importResult.total }} 门课程
              <template v-if="importResult.warnings?.length">
                <div v-for="(w, i) in importResult.warnings" :key="i" class="text-caption">{{ w }}</div>
              </template>
            </v-alert>
          </v-card>

          <v-card v-else class="pa-4">
            <div class="d-flex align-center mb-3">
              <v-icon icon="mdi-check-circle" color="success" size="28" class="mr-3" />
              <div>
                <div class="text-subtitle-1 font-weight-semibold">课表已导入</div>
                <div class="text-body-2" style="opacity: 0.7">可以开始添加目标了</div>
              </div>
            </div>
            <div class="d-flex flex-wrap" style="gap: 8px">
              <v-btn variant="tonal" size="small" prepend-icon="mdi-calendar" @click="router.push('/schedule')">查看课表</v-btn>
              <v-btn variant="text" size="small" @click="router.push('/schedule')">更换课表</v-btn>
              <v-spacer />
              <v-btn color="primary" @click="step = 2">下一步：添加目标</v-btn>
            </div>
          </v-card>
        </v-stepper-window-item>

        <v-stepper-window-item :value="2">
          <v-card class="pa-4">
            <div class="text-subtitle-1 font-weight-semibold mb-2">添加新目标</div>
            <v-textarea v-model="goalText" label="例如：学习分布式系统" variant="outlined" rows="3" auto-grow />
            <v-text-field v-model="topic" label="资源主题（用于推荐）" variant="outlined" />
            <div class="d-flex justify-end">
              <v-btn color="primary" :loading="busy" @click="createGoalByAi">提交目标并拆解</v-btn>
            </div>
          </v-card>
        </v-stepper-window-item>

        <v-stepper-window-item :value="3">
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

        <v-stepper-window-item :value="4">
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
.upload-zone {
  border: 2px dashed rgba(var(--v-theme-on-surface), 0.18);
  border-radius: 12px;
  padding: 32px 16px;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}
.upload-zone:hover {
  border-color: rgba(var(--v-theme-primary), 0.5);
  background: rgba(var(--v-theme-primary), 0.04);
}
.upload-zone--has-file {
  border-style: solid;
  border-color: rgba(var(--v-theme-success), 0.4);
  background: rgba(var(--v-theme-success), 0.04);
}

.format-hints {
  padding: 12px 16px;
  border-radius: 8px;
  background: rgba(var(--v-theme-on-surface), 0.03);
}

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
