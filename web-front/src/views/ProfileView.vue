<script setup>
import { ref, onActivated, onMounted, computed } from 'vue'
import api from '../plugins/api'

const portrait = ref(null)
const loading = ref(false)
const error = ref('')
const showComputation = ref(false)

const topMetrics = computed(() => {
  const trends = portrait.value?.trends || {}
  return [
    {
      key: 'onTimeRate',
      label: '准时率',
      icon: 'mdi-clock-check-outline',
      value: Math.round((portrait.value?.insights?.onTimeRate ?? 0) * 100),
      suffix: '%',
      color: 'success',
      trend: trends.onTimeRate,
    },
    {
      key: 'completionRate',
      label: '完成率',
      icon: 'mdi-check-circle-outline',
      value: Math.round((portrait.value?.insights?.completionRate ?? 0) * 100),
      suffix: '%',
      color: 'primary',
      trend: trends.completionRate,
    },
    {
      key: 'streak',
      label: '连续打卡',
      icon: 'mdi-fire',
      value: portrait.value?.insights?.streak ?? 0,
      suffix: ' 天',
      color: 'warning',
      trend: trends.streak,
    },
    {
      key: 'avgDelay',
      label: '平均延迟',
      icon: 'mdi-timer-sand',
      value: Math.round(portrait.value?.insights?.avgDelayMinutes ?? 0),
      suffix: ' 分钟',
      color: 'error',
    },
  ]
})

const habitBars = computed(() => [
  {
    key: 'morningPersonScore',
    label: '晨型倾向',
    icon: 'mdi-weather-sunny',
    value: portrait.value?.habits?.morningPersonScore ?? 0,
    max: 100,
    suffix: '',
    hint: '越高越偏晨型',
    color: 'warning',
  },
  {
    key: 'focusDurationAvg',
    label: '平均专注',
    icon: 'mdi-brain',
    value: portrait.value?.habits?.focusDurationAvg ?? 0,
    max: 120,
    suffix: ' 分钟',
    hint: '单次学习的平均时长',
    color: 'primary',
  },
  {
    key: 'procrastinationIndex',
    label: '拖延指数',
    icon: 'mdi-progress-clock',
    value: Math.round((portrait.value?.habits?.procrastinationIndex ?? 0) * 100),
    max: 100,
    suffix: '%',
    hint: '越低越好',
    color: 'error',
  },
])

const cardCols = computed(() => portrait.value?.bestTimeSlots?.length ? 4 : 6)

const metricHelp = {
  onTimeRate: '实际打卡时间与排程开始时间偏差 ≤ 10 分钟即算"准时"。准时率 = 准时次数 ÷ 可匹配的打卡次数。',
  avgDelay: '统计所有迟到的打卡（打卡时间晚于排程时间），取平均延迟分钟数。准时或提前到达不计入。',
  completionRate: '近 7 天排程中状态为"已完成"的比例。反映你按计划执行的完成度。',
  streak: '从今天往前回溯，最多连续打卡的天数。中断一天即重置。',
  morningScore: '分析 10:00 前的打卡和排程占比，得分越高越偏晨型。打卡权重 60%，排程权重 40%。',
  focusAvg: '统计有学习时长的打卡记录，取实际学习分钟数的均值。无打卡时用已完成排程时长降级估算。',
  procrastination: '综合延迟程度(45%)、不准时率(35%)、未完成率(20%)。越高越拖延，冷启动(<3次)用完成率估算。',
  recommendation: '专注=连续映射(avg×0.8→clamp[25,90])−拖延罚分; 休息=专注×0.25比例缩放; 上限=完成率分档−拖延罚分, 连续打卡<2封顶150。',
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

function metricColor(key) {
  const map = {
    onTimeRate: 'success',
    avgDelay: 'error',
    completionRate: 'primary',
    streak: 'warning',
    morningScore: 'warning',
    focusAvg: 'primary',
    procrastination: 'error',
    recommendation: 'primary',
  }
  return map[key] || 'primary'
}

const inputLabels = {
  onTimeCount: '准时次数', matchedCount: '匹配次数',
  lateCount: '迟到次数', totalDelayMinutes: '总延迟(分钟)',
  totalSchedules: '总排程数', doneCount: '已完成数',
  morningPunchCount: '晨间打卡', totalPunchRecords: '总打卡数',
  morningScheduleCount: '晨间排程', totalDurationMinutes: '总学习分钟',
  punchCount: '打卡次数', streak: '当前连续',
  delayScore: '延迟÷180', onTimeRate: '准时率', completionRate: '完成率',
  focusAvgInput: '平均专注时长', streakInput: '连续打卡',
  procrastinationInput: '拖延指数', completionRateInput: '完成率',
  focusBase: '专注基础值', focusPenalty: '拖延罚分',
  completionTier: '完成率分档', procPenalty: '拖延罚分(上限)',
  streakCapped: '新手封顶', localResult: '本地计算结果',
}

function inputLabel(k) { return inputLabels[k] || k }

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
    streak: '从今天向前回溯的连续打卡天数',
    delayScore: '平均延迟分钟 ÷ 180，上限 1.0',
    focusAvgInput: '当前习惯画像中的平均专注时长',
    streakInput: '当前连续打卡天数',
    procrastinationInput: '当前习惯画像中的拖延指数（0~1）',
    completionRateInput: '近 7 天排程完成率',
    focusBase: 'avg × 0.8 后四舍五入到 5 的倍数，再 clamp[25,90]',
    focusPenalty: '拖延 > 0.4 扣 5min，> 0.6 扣 10min',
    completionTier: '完成率 < 30% → 120, 30%~60% → 180, ≥ 60% → 240',
    procPenalty: '拖延 > 0.5 扣 30min，> 0.7 扣 60min',
    streakCapped: '连续打卡 < 2 天时上限封顶 150min',
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

let _ready = false
onMounted(load)
onActivated(() => { if (_ready) load(); _ready = true })
</script>

<template>
  <v-alert v-if="error" type="error" variant="tonal" class="mb-4" density="compact">{{ error }}</v-alert>

  <!-- ====== Page Header ====== -->
  <div class="d-flex align-center flex-wrap ga-3 mb-4">
    <div>
      <div class="text-h5 font-weight-bold">学习画像与建议</div>
      <div class="text-body-2 text-medium-emphasis">基于近 7 天排程与打卡行为自动生成</div>
    </div>
    <v-spacer />
    <v-btn variant="tonal" size="small" :loading="loading" @click="recompute">
      <v-icon icon="mdi-refresh-auto" size="18" class="mr-1" />重新分析
    </v-btn>
    <v-btn variant="text" size="small" :loading="loading" @click="load">
      <v-icon icon="mdi-refresh" size="18" class="mr-1" />刷新
    </v-btn>
  </div>

  <!-- ====== Top Metrics ====== -->
  <v-row v-if="!loading && portrait" class="mb-4">
    <v-col v-for="m in topMetrics" :key="m.key" cols="6" md="3">
      <v-card class="metric-card">
        <div class="d-flex align-center ga-1 mb-2">
          <v-icon :icon="m.icon" size="18" :color="m.color" />
          <span class="text-caption font-weight-medium text-medium-emphasis">{{ m.label }}</span>
        </div>
        <div class="d-flex align-baseline ga-2">
          <span class="metric-value">{{ m.value }}</span>
          <span class="text-caption text-medium-emphasis">{{ m.suffix }}</span>
          <span v-if="m.trend && m.trend.direction !== 'flat'" class="trend-badge" :class="'trend--' + m.trend.direction">
            <v-icon :icon="m.trend.direction === 'up' ? 'mdi-arrow-up-thin' : 'mdi-arrow-down-thin'" size="12" />
            {{ Math.abs(m.trend.delta) }}{{ m.trend.unit }}
          </span>
        </div>
        <v-progress-linear
          v-if="m.key !== 'avgDelay'"
          :model-value="m.value"
          :max="m.key === 'streak' ? Math.max(m.value, 7) : 100"
          height="3"
          rounded
          :color="m.color"
          class="mt-2"
        />
        <div v-else class="text-caption mt-2" style="opacity:0.5">越少越好</div>
      </v-card>
    </v-col>
  </v-row>

  <!-- Loading state -->
  <div v-if="loading" class="mb-4">
    <v-progress-linear indeterminate height="6" rounded color="primary" class="mb-2" />
    <div class="text-caption text-center text-medium-emphasis">正在加载画像数据…</div>
  </div>

  <template v-else-if="portrait">
  <!-- ====== AI Tips (prominent) ====== -->
  <v-card class="mb-4 tips-card">
    <v-card-title class="d-flex align-center pb-1">
      <v-icon icon="mdi-robot-outline" class="mr-2" color="primary" />
      <span class="text-h6">AI 学习建议</span>
      <v-spacer />
      <v-chip v-if="portrait?.tips?.length" size="x-small" variant="tonal" color="primary">
        {{ portrait.tips.length }} 条建议
      </v-chip>
    </v-card-title>
    <v-card-text>
      <v-alert v-if="!(portrait?.tips?.length)" type="info" variant="tonal" density="compact">
        完成 3 次以上计时打卡后，AI 将为你生成个性化学习建议
      </v-alert>
      <div v-else class="tips-list">
        <div v-for="(t, i) in portrait.tips" :key="i" class="tip-item">
          <div class="tip-num">{{ i + 1 }}</div>
          <div class="tip-text">{{ t }}</div>
        </div>
      </div>
    </v-card-text>
  </v-card>

  <v-row class="mb-4">
    <v-col cols="12" :md="cardCols">
      <v-card class="h-100">
        <v-card-title class="d-flex align-center pb-1">
          <v-icon icon="mdi-tune" class="mr-2" />
          <span class="text-body-1 font-weight-semibold">推荐排程参数</span>
        </v-card-title>
        <v-divider />
        <v-card-text>
          <v-alert v-if="!portrait?.recommendation" type="info" variant="tonal" density="compact">
            暂无推荐，请先完成一些打卡
          </v-alert>
          <div v-else class="rec-rows">
            <div class="rec-row">
              <v-icon icon="mdi-timer-outline" size="20" color="primary" class="mr-3" />
              <span class="text-body-2 text-medium-emphasis rec-label">专注时长</span>
              <v-spacer />
              <span class="text-body-2 font-weight-bold">{{ portrait.recommendation.focusMinutes }} 分钟</span>
            </div>
            <v-divider class="my-2" />
            <div class="rec-row">
              <v-icon icon="mdi-coffee-outline" size="20" color="warning" class="mr-3" />
              <span class="text-body-2 text-medium-emphasis rec-label">休息时长</span>
              <v-spacer />
              <span class="text-body-2 font-weight-bold">{{ portrait.recommendation.breakMinutes }} 分钟</span>
            </div>
            <v-divider class="my-2" />
            <div class="rec-row">
              <v-icon icon="mdi-calendar-check-outline" size="20" color="success" class="mr-3" />
              <span class="text-body-2 text-medium-emphasis rec-label">当日上限</span>
              <v-spacer />
              <span class="text-body-2 font-weight-bold">{{ portrait.recommendation.maxDailyMinutes }} 分钟</span>
            </div>
          </div>
        </v-card-text>
      </v-card>
    </v-col>

    <v-col v-if="portrait?.bestTimeSlots?.length" cols="12" :md="cardCols">
      <v-card class="h-100">
        <v-card-title class="d-flex align-center pb-1">
          <v-icon icon="mdi-lightning-bolt-outline" class="mr-2" color="warning" />
          <span class="text-body-1 font-weight-semibold">最佳时段</span>
        </v-card-title>
        <v-divider />
        <v-card-text>
          <div v-for="(slot, i) in portrait.bestTimeSlots" :key="i" class="time-slot-row">
            <div class="d-flex align-center">
              <span class="time-slot-rank" :class="'rank-' + (i + 1)">{{ i + 1 }}</span>
              <span class="text-body-2 font-weight-medium ml-2">{{ slot.label }}</span>
              <v-spacer />
              <span class="text-body-2 font-weight-bold">{{ slot.avgFocusMin }} 分钟</span>
            </div>
            <div class="text-caption text-medium-emphasis ml-6">平均专注，{{ slot.count }} 次打卡</div>
          </div>
        </v-card-text>
      </v-card>
    </v-col>

    <v-col cols="12" :md="cardCols">
      <v-card class="h-100">
        <v-card-title class="d-flex align-center pb-1">
          <v-icon icon="mdi-chart-bar" class="mr-2" />
          <span class="text-body-1 font-weight-semibold">行为画像</span>
        </v-card-title>
        <v-divider />
        <v-card-text>
          <div v-for="bar in habitBars" :key="bar.key" class="habit-bar mb-3">
            <div class="d-flex align-center justify-space-between mb-1">
              <div class="d-flex align-center ga-1">
                <v-icon :icon="bar.icon" size="16" :color="bar.color" />
                <span class="text-body-2 font-weight-medium">{{ bar.label }}</span>
              </div>
              <div class="d-flex align-center ga-1">
                <span class="text-body-2 font-weight-bold">{{ bar.value }}{{ bar.suffix }}</span>
                <span class="text-caption text-medium-emphasis">/ {{ bar.max }}{{ bar.suffix }}</span>
              </div>
            </div>
            <v-progress-linear
              :model-value="bar.value"
              :max="bar.max"
              height="8"
              rounded
              :color="bar.color"
            />
            <div class="text-caption mt-1 text-medium-emphasis">{{ bar.hint }}</div>
          </div>
        </v-card-text>
      </v-card>
    </v-col>
  </v-row>

  <!-- ====== Computation Details ====== -->
  <v-card v-if="portrait?.computation && Object.keys(portrait.computation).length" class="mt-4">
    <v-card-title class="d-flex align-center" style="cursor:pointer" @click="showComputation = !showComputation">
      <v-icon :icon="showComputation ? 'mdi-chevron-up' : 'mdi-chevron-down'" class="mr-2" />
      <span class="text-body-2 font-weight-semibold">计算明细</span>
      <v-spacer />
      <v-chip size="x-small" variant="tonal">{{ Object.keys(portrait.computation).length }} 项指标</v-chip>
    </v-card-title>
    <v-expand-transition>
      <div v-show="showComputation">
        <v-divider />
        <v-card-text>
          <div class="comp-grid">
            <div v-for="(item, key) in portrait.computation" :key="key" class="comp-card rounded-lg">
              <div class="comp-card-inner">
                <!-- Header -->
                <div class="comp-header">
                  <div class="d-flex align-center ga-2">
                    <v-icon :icon="metricIcon(key)" size="20" :color="metricColor(key)" />
                    <span class="text-body-2 font-weight-semibold">{{ item.label }}</span>
                    <v-tooltip location="top" max-width="320">
                      <template #activator="{ props: tp }">
                        <v-icon v-bind="tp" icon="mdi-help-circle-outline" size="14" class="comp-help" />
                      </template>
                      <span>{{ metricHelp[key] || '暂无说明' }}</span>
                    </v-tooltip>
                  </div>
                  <span class="comp-result-value">{{ item.result }}</span>
                </div>

                <!-- Recommendation: visual decision flow -->
                <template v-if="key === 'recommendation'">
                  <!-- New continuous model (focusBase present in inputs) -->
                  <template v-if="item.inputs.focusBase !== undefined">
                  <div class="rec-flow">
                    <div class="rec-step">
                      <v-icon icon="mdi-timer-outline" size="16" color="primary" class="mr-2" />
                      <div class="rec-step-body">
                        <div class="text-caption text-medium-emphasis">专注时长 · 连续映射</div>
                        <div class="text-caption mt-1">
                          (<strong>{{ item.inputs.focusAvgInput }}</strong> × 0.8 ÷ 5) 四舍五入 × 5 = <strong>{{ item.inputs.focusBase }} 分钟</strong>，限制 [25, 90]
                        </div>
                        <div class="rec-rule mt-1">
                          <span class="rec-chip" :class="{ 'rec-chip-active': item.inputs.focusPenalty === 0 }">拖延 ≤ 0.4 · 无调整</span>
                          <span class="rec-chip" :class="{ 'rec-chip-active': item.inputs.focusPenalty === 5 }">拖延 0.4~0.6 · −5分钟</span>
                          <span class="rec-chip" :class="{ 'rec-chip-active': item.inputs.focusPenalty === 10 }">拖延 > 0.6 · −10分钟</span>
                        </div>
                        <div class="text-caption mt-1">
                          当前拖延 <strong>{{ item.inputs.procrastinationInput }}</strong>
                          <span v-if="item.inputs.focusPenalty === 0">≤ 0.4，专注不变</span>
                          <span v-else>→ 专注 −<strong>{{ item.inputs.focusPenalty }} 分钟</strong></span>
                        </div>
                      </div>
                    </div>

                    <v-divider class="my-2" />

                    <div class="rec-step">
                      <v-icon icon="mdi-coffee-outline" size="16" color="warning" class="mr-2" />
                      <div class="rec-step-body">
                        <div class="text-caption text-medium-emphasis">休息时长 · 比例缩放</div>
                        <div class="text-caption mt-1">
                          (专注 × 0.25 ÷ 5) 四舍五入 × 5，限制 [5, 25]
                        </div>
                        <div class="text-caption mt-1">
                          专注时长决定休息：约 <strong>25%</strong> 比例，四舍五入到 5 的倍数
                        </div>
                      </div>
                    </div>

                    <v-divider class="my-2" />

                    <div class="rec-step">
                      <v-icon icon="mdi-calendar-check-outline" size="16" color="success" class="mr-2" />
                      <div class="rec-step-body">
                        <div class="text-caption text-medium-emphasis">每日上限 · 多维决策</div>
                        <div class="text-caption mt-1">① 完成率分档</div>
                        <div class="rec-rule">
                          <span class="rec-chip" :class="{ 'rec-chip-active': item.inputs.completionTier === 120 }">完成率 &lt; 30% → 120分钟</span>
                          <span class="rec-chip" :class="{ 'rec-chip-active': item.inputs.completionTier === 180 }">30% ~ 60% → 180分钟</span>
                          <span class="rec-chip" :class="{ 'rec-chip-active': item.inputs.completionTier === 240 }">≥ 60% → 240分钟</span>
                        </div>
                        <div class="text-caption mt-1">
                          当前完成率 <strong>{{ Math.round(item.inputs.completionRateInput * 100) }}%</strong> → 基础 <strong>{{ item.inputs.completionTier }} 分钟</strong>
                        </div>
                        <div class="text-caption mt-1">② 拖延罚分</div>
                        <div class="rec-rule">
                          <span class="rec-chip" :class="{ 'rec-chip-active': item.inputs.procPenalty === 0 }">拖延 ≤ 0.5 · 无罚分</span>
                          <span class="rec-chip" :class="{ 'rec-chip-active': item.inputs.procPenalty === 30 }">拖延 0.5~0.7 · −30分钟</span>
                          <span class="rec-chip" :class="{ 'rec-chip-active': item.inputs.procPenalty === 60 }">拖延 > 0.7 · −60分钟</span>
                        </div>
                        <div class="text-caption mt-1">
                          当前拖延 <strong>{{ item.inputs.procrastinationInput }}</strong>
                          <span v-if="item.inputs.procPenalty === 0">≤ 0.5，无罚分</span>
                          <span v-else>→ 上限 −<strong>{{ item.inputs.procPenalty }} 分钟</strong></span>
                        </div>
                        <div class="text-caption mt-1">③ 新手保护</div>
                        <div class="rec-rule">
                          <span class="rec-chip" :class="{ 'rec-chip-active': item.inputs.streakCapped }">连续打卡 &lt; 2 → 封顶 150分钟</span>
                          <span class="rec-chip" :class="{ 'rec-chip-active': !item.inputs.streakCapped }">连续打卡 ≥ 2 → 无封顶</span>
                        </div>
                        <div class="text-caption mt-1">
                          连续打卡 <strong>{{ item.inputs.streakInput }} 天</strong>
                          <span v-if="item.inputs.streakCapped">→ 触发封顶 150分钟</span>
                          <span v-else>→ 无封顶限制</span>
                        </div>
                      </div>
                    </div>
                  </div>
                  </template>
                  <!-- Fallback for old cached data without new fields -->
                  <template v-else>
                    <div class="comp-section">
                      <div class="comp-section-label">公式</div>
                      <div class="comp-section-body comp-formula-text">{{ item.formula || '-' }}</div>
                    </div>
                    <div class="comp-section">
                      <div class="comp-section-label">输入</div>
                      <div class="comp-section-body comp-inputs">
                        <v-tooltip v-for="(v, k) in item.inputs" :key="k" location="top" max-width="280">
                          <template #activator="{ props: tp }">
                            <span v-bind="tp" class="comp-chip">{{ inputLabel(k) }}&nbsp;<strong>{{ v }}</strong></span>
                          </template>
                          <span>{{ inputHelp(k) }}</span>
                        </v-tooltip>
                      </div>
                    </div>
                  </template>
                  <v-divider class="my-3" />
                  <div class="d-flex align-center ga-2">
                    <span class="text-body-2 font-weight-semibold">结果：</span>
                    <span class="text-body-2 font-weight-bold">{{ item.result }}</span>
                  </div>
                </template>

                <!-- Generic: formula + inputs for other metrics -->
                <template v-else>
                <!-- Formula -->
                <div class="comp-section">
                  <div class="comp-section-label">公式</div>
                  <div class="comp-section-body comp-formula-text">{{ item.formula || '-' }}</div>
                </div>

                <!-- Inputs -->
                <div class="comp-section">
                  <div class="comp-section-label">输入</div>
                  <div class="comp-section-body comp-inputs">
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
                </div>
                </template>
              </div>
            </div>
          </div>
        </v-card-text>
      </div>
    </v-expand-transition>
  </v-card>
  </template>

  <v-alert v-else type="info" variant="tonal" class="mb-4">暂无画像数据，请先完成一些打卡</v-alert>
</template>

<style scoped>
/* ── Top Metrics ── */
.metric-card {
  padding: 14px 16px;
  border-radius: 12px;
  transition: transform 0.2s, box-shadow 0.2s;
}
.metric-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 16px rgba(var(--v-theme-on-surface), 0.12);
}
.metric-value {
  font-size: 2rem;
  font-weight: 700;
  line-height: 1;
}

/* ── AI Tips ── */
.tips-card {
  border-left: 4px solid rgb(var(--v-theme-primary));
}
.tips-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.tip-item {
  display: flex;
  gap: 14px;
  padding: 12px 14px;
  border-radius: 10px;
  background: rgba(var(--v-theme-on-surface), 0.025);
  border: 1px solid rgba(var(--v-theme-on-surface), 0.05);
  transition: border-color 0.2s;
}
.tip-item:hover {
  border-color: rgba(var(--v-theme-primary), 0.2);
}
.tip-num {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: rgba(var(--v-theme-primary), 0.1);
  color: rgb(var(--v-theme-primary));
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  flex-shrink: 0;
}
.tip-text {
  line-height: 1.65;
  padding-top: 2px;
}

/* ── Recommended Params ── */
.rec-rows {
  display: flex;
  flex-direction: column;
}
.rec-row {
  display: flex;
  align-items: center;
  padding: 4px 0;
}
.rec-label {
  min-width: 64px;
}

/* ── Habit Bars ── */
.habit-bar {
  padding: 8px 12px;
  border-radius: 10px;
  background: rgba(var(--v-theme-on-surface), 0.02);
}
.habit-bar:last-child {
  margin-bottom: 0;
}

/* ── Computation ── */
.comp-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.comp-card {
  padding: 16px;
  background: rgba(var(--v-theme-on-surface), 0.02);
  border: 1px solid rgba(var(--v-theme-on-surface), 0.06);
  transition: border-color 0.15s;
}
.comp-card:hover {
  border-color: rgba(var(--v-theme-primary), 0.2);
}

.comp-card-inner {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.comp-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.comp-help {
  opacity: 0.3;
  cursor: help;
  transition: opacity 0.15s;
}
.comp-help:hover { opacity: 0.75; }

.comp-result-value {
  font-size: 1.25rem;
  font-weight: 700;
  color: rgb(var(--v-theme-primary));
  white-space: nowrap;
}

.comp-section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.comp-section-label {
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  opacity: 0.4;
}

.comp-section-body {
  padding: 8px 12px;
  border-radius: 8px;
  background: rgba(var(--v-theme-on-surface), 0.04);
  border: 1px solid rgba(var(--v-theme-on-surface), 0.06);
}

.comp-inputs {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
}

.comp-formula-text {
  font-size: 12px;
  font-family: "Cascadia Code", "Fira Code", "JetBrains Mono", ui-monospace, monospace;
  opacity: 0.7;
  line-height: 1.6;
}

.comp-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 11px;
  background: rgba(var(--v-theme-on-surface), 0.06);
  border: 1px solid rgba(var(--v-theme-on-surface), 0.08);
  cursor: default;
  white-space: nowrap;
  transition: border-color 0.15s;
}
.comp-chip:hover { border-color: rgba(var(--v-theme-primary), 0.3); }
.comp-chip strong { color: rgb(var(--v-theme-primary)); font-weight: 600; }
.comp-chip-dim { opacity: 0.45; font-style: italic; }

@media (max-width: 960px) {
  .comp-grid {
    grid-template-columns: 1fr;
  }
}

/* ── Recommendation Decision Flow ── */
.rec-flow {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.rec-step {
  display: flex;
  align-items: flex-start;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(var(--v-theme-on-surface), 0.03);
  border: 1px solid rgba(var(--v-theme-on-surface), 0.05);
}
.rec-step-body {
  flex: 1;
  min-width: 0;
}
.rec-rule {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}
.rec-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 11px;
  background: rgba(var(--v-theme-on-surface), 0.06);
  border: 1px solid rgba(var(--v-theme-on-surface), 0.08);
  transition: all 0.15s;
}
.rec-chip-active {
  background: rgba(var(--v-theme-primary), 0.1);
  border-color: rgba(var(--v-theme-primary), 0.35);
  color: rgb(var(--v-theme-primary));
  font-weight: 600;
}
.text-success { color: rgb(var(--v-theme-success)); }
.text-error { color: rgb(var(--v-theme-error)); }

/* ── Trend Badge ── */
.trend-badge {
  display: inline-flex;
  align-items: center;
  gap: 1px;
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 10px;
  white-space: nowrap;
}
.trend--up {
  color: rgb(var(--v-theme-success));
  background: rgba(var(--v-theme-success), 0.1);
}
.trend--down {
  color: rgb(var(--v-theme-error));
  background: rgba(var(--v-theme-error), 0.1);
}

/* ── Time Slots ── */
.time-slot-row {
  padding: 10px 0;
}
.time-slot-row + .time-slot-row {
  border-top: 1px solid rgba(var(--v-theme-on-surface), 0.06);
}
.time-slot-rank {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  flex-shrink: 0;
}
.rank-1 { background: #FFD700; color: #5D4037; }
.rank-2 { background: #C0C0C0; color: #37474F; }
.rank-3 { background: #CD7F32; color: #3E2723; }
</style>
