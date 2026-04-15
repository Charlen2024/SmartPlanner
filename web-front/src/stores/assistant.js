import { defineStore } from 'pinia'
import api from '../plugins/api'

export const useAssistantStore = defineStore('assistant', {
  state: () => ({
    initialized: false,
    minimized: false,
    x: null,
    y: null,
    width: 380,
    height: 520,
    adviceLoading: false,
    adviceText: '',
    adviceError: '',
    chatOpen: false,
    chatInput: '',
    chatLoading: false,
    chatMessages: [],
  }),
  actions: {
    async init() {
      if (this.initialized) return
      this.initialized = true
      await this.refreshAdvice()
    },
    setRect({ x, y, width, height }) {
      if (Number.isFinite(x)) this.x = x
      if (Number.isFinite(y)) this.y = y
      if (Number.isFinite(width)) this.width = Math.max(260, Math.min(900, width))
      if (Number.isFinite(height)) this.height = Math.max(120, Math.min(900, height))
    },
    toggleMinimize() {
      const oldH = this.minimized ? 48 : this.height
      this.minimized = !this.minimized
      const newH = this.minimized ? 48 : this.height
      if (this.y !== null) {
        let ny = this.y + (oldH - newH)
        if (typeof window !== 'undefined') {
          const maxY = Math.max(16, window.innerHeight - newH - 16)
          ny = Math.max(16, Math.min(maxY, ny))
        }
        this.y = ny
      }
    },
    openChat() {
      this.chatOpen = true
      this.minimized = false
    },
    closeChat() {
      this.chatOpen = false
    },
    async refreshAdvice() {
      this.adviceLoading = true
      this.adviceError = ''
      try {
        const t = new Date()
        const d = `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`
        const schedulesRes = await api.get('/user/schedule/task-schedules', { params: { from: `${d}T00:00:00`, to: `${d}T23:59:59` } })
        const schedules = schedulesRes?.data?.data ?? []
        const taskIds = schedules.map((x) => x?.taskId).filter(Boolean)
        if (!taskIds.length) {
          this.adviceText = '今天暂无排程任务'
          return
        }

        const [adviceRes, tasksRes] = await Promise.all([
          api.post('/user/tasks/advice', taskIds),
          api.post('/user/tasks/by-ids', taskIds),
        ])

        const advice = adviceRes?.data?.data ?? {}
        const tasks = tasksRes?.data?.data ?? []
        const taskMap = new Map()
        for (const t of tasks) {
          if (t?.id) taskMap.set(Number(t.id), t)
        }

        const lines = []
        for (let i = 0; i < schedules.length; i++) {
          const s = schedules[i]
          const tid = Number(s?.taskId)
          const title = s?.taskTitle || taskMap.get(tid)?.title || `任务 ${i + 1}`
          const tip = advice?.[String(tid)] || advice?.[tid] || ''
          if (tip) lines.push(`${i + 1}. ${title}：${tip}`)
        }
        this.adviceText = lines.length ? lines.join('\n') : '暂无建议'
      } catch (e) {
        this.adviceError = e?.response?.data?.message || e?.message || '加载建议失败'
      } finally {
        this.adviceLoading = false
      }
    },
    async sendChat() {
      const text = String(this.chatInput ?? '').trim()
      if (!text || this.chatLoading) return

      this.chatMessages.push({ role: 'user', text })
      this.chatInput = ''
      this.chatLoading = true
      try {
        const res = await api.post('/user/agent/chat', text, { headers: { 'Content-Type': 'text/plain' }, timeout: 60000 })
        const answer = res?.data?.data?.answer || res?.data?.data || res?.data?.message || ''
        this.chatMessages.push({ role: 'assistant', text: String(answer || '我暂时没想好，可以换个问法吗？') })
      } catch (e) {
        this.chatMessages.push({ role: 'assistant', text: String(e?.response?.data?.message || e?.message || '服务异常，请稍后再试') })
      } finally {
        this.chatLoading = false
      }
    },
  },
})
