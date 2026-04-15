<script setup>
import { ref, onMounted } from 'vue'
import api from '../plugins/api'

const topic = ref('分布式')
const title = ref('')
const platform = ref('')
const url = ref('')
const summary = ref('')
const list = ref([])
const loading = ref(false)
const error = ref('')

async function searchOnline() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.get('/user/resources/search', { params: { topic: topic.value } })
    list.value = res?.data?.data ?? []
  } catch (e) {
    error.value = e?.response?.data?.message || e?.message || '检索失败'
  } finally {
    loading.value = false
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
  const res = await api.get('/user/resources', { params: { topic: topic.value } })
  list.value = res?.data?.data ?? []
}

async function remove(id) {
  await api.delete(`/user/resources/${id}`)
  await loadLocal()
}

onMounted(loadLocal)
</script>

<template>
  <v-row class="mb-2">
    <v-col cols="12" md="3"><v-text-field v-model="topic" label="主题" variant="outlined" /></v-col>
    <v-col cols="12" md="3"><v-btn color="primary" :loading="loading" @click="searchOnline">AI 推荐</v-btn></v-col>
  </v-row>
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
          <v-btn size="small" variant="text" v-if="r.url" :href="r.url" target="_blank">打开</v-btn>
        </template>
        <div v-if="r.summary || r.contentSummary" class="text-caption mt-1" style="opacity:0.8">
          {{ r.summary || r.contentSummary }}
        </div>
      </v-list-item>
    </v-list>
  </v-card>
</template>
