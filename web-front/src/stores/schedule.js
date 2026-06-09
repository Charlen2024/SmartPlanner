import { defineStore } from 'pinia'

const PHASES = [
  { key: 'prepare',   label: '分析空闲时段',   icon: 'mdi-clock-outline' },
  { key: 'fetch',     label: '获取待排任务',   icon: 'mdi-format-list-checks' },
  { key: 'ai',        label: 'AI智能排序',     icon: 'mdi-brain' },
  { key: 'write',     label: '校验写入日程',   icon: 'mdi-content-save-check-outline' },
  { key: 'done',      label: '排程完成',       icon: 'mdi-check-circle-outline' },
]

export const useScheduleStore = defineStore('schedule', {
  state: () => ({
    active: false,
    date: '',
    taskCount: 0,
    phases: {},
    // Scheduling parameters shown on completion
    params: {
      focusMinutes: 0,
      breakMinutes: 0,
      maxDailyMinutes: 0,
      procrastinationIndex: 0,
    },
    error: '',
    stageTimers: [],
    dismissTimer: null,
  }),
  getters: {
    phaseList: () => PHASES,
    proLabel: (state) => {
      const v = state.params.procrastinationIndex
      if (v <= 0) return ''
      if (v < 0.3) return '高度自律'
      if (v < 0.6) return '中等自律'
      return '容易拖延'
    },
    proColor: (state) => {
      const v = state.params.procrastinationIndex
      if (v <= 0) return ''
      if (v < 0.3) return 'success'
      if (v < 0.6) return 'warning'
      return 'error'
    },
  },
  actions: {
    start(date) {
      this._reset()
      this.active = true
      this.date = date || ''
      this.phases = Object.fromEntries(PHASES.map(p => [p.key, 'pending']))
      this.phases.prepare = 'active'
    },

    /** Called on each SCHEDULE_PROGRESS event */
    onProgress(stage, progress, message) {
      // Map backend stages to frontend phase keys
      const stageMap = {
        PREPARE: 'prepare',
        FETCH_TASKS: 'fetch',
        WAIT_TASKS: 'fetch',
        AUTO_CREATE_GOAL: 'fetch',
        AUTO_CREATE_TASKS: 'fetch',
        CLEAR_EXISTING: 'fetch',
        CALL_AI: 'ai',
        FALLBACK: 'ai',
        MAP_TASKS: 'ai',
        VALIDATE: 'write',
        WRITE_DB: 'write',
        ASSIGN_GOALS: 'write',
        DONE: 'done',
      }
      const phaseKey = stageMap[stage]
      if (!phaseKey) return

      // Mark all phases up to and including this one as done
      const idx = PHASES.findIndex(p => p.key === phaseKey)
      if (idx < 0) return
      for (let i = 0; i < idx; i++) {
        if (this.phases[PHASES[i].key] !== 'done') {
          this.phases[PHASES[i].key] = 'done'
        }
      }
      // Set current as active (if not done)
      if (phaseKey !== 'done') {
        this.phases[phaseKey] = 'active'
      }
    },

    /** Called on SCHEDULE_DONE */
    onDone(params) {
      this._clearStageTimers()
      // Mark all pre-done phases as done
      for (const p of PHASES) {
        if (p.key === 'done') break
        if (this.phases[p.key] !== 'done') this.phases[p.key] = 'done'
      }
      // Stagger final phases
      this.stageTimers.push(setTimeout(() => {
        this.completePhase('write')
        this.stageTimers.push(setTimeout(() => this.complete(), 400))
      }, 400))

      // Store scheduling parameters
      if (params) {
        this.params.focusMinutes = params.focusMinutes || 0
        this.params.breakMinutes = params.breakMinutes || 0
        this.params.maxDailyMinutes = params.maxDailyMinutes || 0
        this.params.procrastinationIndex = params.procrastinationIndex || 0
        this.taskCount = params.taskCount || 0
      }
    },

    /** Called on SCHEDULE_FAILED */
    onFailed(error) {
      this._clearStageTimers()
      this.error = error || '排程失败'
      for (const key of Object.keys(this.phases)) {
        if (this.phases[key] === 'active') this.phases[key] = 'done'
        if (this.phases[key] === 'pending') this.phases[key] = 'done'
      }
      this.phases.done = 'done'
      if (this.dismissTimer) clearTimeout(this.dismissTimer)
      this.dismissTimer = setTimeout(() => this.dismiss(), 8000)
    },

    _clearStageTimers() {
      this.stageTimers.forEach(t => clearTimeout(t))
      this.stageTimers = []
    },

    completePhase(key) {
      this.phases[key] = 'done'
      const idx = PHASES.findIndex(p => p.key === key)
      if (idx >= 0 && idx + 1 < PHASES.length) {
        this.phases[PHASES[idx + 1].key] = 'active'
      }
    },

    complete() {
      for (const p of PHASES) this.phases[p.key] = 'done'
      if (this.dismissTimer) clearTimeout(this.dismissTimer)
      this.dismissTimer = setTimeout(() => this.dismiss(), 6000)
    },

    dismiss() {
      if (this.dismissTimer) { clearTimeout(this.dismissTimer); this.dismissTimer = null }
      this._reset()
    },

    _reset() {
      this._clearStageTimers()
      this.active = false
      this.date = ''
      this.taskCount = 0
      this.phases = {}
      this.params = { focusMinutes: 0, breakMinutes: 0, maxDailyMinutes: 0, procrastinationIndex: 0 }
      this.error = ''
    },
  },
})
