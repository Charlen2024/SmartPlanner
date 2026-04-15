<script setup>
import { onMounted, ref } from 'vue'
import api from '../plugins/api'

const loading = ref(false)
const error = ref('')
const list = ref([])
const content = ref('')
const mood = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.get('/user/journals')
    list.value = res?.data?.data ?? []
  } catch (e) {
    error.value = '加载失败'
  } finally {
    loading.value = false
  }
}

async function create() {
  if (!content.value) return
  await api.post('/user/journals', null, { params: { content: content.value, mood: mood.value } })
  content.value = ''
  mood.value = ''
  await load()
}

onMounted(load)
</script>

<template>
  <v-row class="mb-2" align="center">
    <v-col cols="12" md="7">
      <v-textarea v-model="content" label="随笔内容" rows="2" auto-grow variant="outlined" density="comfortable" />
    </v-col>
    <v-col cols="12" md="3">
      <v-text-field v-model="mood" label="心情" variant="outlined" density="comfortable" />
    </v-col>
    <v-col cols="12" md="2">
      <v-btn color="primary" :loading="loading" @click="create">记录</v-btn>
    </v-col>
  </v-row>
  <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>
  <v-card>
    <v-card-title class="d-flex align-center">
      <v-icon icon="mdi-notebook" class="mr-2" />
      随笔
    </v-card-title>
    <v-divider />
    <v-list>
      <v-list-item
        v-for="j in list"
        :key="j.id"
        :title="j.content"
        :subtitle="`${j.mood || '心情：-'} · ${String(j.createdAt || '').replace('T',' ').slice(0,16)}`"
      />
    </v-list>
  </v-card>
</template>
