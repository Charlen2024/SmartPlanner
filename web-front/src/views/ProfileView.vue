<script setup>
import { ref, onMounted } from 'vue'
import api from '../plugins/api'

const portrait = ref(null)
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.get('/user/portrait')
    portrait.value = res?.data?.data ?? null
  } catch (e) {
    error.value = e?.response?.data?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function recompute() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.post('/user/portrait/recompute')
    portrait.value = res?.data?.data ?? null
  } catch (e) {
    error.value = e?.response?.data?.message || e?.message || '重新分析失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="portrait-view">
    <v-row class="mb-3" align="center">
      <v-col cols="12" md="6">
        <div class="text-h6 font-weight-semibold">学习画像与建议</div>
        <div class="text-body-2" style="opacity:0.75">基于你近 7 天的排程与打卡行为自动生成</div>
      </v-col>
      <v-col cols="12" md="6" class="d-flex justify-end">
        <v-btn variant="tonal" class="mr-2" :loading="loading" @click="recompute">重新分析</v-btn>
        <v-btn variant="tonal" :loading="loading" @click="load">刷新</v-btn>
      </v-col>
    </v-row>

    <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

    <v-row>
      <v-col cols="12" md="4">
        <v-card class="pa-2">
          <v-card-title class="d-flex align-center">
            <v-icon icon="mdi-clock-check-outline" class="mr-2" />
            准时率
          </v-card-title>
          <v-card-text>
            <div class="text-h4 font-weight-bold">{{ Math.round((portrait?.insights?.onTimeRate ?? 0) * 100) }}%</div>
            <v-progress-linear :model-value="Math.round((portrait?.insights?.onTimeRate ?? 0) * 100)" height="10" rounded color="primary" />
          </v-card-text>
        </v-card>
      </v-col>
      <v-col cols="12" md="4">
        <v-card class="pa-2">
          <v-card-title class="d-flex align-center">
            <v-icon icon="mdi-timer-sand" class="mr-2" />
            平均延迟
          </v-card-title>
          <v-card-text>
            <div class="text-h4 font-weight-bold">{{ Math.round(portrait?.insights?.avgDelayMinutes ?? 0) }} min</div>
            <div class="text-body-2" style="opacity:0.7">相对排程开始时间的平均延迟</div>
          </v-card-text>
        </v-card>
      </v-col>
      <v-col cols="12" md="4">
        <v-card class="pa-2">
          <v-card-title class="d-flex align-center">
            <v-icon icon="mdi-fire" class="mr-2" />
            连续打卡
          </v-card-title>
          <v-card-text>
            <div class="text-h4 font-weight-bold">{{ portrait?.insights?.streak ?? 0 }}</div>
          </v-card-text>
        </v-card>
      </v-col>

      <v-col cols="12">
        <v-card class="pa-2">
          <v-card-title class="d-flex align-center">
            <v-icon icon="mdi-robot-outline" class="mr-2" />
            AI 建议
          </v-card-title>
          <v-card-text>
            <v-alert v-if="!(portrait?.tips?.length)" type="info" variant="tonal">暂无建议</v-alert>
            <v-list v-else density="comfortable">
              <v-list-item v-for="(t, i) in portrait.tips" :key="i" :title="t" />
            </v-list>
          </v-card-text>
        </v-card>
      </v-col>

      <v-col cols="12">
        <v-card class="pa-2">
          <v-card-title class="d-flex align-center">
            <v-icon icon="mdi-tune" class="mr-2" />
            推荐排程参数
          </v-card-title>
          <v-card-text>
            <v-alert v-if="!portrait?.recommendation" type="info" variant="tonal">暂无推荐</v-alert>
            <v-row v-else>
              <v-col cols="12" md="4">
                <div class="text-subtitle-2 font-weight-semibold mb-1">专注时长</div>
                <div class="text-h5 font-weight-bold">{{ portrait.recommendation.focusMinutes }} min</div>
              </v-col>
              <v-col cols="12" md="4">
                <div class="text-subtitle-2 font-weight-semibold mb-1">休息时长</div>
                <div class="text-h5 font-weight-bold">{{ portrait.recommendation.breakMinutes }} min</div>
              </v-col>
              <v-col cols="12" md="4">
                <div class="text-subtitle-2 font-weight-semibold mb-1">当日上限</div>
                <div class="text-h5 font-weight-bold">{{ portrait.recommendation.maxDailyMinutes }} min</div>
              </v-col>
            </v-row>
          </v-card-text>
        </v-card>
      </v-col>

      <v-col cols="12">
        <v-card class="pa-2">
          <v-card-title class="d-flex align-center">
            <v-icon icon="mdi-account-circle-outline" class="mr-2" />
            画像数据
          </v-card-title>
          <v-card-text>
            <v-row>
              <v-col cols="12" md="4">
                <div class="text-subtitle-2 font-weight-semibold mb-1">晨型倾向</div>
                <v-progress-linear :model-value="portrait?.habits?.morningPersonScore ?? 0" height="10" rounded color="secondary" />
                <div class="text-caption mt-1" style="opacity:0.75">{{ portrait?.habits?.morningPersonScore ?? 0 }}</div>
              </v-col>
              <v-col cols="12" md="4">
                <div class="text-subtitle-2 font-weight-semibold mb-1">平均专注时长</div>
                <v-progress-linear :model-value="Math.min(portrait?.habits?.focusDurationAvg ?? 0, 120)" height="10" rounded color="secondary" />
                <div class="text-caption mt-1" style="opacity:0.75">{{ portrait?.habits?.focusDurationAvg ?? 0 }} min</div>
              </v-col>
              <v-col cols="12" md="4">
                <div class="text-subtitle-2 font-weight-semibold mb-1">拖延指数</div>
                <v-progress-linear :model-value="Math.min((portrait?.habits?.procrastinationIndex ?? 0) * 100, 100)" height="10" rounded color="secondary" />
                <div class="text-caption mt-1" style="opacity:0.75">{{ Math.round((portrait?.habits?.procrastinationIndex ?? 0) * 100) }}%</div>
              </v-col>
            </v-row>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>
  </div>
</template>

<style scoped>
.portrait-view {
  padding: 16px;
}
</style>