<script setup>
import { computed, onActivated, onMounted, ref, watch } from 'vue'
import api from '../plugins/api'
import { useNotifyStore } from '../stores/notify'
import { useDecomposeStore } from '../stores/decompose'
import { useAuthStore } from '../stores/auth'
import { useRouter } from 'vue-router'

const step = ref(0)
const initializing = ref(true)
const busy = ref(false)
const error = ref('')
const importResult = ref(null)
const notify = useNotifyStore()
const decompose = useDecomposeStore()
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
const goalText = ref('')

const dashboard = ref(null)
const tasks = ref([])
const currentGoalId = ref(null)
const currentGoalTitle = ref('')
const goalsList = ref([])
const feedbackOpen = ref(false)
const feedbackText = ref('')
const tasksLoading = ref(false)
const tasksAccepted = ref(false)

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
  if (needsImport.value) return '导入课表 → 添加新目标 → AI 拆解任务 → 确认并排程'
  return '课表已导入 → 添加新目标 → AI 拆解任务 → 确认并排程'
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
  const d = await api.get('/user/dashboard')
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

function hasRealTasks(list) {
  const arr = Array.isArray(list) ? list : []
  if (!arr.length) return false
  return arr.some((t) => t?.id && !String(t?.title || '').startsWith('[AI降级]'))
}

async function onSelectGoal(goalId) {
  currentGoalId.value = goalId
  const g = goalsList.value.find((x) => x.id === goalId)
  currentGoalTitle.value = g?.title ?? ''
  await loadGoalTasks(goalId)
}

function acceptTasks() {
  tasksAccepted.value = true
  notify.success('已确认任务，现在可以去目标页生成排程了')
  safeSaveWizardState({ tasksAcceptedGoalId: currentGoalId.value || null })
}

async function submitFeedback() {
  if (!currentGoalId.value) return
  busy.value = true
  error.value = ''
  // Set loading before API call so SSE GOAL_TASK_READY can be caught
  tasksLoading.value = true
  tasks.value = []
  tasksAccepted.value = false
  try {
    await api.post(`/user/goals/${currentGoalId.value}/tasks/regenerate`, feedbackText.value, { headers: { 'Content-Type': 'text/plain;charset=UTF-8' } })
    feedbackOpen.value = false
    feedbackText.value = ''
    notify.info('已提交意见，后台正在重新生成任务')
  } catch (e) {
    error.value = e?.response?.data?.message || '提交失败'
    tasksLoading.value = false
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
    const created = await api.post('/user/goals/ai', goalText.value, { headers: { 'Content-Type': 'text/plain;charset=UTF-8' } })
    const goal = created?.data?.data ?? null
    currentGoalId.value = goal?.id ?? null
    currentGoalTitle.value = goal?.title ?? ''
    // DecomposePanel will pick up SSE events automatically via DefaultLayout
    tasksLoading.value = true
    tasks.value = []
    tasksAccepted.value = false
    step.value = 3
    safeSaveWizardState({ step: 3, currentGoalId: currentGoalId.value, goalText: goalText.value })
  } catch (e) {
    error.value = e?.response?.data?.message || '提交目标失败'
  } finally {
    busy.value = false
  }
}

function resetWizardState() {
  step.value = 1
  currentGoalId.value = null
  currentGoalTitle.value = ''
  tasks.value = []
  tasksLoading.value = false
  tasksAccepted.value = false
  feedbackOpen.value = false
  feedbackText.value = ''
}

function finishWizard() {
  safeClearWizardState()
  resetWizardState()
  notify.success('已完成本次向导')
  router.push('/')
}

function finishWizardAndGo(to) {
  safeClearWizardState()
  resetWizardState()
  notify.success('已结束本页流程，请在目标页完成排程')
  router.push(to)
}

async function initWizard() {
  // Resolve step from localStorage immediately — no need to wait for API
  let desiredStep = 1
  const saved = safeLoadWizardState()
  const savedStep = Number(saved?.step)
  const savedGoalId = Number(saved?.currentGoalId)
  if (Number.isFinite(savedStep) && savedStep >= 1 && savedStep <= 3) desiredStep = savedStep
  if (Number.isFinite(savedGoalId) && savedGoalId > 0) {
    currentGoalId.value = savedGoalId
    goalText.value = saved?.goalText || goalText.value
  }
  step.value = desiredStep
  initializing.value = false

  // Load everything in background
  try { await auth.fetchMe() } catch (e) {}
  if (needsImport.value && desiredStep > 1) {
    safeClearWizardState()
    step.value = 1
  }
  await loadGoals(false)
  // If on step 3 with a saved goal, try loading tasks
  if (step.value === 3 && currentGoalId.value) {
    await loadGoalTasks(currentGoalId.value)
    tasksLoading.value = !hasRealTasks(tasks.value)
  }
}

let _ready5 = false
onMounted(initWizard)
onActivated(() => { if (_ready5) loadGoals(false); _ready5 = true })

watch(
  () => step.value,
  (v) => {
    if (initializing.value) return
    const n = Number(v)
    if (Number.isFinite(n) && n >= 1 && n <= 3) safeSaveWizardState({ step: n })
  },
)

watch(
  () => currentGoalId.value,
  (v) => {
    safeSaveWizardState({ currentGoalId: v || null })
  },
)

watch(
  () => notify.signalSeq?.GOAL_TASK_READY,
  async (seq) => {
    if (!seq || !currentGoalId.value || !tasksLoading.value) return
    try {
      await loadGoalTasks(currentGoalId.value)
      if (hasRealTasks(tasks.value)) {
        tasksLoading.value = false
        // Sync real DB tasks back to the decompose floating panel
        if (decompose.active) {
          decompose.syncTasks(tasks.value)
        }
      }
    } catch (e) { /* ignore, user can manually refresh */ }
  },
)


</script>

<template>
  <v-container style="max-width: 1200px">
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
        <v-stepper-item :value="3" title="确认任务" />
      </v-stepper-header>

      <v-stepper-window>
        <v-stepper-window-item :value="1">
          <v-card v-if="needsImport || showScheduleUpload" class="pa-6" elevation="0">
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
                {{ (scheduleFile?.[0] ?? scheduleFile)?.name || '' }}
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

          <v-card v-else class="pa-4" elevation="0">
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
          <v-card class="pa-8" elevation="0">
            <!-- Header with icon -->
            <div class="text-center mb-6">
              <v-icon icon="mdi-target" size="48" color="primary" class="mb-3" style="opacity:0.6" />
              <div class="text-h6 font-weight-bold mb-2">添加新目标</div>
              <div class="text-body-1 text-medium-emphasis" style="max-width:480px;margin:0 auto">
                描述你想学习的内容，AI 将自动拆解为可执行的子任务，并从资源库匹配学习资料
              </div>
            </div>

            <!-- Form area -->
            <div style="max-width: 640px; margin: 0 auto;">
              <v-textarea
                v-model="goalText"
                label="目标描述"
                placeholder="尽量具体，例如：「两个月内掌握分布式系统核心概念，能独立设计一个分布式 KV 存储」"
                variant="outlined"
                rows="4"
                auto-grow
                class="mb-4"
              />


              <v-alert type="info" variant="tonal" class="mb-6">
                <div>
                  <div class="font-weight-medium text-body-2">提交后会发生什么？</div>
                  <div class="text-caption mt-1">AI 在后台拆解任务，进度显示在右上角浮动面板，完成后自动刷新本页。你可以先去写随笔或浏览其他页面。</div>
                </div>
              </v-alert>

              <div class="d-flex justify-space-between align-center">
                <v-btn variant="text" size="small" prepend-icon="mdi-arrow-left" @click="step = 1">上一步</v-btn>
                <v-btn color="primary" size="large" :loading="busy" :disabled="!goalText.trim()" @click="createGoalByAi">
                  <v-icon icon="mdi-brain" size="20" class="mr-1" />提交目标并拆解
                </v-btn>
              </div>
            </div>
          </v-card>
        </v-stepper-window-item>

        <v-stepper-window-item :value="3">
          <v-card class="pa-4" elevation="0">
            <!-- Loading state: tasks not ready yet, DecomposePanel shows progress -->
            <template v-if="tasksLoading && !hasRealTasks(tasks)">
              <v-alert type="info" variant="tonal" class="mb-4">
                <div class="d-flex align-center">
                  <v-progress-circular indeterminate size="16" width="2" class="mr-3" />
                  <div>
                    <div class="font-weight-medium">AI 正在拆解任务</div>
                    <div class="text-caption mt-1">进度见右上角浮动面板，完成后自动刷新本页</div>
                  </div>
                </div>
              </v-alert>
              <div class="text-center text-body-2 text-medium-emphasis mb-3">
                目标「{{ currentGoalTitle || goalText }}」已提交，可以先做别的
              </div>
              <div class="d-flex flex-wrap justify-center" style="gap:8px">
                <v-btn variant="tonal" prepend-icon="mdi-book-open-page-variant" @click="router.push('/journals')">去写随笔</v-btn>
                <v-btn variant="tonal" prepend-icon="mdi-target" @click="finishWizardAndGo('/goals')">去目标页</v-btn>
                <v-btn variant="tonal" :loading="busy" @click="loadGoalTasks(currentGoalId).then(() => { if (hasRealTasks(tasks)) tasksLoading = false })">
                  <v-icon icon="mdi-refresh" size="18" class="mr-1" />手动刷新
                </v-btn>
              </div>
            </template>

            <!-- Empty / error state: not loading but no tasks -->
            <template v-else-if="!hasRealTasks(tasks)">
              <v-alert type="warning" variant="tonal" class="mb-3">
                任务尚未生成。可能是后台处理较慢，或目标描述不够具体。
              </v-alert>
              <div class="d-flex flex-wrap justify-center" style="gap:8px">
                <v-btn variant="tonal" @click="router.push('/journals')">去写随笔</v-btn>
                <v-btn variant="tonal" :loading="busy" @click="loadGoalTasks(currentGoalId)">刷新</v-btn>
                <v-btn variant="tonal" color="warning" @click="feedbackOpen = true">改进目标描述</v-btn>
                <v-btn v-if="currentGoalId" color="primary" @click="finishWizardAndGo('/goals')">去目标页</v-btn>
              </div>
            </template>

            <!-- Tasks ready -->
            <template v-else>
              <div class="sp-scroll">
                <div class="sp-scroll-header">
                  <div class="d-flex align-center flex-wrap ga-2">
                    <div class="text-subtitle-1 font-weight-semibold">任务列表</div>
                    <v-spacer />
                    <v-btn variant="tonal" size="small" color="success" :disabled="!currentGoalId || tasksAccepted" @click="acceptTasks">
                      <v-icon :icon="tasksAccepted ? 'mdi-check-circle' : 'mdi-check'" size="16" class="mr-1" />{{ tasksAccepted ? '已确认' : '满意' }}
                    </v-btn>
                    <v-btn variant="tonal" size="small" color="warning" :disabled="!currentGoalId" @click="feedbackOpen = true">
                      <v-icon icon="mdi-pencil" size="16" class="mr-1" />不满意
                    </v-btn>
                    <v-btn variant="tonal" size="small" :loading="busy" @click="refreshAll">
                      <v-icon icon="mdi-refresh" size="16" class="mr-1" />刷新
                    </v-btn>
                  </div>
                  <div class="text-caption mt-1" style="opacity:0.75">
                    当前目标：{{ currentGoalTitle || '-' }} ｜ 共 {{ tasks.length }} 个任务（展示前 {{ displayTasks.length }} 条）
                  </div>
                </div>
                <v-list density="comfortable">
                  <v-list-item v-for="t in displayTasks" :key="t.id" :title="t.title" :subtitle="t.description" />
                </v-list>
              </div>
              <div class="d-flex flex-wrap justify-end mt-3" style="gap:8px">
                <v-btn variant="tonal" @click="finishWizard">回到首页</v-btn>
                <v-btn color="primary" @click="finishWizardAndGo('/goals')">
                  <v-icon icon="mdi-calendar-clock" size="18" class="mr-1" />去目标页排程
                </v-btn>
              </div>
            </template>
          </v-card>
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
  </v-container>
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

.sp-scroll {
  max-height: 64vh;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior: contain;
  border: 1px solid rgba(var(--v-theme-on-surface), 0.08);
  border-radius: 16px;
}

.sp-scroll-header {
  position: sticky;
  top: 0;
  z-index: 2;
  padding: 12px 12px 10px 12px;
  background: rgba(var(--v-theme-surface), 0.92);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid rgba(var(--v-theme-on-surface), 0.08);
}
</style>
