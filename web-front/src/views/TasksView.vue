<script setup>
import { onMounted, ref } from 'vue'
import api from '../plugins/api'

const loading = ref(false)
const error = ref('')
const tasks = ref([])

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.get('/user/tasks/pending')
    tasks.value = res?.data?.data ?? []
  } catch (e) {
    error.value = '加载失败'
  } finally {
    loading.value = false
  }
}

async function updateStatus(taskId, status) {
  await api.patch(`/user/tasks/${taskId}/status`, null, { params: { status } })
  await load()
}

onMounted(load)
</script>

<template>
  <v-row class="mb-2" align="center">
    <v-col cols="12" md="3">
      <v-btn color="primary" :loading="loading" @click="load">刷新</v-btn>
    </v-col>
  </v-row>
  <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>
  <v-card>
    <v-card-title>待办任务</v-card-title>
    <v-divider />
    <v-list>
      <v-list-item v-for="t in tasks" :key="t.id" :title="t.title" :subtitle="t.description">
        <template #append>
          <v-btn size="small" variant="tonal" color="success" class="mr-2" @click="updateStatus(t.id, 1)">完成</v-btn>
          <v-btn size="small" variant="text" color="warning" @click="updateStatus(t.id, 2)">取消</v-btn>
        </template>
      </v-list-item>
    </v-list>
  </v-card>
</template>
