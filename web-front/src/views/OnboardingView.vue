<script setup>
import { computed, ref } from 'vue'
import DefaultLayout from '../layouts/DefaultLayout.vue'
import api from '../plugins/api'
import { useAuthStore } from '../stores/auth'
import { useRouter } from 'vue-router'
import { useNotifyStore } from '../stores/notify'

const auth = useAuthStore()
const router = useRouter()
const notify = useNotifyStore()

const step = ref(1)
const busy = ref(false)
const error = ref('')

const file = ref(null)
const goalText = ref('')
const date = ref('')
const free = ref([])
const schedules = ref([])
const planNote = ref('')
const planning = ref(false)
const planningProgress = ref(0)
const planningMessage = ref('')

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

function fmt(dt) {
  if (!dt) return '-'
  return String(dt).replace('T', ' ').slice(0, 16)
}

async function loadFree() {
  if (!date.value) return
  const res = await api.get('/user/schedule/free-time', { params: { date: date.value } })
  free.value = res?.data?.data ?? []
}

async function uploadSchedule() {
  if (!file.value) return
  busy.value = true
  error.value = ''
  try {
    const fd = new FormData()
    fd.append('file', file.value)
    await api.post('/user/schedule/import', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
    await auth.fetchMe()
    const t = new Date()
    date.value = `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`
    await loadFree()
    step.value = 2
  } catch (e) {
    error.value = e?.response?.data?.message || e?.message || '课表上传失败'
  } finally {
    busy.value = false
  }
}

async function createGoal() {
  if (!goalText.value) return
  busy.value = true
  error.value = ''
  try {
    await api.post('/user/goals/ai', goalText.value, { headers: { 'Content-Type': 'text/plain' } })
    step.value = 3
  } catch (e) {
    error.value = e?.response?.data?.message || e?.message || '目标生成失败'
  } finally {
    busy.value = false
  }
}

async function generatePlan() {
  if (!date.value) return
  busy.value = true
  error.value = ''
  try {
    planning.value = true
    planningProgress.value = 5
    planningMessage.value = '已启动后台排程'

    const startRes = await api.post('/user/schedule/daily-plan/jobs', { date: date.value, mode: 'replace' }, { timeout: 15000 })
    const jobId = startRes?.data?.data?.jobId
    if (!jobId) {
      throw new Error('启动排程任务失败')
    }

    notify.info('已启动后台智能排程，可继续其他操作')

    for (let i = 0; i < 180; i++) {
      await new Promise((r) => setTimeout(r, 2000))
      const st = await api.get(`/user/schedule/daily-plan/jobs/${jobId}`)
      const data = st?.data?.data ?? null
      const progress = Number(data?.progress)
      if (Number.isFinite(progress)) {
        planningProgress.value = Math.max(0, Math.min(100, progress))
      }
      planningMessage.value = data?.message || data?.stage || planningMessage.value
      if (data?.status === 'DONE') {
        const result = data?.result ?? null
        planNote.value = result?.note ?? ''
        schedules.value = result?.schedules ?? []
        planningProgress.value = 100
        planningMessage.value = '已完成排程并写入日程'
        planning.value = false
        await loadFree()
        step.value = 4
        return
      }
      if (data?.status === 'FAILED') {
        throw new Error(data?.error || '生成计划失败')
      }
    }
    throw new Error('排程超时，请稍后重试')
  } catch (e) {
    error.value = e?.response?.data?.message || e?.message || '生成计划失败'
    planning.value = false
  } finally {
    busy.value = false
  }
}

function goDashboard() {
  router.push('/')
}
</script>

<template>
  <DefaultLayout>
    <v-card class="pa-4">
      <div class="text-h6 mb-2">新用户向导</div>
      <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

      <v-stepper v-model="step" elevation="0">
        <v-stepper-header>
          <v-stepper-item :value="1" title="上传课表" />
          <v-divider />
          <v-stepper-item :value="2" title="生成任务" />
          <v-divider />
          <v-stepper-item :value="3" title="生成计划" />
          <v-divider />
          <v-stepper-item :value="4" title="完成" />
        </v-stepper-header>

        <v-stepper-window>
          <v-stepper-window-item :value="1">
            <v-card class="pa-4" variant="tonal">
              <v-file-input v-model="file" label="选择课表文件（.ics/.xlsx/.csv）" prepend-icon="mdi-upload" variant="outlined" />
              <div class="d-flex justify-end mt-2">
                <v-btn color="primary" :loading="busy" @click="uploadSchedule">上传并解析</v-btn>
              </div>
            </v-card>
          </v-stepper-window-item>

          <v-stepper-window-item :value="2">
            <v-card class="pa-4" variant="tonal">
              <v-textarea v-model="goalText" label="输入学习目标（将由大模型拆解任务）" variant="outlined" rows="3" auto-grow />
              <div class="d-flex justify-end mt-2">
                <v-btn color="primary" :loading="busy" @click="createGoal">生成任务</v-btn>
              </div>
            </v-card>
          </v-stepper-window-item>

          <v-stepper-window-item :value="3">
            <v-card class="pa-4" variant="tonal">
              <v-select v-model="date" :items="dateOptions" label="选择日期" variant="outlined" />
              <v-alert v-if="!free?.length" type="info" variant="tonal" class="mt-2">该日暂无空闲时间，请先更新课表或换一天</v-alert>
              <div v-else class="mt-2">
                <div class="text-caption mb-1" style="opacity:0.75">空闲时间（严禁与课表冲突）</div>
                <div v-for="(f, i) in free" :key="i" class="text-body-2">{{ fmt(f.start) }} - {{ fmt(f.end) }}</div>
              </div>
              <div v-if="planning" class="mt-3">
                <div class="text-body-2 mb-2" style="opacity:0.85">{{ planningMessage }}</div>
                <v-progress-linear :model-value="planningProgress" height="8" rounded color="primary" />
                <div class="text-caption mt-1" style="opacity:0.7">{{ planningProgress }}%</div>
              </div>
              <div class="d-flex justify-end mt-3">
                <v-btn color="primary" :loading="busy" :disabled="planning" @click="generatePlan">生成当日计划</v-btn>
              </div>
            </v-card>
          </v-stepper-window-item>

          <v-stepper-window-item :value="4">
            <v-card class="pa-4" variant="tonal">
              <v-alert v-if="planNote" type="info" variant="tonal" class="mb-3">{{ planNote }}</v-alert>
              <v-alert v-if="!schedules?.length" type="info" variant="tonal" class="mb-3">暂无排程结果（可返回上一步调整日期再生成）</v-alert>
              <div v-else>
                <div class="text-caption mb-1" style="opacity:0.75">当日排程</div>
                <div v-for="s in schedules" :key="s.id || `${s.taskId}-${s.startTime}`" class="text-body-2">
                  {{ s.taskTitle || `任务 ${s.taskId}` }}：{{ fmt(s.startTime) }} - {{ fmt(s.endTime) }}
                </div>
              </div>
              <div class="d-flex justify-end mt-3">
                <v-btn color="primary" @click="goDashboard">进入首页</v-btn>
              </div>
            </v-card>
          </v-stepper-window-item>
        </v-stepper-window>
      </v-stepper>
    </v-card>
  </DefaultLayout>
</template>
