<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../plugins/api'

const loading = ref(false)
const error = ref('')
const goalQuery = ref('')
const dashboard = ref(null)
const weather = ref(null)
const today = ref(new Date().toISOString().slice(0, 10))

function fmt(dt) {
  if (!dt) return '-'
  return String(dt).replace('T', ' ').slice(0, 16)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [res, w] = await Promise.all([
      api.get('/user/dashboard'),
      api.get('/user/weather'),
    ])
    dashboard.value = res?.data?.data ?? null
    weather.value = w?.data?.data ?? null
  } catch (e) {
    error.value = '加载失败'
  } finally {
    loading.value = false
  }
}

const filteredGoals = computed(() => {
  const q = String(goalQuery.value || '').trim()
  const list = dashboard.value?.goals ?? []
  if (!q) return list
  return list.filter((g) => String(g?.title || '').includes(q) || String(g?.description || '').includes(q))
})

function tempText() {
  const t = weather.value?.temperature
  if (t === null || t === undefined) return '-'
  return `${t}`
}

function windText() {
  const w = weather.value?.windspeed
  if (w === null || w === undefined) return '-'
  return `${w}`
}

onMounted(load)
</script>

<template>
  <v-row class="mb-2" align="center">
    <v-col cols="12" md="6">
      <v-text-field v-model="goalQuery" label="目标查询" density="comfortable" variant="outlined" />
    </v-col>
    <v-col cols="12" md="3">
      <v-btn color="primary" :loading="loading" @click="load">刷新</v-btn>
    </v-col>
  </v-row>

  <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

  <v-row v-if="dashboard">
    <v-col cols="12" md="3">
      <v-card class="pa-2">
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-bullseye-arrow" class="mr-2" />
          目标
        </v-card-title>
        <v-card-text class="text-h4 font-weight-bold">{{ dashboard.goals?.length ?? 0 }}</v-card-text>
      </v-card>
    </v-col>
    <v-col cols="12" md="3">
      <v-card class="pa-2">
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-fire" class="mr-2" />
          连续打卡
        </v-card-title>
        <v-card-text class="text-h4 font-weight-bold">{{ dashboard.streak ?? 0 }}</v-card-text>
      </v-card>
    </v-col>
    <v-col cols="12" md="6">
      <v-card class="pa-2">
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-weather-partly-cloudy" class="mr-2" />
          今日 {{ today }}
        </v-card-title>
        <v-card-text>
          <div v-if="weather">
            <div class="text-h5 font-weight-bold">{{ weather.summary || '天气' }} {{ tempText() }}°C</div>
            <div class="text-body-2" style="opacity:0.75">风速 {{ windText() }} km/h</div>
          </div>
          <div v-else class="text-body-2" style="opacity:0.7">天气加载中…</div>
        </v-card-text>
      </v-card>
    </v-col>
    <v-col cols="12" md="6">
      <v-card class="pa-2">
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-calendar-clock" class="mr-2" />
          今日空闲时间
        </v-card-title>
        <v-card-text>
          <div v-for="(slot, i) in dashboard.freeTimeSlots ?? []" :key="i">
            {{ slot.start }} - {{ slot.end }}
          </div>
        </v-card-text>
      </v-card>
    </v-col>

    <v-col cols="12" md="6">
      <v-card class="pa-2">
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-timetable" class="mr-2" />
          任务日程
        </v-card-title>
        <v-card-text>
          <div v-for="(s, i) in dashboard.taskSchedules ?? []" :key="i">
            任务 {{ i + 1 }}：{{ fmt(s.startTime) }} - {{ fmt(s.endTime) }}
          </div>
        </v-card-text>
      </v-card>
    </v-col>

    <v-col cols="12">
      <v-card class="pa-2">
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-chart-arc" class="mr-2" />
          目标进度
        </v-card-title>
        <v-card-text>
          <v-alert v-if="!(dashboard.goalProgress?.length)" type="info" variant="tonal">暂无目标进度</v-alert>
          <div v-for="g in dashboard.goalProgress ?? []" :key="g.goalId" class="mb-4">
            <div class="d-flex justify-space-between mb-1">
              <div class="text-subtitle-2 font-weight-semibold">{{ g.title }}</div>
              <div class="text-caption" style="opacity:0.75">{{ g.doneTasks }}/{{ g.totalTasks }}（{{ g.percent }}%）</div>
            </div>
            <v-progress-linear :model-value="g.percent" height="10" rounded color="primary" />
          </div>
        </v-card-text>
      </v-card>
    </v-col>

    <v-col cols="12">
      <v-card class="pa-2">
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-magnify" class="mr-2" />
          目标查询结果
        </v-card-title>
        <v-card-text>
          <v-alert v-if="!(filteredGoals?.length)" type="info" variant="tonal">暂无匹配目标</v-alert>
          <v-list v-else density="compact">
            <v-list-item v-for="g in filteredGoals" :key="g.id" :title="g.title" :subtitle="g.description" />
          </v-list>
        </v-card-text>
      </v-card>
    </v-col>
  </v-row>
</template>
