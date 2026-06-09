<script setup>
import { useScheduleStore } from '../stores/schedule'

const sc = useScheduleStore()

function phaseClass(key) {
  return sc.phases[key] || 'pending'
}
</script>

<template>
  <Transition name="sc-panel">
    <div v-if="sc.active" class="sc-panel">
      <div class="sc-card">
        <!-- Close button (only show after done) -->
        <v-btn
          icon="mdi-close"
          size="x-small"
          variant="text"
          class="sc-close"
          @click="sc.dismiss()"
        />

        <!-- Header: date + badge -->
        <div class="sc-header">
          <span class="sc-title">智能排程</span>
          <span v-if="sc.date" class="sc-date">{{ sc.date }}</span>
        </div>

        <!-- Pipeline stepper -->
        <div class="sc-pipeline">
          <div
            v-for="(p, i) in sc.phaseList"
            :key="p.key"
            class="sc-phase"
          >
            <div class="sc-phase-row">
              <div :class="['sc-node', phaseClass(p.key)]">
                <v-icon
                  v-if="phaseClass(p.key) === 'done'"
                  icon="mdi-check"
                  size="12"
                />
                <v-icon
                  v-else-if="phaseClass(p.key) === 'active' && p.key === 'ai'"
                  icon="mdi-brain"
                  size="14"
                />
                <v-icon
                  v-else
                  :icon="p.icon"
                  size="14"
                />
              </div>
              <span :class="['sc-label', phaseClass(p.key)]">{{ p.label }}</span>
              <span v-if="phaseClass(p.key) === 'active'" class="sc-status-tag">进行中</span>
            </div>
            <div
              v-if="i < sc.phaseList.length - 1"
              :class="['sc-connector', phaseClass(p.key)]"
            />
          </div>
        </div>

        <!-- Error display -->
        <div v-if="sc.error" class="sc-error">
          <v-icon icon="mdi-alert-circle" size="14" color="error" />
          <span>{{ sc.error }}</span>
        </div>

        <!-- Scheduling parameters (show on done) -->
        <div v-if="sc.phases.done === 'done' && !sc.error" class="sc-params">
          <div class="sc-params-title">
            <v-icon icon="mdi-tune-variant" size="14" />
            <span>排程参数</span>
          </div>
          <div class="sc-params-grid">
            <div class="sc-param-item">
              <span class="sc-param-val">{{ sc.params.focusMinutes }}</span>
              <span class="sc-param-lbl">专注时长<br/>分钟</span>
            </div>
            <div class="sc-param-divider" />
            <div class="sc-param-item">
              <span class="sc-param-val">{{ sc.params.breakMinutes }}</span>
              <span class="sc-param-lbl">休息间隔<br/>分钟</span>
            </div>
            <div class="sc-param-divider" />
            <div class="sc-param-item">
              <span class="sc-param-val">{{ sc.params.maxDailyMinutes }}</span>
              <span class="sc-param-lbl">日学习<br/>上限(分)</span>
            </div>
          </div>
          <div v-if="sc.params.procrastinationIndex > 0" class="sc-pro-row">
            <span class="sc-pro-label">拖延指数</span>
            <div class="sc-pro-bar-wrap">
              <div class="sc-pro-bar" :style="{ width: Math.round(sc.params.procrastinationIndex * 100) + '%' }" :class="sc.proColor" />
            </div>
            <span class="sc-pro-val">{{ Math.round(sc.params.procrastinationIndex * 100) }}%</span>
            <v-chip size="x-small" :color="sc.proColor" variant="tonal" class="ml-1">{{ sc.proLabel }}</v-chip>
          </div>
        </div>

        <!-- Complete badge -->
        <div v-if="sc.phases.done === 'done' && !sc.error" class="sc-complete">
          <v-icon icon="mdi-check-circle" color="success" size="16" />
          <span>已排程 {{ sc.taskCount }} 个任务</span>
        </div>

        <!-- Closed loop indicator -->
        <div v-if="sc.phases.done === 'done' && !sc.error" class="sc-loop">
          <div class="sc-loop-line">
            <span class="sc-loop-dot done" />
            <span class="sc-loop-seg done" />
            <span class="sc-loop-dot done" />
            <span class="sc-loop-seg done" />
            <span class="sc-loop-dot done" />
          </div>
          <div class="sc-loop-labels">
            <span>目标</span>
            <span>拆解</span>
            <span>排程</span>
          </div>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.sc-panel {
  position: fixed;
  top: 80px;
  right: 16px;
  z-index: 9998;
  width: 300px;
}
.sc-card {
  position: relative;
  padding: 16px;
  border-radius: 16px;
  background: rgba(var(--v-theme-surface), 0.88);
  backdrop-filter: blur(20px) saturate(160%);
  -webkit-backdrop-filter: blur(20px) saturate(160%);
  border: 1px solid rgba(var(--v-theme-on-surface), 0.1);
  box-shadow: 0 12px 40px rgba(0,0,0,0.15);
  overflow: hidden;
}
.sc-card::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: linear-gradient(135deg, rgba(var(--v-theme-secondary), 0.05), transparent 50%, rgba(var(--v-theme-primary), 0.03));
  pointer-events: none;
}
.sc-close {
  position: absolute;
  top: 6px;
  right: 6px;
  z-index: 1;
}
.sc-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.sc-title {
  font-size: 13px;
  font-weight: 700;
  color: rgb(var(--v-theme-secondary));
}
.sc-date {
  font-size: 11px;
  font-weight: 500;
  opacity: 0.55;
  background: rgba(var(--v-theme-on-surface), 0.05);
  padding: 1px 8px;
  border-radius: 8px;
}

/* ── Pipeline stepper ── */
.sc-pipeline {
  display: flex;
  flex-direction: column;
  gap: 0;
}
.sc-phase {
  position: relative;
}
.sc-phase-row {
  display: flex;
  align-items: center;
  gap: 10px;
  position: relative;
  z-index: 1;
}

/* Node */
.sc-node {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all 0.4s cubic-bezier(0.22, 1, 0.36, 1);
}
.sc-node.pending {
  background: rgba(var(--v-theme-on-surface), 0.06);
  color: rgba(var(--v-theme-on-surface), 0.3);
}
.sc-node.active {
  background: rgba(var(--v-theme-secondary), 0.18);
  color: rgb(var(--v-theme-secondary));
  box-shadow: 0 0 12px rgba(var(--v-theme-secondary), 0.35);
  animation: sc-node-glow 1.5s ease-in-out infinite;
}
.sc-node.done {
  background: rgba(var(--v-theme-success), 0.15);
  color: rgb(var(--v-theme-success));
}

/* Label */
.sc-label {
  font-size: 12px;
  flex: 1;
  transition: all 0.4s;
}
.sc-label.pending { opacity: 0.35; }
.sc-label.active  { opacity: 1; font-weight: 600; color: rgb(var(--v-theme-secondary)); }
.sc-label.done    { opacity: 0.65; }

/* Status tag */
.sc-status-tag {
  font-size: 10px;
  color: rgb(var(--v-theme-secondary));
  background: rgba(var(--v-theme-secondary), 0.1);
  padding: 1px 8px;
  border-radius: 10px;
  font-weight: 600;
  animation: sc-status-pulse 1.2s ease-in-out infinite;
}

/* Connector line */
.sc-connector {
  width: 2px;
  height: 14px;
  margin-left: 14px;
  margin-top: 2px;
  margin-bottom: 2px;
  background: rgba(var(--v-theme-on-surface), 0.08);
  border-radius: 1px;
  transition: background 0.5s;
}
.sc-connector.active {
  background: linear-gradient(to bottom, rgb(var(--v-theme-secondary)), rgba(var(--v-theme-secondary), 0.3));
}
.sc-connector.done {
  background: rgba(var(--v-theme-success), 0.3);
}

/* ── Error ── */
.sc-error {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  padding: 8px 12px;
  border-radius: 10px;
  background: rgba(var(--v-theme-error), 0.08);
  font-size: 12px;
  color: rgb(var(--v-theme-error));
}

/* ── Scheduling Parameters ── */
.sc-params {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid rgba(var(--v-theme-on-surface), 0.06);
}
.sc-params-title {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 600;
  opacity: 0.6;
  margin-bottom: 10px;
}
.sc-params-grid {
  display: flex;
  align-items: center;
  justify-content: space-around;
  margin-bottom: 10px;
}
.sc-param-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}
.sc-param-val {
  font-size: 20px;
  font-weight: 700;
  color: rgb(var(--v-theme-secondary));
  line-height: 1.1;
}
.sc-param-lbl {
  font-size: 9px;
  opacity: 0.5;
  text-align: center;
  line-height: 1.3;
}
.sc-param-divider {
  width: 1px;
  height: 36px;
  background: rgba(var(--v-theme-on-surface), 0.08);
}
.sc-pro-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-radius: 8px;
  background: rgba(var(--v-theme-on-surface), 0.03);
}
.sc-pro-label {
  font-size: 10px;
  opacity: 0.5;
  white-space: nowrap;
}
.sc-pro-bar-wrap {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: rgba(var(--v-theme-on-surface), 0.08);
  overflow: hidden;
}
.sc-pro-bar {
  height: 100%;
  border-radius: 3px;
  transition: width 0.6s cubic-bezier(0.22, 1, 0.36, 1);
  background: rgb(var(--v-theme-success));
}
.sc-pro-bar.warning { background: rgb(var(--v-theme-warning)); }
.sc-pro-bar.error   { background: rgb(var(--v-theme-error)); }
.sc-pro-val {
  font-size: 11px;
  font-weight: 600;
  opacity: 0.7;
}

/* ── Complete ── */
.sc-complete {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid rgba(var(--v-theme-on-surface), 0.06);
  font-size: 12px;
  font-weight: 600;
}

/* ── Closed loop indicator ── */
.sc-loop {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid rgba(var(--v-theme-on-surface), 0.06);
}
.sc-loop-line {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0;
  margin-bottom: 4px;
}
.sc-loop-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
  background: rgba(var(--v-theme-success), 0.5);
  transition: all 0.5s;
}
.sc-loop-dot.done {
  background: rgb(var(--v-theme-success));
  box-shadow: 0 0 6px rgba(var(--v-theme-success), 0.4);
}
.sc-loop-seg {
  width: 48px;
  height: 2px;
  flex-shrink: 0;
  background: rgba(var(--v-theme-success), 0.25);
  transition: background 0.5s;
}
.sc-loop-seg.done {
  background: rgba(var(--v-theme-success), 0.6);
}
.sc-loop-labels {
  display: flex;
  justify-content: space-around;
  font-size: 9px;
  opacity: 0.5;
  padding: 0 10px;
}

/* ── Transitions ── */
.sc-panel-enter-active { transition: all 0.4s cubic-bezier(0.22, 1, 0.36, 1); }
.sc-panel-leave-active { transition: all 0.3s ease-in; }
.sc-panel-enter-from { opacity: 0; transform: translateX(40px); }
.sc-panel-leave-to   { opacity: 0; transform: translateX(40px); }

/* ── Keyframes ── */
@keyframes sc-node-glow {
  0%, 100% { box-shadow: 0 0 8px rgba(var(--v-theme-secondary), 0.25); }
  50%      { box-shadow: 0 0 18px rgba(var(--v-theme-secondary), 0.5); }
}
@keyframes sc-status-pulse {
  0%, 100% { opacity: 1; }
  50%      { opacity: 0.55; }
}
</style>
