<script setup>
import { useDecomposeStore } from '../stores/decompose'

const dc = useDecomposeStore()

function phaseClass(key) {
  return dc.phases[key] || 'pending'
}
</script>

<template>
  <Transition name="dc-panel">
    <div v-if="dc.active" class="dc-panel">
      <div class="dc-card">
        <!-- Close button -->
        <v-btn
          icon="mdi-close"
          size="x-small"
          variant="text"
          class="dc-close"
          @click="dc.dismiss()"
        />

        <!-- Header -->
        <div class="dc-header">
          <span class="dc-title">智能拆解</span>
          <span v-if="dc.goalTitle" class="dc-subtitle">{{ dc.goalTitle }}</span>
        </div>

        <!-- Pipeline stepper -->
        <div class="dc-pipeline">
          <div
            v-for="(p, i) in dc.phaseList"
            :key="p.key"
            class="dc-phase"
          >
            <div class="dc-phase-row">
              <!-- Node: circular icon -->
              <div :class="['dc-node', phaseClass(p.key)]">
                <v-icon
                  v-if="phaseClass(p.key) === 'done'"
                  icon="mdi-check"
                  size="12"
                />
                <v-icon
                  v-else
                  :icon="p.icon"
                  size="14"
                />
              </div>
              <!-- Label -->
              <span :class="['dc-label', phaseClass(p.key)]">{{ p.label }}</span>
              <!-- Status text -->
              <span v-if="phaseClass(p.key) === 'active'" class="dc-status-tag">进行中</span>
            </div>
            <!-- Connector line -->
            <div
              v-if="i < dc.phaseList.length - 1"
              :class="['dc-connector', phaseClass(p.key)]"
            />
          </div>
        </div>

        <!-- Error display -->
        <div v-if="dc.error" class="dc-error">
          <v-icon icon="mdi-alert-circle" size="14" color="error" />
          <span>{{ dc.error }}</span>
        </div>

        <!-- Task list -->
        <TransitionGroup
          v-if="dc.tasks.length"
          name="dc-task"
          tag="div"
          class="dc-tasks"
        >
          <div
            v-for="(t, i) in dc.tasks"
            :key="i"
            :class="['dc-task', { revealed: t.revealed }]"
          >
            <span class="dc-task-num">{{ i + 1 }}</span>
            <span class="dc-task-title">{{ t.title }}</span>
          </div>
        </TransitionGroup>

        <!-- Complete badge -->
        <div v-if="dc.phases.done === 'done'" class="dc-complete">
          <v-icon icon="mdi-check-circle" color="success" size="16" />
          <span>共生成 {{ dc.taskCount }} 个任务</span>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.dc-panel {
  position: fixed;
  top: 80px;
  right: 16px;
  z-index: 9998;
  width: 300px;
}
.dc-card {
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
.dc-card::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: linear-gradient(135deg, rgba(var(--v-theme-primary), 0.05), transparent 50%, rgba(var(--v-theme-secondary), 0.03));
  pointer-events: none;
}
.dc-close {
  position: absolute;
  top: 6px;
  right: 6px;
  z-index: 1;
}
.dc-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.dc-title {
  font-size: 13px;
  font-weight: 700;
  color: rgb(var(--v-theme-primary));
}
.dc-subtitle {
  font-size: 11px;
  font-weight: 500;
  opacity: 0.55;
  background: rgba(var(--v-theme-on-surface), 0.05);
  padding: 1px 8px;
  border-radius: 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 180px;
}

/* ── Pipeline stepper ── */
.dc-pipeline {
  display: flex;
  flex-direction: column;
  gap: 0;
}
.dc-phase {
  position: relative;
}
.dc-phase-row {
  display: flex;
  align-items: center;
  gap: 10px;
  position: relative;
  z-index: 1;
}

/* Node */
.dc-node {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all 0.4s cubic-bezier(0.22, 1, 0.36, 1);
}
.dc-node.pending {
  background: rgba(var(--v-theme-on-surface), 0.06);
  color: rgba(var(--v-theme-on-surface), 0.3);
}
.dc-node.active {
  background: rgba(var(--v-theme-primary), 0.18);
  color: rgb(var(--v-theme-primary));
  box-shadow: 0 0 12px rgba(var(--v-theme-primary), 0.35);
  animation: dc-node-glow 1.5s ease-in-out infinite;
}
.dc-node.done {
  background: rgba(var(--v-theme-success), 0.15);
  color: rgb(var(--v-theme-success));
}

/* Label */
.dc-label {
  font-size: 12px;
  flex: 1;
  transition: all 0.4s;
}
.dc-label.pending { opacity: 0.35; }
.dc-label.active  { opacity: 1; font-weight: 600; color: rgb(var(--v-theme-primary)); }
.dc-label.done    { opacity: 0.65; }

/* Status tag */
.dc-status-tag {
  font-size: 10px;
  color: rgb(var(--v-theme-primary));
  background: rgba(var(--v-theme-primary), 0.1);
  padding: 1px 8px;
  border-radius: 10px;
  font-weight: 600;
  animation: dc-status-pulse 1.2s ease-in-out infinite;
}

/* Connector line */
.dc-connector {
  width: 2px;
  height: 14px;
  margin-left: 14px;
  margin-top: 2px;
  margin-bottom: 2px;
  background: rgba(var(--v-theme-on-surface), 0.08);
  border-radius: 1px;
  transition: background 0.5s;
}
.dc-connector.active {
  background: linear-gradient(to bottom, rgb(var(--v-theme-primary)), rgba(var(--v-theme-primary), 0.3));
}
.dc-connector.done {
  background: rgba(var(--v-theme-success), 0.3);
}

/* ── Error ── */
.dc-error {
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

/* ── Tasks ── */
.dc-tasks {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid rgba(var(--v-theme-on-surface), 0.06);
  display: flex;
  flex-direction: column;
  gap: 5px;
  max-height: 200px;
  overflow-y: auto;
}
.dc-task {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px;
  border-radius: 8px;
  background: rgba(var(--v-theme-on-surface), 0.03);
  opacity: 0;
  transform: translateX(12px);
  transition: all 0.3s cubic-bezier(0.22, 1, 0.36, 1);
}
.dc-task.revealed {
  opacity: 1;
  transform: translateX(0);
}
.dc-task-num {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: rgba(var(--v-theme-primary), 0.1);
  color: rgb(var(--v-theme-primary));
  font-size: 10px;
  font-weight: 700;
  flex-shrink: 0;
}
.dc-task-title {
  font-size: 11px;
  line-height: 1.4;
}

/* ── Complete ── */
.dc-complete {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid rgba(var(--v-theme-on-surface), 0.06);
  font-size: 12px;
  font-weight: 600;
}

/* ── Transitions ── */
.dc-panel-enter-active { transition: all 0.4s cubic-bezier(0.22, 1, 0.36, 1); }
.dc-panel-leave-active { transition: all 0.3s ease-in; }
.dc-panel-enter-from { opacity: 0; transform: translateX(40px); }
.dc-panel-leave-to   { opacity: 0; transform: translateX(40px); }

.dc-task-enter-active { transition: all 0.3s cubic-bezier(0.22, 1, 0.36, 1); }
.dc-task-leave-active { transition: all 0.2s ease-in; }
.dc-task-enter-from   { opacity: 0; transform: translateX(12px); }
.dc-task-leave-to     { opacity: 0; transform: translateX(-12px); }

/* ── Keyframes ── */
@keyframes dc-node-glow {
  0%, 100% { box-shadow: 0 0 8px rgba(var(--v-theme-primary), 0.25); }
  50%      { box-shadow: 0 0 18px rgba(var(--v-theme-primary), 0.5); }
}
@keyframes dc-status-pulse {
  0%, 100% { opacity: 1; }
  50%      { opacity: 0.55; }
}
</style>
