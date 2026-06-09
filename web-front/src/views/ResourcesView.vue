<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import api from '../plugins/api'

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

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

let topicDebounce = null

// 快速搜索：直接调用 kNN 向量检索 + multiMatch 降级
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

// AI 推荐：异步 RAG job，返回 AI 学习建议 + 课程资源
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
    const startRes = await api.post('/user/resources/search/advice/jobs', { topic: topic.value.trim() }, { timeout: 15000 })
    const jobId = startRes?.data?.data?.jobId
    if (!jobId) throw new Error('启动检索任务失败')

    for (let i = 0; i < 180; i++) {
      await sleep(1000)
      if (aborted.value) return
      if (seq !== jobSeq.value) return
      const st = await api.get(`/user/resources/search/advice/jobs/${jobId}`, { timeout: 15000 })
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
</script>

<template>
  <v-row class="mb-2">
    <v-col cols="12" md="3">
      <v-text-field v-model="topic" label="主题" variant="outlined" @keyup.enter="searchFast" />
    </v-col>
    <v-col cols="12" md="auto">
      <v-btn color="primary" variant="elevated" :loading="loading" :disabled="!topic?.trim()" @click="searchFast">
        快速搜索
      </v-btn>
    </v-col>
    <v-col cols="12" md="auto">
      <v-btn variant="tonal" :loading="aiLoading" :disabled="!topic?.trim()" @click="searchRag">
        AI 推荐
      </v-btn>
    </v-col>
  </v-row>

  <v-alert v-if="advice" type="info" variant="tonal" class="mb-4" style="white-space: pre-wrap;">
    <template #title>AI 学习建议</template>
    {{ advice }}
  </v-alert>
  <v-alert v-if="crawlMsg" type="success" variant="tonal" class="mb-4">{{ crawlMsg }}</v-alert>
  <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

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

  <v-row class="mb-2">
    <v-col cols="12" md="3"><v-text-field v-model="title" label="标题" variant="outlined" /></v-col>
    <v-col cols="12" md="2"><v-text-field v-model="platform" label="平台" variant="outlined" /></v-col>
    <v-col cols="12" md="3"><v-text-field v-model="url" label="链接" variant="outlined" /></v-col>
    <v-col cols="12" md="3"><v-text-field v-model="summary" label="摘要" variant="outlined" /></v-col>
    <v-col cols="12" md="1"><v-btn @click="createResource">保存</v-btn></v-col>
  </v-row>

  <v-card>
    <v-card-title class="d-flex align-center">
      学习资源
      <v-chip v-if="searchMode === 'fast' && !loading" size="small" color="primary" variant="tonal" class="ml-2">快速</v-chip>
      <v-chip v-if="searchMode === 'rag' && !aiLoading" size="small" variant="tonal" class="ml-2">AI</v-chip>
    </v-card-title>
    <v-divider />
    <div v-if="list.length === 0 && !loading && !aiLoading" class="text-center py-8 text-medium-emphasis text-body-2">
      输入主题，点击快速搜索或 AI 推荐
    </div>
    <v-list v-else lines="two">
      <v-list-item
        v-for="(r, i) in list"
        :key="r.id || i"
        :title="r.title || r?.title"
      >
        <template #prepend>
          <span class="text-caption text-medium-emphasis" style="min-width:24px">{{ i + 1 }}</span>
        </template>
        <template #subtitle>
          <div class="d-flex align-center ga-2">
            <span>{{ r.platform || r?.platform }}</span>
            <span v-if="r.summary || r.contentSummary" class="text-caption">- {{ r.summary || r.contentSummary }}</span>
          </div>
        </template>
        <template #append>
          <v-btn size="small" variant="text" v-if="r.url || r.sourceUrl" :href="r.url || r.sourceUrl" target="_blank">打开</v-btn>
          <v-btn size="small" variant="text" v-if="r.id" color="error" @click="remove(r.id)">删除</v-btn>
        </template>
      </v-list-item>
    </v-list>
    <v-divider />
    <v-card-actions class="justify-center py-2">
      <v-btn variant="text" size="small" class="text-medium-emphasis" @click="openFeedback">
        找不到想要的？反馈给我们
      </v-btn>
    </v-card-actions>
  </v-card>
</template>
