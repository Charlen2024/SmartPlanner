import { defineStore } from 'pinia'

let nextId = 1

export const useNotifyStore = defineStore('notify', {
  state: () => ({
    ownerUserId: null,
    recentReminderSig: {},
    _recentPushSig: {},
    items: [],
    reminders: [],
    signalSeq: {
      GOAL_TASK_READY: 0,
      SCHEDULE_DONE: 0,
      SCHEDULE_FAILED: 0,
      RESOURCE_ADVICE_DONE: 0,
      RESOURCE_ADVICE_FAILED: 0,
    },
    lastSignal: null,
  }),
  actions: {
    setOwner(userId) {
      const uid = userId != null ? Number(userId) : null
      const next = Number.isFinite(uid) && uid > 0 ? uid : null
      if (this.ownerUserId && next && this.ownerUserId !== next) {
        this.$reset()
      }
      this.ownerUserId = next
      this._restoreReminders()
    },
    push(message, type = 'info', timeout = 4500) {
      const msg = String(message ?? '').trim()
      if (!msg) return -1
      const sig = `t:${type}|m:${msg}`
      const now = Date.now()
      const last = Number(this._recentPushSig?.[sig] || 0)
      if (last && now - last < 30000) return -1
      this._recentPushSig = this._recentPushSig || {}
      this._recentPushSig[sig] = now
      if (Object.keys(this._recentPushSig).length > 100) {
        const next = {}
        for (const k of Object.keys(this._recentPushSig)) {
          if (now - Number(this._recentPushSig[k]) < 5 * 60_000) next[k] = this._recentPushSig[k]
        }
        this._recentPushSig = next
      }
      const id = nextId++
      this.items.push({
        id,
        open: true,
        message: msg,
        type,
        timeout,
      })
      return id
    },
    close(id) {
      const item = this.items.find((x) => x.id === id)
      if (item) item.open = false
    },
    remove(id) {
      this.items = this.items.filter((x) => x.id !== id)
    },
    info(message, timeout) {
      return this.push(message, 'info', timeout)
    },
    success(message, timeout) {
      return this.push(message, 'success', timeout)
    },
    error(message, timeout) {
      return this.push(message, 'error', timeout)
    },
    _persistReminders() {
      try {
        const arr = (this.reminders || []).slice(0, 30).map(r => ({
          _key: r._key,
          id: r.id,
          type: r.type,
          content: r.content,
          ts: r.ts,
          payload: r.payload,
          read: r.read,
        }))
        localStorage.setItem('sp_reminders:' + (this.ownerUserId || 'anon'), JSON.stringify(arr))
      } catch (e) {}
    },
    _restoreReminders() {
      try {
        if (!this.ownerUserId) return
        const raw = localStorage.getItem('sp_reminders:' + this.ownerUserId)
        if (!raw) return
        const arr = JSON.parse(raw)
        if (!Array.isArray(arr)) return
        this.reminders = arr.slice(0, 30)
      } catch (e) {}
    },
    addReminder(msg) {
      const m = msg && typeof msg === 'object' ? msg : null
      if (!m) return
      if (this.ownerUserId && m.userId != null && Number(m.userId) !== Number(this.ownerUserId)) return
      const ts = Number(m.ts || Date.now())
      const type = String(m.type || '')
      const content = String(m.content || '')
      const trigger = String(m?.payload?.data?.trigger || '')
      const sig = `t:${type}|tr:${trigger}|c:${content}`
      const last = Number(this.recentReminderSig?.[sig] || 0)
      if (last && ts - last < 120000) return
      this.recentReminderSig = this.recentReminderSig || {}
      this.recentReminderSig[sig] = ts
      if (Object.keys(this.recentReminderSig).length > 200) {
        const next = {}
        for (const k of Object.keys(this.recentReminderSig)) {
          const v = Number(this.recentReminderSig[k] || 0)
          if (ts - v < 10 * 60 * 1000) next[k] = v
        }
        this.recentReminderSig = next
      }
      const id = String(m.id || '')
      const key = id ? `id:${id}` : `ts:${Number(m.ts || 0)}|type:${String(m.type || '')}|content:${String(m.content || '')}`
      if (this.reminders.some((x) => x._key === key)) return
      const item = {
        _key: key,
        id: id || null,
        type: String(m.type || ''),
        content: String(m.content || ''),
        ts: Number(m.ts || Date.now()),
        payload: m.payload ?? null,
        read: false,
      }
      const next = [item, ...(this.reminders || [])]
      this.reminders = next.slice(0, 30)
      this._persistReminders()
    },
    markReminderRead(key) {
      const k = String(key || '')
      if (!k) return
      this.reminders = (this.reminders || []).map((r) => (r._key === k ? { ...r, read: true } : r))
    },
    clearReminders() {
      this.reminders = []
    },
    signal(type, payload) {
      const t = String(type || '').trim()
      if (t && Object.prototype.hasOwnProperty.call(this.signalSeq, t)) {
        this.signalSeq[t] = Number(this.signalSeq[t] || 0) + 1
      }
      this.lastSignal = { type: t, payload: payload ?? null, ts: Date.now() }
    },
  },
})
