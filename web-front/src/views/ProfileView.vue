<script setup>
import { ref, onMounted } from 'vue'
import api from '../plugins/api'

const portrait = ref(null)
const loading = ref(false)
const error = ref('')
const showComputation = ref(false)

const metricHelp = {
  onTimeRate: '实际打卡时间与排程开始时间偏差 ≤ 10 分钟即算"准时"。准时率 = 准时次数 ÷ 可匹配的打卡次数。',
  avgDelay: '统计所有迟到的打卡（打卡时间晚于排程时间），取平均延迟分钟数。准时或提前到达不计入。',
  completionRate: '近 7 天排程中状态为"已完成"的比例。反映你按计划执行的完成度。',
  streak: '从今天往前回溯，最多连续打卡的天数。中断一天即重置。',
  morningScore: '分析 10:00 前的打卡和排程占比，得分越高越偏晨型。打卡权重 60%，排程权重 40%。',
  focusAvg: '统计有学习时长的打卡记录，取实际学习分钟数的均值。无打卡时用已完成排程时长降级估算。',
  procrastination: '综合延迟程度(45%)、不准时率(35%)、未完成率(20%)。越高越拖延，冷启动(<3次)用完成率估算。',
  recommendation: '根据专注时长区间推荐排程参数：<40→30min，40~69→45min，≥70→60min。新手或准时率低时降低上限。',
}

function metricIcon(key) {
  const map = {
    onTimeRate: 'mdi-clock-check-outline',
    avgDelay: 'mdi-timer-sand',
    completionRate: 'mdi-check-circle-outline',
    streak: 'mdi-fire',
    morningScore: 'mdi-weather-sunny',
    focusAvg: 'mdi-brain',
    procrastination: 'mdi-progress-clock',
    recommendation: 'mdi-tune',
  }
  return map[key] || 'mdi-calculator'
}

const inputLabels = {
  onTimeCount: '准时次数', matchedCount: '匹配次数',
  lateCount: '迟到次数', totalDelayMinutes: '总延迟(分钟)',
  totalSchedules: '总排程数', doneCount: '已完成数',
  morningPunchCount: '晨间打卡', totalPunchRecords: '总打卡数',
  morningScheduleCount: '晨间排程', totalDurationMinutes: '总学习分钟',
  punchCount: '打卡次数',
  delayScore: '延迟分', onTimeRate: '准时率', completionRate: '完成率',
  focusAvgInput: '专注均值', streakInput: '连续打卡', onTimeRateInput: '准时率',
  localResult: '本地计算结果',
}

function inputLabel(k) {
  return inputLabels[k] || k
}

function inputHelp(k) {
  const h = {
    onTimeCount: '打卡时间与排程偏差 ≤ 10 分钟的次数',
    matchedCount: '打卡记录能匹配到对应排程的次数（偏差 ≤ 180 分钟）',
    lateCount: '打卡时间晚于排程开始时间的次数',
    totalDelayMinutes: '所有迟到的延迟分钟数之和',
    totalSchedules: '近 7 天排程总数',
    doneCount: '状态为"已完成"的排程数',
    morningPunchCount: '10:00 之前的打卡次数',
    totalPunchRecords: '近 7 天打卡记录总数',
    morningScheduleCount: '开始时间在 10:00 之前的排程数',
    totalDurationMinutes: '所有打卡的 durationSeconds 折算为分钟数之和',
    punchCount: '有有效时长的打卡次数',
    delayScore: '延迟分钟数 / 180，上限 1.0',
    focusAvgInput: '当前习惯画像中的平均专注时长',
    streakInput: '当前连续打卡天数',
    onTimeRateInput: '当前准时率',
    localResult: '后端本地公式算出的原始值，AI 可能在此基础上微调',
  }
  return h[k] || '该项指标的计算输入'
}

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

      <!-- 计算明细 -->
      <v-col v-if="portrait?.computation && Object.keys(portrait.computation).length" cols="12">
        <v-card class="pa-2">
          <v-card-title class="d-flex align-center" style="cursor:pointer" @click="showComputation = !showComputation">
            <v-icon :icon="showComputation ? 'mdi-chevron-up' : 'mdi-chevron-down'" class="mr-2" />
            计算明细
            <v-spacer />
            <v-chip size="x-small" variant="tonal">{{ Object.keys(portrait.computation).length }} 项指标</v-chip>
          </v-card-title>
          <v-expand-transition>
            <v-card-text v-show="showComputation">
              <div class="comp-grid">
                <div v-for="(item, key) in portrait.computation" :key="key" class="comp-row">
                  <!-- 指标名 + 帮助图标 -->
                  <div class="comp-label">
                    <v-icon :icon="metricIcon(key)" size="18" class="mr-1" />
                    <span class="text-body-2 font-weight-semibold">{{ item.label }}</span>
                    <v-tooltip location="top" max-width="320">
                      <template #activator="{ props: tp }">
                        <v-icon v-bind="tp" icon="mdi-help-circle-outline" size="14" class="ml-1 comp-help" />
                      </template>
                      <span>{{ metricHelp[key] || '暂无说明' }}</span>
                    </v-tooltip>
                  </div>

                  <!-- 输入数据 -->
                  <div class="comp-inputs">
                    <template v-if="item.inputs && Object.keys(item.inputs).length">
                      <v-tooltip v-for="(v, k) in item.inputs" :key="k" location="top" max-width="280">
                        <template #activator="{ props: tp }">
                          <span v-bind="tp" class="comp-chip">{{ inputLabel(k) }}&nbsp;<strong>{{ v }}</strong></span>
                        </template>
                        <span>{{ inputHelp(k) }}</span>
                      </v-tooltip>
                    </template>
                    <span v-else class="comp-chip comp-chip-dim">直接获取</span>
                  </div>

                  <!-- 公式与结果 -->
                  <div class="comp-result">
                    <v-tooltip location="top" max-width="360">
                      <template #activator="{ props: tp }">
                        <span v-bind="tp" class="comp-formula">{{ item.formula }}</span>
                      </template>
                      <span>计算公式</span>
                    </v-tooltip>
                    <v-icon size="16" class="mx-2" style="opacity:0.4">mdi-arrow-right</v-icon>
                    <span class="comp-value">{{ item.result }}</span>
                  </div>
                </div>
              </div>
            </v-card-text>
          </v-expand-transition>
        </v-card>
      </v-col>
    </v-row>
  </div>
</template>

<style scoped>
.portrait-view {
  padding: 16px;
}

.comp-grid {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.comp-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-radius: 12px;
  background: rgba(var(--v-theme-on-surface), 0.03);
  transition: background 0.15s;
  flex-wrap: wrap;
}

.comp-row:hover {
  background: rgba(var(--v-theme-on-surface), 0.06);
}

.comp-label {
  display: flex;
  align-items: center;
  min-width: 130px;
  flex-shrink: 0;
}

.comp-help {
  opacity: 0.35;
  cursor: help;
  transition: opacity 0.15s;
}

.comp-help:hover {
  opacity: 0.8;
}

.comp-inputs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  flex: 1;
  min-width: 0;
}

.comp-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 8px;
  font-size: 12px;
  background: rgba(var(--v-theme-on-surface), 0.07);
  border: 1px solid rgba(var(--v-theme-on-surface), 0.1);
  cursor: default;
  white-space: nowrap;
  transition: border-color 0.15s;
}

.comp-chip:hover {
  border-color: rgba(var(--v-theme-primary), 0.35);
}

.comp-chip strong {
  color: rgb(var(--v-theme-primary));
}

.comp-chip-dim {
  opacity: 0.5;
  font-style: italic;
}

.comp-result {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  margin-left: auto;
}

.comp-formula {
  font-size: 12px;
  opacity: 0.55;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: help;
}

.comp-value {
  font-size: 16px;
  font-weight: 700;
  color: rgb(var(--v-theme-primary));
  white-space: nowrap;
}
</style>