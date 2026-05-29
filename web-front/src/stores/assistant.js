import { defineStore } from 'pinia'
import { marked } from 'marked'
import api from '../plugins/api'

marked.setOptions({ breaks: true, gfm: true })

function sanitizeHtml(html) {
  if (!html) return ''
  return html
    .replace(/<script[^>]*>[\s\S]*?<\/script>/gi, '')
    .replace(/<iframe[^>]*>[\s\S]*?<\/iframe>/gi, '')
    .replace(/<object[^>]*>[\s\S]*?<\/object>/gi, '')
    .replace(/<embed[^>]*>/gi, '')
    .replace(/on\w+\s*=\s*"[^"]*"/gi, '')
    .replace(/on\w+\s*=\s*'[^']*'/gi, '')
    .replace(/on\w+\s*=\s*[^\s>]*/gi, '')
    .replace(/javascript\s*:/gi, '')
}

function renderAiHtml(text) {
  if (!text) return ''
  try {
    // 修复 LLM 输出的 markdown 格式问题：##Heading → ## Heading，-item → - item
    let fixed = text
      .replace(/^(#{1,6})([^\s#])/gm, '$1 $2')
      .replace(/^(\s*)([-*])([^\s])/gm, '$1$2 $3')
    return sanitizeHtml(marked.parse(fixed))
  } catch {
    return text
  }
}

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
  return String(s || '').toLowerCase().replaceAll(/\s+/g, '').replaceAll(/[，。、\-—…！？：；""''（）【】《》()\[\]{}<>]/g, '')
}

function extractExplicitPath(raw) {
  const m = String(raw || '').match(/(?:^|\s)(\/[a-z0-9\-\/]+)(?:\s|$)/i)
  if (!m?.[1]) return null
  return NAV_ITEMS.some((x) => x.to === m[1]) ? m[1] : null
}

function extractNavTarget(raw) {
  const s0 = String(raw || '').trim()
  if (!s0) return null
  const explicit = extractExplicitPath(s0)
  if (explicit) { const item = NAV_ITEMS.find((x) => x.to === explicit); return item ? { to: item.to, title: item.title } : { to: explicit, title: explicit } }
  const s = normalizeText(s0)
  if (!/打开|进入|跳转|导航|带我去|去/.test(s) && !/页面|界面|菜单|功能|模块/.test(s)) return null
  if (s.includes('2048') || s.includes('小游戏')) return { to: '/games/2048', title: '2048' }
  if (s.includes('仪表盘') || s.includes('首页') || s.includes('主页')) return { to: '/', title: '仪表盘' }
  if (s.includes('课表') || s.includes('plan')) return { to: '/plan', title: '学习计划' }
  if (s.includes('学习计划') || (s.includes('计划') && !s.includes('排程'))) return { to: '/plan', title: '学习计划' }
  if (s.includes('目标') || s.includes('任务')) return { to: '/goals', title: '目标' }
  if (s.includes('随笔') || s.includes('日记') || s.includes('复盘')) return { to: '/journals', title: '随笔' }
  if (s.includes('日程') || s.includes('排程') || s.includes('日历')) return { to: '/schedule', title: '日程' }
  if (s.includes('资源') || s.includes('课程')) return { to: '/resources', title: '资源' }
  if (s.includes('打卡')) return { to: '/punch', title: '打卡' }
  if (s.includes('画像') || s.includes('我的')) return { to: '/profile', title: '画像' }
  return null
}

function extractNavigateDirective(text) {
  const out = []
  for (const line of String(text || '').split('\n')) {
    const m = String(line || '').match(/(?:跳转|打开|进入)\s*[:：]?\s*(\/[a-z0-9\-\/]+)/i)
    if (m?.[1] && NAV_ITEMS.some((x) => x.to === m[1]) && !out.includes(m[1])) out.push(m[1])
  }
  return out
}

function stripNavigateDirective(text) {
  return String(text || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n').replace(/(?:跳转|打开|进入)\s*[:：]?\s*\/[a-z0-9\-\/]+/gi, '').replace(/\n{3,}/g, '\n\n').trim()
}

function dedupParagraphs(text) {
  if (!text) return text

  // 规范化：合并 3 个以上的连续换行为 2 个，避免 LLM 多余空行导致段落边界不一致
  const normalized = text.replace(/\n{3,}/g, '\n\n')
  const paras = normalized.split(/\n\n+/)
  const seen = new Set()
  const seenLines = new Set()
  const seenLongLines = []
  const seenSections = new Set()
  const result = []

  for (const p of paras) {
    const key = p.replace(/\s+/g, ' ').trim()
    if (key.length < 3) { result.push(p); continue }
    if (seen.has(key)) continue

    const lines = p.split(/\n/).map(l => l.replace(/\s+/g, ' ').trim()).filter(l => l.length > 3)

    // 检测重复的 markdown 标题段（## 本周打卡 / ## 随笔回顾 / ## 小结 等）
    const headingMatch = key.match(/^##\s+(.+)/)
    if (headingMatch) {
      const h = headingMatch[1]
      if (seenSections.has(h)) continue
      seenSections.add(h)
    }

    let isDup = false

    if (lines.length >= 3) {
      const dupCount = lines.filter(l => seenLines.has(l)).length
      if (dupCount > lines.length * 0.6) isDup = true
    } else if (lines.length === 1 && lines[0].length > 50) {
      // 长单行段落：模糊匹配已见过的长行，防止 LLM 用不同换行位置重复输出同一内容
      for (const prev of seenLongLines) {
        const shorter = prev.length < lines[0].length ? prev : lines[0]
        const longer = prev.length < lines[0].length ? lines[0] : prev
        if (longer.includes(shorter)) { isDup = true; break }
      }
    }

    if (isDup) continue

    seen.add(key)
    for (const l of lines) {
      seenLines.add(l)
      if (l.length > 50) seenLongLines.push(l)
    }
    result.push(p)
  }

  return result.join('\n\n')
}

let _msgIdCounter = 0
function nextMsgId() { return 'm_' + (++_msgIdCounter) + '_' + Date.now() }

export const useAssistantStore = defineStore('assistant', {
  state: () => ({
    initialized: false, minimized: false, x: null, y: null, width: 380, height: 520,
    adviceText: '', chatOpen: false, chatInput: '', chatLoading: false, chatMessages: [], navRequest: null,
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
      const oh = this.minimized ? 48 : this.height
      this.minimized = !this.minimized
      const nh = this.minimized ? 48 : this.height
      if (this.y !== null) {
        let ny = this.y + (oh - nh)
        if (typeof window !== 'undefined') ny = Math.max(16, Math.min(window.innerHeight - nh - 16, ny))
        this.y = ny
      }
    },
    openChat() { this.chatOpen = true; this.minimized = false },
    closeChat() { this.chatOpen = false },
    requestNavigate(to, title) {
      if (!to) return
      this.navRequest = { to: String(to), title: String(title || to), at: Date.now() }
    },
    clearNavRequest() { this.navRequest = null },
    setCareText(text) {
      const t = String(text || '').trim()
      if (t) this.adviceText = t
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
          let buf = '', sseBuf = '', lastFlush = 0, lastFlushed = ''
          const flush = () => { if (buf !== lastFlushed) { lastFlushed = buf; aiMsg.text = buf; lastFlush = Date.now() } }
          const processSSE = () => {
            const lines = sseBuf.split('\n'); sseBuf = lines.pop() || ''
            let data = [], inData = false, inEvent = false
            for (const l of lines) {
              if (l.startsWith('event:')) { inData = false; inEvent = true }
              else if (l.startsWith('data:')) { if (!inEvent) data.push(l.slice(5).trimStart()); inData = true; inEvent = false }
              else if (inData && !inEvent) data.push(l)
            }
            if (data.length) {
              while (data.length && data[data.length - 1] === '') data.pop()
              const chunk = data.join('\n')
              if (chunk && !buf.endsWith(chunk)) buf += chunk
            }
            if (Date.now() - lastFlush > 50) flush()
          }
          while (true) {
            const { value, done } = await reader.read()
            if (done) { processSSE(); flush(); break }
            sseBuf += decoder.decode(value, { stream: true })
            processSSE()
          }
          if (aiMsg.text && aiMsg.text.trim()) {
            aiMsg.text = dedupParagraphs(aiMsg.text.trim())
          } else {
            aiMsg.text = '我暂时没想好，可以换个问法吗？'
          }
          const navPaths = extractNavigateDirective(aiMsg.text)
          if (navPaths?.length) {
            aiMsg.navs = navPaths.map(p => { const it = NAV_ITEMS.find(x => x.to === p); return { to: p, title: it?.title || p } })
            aiMsg.text = stripNavigateDirective(aiMsg.text)
          }
          aiMsg.html = renderAiHtml(aiMsg.text)
        } finally { clearTimeout(t) }
      } catch (e) {
        const msg = String(e?.message || '服务异常，请稍后再试')
        if (aiMsg) aiMsg.text = msg
        else this.chatMessages.push({ _key: nextMsgId(), role: 'assistant', text: msg })
      } finally { this.chatLoading = false }
    },
  },
})