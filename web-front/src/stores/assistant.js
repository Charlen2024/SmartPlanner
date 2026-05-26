import { defineStore } from 'pinia'
import api from '../plugins/api'

const NAV_ITEMS = [
  { title: '仪表盘', to: '/' },
  { title: '学习计划', to: '/plan' },
  { title: '目标', to: '/goals' },
  { title: '随笔', to: '/journals' },
  { title: '日程', to: '/schedule' },
  { title: '资源', to: '/resources' },
  { title: '打卡', to: '/punch' },
  { title: '画像', to: '/profile' },
  { title: '2048', to: '/games/2048' },
]

function normalizeText(s) {
  return String(s || '')
    .toLowerCase()
    .replaceAll(/\s+/g, '')
    .replaceAll(/[，。、\-—…！？：；"''（）【】《》()\[\]{}<>]/g, '')
}

function extractExplicitPath(raw) {
  const m = String(raw || '').match(/(?:^|\s)(\/[a-z0-9\-\/]+)(?:\s|$)/i)
  if (!m?.[1]) return null
  const p = m[1]
  const ok = NAV_ITEMS.some((x) => x.to === p)
  return ok ? p : null
}

function extractNavTarget(raw) {
  const s0 = String(raw || '').trim()
  if (!s0) return null
  const explicit = extractExplicitPath(s0)
  if (explicit) {
    const item = NAV_ITEMS.find((x) => x.to === explicit)
    return item ? { to: item.to, title: item.title } : { to: explicit, title: explicit }
  }
  const s = normalizeText(s0)
  const hasVerb = /打开|进入|跳转|导航|带我去|去/.test(s)
  const hasHint = /页面|界面|菜单|功能|模块/.test(s)
  if (!hasVerb && !hasHint) return null
  if (s.includes('2048') || s.includes('2048游戏') || s.includes('玩2048') || s.includes('小游戏')) return { to: '/games/2048', title: '2048' }
  if (s.includes('仪表盘') || s.includes('首页') || s.includes('主页') || s === '打开' || s === '去') return { to: '/', title: '仪表盘' }
  if (s.includes('导入课表') || s.includes('上传课表') || s.includes('课表') || s.includes('plan')) return { to: '/plan', title: '学习计划' }
  if (s.includes('学习计划') || (s.includes('计划') && !s.includes('排程'))) return { to: '/plan', title: '学习计划' }
  if (s.includes('目标')) return { to: '/goals', title: '目标' }
  if (s.includes('任务')) return { to: '/goals', title: '目标' }
  if (s.includes('随笔') || s.includes('日记') || s.includes('复盘')) return { to: '/journals', title: '随笔' }
  if (s.includes('日程') || s.includes('排程') || s.includes('日历')) return { to: '/schedule', title: '日程' }
  if (s.includes('资源') || s.includes('课程')) return { to: '/resources', title: '资源' }
  if (s.includes('打卡')) return { to: '/punch', title: '打卡' }
  if (s.includes('画像') || s.includes('我的')) return { to: '/profile', title: '画像' }
  return null
}

function extractNavigateDirective(text) {
  const out = []
  const lines = String(text || '').split('\n')
  for (const line of lines) {
    const m = String(line || '').match(/(?:跳转|打开|进入)\s*[:：]?\s*(\/[a-z0-9\-\/]+)/i)
    const p = m?.[1]
    if (!p) continue
    if (!NAV_ITEMS.some((x) => x.to === p)) continue
    if (!out.includes(p)) out.push(p)
  }
  return out
}

function stripNavigateDirective(text) {
  const raw = String(text || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  const cleaned = raw.replace(/(?:跳转|打开|进入)\s*[:：]?\s*\/[a-z0-9\-\/]+/gi, '')
  return cleaned.replace(/\n{3,}/g, '\n\n').trim()
}

let _msgIdCounter = 0
function nextMsgId() { return 'm_' + (++_msgIdCounter) + '_' + Date.now() }

export const useAssistantStore = defineStore('assistant', {
  state: () => ({
    initialized: false,
    minimized: false,
    x: null,
    y: null,
    width: 380,
    height: 520,
    adviceText: '',
    chatOpen: false,
    chatInput: '',
    chatLoading: false,
    chatMessages: [],
    navRequest: null,
  }),
  actions: {
    async init() {
      if (this.initialized) return
      this.initialized = true
      this.adviceText = '欢迎回来，先照顾好自己。'
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
    openChat() { this.chatOpen = true; this.minimized = false },
    closeChat() { this.chatOpen = false },
    requestNavigate(to, title) {
      const dest = String(to || '').trim()
      if (!dest) return
      this.navRequest = { to: dest, title: String(title || dest), at: Date.now() }
    },
    clearNavRequest() { this.navRequest = null },
    setCareText(text) {
      const t = String(text || '').trim()
      if (!t) return
      this.adviceText = t
    },
    async sendChat() {
      const text = String(this.chatInput ?? '').trim()
      if (!text || this.chatLoading) return
      this.chatMessages.push({ _key: nextMsgId(), role: 'user', text })
      this.chatInput = ''
      const nav = extractNavTarget(text)
      if (nav?.to) {
        this.requestNavigate(nav.to, nav.title)
        this.chatMessages.push({ _key: nextMsgId(), role: 'assistant', text: '已为你打开：' + nav.title })
        return
      }
      this.chatLoading = true
      let aiMsg = null
      try {
        aiMsg = { _key: nextMsgId(), role: 'assistant', text: '' }
        this.chatMessages.push(aiMsg)
        aiMsg = this.chatMessages[this.chatMessages.length - 1]
        const token = localStorage.getItem('accessToken')
        const ctrl = new AbortController()
        const t = setTimeout(() => ctrl.abort(), 120000)
        try {
          const res = await fetch('/api/user/agent/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
            body: text, signal: ctrl.signal,
          })
          if (!res.ok) throw new Error('HTTP ' + res.status)
          const reader = res.body?.getReader?.()
          if (!reader) { aiMsg.text = String(await res.text() || '服务返回为空'); return }
          const decoder = new TextDecoder('utf-8')
          let buf = '', sseBuf = '', lastFlush = 0, lastFlushedText = ''
          const flushText = () => {
            if (buf === lastFlushedText) return
            lastFlushedText = buf; aiMsg.text = buf; lastFlush = Date.now()
          }
          const processSSE = () => {
            const dataLines = []
            const sseLines = sseBuf.split('\n')
            sseBuf = sseLines.pop() || ''
            let inData = false, inEvent = false
            for (const line of sseLines) {
              if (line.startsWith('event:')) { inData = false; inEvent = true }
              else if (line.startsWith('data:')) { if (!inEvent) dataLines.push(line.slice(5).trimStart()); inData = true; inEvent = false }
              else if (inData && !inEvent) { dataLines.push(line) }
            }
            if (dataLines.length) {
              while (dataLines.length && dataLines[dataLines.length - 1] === '') dataLines.pop()
              const newText = dataLines.join('\n')
              if (newText && !buf.endsWith(newText)) buf += newText
            }
            if (Date.now() - lastFlush > 50) flushText()
          }
          while (true) {
            const { value, done } = await reader.read()
            if (done) { processSSE(); flushText(); break }
            sseBuf += decoder.decode(value, { stream: true })
            processSSE()
          }
          if (!aiMsg.text || !aiMsg.text.trim()) aiMsg.text = '我暂时没想好，可以换个问法吗？'
          else aiMsg.text = aiMsg.text.trim()
          const navPaths = extractNavigateDirective(aiMsg.text)
          if (navPaths?.length) {
            aiMsg.navs = navPaths.map(p => { const item = NAV_ITEMS.find(x => x.to === p); return { to: p, title: item?.title || p } })
            aiMsg.text = stripNavigateDirective(aiMsg.text)
          }
        } finally { clearTimeout(t) }
      } catch (e) {
        const msg = String(e?.message || '服务异常，请稍后再试')
        if (aiMsg) aiMsg.text = msg
        else this.chatMessages.push({ _key: nextMsgId(), role: 'assistant', text: msg })
      } finally { this.chatLoading = false }
    },
  },
})