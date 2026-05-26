<script setup>
import { onMounted, ref } from 'vue'
import api from '../plugins/api'
import { useNotifyStore } from '../stores/notify'

const loading = ref(false)
const error = ref('')
const list = ref([])
const content = ref('')
const mood = ref('')
const notify = useNotifyStore()

const deleteOpen = ref(false)
const deleteBusy = ref(false)
const deleting = ref(null)

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

function askDelete(j) {
  if (!j?.id) return
  deleting.value = j
  deleteOpen.value = true
}

async function confirmDelete() {
  const id = Number(deleting.value?.id)
  if (!Number.isFinite(id) || id <= 0) return
  deleteBusy.value = true
  try {
    await api.delete(`/user/journals/${id}`)
    notify.success('已删除')
    deleteOpen.value = false
    deleting.value = null
    await load()
  } catch (e) {
    notify.error(e?.response?.data?.message || e?.message || '删除失败')
  } finally {
    deleteBusy.value = false
  }
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
      >
        <v-list-item-title style="white-space: pre-wrap; word-break: break-word;">{{ j.content }}</v-list-item-title>
        <v-list-item-subtitle>
          {{ `${j.mood || '心情：-'} · ${String(j.createdAt || '').replace('T',' ').slice(0,16)}` }}
        </v-list-item-subtitle>
        <template #append>
          <v-btn
            icon="mdi-delete-outline"
            size="small"
            variant="text"
            color="error"
            @click.stop="askDelete(j)"
          />
        </template>
      </v-list-item>
    </v-list>
  </v-card>

  <v-dialog v-model="deleteOpen" max-width="520">
    <v-card>
      <v-card-title class="d-flex align-center">
        <div class="text-subtitle-1 font-weight-semibold">删除随笔</div>
        <v-spacer />
        <v-btn size="small" variant="text" @click="deleteOpen = false">关闭</v-btn>
      </v-card-title>
      <v-divider />
      <v-card-text>
        确认删除这条随笔吗？删除后不可恢复。
      </v-card-text>
      <v-divider />
      <v-card-actions class="d-flex justify-end">
        <v-btn variant="text" @click="deleteOpen = false">取消</v-btn>
        <v-btn color="error" variant="tonal" :loading="deleteBusy" @click="confirmDelete">删除</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
