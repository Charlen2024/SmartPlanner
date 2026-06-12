import { defineStore } from 'pinia'
import api from '../plugins/api'
import { renderMarkdown, renderMarkdownStreaming } from '../plugins/markdown'

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

function normalizeWhitespace(text) {
  return text
    .replace(/\r\n/g, '\n').replace(/\r/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

// Auto-link known app paths like /journals /punch that appear as plain text
function autoLinkNavPaths(text) {
  const paths = ['/journals', '/punch', '/goals', '/schedule', '/resources', '/profile', '/games/2048']
  // Protect existing markdown links [text](/path) from being double-processed
  const protected_ = new Map()
  let idx = 0
  let work = text.replace(/\]\((\/[a-z0-9\-\/]+)\)/g, (match) => {
    const key = `__PROTECTED_LINK_${idx}__`
    protected_.set(key, match)
    idx++
    return key
  })
  for (const path of paths) {
    const escaped = path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const re = new RegExp(`(?<![\\w([/])${escaped}(?![\\w)\\]])`, 'g')
    work = work.replace(re, `[${path}](${path})`)
  }
  for (const [key, original] of protected_) {
    work = work.replace(key, original)
  }
  return work
}

function renderAiHtml(text, streaming = false) {
  if (!text) return ''
  const fixed = autoLinkNavPaths(normalizeWhitespace(text))
  const html = streaming ? renderMarkdownStreaming(fixed) : renderMarkdown(fixed)
  return html ? sanitizeHtml(html) : text
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

// Normalize markdown links to plain paths, e.g. [/punch](/punch) → /punch
function normalizeNavLinks(text) {
  return String(text || '').replace(/\[([^\]]*)\]\((\/[a-z0-9\-\/]+)\)/g, '$2')
}

function extractNavigateDirective(text) {
  const out = []
  // Match both single-path "跳转: /punch" and multi-path "跳转: /punch | /journals"
  const re = /(?:跳转|打开|进入)\s*[:：]?\s*(\/[a-z0-9\-\/]+(?:\s*[|,，、]\s*\/[a-z0-9\-\/]+)*)/gi
  for (let line of String(text || '').split('\n')) {
    line = normalizeNavLinks(line)
    let m
    while ((m = re.exec(line)) !== null) {
      const paths = m[1].split(/[|,，、]/).map(p => p.trim()).filter(Boolean)
      for (const p of paths) {
        if (NAV_ITEMS.some((x) => x.to === p) && !out.includes(p)) out.push(p)
      }
    }
  }
  return out
}

function stripNavigateDirective(text) {
  return String(text || '')
    .replace(/\r\n/g, '\n').replace(/\r/g, '\n')
    .split('\n')
    .filter(line => {
      const normalized = normalizeNavLinks(line)
      return !/^(?:跳转|打开|进入)\s*[:：]?\s*(?:\/[a-z0-9\-\/]+[\s|,，、]*)+$/gi.test(normalized)
    })
    .join('\n')
    .replace(/\n{3,}/g, '\n\n').trim()
}

const TOOL_FRIENDLY_NAME = {
  listGoals: '正在查看你的目标',
  listPendingTasks: '正在获取待办任务',
  listGoalTasks: '正在获取目标任务',
  listTodaySchedules: '正在查询今日排程',
  listTaskSchedules: '正在查询排程',
  listClasses: '正在查询课表',
  listPunchRecords: '正在查询打卡记录',
  listRecentJournals: '正在查询随笔',
  searchPersonalData: '正在检索个人资料',
  getWeather: '正在查询天气',
}

function mapToolName(raw) {
  if (!raw) return null
  // Strip arguments, keep only the tool name
  const toolName = raw.split(/[(\s]/)[0]?.trim()
  if (!toolName) return raw
  return TOOL_FRIENDLY_NAME[toolName] || ('正在执行: ' + toolName)
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
    chatInput: '', chatLoading: false, chatMessages: [], navRequest: null,
  }),
  actions: {
    async init() {
      if (this.initialized) return
      this.initialized = true
      // Pre-build agent on page load so first chat is fast
      api.post('/agent/warmup').catch(() => {})
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
    requestNavigate(to, title) {
      if (!to) return
      this.navRequest = { to: String(to), title: String(title || to), at: Date.now() }
    },
    clearNavRequest() { this.navRequest = null },
    setCareText(text) {
      // hook for DefaultLayout SSE AGENT_REMINDER
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

        let token = localStorage.getItem('accessToken')
        let streamRes = null

        const doStream = async (accessToken) => {
          const ctrl = new AbortController()
          const t = setTimeout(() => ctrl.abort(), 120000)
          try {
            const res = await fetch('/api/agent/chat/stream', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json', ...(accessToken ? { Authorization: 'Bearer ' + accessToken } : {}) },
              body: JSON.stringify({ message: text }), signal: ctrl.signal,
            })
            if (res.status === 401) return { needRefresh: true }
            if (!res.ok) throw new Error('HTTP ' + res.status)
            return { res }
          } finally { clearTimeout(t) }
        }

        const first = await doStream(token)
        if (first.needRefresh) {
          const refreshToken = localStorage.getItem('refreshToken')
          if (refreshToken) {
            try {
              const refreshRes = await api.post('/auth/refresh', { refreshToken })
              const newAccess = refreshRes?.data?.data?.accessToken
              const newRefresh = refreshRes?.data?.data?.refreshToken
              if (newAccess) {
                localStorage.setItem('accessToken', newAccess)
                if (newRefresh) localStorage.setItem('refreshToken', newRefresh)
                token = newAccess
                const retry = await doStream(token)
                if (retry.res) streamRes = retry.res
                else if (retry.needRefresh) throw new Error('会话已过期，请重新登录')
              } else {
                throw new Error('会话已过期，请重新登录')
              }
            } catch (e) {
              if (e.message === '会话已过期，请重新登录') throw e
              const retry2 = await doStream(token)
              if (retry2.res) streamRes = retry2.res
              else throw new Error('服务异常，请稍后再试')
            }
          }
        } else if (first.res) {
          streamRes = first.res
        }

        if (!streamRes) {
          aiMsg.text = '服务连接失败，请稍后再试'
          return
        }

        const reader = streamRes.body?.getReader?.()
        if (!reader) { aiMsg.text = String(await streamRes.text() || '服务返回为空'); return }
        const decoder = new TextDecoder('utf-8')
        let buf = '', sseBuf = '', lastFlush = 0, lastFlushed = '', pendingToolLine = ''
        const MIN_FIRST_FLUSH = 20 // wait until we have enough text to avoid fragmenting the first word
        let hasFlushed = false
        const MIN_PARAGRAPH_CHARS = 60 // collapse \n\n → space until we have enough content
        const flush = () => {
          if (buf !== lastFlushed) {
            if (!hasFlushed && buf.length < MIN_FIRST_FLUSH) return
            hasFlushed = true
            lastFlushed = buf
            // Strip leading newlines from SSE framing
            let display = buf.replace(/^\n+/, '')
            // During early streaming, collapse double-newlines to avoid premature paragraph breaks
            // ("我\n\n来查查" would otherwise show "我" as a separated paragraph)
            if (display.length < MIN_PARAGRAPH_CHARS) {
              display = display.replace(/\n\n+/g, '')
            }
            aiMsg.text = display
            aiMsg.html = renderAiHtml(display, true)
            lastFlush = Date.now()
          }
        }
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
            // Intercept tool call markers — inject styled inline lines
            if (chunk.startsWith('__SP_TOOL:CALL:')) {
              const raw = chunk.slice(15)
              const name = raw.endsWith('__') ? raw.slice(0, -2) : raw
              const friendly = mapToolName(name?.trim()) || '处理中...'
              const line = '\n\n<div class="sp-tool-call"><span class="sp-tool-icon">&#9900;</span><span>' + friendly + '</span></div>\n\n'
              pendingToolLine = line
              buf += line
              flush()
              return
            }
            if (chunk === '__SP_TOOL:DONE__') {
              if (pendingToolLine && buf.includes(pendingToolLine)) {
                buf = buf.replace(pendingToolLine, '')
              }
              pendingToolLine = ''
              flush()
              return
            }
            if (chunk && !buf.endsWith(chunk)) {
              // Filter raw JSON tool call attempts leaked by LLM (e.g. {"name": "listTodaySchedules", "arguments": {}})
              if (/^\{"name"\s*:\s*"\w+"\s*,\s*"arguments"\s*:/.test(chunk)) return
              buf += chunk
            }
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
      } catch (e) {
        const msg = String(e?.message || '服务异常，请稍后再试')
        if (aiMsg) aiMsg.text = msg
        else this.chatMessages.push({ _key: nextMsgId(), role: 'assistant', text: msg })
      } finally { this.chatLoading = false }
    },
  },
})