<script setup>
import { ref, computed, watch, onActivated, onBeforeUnmount, onDeactivated } from 'vue'
import api from '../plugins/api'
import { renderMarkdown } from '../plugins/markdown'

const topic = ref('')
const title = ref('')
const platform = ref('')
const url = ref('')
const summary = ref('')
const list = ref([])
const loading = ref(false)
const aiLoading = ref(false)
const crawlLoading = ref(false)
const crawlMsg = ref('')
const error = ref('')
const advice = ref('')
const searchMode = ref('') // 'fast' | 'rag'
const jobSeq = ref(0)
const aborted = ref(false)
const feedbackDialog = ref(false)
const feedbackNotes = ref('')
const feedbackDone = ref(false)
const feedbackTopic = ref('')
const showAddForm = ref(false)

const quickTopics = ['Java 多线程', 'Spring Boot', 'MySQL 索引', 'Redis 缓存', '分布式系统', '数据结构', 'Python 爬虫', '微服务']

const adviceHtml = computed(() => {
  if (!advice.value) return ''
  const fixed = advice.value
    .replace(/^(#{1,6})([^\s#])/gm, '$1 $2')
    .replace(/^(\s*)([-*])([^\s])/gm, '$1$2 $3')
  return renderMarkdown(fixed)
})

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

let topicDebounce = null

function platformIcon(platform) {
  const p = (platform || '').toLowerCase()
  if (p.includes('bilibili') || p.includes('b站')) return 'mdi-video-box'
  if (p.includes('zhihu') || p.includes('知乎')) return 'mdi-forum'
  if (p.includes('github')) return 'mdi-github'
  if (p.includes('course') || p.includes('mooc') || p.includes('慕课')) return 'mdi-school'
  if (p.includes('youtube') || p.includes('b站')) return 'mdi-youtube'
  if (p.includes('csdn')) return 'mdi-language-css3'
  if (p.includes('掘金') || p.includes('juejin')) return 'mdi-gold'
  return 'mdi-open-in-new'
}

function platformColor(platform) {
  const p = (platform || '').toLowerCase()
  if (p.includes('bilibili') || p.includes('b站')) return '#fb7299'
  if (p.includes('zhihu') || p.includes('知乎')) return '#0066ff'
  if (p.includes('github')) return '#333333'
  if (p.includes('youtube')) return '#ff0000'
  return undefined
}

async function searchFast() {
  if (!topic.value?.trim()) return
  searchMode.value = 'fast'
  loading.value = true
  error.value = ''
  advice.value = ''
  crawlMsg.value = ''
  list.value = []
  try {
    const res = await api.get('/user/resources/search', {
      params: { topic: topic.value.trim() },
      timeout: 15000
    })
    list.value = res?.data?.data ?? []
    if (list.value.length === 0) {
      error.value = '未找到相关资源'
    }
  } catch (e) {
    if (e?.response?.status === 401) {
      error.value = '未登录或登录已过期，请重新登录后再检索'
    } else {
      error.value = e?.response?.data?.message || e?.message || '检索失败'
    }
  } finally {
    loading.value = false
  }
}

async function searchRag() {
  if (!topic.value?.trim()) {
    advice.value = ''
    error.value = ''
    list.value = []
    return
  }
  searchMode.value = 'rag'
  const seq = (jobSeq.value += 1)
  aiLoading.value = true
  error.value = ''
  advice.value = ''
  crawlMsg.value = ''
  list.value = []
  try {
    const startRes = await api.post('/user/resources/search/advice/jobs', { topic: topic.value.trim() }, { timeout: 30000 })
    const jobId = startRes?.data?.data?.jobId
    if (!jobId) throw new Error('启动检索任务失败')

    for (let i = 0; i < 180; i++) {
      await sleep(1000)
      if (aborted.value) return
      if (seq !== jobSeq.value) return
      const st = await api.get(`/user/resources/search/advice/jobs/${jobId}`, { timeout: 30000 })
      const data = st?.data?.data ?? null
      if (data?.status === 'DONE') {
        const r = data?.result ?? null
        advice.value = r?.advice ?? ''
        list.value = r?.resources ?? []
        return
      }
      if (data?.status === 'FAILED') {
        throw new Error(data?.error || '检索失败')
      }
    }
    throw new Error('等待检索结果超时，请稍后重试')
  } catch (e) {
    if (aborted.value) return
    if (e?.response?.status === 401) {
      error.value = '未登录或登录已过期，请重新登录后再检索'
    } else if (e?.code === 'ECONNABORTED') {
      error.value = '请求超时，请稍后重试'
    } else {
      error.value = e?.response?.data?.message || e?.message || '检索失败'
    }
  } finally {
    if (seq === jobSeq.value) aiLoading.value = false
  }
}

function openFeedback() {
  feedbackTopic.value = topic.value?.trim() || ''
  feedbackNotes.value = ''
  feedbackDone.value = false
  feedbackDialog.value = true
}

function closeFeedback() {
  feedbackDialog.value = false
}

async function submitFeedback() {
  const t = feedbackTopic.value?.trim()
  if (!t) return
  crawlLoading.value = true
  error.value = ''
  crawlMsg.value = ''
  try {
    await api.post('/user/resources/crawl', null, {
      params: { topic: t, notes: feedbackNotes.value?.trim() || '' },
      timeout: 15000
    })
    feedbackDone.value = true
  } catch (e) {
    closeFeedback()
    if (e?.response?.status === 401) {
      error.value = '未登录或登录已过期'
    } else {
      error.value = e?.response?.data?.message || e?.message || '请求失败'
    }
  } finally {
    crawlLoading.value = false
  }
}

async function createResource() {
  if (!topic.value || !title.value) return
  try {
    await api.post('/user/resources', null, { params: { topic: topic.value, title: title.value, platform: platform.value, url: url.value, summary: summary.value } })
    title.value = ''
    platform.value = ''
    url.value = ''
    summary.value = ''
    showAddForm.value = false
    await loadLocal()
  } catch (e) {
    error.value = e?.response?.data?.message || e?.message || '保存失败'
  }
}

async function loadLocal() {
  if (!topic.value?.trim()) {
    list.value = []
    return
  }
  const res = await api.get('/user/resources', { params: { topic: topic.value } })
  list.value = res?.data?.data ?? []
}

async function remove(id) {
  try {
    await api.delete(`/user/resources/${id}`)
    await loadLocal()
  } catch (e) {
    error.value = e?.response?.data?.message || e?.message || '删除失败'
  }
}

watch(
  topic,
  (v) => {
    clearTimeout(topicDebounce)
    if (!v?.trim()) {
      advice.value = ''
      error.value = ''
      crawlMsg.value = ''
      list.value = []
      return
    }
    topicDebounce = setTimeout(() => loadLocal(), 400)
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  aborted.value = true
  clearTimeout(topicDebounce)
})
onDeactivated(() => {
  aborted.value = true
  clearTimeout(topicDebounce)
})
onActivated(() => {
  aborted.value = false
})
</script>

<template>
  <!-- ====== Search Bar ====== -->
  <v-card class="mb-4 pa-4">
    <div class="text-h6 font-weight-bold mb-3 d-flex align-center">
      <v-icon icon="mdi-magnify" class="mr-2" color="primary" />
      资源检索
    </div>

    <v-row dense align="center">
      <v-col cols="12" md="8">
        <v-text-field
          v-model="topic"
          label="输入你想学的主题..."
          variant="outlined"
          density="comfortable"
          hide-details
          @keyup.enter="searchFast"
        >
          <template #prepend-inner>
            <v-icon icon="mdi-text-search" size="20" class="mt-1" />
          </template>
        </v-text-field>
      </v-col>
      <v-col cols="12" md="4" class="d-flex ga-2">
        <v-btn
          color="primary"
          variant="elevated"
          :loading="loading"
          :disabled="!topic?.trim()"
          @click="searchFast"
          class="flex-grow-1"
        >
          <v-icon icon="mdi-lightning-bolt" size="18" class="mr-1" />
          快速搜索
        </v-btn>
        <v-btn
          variant="tonal"
          :loading="aiLoading"
          :disabled="!topic?.trim()"
          @click="searchRag"
          class="flex-grow-1"
        >
          <v-icon icon="mdi-robot" size="18" class="mr-1" />
          AI 推荐
        </v-btn>
      </v-col>
    </v-row>

    <!-- Quick topic chips -->
    <div class="mt-3 d-flex flex-wrap align-center ga-1">
      <span class="text-caption text-medium-emphasis mr-1">试试：</span>
      <v-chip
        v-for="qt in quickTopics"
        :key="qt"
        size="small"
        variant="outlined"
        :color="topic === qt ? 'primary' : undefined"
        @click="topic = qt; searchFast()"
        class="cursor-pointer"
      >
        {{ qt }}
      </v-chip>
    </div>
  </v-card>

  <!-- ====== Messages ====== -->
  <v-alert v-if="error" type="error" variant="tonal" class="mb-4" density="compact">{{ error }}</v-alert>
  <v-alert v-if="crawlMsg" type="success" variant="tonal" class="mb-4" density="compact">
    {{ crawlMsg }}
  </v-alert>

  <!-- ====== AI Advice Card ====== -->
  <v-card v-if="advice" class="mb-4 advice-card">
    <v-card-title class="d-flex align-center pb-0">
      <v-icon icon="mdi-robot-outline" class="mr-2" color="primary" />
      AI 学习建议
      <v-spacer />
      <v-chip size="x-small" variant="tonal" color="primary">AI 生成</v-chip>
    </v-card-title>
    <v-card-text>
      <div class="advice-content" v-html="adviceHtml" />
    </v-card-text>
  </v-card>

  <!-- ====== Results ====== -->
  <v-card>
    <v-card-title class="d-flex align-center">
      <v-icon icon="mdi-book-open-page-variant" class="mr-2" />
      学习资源
      <v-chip v-if="searchMode === 'fast' && !loading && list.length" size="small" color="primary" variant="tonal" class="ml-2">
        <v-icon icon="mdi-lightning-bolt" size="14" class="mr-1" />快速
      </v-chip>
      <v-chip v-if="searchMode === 'rag' && !aiLoading && list.length" size="small" variant="tonal" class="ml-2">
        <v-icon icon="mdi-robot" size="14" class="mr-1" />AI
      </v-chip>
      <v-spacer />
      <span v-if="list.length" class="text-caption text-medium-emphasis">{{ list.length }} 条结果</span>
    </v-card-title>
    <v-divider />

    <!-- Loading skeleton -->
    <div v-if="loading || aiLoading" class="pa-4">
      <div v-for="n in 3" :key="n" class="d-flex ga-3 mb-3">
        <v-skeleton-loader type="list-item-avatar-two-line" class="flex-grow-1" />
      </div>
    </div>

    <!-- Empty state -->
    <div v-else-if="list.length === 0" class="text-center py-10">
      <v-icon icon="mdi-book-search" size="64" class="mb-3 text-medium-emphasis" style="opacity:0.35" />
      <div class="text-h6 font-weight-medium text-medium-emphasis mb-1">探索你的学习资源</div>
      <div class="text-body-2 text-medium-emphasis" style="opacity:0.7">
        输入主题关键词，选择「快速搜索」即时匹配<br/>或「AI 推荐」获取深度学习建议与资源
      </div>
    </div>

    <!-- Resource cards -->
    <div v-else class="pa-2">
      <div
        v-for="(r, i) in list"
        :key="r.id || i"
        class="resource-item pa-3 mb-1 rounded"
      >
        <div class="d-flex align-start ga-3">
          <!-- Rank badge -->
          <div class="resource-rank">
            <span class="text-caption font-weight-bold">{{ i + 1 }}</span>
          </div>

          <!-- Content -->
          <div class="flex-grow-1" style="min-width:0">
            <div class="d-flex align-center ga-2 mb-1">
              <span class="text-body-1 font-weight-semibold resource-title">{{ r.title || r?.title || '未命名资源' }}</span>
              <v-icon
                v-if="r.platform || r?.platform"
                :icon="platformIcon(r.platform || r?.platform)"
                size="16"
                :color="platformColor(r.platform || r?.platform)"
              />
              <v-chip
                v-if="r.platform || r?.platform"
                size="x-small"
                variant="flat"
                class="resource-platform-chip"
                :style="{ background: (platformColor(r.platform || r?.platform) || 'rgb(var(--v-theme-primary))') + '18', color: platformColor(r.platform || r?.platform) || 'rgb(var(--v-theme-primary))' }"
              >
                {{ r.platform || r?.platform }}
              </v-chip>
            </div>
            <div
              v-if="r.summary || r.contentSummary"
              class="text-body-2 text-medium-emphasis resource-summary"
            >
              {{ r.summary || r.contentSummary }}
            </div>
          </div>

          <!-- Actions -->
          <div class="d-flex ga-1 flex-shrink-0">
            <v-btn
              v-if="r.url || r.sourceUrl"
              size="small"
              variant="tonal"
              color="primary"
              :href="r.url || r.sourceUrl"
              target="_blank"
              density="compact"
            >
              <v-icon icon="mdi-open-in-new" size="16" class="mr-1" />
              打开
            </v-btn>
            <v-btn
              v-if="r.id"
              size="small"
              variant="text"
              color="error"
              icon="mdi-delete-outline"
              density="compact"
              @click="remove(r.id)"
            />
          </div>
        </div>
      </div>
    </div>

    <v-divider />
    <v-card-actions class="justify-space-between pa-3 flex-wrap ga-2">
      <v-btn variant="text" size="small" class="text-medium-emphasis" @click="openFeedback">
        <v-icon icon="mdi-message-text-outline" size="16" class="mr-1" />
        找不到想要的？反馈给我们
      </v-btn>
      <v-btn variant="text" size="small" class="text-medium-emphasis" @click="showAddForm = !showAddForm">
        <v-icon :icon="showAddForm ? 'mdi-chevron-up' : 'mdi-plus-circle-outline'" size="16" class="mr-1" />
        {{ showAddForm ? '收起' : '手动添加资源' }}
      </v-btn>
    </v-card-actions>

    <!-- Manual add form (collapsible) -->
    <v-expand-transition>
      <div v-if="showAddForm">
        <v-divider />
        <div class="pa-4">
          <div class="text-subtitle-2 font-weight-semibold mb-3">手动添加资源</div>
          <v-row dense>
            <v-col cols="12" md="5">
              <v-text-field v-model="title" label="标题" variant="outlined" density="comfortable" hide-details />
            </v-col>
            <v-col cols="12" md="2">
              <v-text-field v-model="platform" label="平台" variant="outlined" density="comfortable" hide-details />
            </v-col>
            <v-col cols="12" md="4">
              <v-text-field v-model="url" label="链接" variant="outlined" density="comfortable" hide-details />
            </v-col>
            <v-col cols="12" md="1" class="d-flex">
              <v-btn color="primary" variant="tonal" :disabled="!topic || !title" @click="createResource" block density="comfortable">
                保存
              </v-btn>
            </v-col>
          </v-row>
          <v-text-field v-model="summary" label="摘要（选填）" variant="outlined" density="comfortable" class="mt-2" hide-details />
        </div>
      </div>
    </v-expand-transition>
  </v-card>

  <!-- ====== Feedback Dialog ====== -->
  <v-dialog v-model="feedbackDialog" max-width="480" transition="dialog-bottom-transition" persistent>
    <v-card>
      <v-card-title class="text-h6">补充资源</v-card-title>
      <v-card-text>
        <template v-if="!feedbackDone">
          <div class="text-body-2 mb-4 text-medium-emphasis">告诉我们你想找什么，后台会抓取更多资源</div>
          <v-text-field v-model="feedbackTopic" label="主题" variant="outlined" density="comfortable" />
          <v-textarea v-model="feedbackNotes" label="描述（选填）" variant="outlined" rows="3" density="comfortable"
            placeholder="例如：希望多找一些实战项目、入门教程..." />
        </template>
        <div v-else class="text-center py-6">
          <div class="text-h5 mb-2">感谢您的反馈</div>
          <div class="text-body-2 text-medium-emphasis">爬虫正在抓取相关资源，稍后重新搜索即可</div>
        </div>
      </v-card-text>
      <v-card-actions>
        <template v-if="!feedbackDone">
          <v-spacer />
          <v-btn variant="text" @click="closeFeedback">取消</v-btn>
          <v-btn variant="tonal" color="primary" :loading="crawlLoading" :disabled="!feedbackTopic?.trim()" @click="submitFeedback">
            提交反馈
          </v-btn>
        </template>
        <template v-else>
          <v-spacer />
          <v-btn variant="tonal" color="primary" @click="closeFeedback">知道了</v-btn>
        </template>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.advice-card {
  border-left: 4px solid rgb(var(--v-theme-primary));
}
.advice-content :deep(h1),
.advice-content :deep(h2),
.advice-content :deep(h3) {
  font-size: 1.05rem;
  margin: 0.75em 0 0.4em;
  font-weight: 700;
}
.advice-content :deep(h2) { font-size: 1rem; }
.advice-content :deep(h3) { font-size: 0.95rem; }
.advice-content :deep(p) {
  margin: 0.4em 0;
  line-height: 1.7;
}
.advice-content :deep(ul),
.advice-content :deep(ol) {
  padding-left: 1.3em;
  margin: 0.35em 0;
}
.advice-content :deep(li) {
  margin-bottom: 0.25em;
  line-height: 1.6;
}
.advice-content :deep(strong) {
  font-weight: 700;
  color: rgb(var(--v-theme-primary));
}
.advice-content :deep(code) {
  background: rgba(var(--v-theme-on-surface), 0.06);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 0.9em;
}
.advice-content :deep(blockquote) {
  border-left: 3px solid rgba(var(--v-theme-primary), 0.3);
  padding-left: 12px;
  margin: 0.5em 0;
  color: rgba(var(--v-theme-on-surface), 0.7);
}

.resource-item {
  transition: background 0.15s;
}
.resource-item:hover {
  background: rgba(var(--v-theme-on-surface), 0.03);
}
.resource-rank {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: rgba(var(--v-theme-primary), 0.08);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: rgb(var(--v-theme-primary));
}
.resource-title {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.resource-summary {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.55;
}
.resource-platform-chip {
  font-weight: 600;
  letter-spacing: 0.01em;
}

.cursor-pointer {
  cursor: pointer;
}
</style>
