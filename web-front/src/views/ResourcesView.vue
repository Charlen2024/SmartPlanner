<script setup>
import { ref, watch } from 'vue'
import api from '../plugins/api'

const topic = ref('')
const title = ref('')
const platform = ref('')
const url = ref('')
const summary = ref('')
const list = ref([])
const loading = ref(false)
const error = ref('')
const advice = ref('')
const jobSeq = ref(0)

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

async function searchOnline() {
  if (!topic.value?.trim()) {
    advice.value = ''
    error.value = ''
    list.value = []
    return
  }
  const seq = (jobSeq.value += 1)
  loading.value = true
  error.value = ''
  advice.value = ''
  list.value = []
  try {
    const startRes = await api.post('/user/resources/search/advice/jobs', { topic: topic.value.trim() }, { timeout: 15000 })
    const jobId = startRes?.data?.data?.jobId
    if (!jobId) throw new Error('启动检索任务失败')

    for (let i = 0; i < 180; i++) {
      await sleep(1000)
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
    if (e?.response?.status === 401) {
      error.value = '未登录或登录已过期，请重新登录后再检索'
    } else if (e?.code === 'ECONNABORTED') {
      error.value = '请求超时，请稍后重试'
    } else {
      error.value = e?.response?.data?.message || e?.message || '检索失败'
    }
  } finally {
    if (seq === jobSeq.value) loading.value = false
  }
}

async function createResource() {
  if (!topic.value || !title.value) return
  await api.post('/user/resources', null, { params: { topic: topic.value, title: title.value, platform: platform.value, url: url.value, summary: summary.value } })
  title.value = ''
  platform.value = ''
  url.value = ''
  summary.value = ''
  await loadLocal()
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
  await api.delete(`/user/resources/${id}`)
  await loadLocal()
}

watch(
  topic,
  async (v) => {
    if (!v?.trim()) {
      advice.value = ''
      error.value = ''
      list.value = []
      return
    }
    await loadLocal()
  },
  { immediate: true }
)
</script>

<template>
  <v-row class="mb-2">
    <v-col cols="12" md="3"><v-text-field v-model="topic" label="主题" variant="outlined" /></v-col>
    <v-col cols="12" md="3"><v-btn color="primary" :loading="loading" :disabled="!topic?.trim()" @click="searchOnline">ES 检索 / RAG 推荐</v-btn></v-col>
  </v-row>
  <v-alert v-if="advice" type="info" variant="tonal" class="mb-4" style="white-space: pre-wrap;">{{ advice }}</v-alert>
  <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>
  <v-row class="mb-2">
    <v-col cols="12" md="3"><v-text-field v-model="title" label="标题" variant="outlined" /></v-col>
    <v-col cols="12" md="2"><v-text-field v-model="platform" label="平台" variant="outlined" /></v-col>
    <v-col cols="12" md="3"><v-text-field v-model="url" label="链接" variant="outlined" /></v-col>
    <v-col cols="12" md="3"><v-text-field v-model="summary" label="摘要" variant="outlined" /></v-col>
    <v-col cols="12" md="1"><v-btn @click="createResource">保存</v-btn></v-col>
  </v-row>
  <v-card>
    <v-card-title class="d-flex align-center">
      <v-icon icon="mdi-book-open-variant" class="mr-2" />
      学习资源
    </v-card-title>
    <v-divider />
    <v-list>
      <v-list-item
        v-for="(r, i) in list"
        :key="r.id || i"
        :title="`${i + 1}. ${r.title || r?.title}`"
        :subtitle="r.platform || r?.platform"
      >
        <template #append>
          <v-btn size="small" variant="text" v-if="r.id" color="error" @click="remove(r.id)">删除</v-btn>
          <v-btn size="small" variant="text" v-if="r.url || r.sourceUrl" :href="r.url || r.sourceUrl" target="_blank">打开</v-btn>
        </template>
        <div v-if="r.summary || r.contentSummary" class="text-caption mt-1" style="opacity:0.8">
          {{ r.summary || r.contentSummary }}
        </div>
      </v-list-item>
    </v-list>
  </v-card>
</template>
