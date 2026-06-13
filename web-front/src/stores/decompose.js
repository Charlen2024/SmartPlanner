import { defineStore } from 'pinia'

const MAX_DISPLAY = 5

const PHASES = [
  { key: 'intent',   label: '分析目标意图',   icon: 'mdi-magnify' },
  { key: 'llm',      label: 'AI拆解生成任务',  icon: 'mdi-brain' },
  { key: 'saving',   label: '保存任务入库',    icon: 'mdi-content-save-outline' },
  { key: 'resources',label: '触发资源检索',    icon: 'mdi-book-search-outline' },
  { key: 'done',     label: '全部完成',        icon: 'mdi-check-circle-outline' },
]

export const useDecomposeStore = defineStore('decompose', {
  state: () => ({
    active: false,
    goalTitle: '',
    taskCount: 0,
    tasks: [],
    phases: {},
    error: '',
    autoplayTimer: null,
    stageTimers: [],
  }),
  getters: {
    phaseList: () => PHASES,
  },
  actions: {
    start(goalTitle) {
      this._reset()
      this.active = true
      this.goalTitle = goalTitle || ''
      this.phases = Object.fromEntries(PHASES.map(p => [p.key, 'pending']))
      this.phases.intent = 'active'
    },

    /** Called when backend sends GOAL_DECOMPOSE_PROGRESS — LLM has produced tasks */
    onTasksGenerated(titles) {
      this._clearStageTimers()
      this.completePhase('intent')
      // Show tasks immediately, even while phase animation continues
      this.setTasks(titles || [])
      // Stagger remaining phases so the user sees the pipeline animate
      this.stageTimers.push(setTimeout(() => this.completePhase('llm'), 800))
      this.stageTimers.push(setTimeout(() => this.completePhase('saving'), 1600))
    },

    /** Called when backend sends GOAL_TASK_READY — everything is done */
    onAllDone(titles, fallbackCount) {
      this._clearStageTimers()
      // Ensure tasks are set (defensive, in case PROGRESS was missed or had stale data)
      if (!this.tasks.length) {
        if (titles && titles.length) {
          this.setTasks(titles)
        } else if (fallbackCount > 0) {
          // Backend sent a count but title list was computed before fallback (pre-fix server)
          // Generate minimal placeholders so the panel doesn't show 0
          const placeholders = Array.from({ length: fallbackCount }, (_, i) => `任务 ${i + 1}`)
          this.setTasks(placeholders)
        }
      }
      // Mark all pre-done phases as done if they aren't already
      for (const p of PHASES) {
        if (p.key === 'done') break
        if (this.phases[p.key] !== 'done') this.phases[p.key] = 'done'
      }
      // Short stagger for the final two phases
      this.stageTimers.push(setTimeout(() => {
        this.completePhase('resources')
        this.stageTimers.push(setTimeout(() => this.complete(), 400))
      }, 400))
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
    setTasks(taskTitles) {
      this.tasks = (taskTitles || []).map(t => ({ title: t }))
      this.taskCount = this.tasks.length
      this._autoplayReveal()
    },
    _autoplayReveal() {
      if (this.autoplayTimer) clearInterval(this.autoplayTimer)
      let idx = 0
      const step = () => {
        if (idx >= this.tasks.length) {
          clearInterval(this.autoplayTimer)
          this.autoplayTimer = null
          return
        }
        this.tasks[idx].revealed = true
        idx++
      }
      step()
      this.autoplayTimer = setInterval(step, 220)
    },
    onFailed(error) {
      this._clearStageTimers()
      this.error = error || '拆解失败'
      for (const key of Object.keys(this.phases)) {
        if (this.phases[key] === 'active') this.phases[key] = 'done'
        if (this.phases[key] === 'pending') this.phases[key] = 'done'
      }
      this.phases.done = 'done'
    },
    /** Called by views after loading real tasks from API to replace SSE titles */
    syncTasks(taskList) {
      const list = Array.isArray(taskList) ? taskList : []
      this.taskCount = list.length
      this.tasks = list.slice(0, MAX_DISPLAY).map(t => ({
        id: t.id,
        title: typeof t === 'string' ? t : (t.title || ''),
        revealed: true,
      }))
      if (this.autoplayTimer) { clearInterval(this.autoplayTimer); this.autoplayTimer = null }
      for (const p of PHASES) this.phases[p.key] = 'done'
    },
    complete() {
      if (this.autoplayTimer) { clearInterval(this.autoplayTimer); this.autoplayTimer = null }
      this.tasks.forEach(t => { t.revealed = true })
      for (const p of PHASES) this.phases[p.key] = 'done'
    },
    dismiss() {
      this._reset()
    },
    _reset() {
      this._clearStageTimers()
      this.active = false
      this.goalTitle = ''
      this.taskCount = 0
      this.tasks = []
      this.phases = {}
      this.error = ''
      if (this.autoplayTimer) { clearInterval(this.autoplayTimer); this.autoplayTimer = null }
    },
  },
})
