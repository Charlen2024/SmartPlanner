<script setup>
import { useAuthStore } from '../stores/auth'
import { computed, nextTick, onMounted, onUnmounted, ref, useSlots, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useDisplay, useTheme } from 'vuetify'
import { useNotifyStore } from '../stores/notify'
import { useAssistantStore } from '../stores/assistant'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const display = useDisplay()
const theme = useTheme()
const isDark = computed(() => theme.global.current.value.dark)
const chatScrollRef = ref(null)
watch(() => assistant.chatMessages?.length, () => {
  nextTick(() => {
    const el = chatScrollRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
})
const notify = useNotifyStore()
const assistant = useAssistantStore()
const slots = useSlots()

const dragging = ref(false)
const resizing = ref(false)
let dragStart = null
let resizeStart = null

const drawer = ref(true)
const rail = ref(false)
const notifMenu = ref(false)
const unreadCount = computed(() => (notify.reminders || []).filter(r => !r.read).length)

let sseSource = null
let sseRetryCount = 0
let sseRetryTimer = null
const SSE_MAX_DELAY = 30000

function stopSse() {
  if (sseRetryTimer) {
    clearTimeout(sseRetryTimer)
    sseRetryTimer = null
  }
  if (sseSource) {
    try { sseSource.close() } catch (e) {}
  }
  sseSource = null
  // sseRetryCount preserved for backoff
}

function startSse(token) {
  const t = String(token || '').trim()
  if (!t) return
  stopSse()
  sseSource = new EventSource(`/api/user/notifications/stream?access_token=${encodeURIComponent(t)}`)
  sseSource.addEventListener('GOAL_TASK_READY', (e) => {
    try {
      const data = JSON.parse(e.data)
      notify.addReminder(data)
      notify.success(data.content || 'AI任务拆解已完成！')
    } catch (err) {}
  })
  sseSource.addEventListener('SCHEDULE_DONE', (e) => {
    try {
      const data = JSON.parse(e.data)
      notify.addReminder(data)
      notify.success(data.content || '智能排程已完成！')
    } catch (err) {}
  })
  sseSource.addEventListener('SCHEDULE_FAILED', (e) => {
    try {
      const data = JSON.parse(e.data)
      notify.addReminder(data)
      notify.error(data.content || '智能排程失败！')
    } catch (err) {}
  })
  sseSource.addEventListener('AGENT_REMINDER', (e) => {
    try {
      const data = JSON.parse(e.data)
      notify.addReminder(data)
      notify.info(data.content || '新消息')
      assistant.setCareText(data.content || '')
    } catch (err) {}
  })
  sseSource.addEventListener('AGENT_BADGE', (e) => {
    try {
      const data = JSON.parse(e.data)
      notify.addReminder(data)
      notify.success(data.content || '成就解锁！')
    } catch (err) {}
  })
  sseSource.addEventListener('RESOURCE_ADVICE_DONE', (e) => {
    try {
      const data = JSON.parse(e.data)
      notify.addReminder(data)
      notify.success(data.content || '资源推荐已完成')
    } catch (err) {}
  })
  sseSource.addEventListener('RESOURCE_ADVICE_FAILED', (e) => {
    try {
      const data = JSON.parse(e.data)
      notify.addReminder(data)
      notify.error(data.content || '资源推荐失败')
    } catch (err) {}
  })
  sseSource.onerror = () => {
    stopSse()
    const delay = Math.min(SSE_MAX_DELAY, 1000 * Math.pow(2, sseRetryCount))
    sseRetryCount++
    sseRetryTimer = setTimeout(() => startSse(localStorage.getItem("accessToken")), delay)
  }
  sseSource.onopen = () => { sseRetryCount = 0 }
}

onMounted(() => {
  const saved = localStorage.getItem('theme')
  if (saved) theme.global.name.value = saved
  drawer.value = !display.mobile.value
  rail.value = !display.mobile.value

  const token = localStorage.getItem('accessToken')
  if (token) {
    assistant.init()
    startSse(token)
  }

  if (assistant.x == null || assistant.y == null) {
    const w = assistant.width || 380
    const h = assistant.height || 520
    const x = Math.max(16, window.innerWidth - w - 16)
    const y = Math.max(16, window.innerHeight - h - 16)
    assistant.setRect({ x, y, width: w, height: h })
  }
})


onUnmounted(() => {
  stopSse()
})

// Auto-scroll: watch last message text for streaming updates + message count for new messages
watch(() => {
  const msgs = assistant.chatMessages
  if (!msgs?.length) return 0
  return msgs.length + (msgs[msgs.length - 1]?.text?.length || 0)
}, () => {
  nextTick(() => {
    const el = chatScrollRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
})

watch(() => assistant.navRequest, (nav) => {
  if (nav?.to && router) {
    router.push(nav.to)
    assistant.clearNavRequest()
  }
})

const userLabel = computed(() => auth.me?.username ? `@${auth.me.username}` : '')

const title = computed(() => {
  const map = {
    dashboard: '仪表盘',
    plan: '学习计划',
    goals: '目标',
    journals: '随笔',
    schedule: '日程',
    resources: '资源',
    punch: '打卡',
    profile: '画像',
  }
  return map[route.name] || 'Vibe'
})

const menu = [
  { to: '/plan', title: '学习计划', icon: 'mdi-sitemap-outline' },
  { to: '/', title: '仪表盘', icon: 'mdi-view-dashboard' },
  { to: '/goals', title: '目标', icon: 'mdi-bullseye-arrow' },
  { to: '/journals', title: '随笔', icon: 'mdi-notebook' },
  { to: '/schedule', title: '日程', icon: 'mdi-calendar-clock' },
  { to: '/resources', title: '资源', icon: 'mdi-book-open-variant' },
  { to: '/punch', title: '打卡', icon: 'mdi-checkbox-multiple-marked' },
  { to: '/profile', title: '画像', icon: 'mdi-account-circle' },
  { to: '/games/2048', title: '2048', icon: 'mdi-grid' },
]


function toggleTheme() {
  const next = isDark.value ? 'vibeLight' : 'vibeDark'
  theme.global.name.value = next
  localStorage.setItem('theme', next)
}

function logout() {
  auth.logout()
  assistant.$reset()
  router.push('/login')
}

const quickPrompts = ['今天该做什么？', '总结本周进度', '给我一个学习建议']

function chatHint() {
  if (assistant.chatMessages?.length) return ''
  return '你可以向我提问，如：我今天应该先做哪个任务？/ 这周目标怎么拆更合理？'
}

function clampRect({ x, y, width, height, isMinimized }) {
  const w = Math.max(260, Math.min(900, width))
  const h = isMinimized ? 48 : Math.max(120, Math.min(900, height))
  const maxX = Math.max(16, window.innerWidth - w - 16)
  const maxY = Math.max(16, window.innerHeight - h - 16)
  const nx = Math.max(16, Math.min(maxX, x))
  const ny = Math.max(16, Math.min(maxY, y))
  return { x: nx, y: ny, width: w, height: h }
}

function onDragStart(e) {
  if (!e || e.button !== 0) return
  const target = e.target
  if (target?.closest?.('.vibe-agent-actions')) return
  if (target?.closest?.('.resize-handle')) return
  if (target?.closest?.('button, a, input, textarea, .v-input, .v-field, .v-btn, .v-progress-circular')) return
  dragging.value = true
  const startX = assistant.x ?? 16
  const startY = assistant.y ?? 16
  dragStart = { mouseX: e.clientX, mouseY: e.clientY, startX, startY }
  window.addEventListener('pointermove', onDragMove, { passive: false })
  window.addEventListener('pointerup', onDragEnd, { passive: false })
  e.preventDefault()
}

function onDragMove(e) {
  if (!dragging.value || !dragStart) return
  const dx = e.clientX - dragStart.mouseX
  const dy = e.clientY - dragStart.mouseY
  const rect = clampRect({
    x: dragStart.startX + dx,
    y: dragStart.startY + dy,
    width: assistant.width,
    height: assistant.height,
    isMinimized: assistant.minimized,
  })
  assistant.setRect(rect)
  e.preventDefault()
}

function onDragEnd() {
  dragging.value = false
  dragStart = null
  window.removeEventListener('pointermove', onDragMove)
  window.removeEventListener('pointerup', onDragEnd)
}

function onResizeStart(e, dir = 'se') {
  if (!e || e.button !== 0) return
  resizing.value = true
  resizeStart = {
    mouseX: e.clientX,
    mouseY: e.clientY,
    startW: assistant.width,
    startH: assistant.height,
    startX: assistant.x ?? 16,
    startY: assistant.y ?? 16,
    dir,
  }
  window.addEventListener('pointermove', onResizeMove, { passive: false })
  window.addEventListener('pointerup', onResizeEnd, { passive: false })
  e.preventDefault()
}

function onResizeMove(e) {
  if (!resizing.value || !resizeStart) return
  const dx = e.clientX - resizeStart.mouseX
  const dy = e.clientY - resizeStart.mouseY
  const { startX, startY, startW, startH, dir } = resizeStart

  let newW = startW
  let newH = startH
  let newX = startX
  let newY = startY

  if (dir.includes('e')) newW = startW + dx
  if (dir.includes('s')) newH = startH + dy
  if (dir.includes('w')) {
    newW = startW - dx
    newX = startX + dx
  }
  if (dir.includes('n')) {
    newH = startH - dy
    newY = startY + dy
  }

  const minW = 260, maxW = 900
  const minH = 120, maxH = 900

  if (newW < minW) {
    if (dir.includes('w')) newX -= (minW - newW)
    newW = minW
  } else if (newW > maxW) {
    if (dir.includes('w')) newX -= (maxW - newW)
    newW = maxW
  }

  if (newH < minH) {
    if (dir.includes('n')) newY -= (minH - newH)
    newH = minH
  } else if (newH > maxH) {
    if (dir.includes('n')) newY -= (maxH - newH)
    newH = maxH
  }

  if (dir.includes('e') && newX + newW > window.innerWidth - 16) {
    newW = window.innerWidth - 16 - newX
  }
  if (dir.includes('s') && newY + newH > window.innerHeight - 16) {
    newH = window.innerHeight - 16 - newY
  }
  if (dir.includes('w') && newX < 16) {
    newW -= (16 - newX)
    newX = 16
  }
  if (dir.includes('n') && newY < 16) {
    newH -= (16 - newY)
    newY = 16
  }

  assistant.setRect({
    x: newX,
    y: newY,
    width: newW,
    height: newH,
  })
  e.preventDefault()
}

function onResizeEnd() {
  resizing.value = false
  resizeStart = null
  window.removeEventListener('pointermove', onResizeMove)
  window.removeEventListener('pointerup', onResizeEnd)
}
</script>

<template>
  <v-layout class="vibe-shell">
    <v-app-bar elevation="0" height="64" class="vibe-appbar">
      <v-app-bar-nav-icon @click="drawer = !drawer" />
      <v-app-bar-title class="font-weight-semibold">
        {{ title }}
        <span v-if="userLabel" class="vibe-user">{{ userLabel }}</span>
      </v-app-bar-title>
      <v-spacer />
      <v-btn  class="mr-2" @click="toggleTheme">
        {{ isDark ? '浅色' : '深色' }}
      </v-btn>
      <v-menu v-model="notifMenu" :close-on-content-click="false" location="bottom end">
        <template #activator="{ props: menuProps }">
          <v-badge :model-value="unreadCount > 0" :content="unreadCount" color="error" overlap>
            <v-btn v-bind="menuProps" icon="mdi-bell-outline"  class="mr-2" />
          </v-badge>
        </template>
        <v-card min-width="340" max-width="420" max-height="480" class="overflow-y-auto">
          <div class="d-flex align-center pa-3 border-b">
            <span class="text-subtitle-1 font-weight-semibold">消息通知</span>
            <v-spacer />
            <v-btn v-if="notify.reminders.length" size="small"  @click="notify.clearReminders()">清空</v-btn>
          </div>
          <div v-if="!notify.reminders.length" class="pa-6 text-center text-medium-emphasis">
            <v-icon size="40" class="mb-2">mdi-bell-off-outline</v-icon>
            <div class="text-caption">暂无消息</div>
          </div>
          <v-list v-else density="compact" class="py-1">
            <v-list-item
                v-for="r in notify.reminders"
                :key="r._key"
                :class="{ 'bg-primary-lighten-5': !r.read }"
                @click="notify.markReminderRead(r._key); if (r.payload?.nav) { router.push(r.payload.nav); notifMenu = false }"
            >
              <template #prepend>
                <v-icon :color="r.payload?.level === 'warning' ? 'warning' : r.payload?.level === 'success' ? 'success' : undefined" size="18">
                  {{ r.type === 'AGENT_BADGE' ? 'mdi-trophy' : r.type === 'AGENT_REMINDER' ? 'mdi-bell-ring' : 'mdi-bell' }}
                </v-icon>
              </template>
              <v-list-item-title class="text-body-2" style="white-space: normal">{{ r.content }}</v-list-item-title>
              <v-list-item-subtitle class="text-caption mt-1">
                {{ new Date(r.ts).toLocaleString('zh-CN', { month:'numeric', day:'numeric', hour:'2-digit', minute:'2-digit' }) }}
                <v-chip v-if="r.payload?.nav" size="x-small" variant="tonal" class="ml-1">可跳转</v-chip>
              </v-list-item-subtitle>
            </v-list-item>
          </v-list>
        </v-card>
      </v-menu>
      <v-btn variant="tonal" color="primary" class="ml-2" @click="logout">退出</v-btn>
    </v-app-bar>

    <v-navigation-drawer
        v-model="drawer"
        :rail="rail"
        :temporary="display.mobile.value"
        :permanent="!display.mobile.value"
        width="288"
        rail-width="76"
        class="vibe-drawer"
    >
      <div v-if="!rail" class="px-3 pt-3 pb-2">
        <v-card variant="tonal" color="primary" class="pa-3 rounded-lg">
          <div class="text-subtitle-2 font-weight-semibold">SmartPlanner</div>
          <div class="text-caption">学习 · 计划 · 打卡</div>
        </v-card>
      </div>

      <v-list nav density="compact" class="mt-1">

        <v-list-item
            v-for="m in menu"
            :key="m.to"
            :to="m.to"
            :exact="m.to === '/'"
            :prepend-icon="m.icon"
            rounded="lg"
        >
          <v-list-item-title v-if="!rail" class="text-body-2">{{ m.title }}</v-list-item-title>
        </v-list-item>
      </v-list>

      <template #append>
        <div class="pa-2">
          <v-btn  size="small" @click="rail = !rail" :icon="rail ? 'mdi-chevron-right' : 'mdi-chevron-left'" class="mx-auto d-flex" />
        </div>
      </template>
    </v-navigation-drawer>

    <v-main class="vibe-main">
      <v-container class="py-6" style="max-width: 1200px">
        <slot v-if="slots.default" />
        <router-view v-else />
      </v-container>
    </v-main>

    <div class="notif-stack">
      <v-alert
          v-for="(n, idx) in notify.items"
          :key="n.id"
          :text="n.message"
          :type="n.type"
          closable
          density="compact"
          variant="tonal"
          class="notif-toast"
          @click:close="notify.remove(n.id)"
      />
    </div>

    <div
        v-show="assistant.x !== null"
        class="vibe-agent"
        :class="{ minimized: assistant.minimized }"
        :style="{
        left: `${assistant.x ?? 16}px`,
        top: `${assistant.y ?? 16}px`,
        width: `${assistant.width ?? 380}px`,
      }"
    >
      <v-card
          class="vibe-agent-card d-flex flex-column"
          variant="tonal"
          :style="{
          height: assistant.minimized ? '48px' : `${assistant.height ?? 520}px`,
          overflow: 'hidden',
        }"
      >
        <div class="d-flex align-center px-3 py-2 vibe-agent-header flex-shrink-0" @pointerdown="onDragStart">
          <v-icon class="mr-2" color="primary" size="20">mdi-robot-outline</v-icon>
          <div class="text-subtitle-2 font-weight-semibold vibe-agent-title">Agent</div>
          <v-spacer />
          <div @pointerdown.stop>
            <v-btn size="small" variant="text" :icon="assistant.minimized ? 'mdi-arrow-expand' : 'mdi-window-minimize'" @click.stop="assistant.toggleMinimize" density="compact" />
          </div>
        </div>
        <v-divider v-show="!assistant.minimized" class="flex-shrink-0" />

        <!-- Messages area (scrollable) -->
        <div v-show="!assistant.minimized" ref="chatScrollRef" class="flex-grow-1 px-3 pt-3" style="overflow-y: auto; min-height: 0;">
          <div v-if="!assistant.chatMessages?.length && !assistant.chatLoading" class="d-flex flex-column align-center justify-center" style="height: 100%; opacity: 0.45; padding-top: 60px;">
            <v-icon size="48" class="mb-2">mdi-robot-outline</v-icon>
            <div class="text-caption">问我任何关于学习计划的问题</div>
          </div>
          <div v-if="assistant.chatMessages?.length">
            <div v-for="m in assistant.chatMessages" :key="m._key || m.text?.slice(0,8) + Math.random()" class="mb-3">
              <div :class="['vibe-chat-bubble', m.role === 'user' ? 'vibe-chat-user' : 'vibe-chat-ai']">
                <span style="white-space: pre-wrap;">{{ m.text }}</span>
                <div v-if="m.navs?.length" class="mt-2 d-flex flex-wrap ga-1">
                  <v-chip
                    v-for="nav in m.navs"
                    :key="nav.to"
                    size="x-small"
                    variant="tonal"
                    color="primary"
                    @click.stop="router.push(nav.to)"
                  >跳转: {{ nav.title }}</v-chip>
                </div>
              </div>
            </div>
          </div>

        </div>

        <!-- Input area (sticky bottom) -->
        <div v-show="!assistant.minimized" class="flex-shrink-0 px-3 pb-3 pt-1">
          <div class="d-flex flex-wrap ga-1 mb-2">
            <v-chip
              v-for="p in quickPrompts"
              :key="p"
              size="x-small"
              variant="tonal"
              :disabled="assistant.chatLoading"
              @click="assistant.chatInput = p; assistant.sendChat()"
            >{{ p }}</v-chip>
          </div>
          <div class="vibe-chat-bar">
            <v-text-field
                v-model="assistant.chatInput"
                :placeholder="chatHint()"
                variant="plain"
                density="compact"
                :disabled="assistant.chatLoading"
                class="vibe-chat-field"
                hide-details
                @keyup.enter="assistant.sendChat()"
            />
            <v-btn
                icon="mdi-send"
                :color="assistant.chatInput.trim() ? 'primary' : undefined"
                variant="text"
                size="small"
                :loading="assistant.chatLoading"
                :disabled="!assistant.chatInput.trim() && !assistant.chatLoading"
                @click="assistant.sendChat()"
            />
          </div>
        </div>

        <div v-show="!assistant.minimized" class="resize-handle resize-tl" @pointerdown.stop="onResizeStart($event, 'nw')" />
        <div v-show="!assistant.minimized" class="resize-handle resize-tr" @pointerdown.stop="onResizeStart($event, 'ne')" />
        <div v-show="!assistant.minimized" class="resize-handle resize-bl" @pointerdown.stop="onResizeStart($event, 'sw')" />
        <div v-show="!assistant.minimized" class="resize-handle resize-br vibe-agent-resize" @pointerdown.stop="onResizeStart($event, 'se')" />
      </v-card>
    </div>

  </v-layout>
</template>

<style scoped>
.vibe-shell {
  height: 100vh;
}
.vibe-appbar {
  backdrop-filter: blur(10px);
  background: rgba(var(--v-theme-surface), 0.85);
  border-bottom: 1px solid rgba(var(--v-theme-on-surface), 0.08);
}
.vibe-drawer {
  border-right: 1px solid rgba(var(--v-theme-on-surface), 0.08);
}
.vibe-main {
  background:
      radial-gradient(1200px 700px at 20% -10%, rgba(var(--v-theme-primary), 0.18), transparent 55%),
      radial-gradient(1000px 600px at 90% 0%, rgba(var(--v-theme-secondary), 0.16), transparent 55%),
      linear-gradient(180deg, rgba(var(--v-theme-background), 1), rgba(var(--v-theme-background), 1));
  overflow-y: auto;
}
.vibe-user {
  margin-left: 10px;
  font-size: 12px;
  opacity: 0.75;
}
.vibe-agent {
  position: fixed;
  z-index: 3000;
}
.vibe-agent-card {
  position: relative;
  border: 1px solid rgba(var(--v-theme-on-surface), 0.14);
  background: transparent !important;
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.18);
  will-change: width, height, transform;
}
.vibe-agent-card::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: rgba(var(--v-theme-surface), 0.92);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  transform: translateZ(0);
  will-change: width, height, transform;
  pointer-events: none;
}
.vibe-agent-card > * {
  position: relative;
  z-index: 1;
}
.vibe-agent-header {
  position: relative;
  z-index: 6;
  cursor: move;
  user-select: none;
  touch-action: none;
  background: linear-gradient(180deg, rgba(var(--v-theme-on-surface), 0.03), transparent);
}
.vibe-agent-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.vibe-agent-actions {
  margin-left: auto;
  margin-right: 18px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: nowrap;
  white-space: nowrap;
}
.vibe-agent-actions :deep(.v-btn) {
  min-width: 72px;
  padding-inline: 12px;
}
.vibe-agent-actions :deep(.v-btn__loader) {
  margin-inline-end: 8px;
}
.vibe-agent-loading {
  margin-left: 8px;
  opacity: 0.85;
}

.vibe-agent-resize {
  position: absolute;
  right: 2px;
  bottom: 2px;
  width: 18px;
  height: 18px;
  cursor: nwse-resize;
  touch-action: none;
  opacity: 0.6;
}
.resize-handle {
  position: absolute;
  width: 18px;
  height: 18px;
  z-index: 2;
}
.resize-tl { top: -6px; left: -6px; cursor: nwse-resize; }
.resize-tr { top: -6px; right: -6px; cursor: nesw-resize; }
.resize-bl { bottom: -6px; left: -6px; cursor: nesw-resize; }
.resize-br { bottom: -6px; right: -6px; cursor: nwse-resize; }
.vibe-agent-resize:hover {
  opacity: 1;
}
.vibe-agent-resize:after {
  content: '';
  position: absolute;
  right: 2px;
  bottom: 2px;
  width: 12px;
  height: 12px;
  border-right: 2px solid rgba(var(--v-theme-on-surface), 0.35);
  border-bottom: 2px solid rgba(var(--v-theme-on-surface), 0.35);
  border-radius: 2px;
}
.vibe-agent-chat {
  max-height: 240px;
  overflow-y: auto;
  padding: 8px 10px;
  border: 1px solid rgba(var(--v-theme-on-surface), 0.14);
  border-radius: 8px;
  background: rgba(var(--v-theme-surface), 0.74);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
}

.vibe-agent :deep(.v-field__overlay) {
  background: rgba(var(--v-theme-surface), 0.70) !important;
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
}

/* === Notification Toast === */
.notif-stack {
  position: fixed;
  top: 80px;
  right: 16px;
  z-index: 9999;
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-width: 420px;
}
.notif-toast {
  border-radius: 14px !important;
  backdrop-filter: blur(24px) saturate(180%) !important;
  -webkit-backdrop-filter: blur(24px) saturate(180%) !important;
}
/* Override v-alert tonal background to be more transparent */
.notif-toast.v-alert--variant-tonal {
  background: rgba(var(--v-theme-surface), 0.55) !important;
  box-shadow: 0 8px 32px rgba(0,0,0,0.12), 0 1px 3px rgba(0,0,0,0.06) !important;
  border: 1px solid rgba(var(--v-theme-on-surface), 0.07) !important;
}

/* Sidebar menu items */
.vibe-menu-item {
  margin: 2px 8px;
  border-radius: 10px;
}
/* Chat bubbles */
.vibe-chat-bubble {
  padding: 10px 14px;
  border-radius: 16px;
  max-width: 92%;
  word-break: break-word;
  line-height: 1.55;
  font-size: 13px;
}
.vibe-chat-user {
  margin-left: auto;
  background: rgba(var(--v-theme-primary), 0.15);
  border-bottom-right-radius: 6px;
}
.vibe-chat-ai {
  margin-right: auto;
  background: rgba(var(--v-theme-on-surface), 0.06);
  border-bottom-left-radius: 6px;
}
.vibe-chat-scroll {
  scroll-behavior: smooth;
}
.vibe-chat-input :deep(.v-field) {
  background: transparent !important;
}
.vibe-chat-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  border-radius: 16px;
  background: rgba(var(--v-theme-on-surface), 0.04);
  border: 1px solid rgba(var(--v-theme-on-surface), 0.08);
  transition: border-color 0.2s;
}
.vibe-chat-bar:focus-within {
  border-color: rgba(var(--v-theme-primary), 0.35);
  background: rgba(var(--v-theme-on-surface), 0.06);
}
.vibe-chat-field {
  flex: 1;
}
.vibe-chat-field :deep(.v-field__overlay) {
  opacity: 0 !important;
}
.vibe-chat-field :deep(.v-field__input) {
  font-size: 13px !important;
  padding-top: 6px !important;
  padding-bottom: 6px !important;
}
.vibe-chat-input :deep(.v-field__overlay) {
  opacity: 0 !important;
}
/* Agent card stronger glass */
.vibe-agent-card {
  backdrop-filter: blur(20px) saturate(160%) !important;
  -webkit-backdrop-filter: blur(20px) saturate(160%) !important;
}
.vibe-agent-card::before {
  backdrop-filter: blur(20px) saturate(160%) !important;
  -webkit-backdrop-filter: blur(20px) saturate(160%) !important;
}
</style>