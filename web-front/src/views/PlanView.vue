<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../plugins/api'
import { useNotifyStore } from '../stores/notify'

const step = ref(1)
const busy = ref(false)
const error = ref('')
const importResult = ref(null)
const notify = useNotifyStore()

const scheduleFile = ref(null)
const goalText = ref('学习分布式系统')
const topic = ref('分布式')

const dashboard = ref(null)
const tasks = ref([])
const schedules = ref([])
const currentGoalId = ref(null)
const currentGoalTitle = ref('')
const goalsList = ref([])
const feedbackOpen = ref(false)
const feedbackText = ref('')

const today = computed(() => new Date().toISOString().slice(0, 10))

const displayTasks = computed(() => tasks.value?.slice?.(0, 80) ?? [])
const displaySchedules = computed(() => schedules.value?.slice?.(0, 80) ?? [])

function fmt(dt) {
  if (!dt) return '-'
  return String(dt).replace('T', ' ').slice(0, 16)
}

async function refreshAll() {
  const [d, s] = await Promise.all([
    api.get('/user/dashboard', { params: { topic: topic.value } }),
    api.get('/user/schedule/task-schedules'),
  ])
  dashboard.value = d?.data?.data ?? null
  schedules.value = s?.data?.data ?? []
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

async function onSelectGoal(goalId) {
  currentGoalId.value = goalId
  const g = goalsList.value.find((x) => x.id === goalId)
  currentGoalTitle.value = g?.title ?? ''
  await loadGoalTasks(goalId)
}

function acceptTasks() {
  notify.success('已标记为满意')
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
    for (let i = 0; i < 25; i++) {
      await new Promise((r) => setTimeout(r, 1500))
      await loadGoalTasks(currentGoalId.value)
      if ((tasks.value?.length ?? 0) >= 3 && !tasks.value.some((t) => String(t?.title || '').startsWith('[AI降级]'))) {
        break
      }
    }
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
  } catch (e) {
    error.value = e?.response?.data?.message || '提交目标失败'
  } finally {
    busy.value = false
  }
}

async function autoSchedule() {
  busy.value = true
  error.value = ''
  try {
    const t = new Date()
    const d = `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`
    const startRes = await api.post('/user/schedule/daily-plan/jobs', { date: d, mode: 'replace' }, { timeout: 15000 })
    const jobId = startRes?.data?.data?.jobId
    if (!jobId) throw new Error('启动排程任务失败')
    notify.info('已启动后台智能排程（完成后右上角会提示）')
    setTimeout(async () => {
      try {
        for (let i = 0; i < 180; i++) {
          await new Promise((r) => setTimeout(r, 2000))
          const st = await api.get(`/user/schedule/daily-plan/jobs/${jobId}`)
          const data = st?.data?.data ?? null
          if (data?.status === 'DONE') {
            await refreshAll()
            break
          }
          if (data?.status === 'FAILED') {
            break
          }
        }
      } catch (e) {}
    }, 1000)
    step.value = 5
  } catch (e) {
    if (e?.code === 'ECONNABORTED') {
      error.value = '智能排程请求超时，请稍后重试'
    } else {
      error.value = e?.response?.data?.message || e?.message || '智能排程失败'
    }
    notify.error(error.value)
  } finally {
    busy.value = false
  }
}

async function punchFirstTask() {
  const t = tasks.value?.[0]
  if (!t) return
  busy.value = true
  error.value = ''
  try {
    const fd = new FormData()
    fd.append('taskId', t.id)
    fd.append('type', 1)
    fd.append('location', '31.2304,121.4737')
    await api.post('/user/punch/submit', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
    notify.success('已打卡')
  } catch (e) {
    error.value = '打卡失败'
    notify.error(error.value)
  } finally {
    busy.value = false
  }
}

onMounted(refreshAll)
</script>

<template>
  
    <v-row class="mb-4" align="center">
      <v-col cols="12" md="7">
        <div class="text-h5 font-weight-bold">学习计划向导</div>
        <div class="text-body-2" style="opacity: 0.78">导入课表 → 识别空闲时间 → 设定目标 → 拆解任务 → 智能排程 → 打卡</div>
      </v-col>
      <v-col cols="12" md="5" class="d-flex justify-end">
        <v-btn variant="tonal" :loading="busy" @click="refreshAll">刷新数据</v-btn>
      </v-col>
    </v-row>

    <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

    <v-stepper v-model="step" elevation="0">
      <v-stepper-header>
        <v-stepper-item :value="1" title="导入课表" />
        <v-divider />
        <v-stepper-item :value="2" title="识别空闲时间" />
        <v-divider />
        <v-stepper-item :value="3" title="设定目标" />
        <v-divider />
        <v-stepper-item :value="4" title="任务拆解" />
        <v-divider />
        <v-stepper-item :value="5" title="智能排程/打卡" />
      </v-stepper-header>

      <v-stepper-window>
        <v-stepper-window-item :value="1">
          <v-card class="pa-4">
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
              <v-btn color="primary" :loading="busy" @click="importSchedule">导入</v-btn>
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
            <div class="text-subtitle-1 font-weight-semibold mb-2">设定学习目标</div>
            <v-textarea v-model="goalText" label="例如：学习分布式系统" variant="outlined" rows="3" auto-grow />
            <v-text-field v-model="topic" label="资源主题（用于推荐）" variant="outlined" />
            <div class="d-flex justify-end">
              <v-btn color="primary" :loading="busy" @click="createGoalByAi">提交目标并拆解</v-btn>
            </div>
          </v-card>
        </v-stepper-window-item>

        <v-stepper-window-item :value="4">
          <v-card class="pa-4">
            <v-alert v-if="!tasks?.length" type="info" variant="tonal" class="mb-3">暂无待办任务，稍后刷新重试</v-alert>
            <div v-else class="vibe-scroll">
              <div class="vibe-scroll-header">
                <div class="d-flex align-center">
                  <div class="text-subtitle-1 font-weight-semibold">任务列表</div>
                  <v-spacer />
                  <v-select
                    v-model="currentGoalId"
                    :items="goalsList"
                    item-title="title"
                    item-value="id"
                    label="切换目标"
                    density="compact"
                    variant="outlined"
                    style="max-width: 360px"
                    class="mr-2"
                    @update:modelValue="onSelectGoal"
                  />
                  <v-btn variant="tonal" class="mr-2" :disabled="!currentGoalId" @click="acceptTasks">满意</v-btn>
                  <v-btn variant="tonal" color="warning" class="mr-2" :disabled="!currentGoalId" @click="feedbackOpen = true">不满意</v-btn>
                  <v-btn variant="tonal" class="mr-2" :loading="busy" @click="refreshAll">刷新</v-btn>
                  <v-btn color="primary" :loading="busy" @click="autoSchedule">智能排程</v-btn>
                </div>
                <div class="text-caption mt-1" style="opacity:0.75">
                  当前目标：{{ currentGoalTitle || '-' }}（ID {{ currentGoalId || '-' }}）｜当前显示 {{ displayTasks.length }} / {{ tasks.length }}（仅展示前 80 条）
                </div>
              </div>
              <v-list density="comfortable">
                <v-list-item v-for="t in displayTasks" :key="t.id" :title="t.title" :subtitle="t.description" />
              </v-list>
            </div>
          </v-card>
        </v-stepper-window-item>

        <v-stepper-window-item :value="5">
          <v-row>
            <v-col cols="12" md="6">
              <v-card class="pa-4">
                <div class="text-subtitle-1 font-weight-semibold mb-2">排程结果</div>
                <v-alert v-if="!schedules?.length" type="info" variant="tonal">暂无排程记录</v-alert>
                <div v-else class="vibe-scroll">
                  <div class="vibe-scroll-header">
                    <div class="text-caption" style="opacity:0.75">
                      当前显示 {{ displaySchedules.length }} / {{ schedules.length }}（为避免页面卡顿，仅展示前 80 条）
                    </div>
                  </div>
                  <div v-for="s in displaySchedules" :key="s.id" class="mb-2 px-3 py-2">
                    <div class="text-subtitle-2 font-weight-semibold">{{ s.taskTitle || `任务 ${s.taskId}` }}</div>
                    <div class="text-body-2" style="opacity:0.8">{{ fmt(s.startTime) }} - {{ fmt(s.endTime) }}</div>
                  </div>
                </div>
              </v-card>
            </v-col>
            <v-col cols="12" md="6">
              <v-card class="pa-4">
                <div class="text-subtitle-1 font-weight-semibold mb-2">开始打卡</div>
                <div class="text-body-2 mb-3" style="opacity: 0.78">默认对第一条待办任务进行定位打卡（可在“打卡”页面上传证据/查看更多）</div>
                <div class="d-flex justify-end">
                  <v-btn color="primary" :loading="busy" @click="punchFirstTask">一键打卡</v-btn>
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
