<script setup>
import { onActivated, onMounted, ref, computed } from 'vue'
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

const moodOptions = [
  { label: '开心' },
  { label: '平静' },
  { label: '充实' },
  { label: '思考' },
  { label: '烦躁' },
  { label: '难过' },
  { label: '热血' },
  { label: '兴奋' },
]

function selectMood(m) {
  mood.value = mood.value === m.label ? '' : m.label
}

function toDate(dt) {
  if (!dt) return null
  const s = String(dt).replace(' ', 'T')
  if (s.includes('+') || s.endsWith('Z')) return new Date(s)
  // Server sends LocalDateTime in Asia/Shanghai (no zone suffix).
  // JS Date parses such strings as local time.
  return new Date(s)
}

function formatLocalTime(dt) {
  const d = toDate(dt)
  if (!d) return ''
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function relativeTime(dt) {
  const d = toDate(dt)
  if (!d) return ''
  const diff = Math.floor((Date.now() - d.getTime()) / 1000)
  if (diff < 0) return '刚刚'
  if (diff < 60) return '刚刚'
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`
  if (diff < 172800) return '昨天'
  if (diff < 259200) return '前天'
  if (diff < 604800) return `${Math.floor(diff / 86400)} 天前`
  return formatLocalTime(dt).slice(0, 10)
}

const groupedJournals = computed(() => {
  const groups = []
  const now = new Date()
  // Use local date parts — do NOT use toISOString() which gives UTC date
  const todayStr = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  const y = new Date(now - 86400000)
  const yesterday = `${y.getFullYear()}-${String(y.getMonth() + 1).padStart(2, '0')}-${String(y.getDate()).padStart(2, '0')}`
  const db = new Date(now - 172800000)
  const dayBefore = `${db.getFullYear()}-${String(db.getMonth() + 1).padStart(2, '0')}-${String(db.getDate()).padStart(2, '0')}`

  for (const j of (list.value ?? [])) {
    const d = toDate(j.createdAt)
    const dateKey = d ? `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` : ''
    let label
    if (dateKey === todayStr) label = '今天'
    else if (dateKey === yesterday) label = '昨天'
    else if (dateKey === dayBefore) label = '前天'
    else label = dateKey || '更早'

    let group = groups.find(g => g.label === label)
    if (!group) {
      group = { label, dateKey, items: [] }
      groups.push(group)
    }
    group.items.push(j)
  }
  return groups
})

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

const creating = ref(false)

async function create() {
  if (!content.value?.trim()) return
  creating.value = true
  try {
    await api.post('/user/journals', null, { params: { content: content.value.trim(), mood: mood.value } })
    content.value = ''
    mood.value = ''
    await load()
  } catch (e) {
    error.value = e?.response?.data?.message || e?.message || '保存失败'
  } finally {
    creating.value = false
  }
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

let _ready = false
onMounted(load)
onActivated(() => { if (_ready) load(); _ready = true })
</script>

<template>
  <!-- ====== Write Card ====== -->
  <v-card class="mb-4 journal-write-card">
    <v-card-title class="d-flex align-center pb-0">
      <v-icon icon="mdi-feather" class="mr-2" color="primary" />
      写随笔
    </v-card-title>
    <v-card-text>
      <v-textarea
        v-model="content"
        placeholder="今天学了什么？有什么想法？..."
        rows="3"
        auto-grow
        variant="outlined"
        density="comfortable"
        hide-details
        class="mb-3"
      />

      <!-- Mood selector -->
      <div class="d-flex align-center flex-wrap ga-1 mb-3">
        <span class="text-caption text-medium-emphasis mr-1">心情：</span>
        <v-chip
          v-for="m in moodOptions"
          :key="m.label"
          size="small"
          :variant="mood === m.label ? 'flat' : 'outlined'"
          :color="mood === m.label ? 'primary' : undefined"
          @click="selectMood(m)"
          class="cursor-pointer"
        >
          {{ m.label }}
        </v-chip>
      </div>

      <div class="d-flex justify-end">
        <v-btn
          color="primary"
          variant="elevated"
          :loading="creating"
          :disabled="!content?.trim()"
          @click="create"
        >
          <v-icon icon="mdi-check" size="18" class="mr-1" />
          记录
        </v-btn>
      </div>
    </v-card-text>
  </v-card>

  <v-alert v-if="error" type="error" variant="tonal" class="mb-4" density="compact">{{ error }}</v-alert>

  <!-- ====== Journal List ====== -->
  <v-card>
    <v-card-title class="d-flex align-center">
      <v-icon icon="mdi-notebook" class="mr-2" />
      随笔
      <v-spacer />
      <span v-if="list.length" class="text-caption text-medium-emphasis">{{ list.length }} 篇</span>
    </v-card-title>
    <v-divider />

    <!-- Loading skeleton -->
    <div v-if="loading" class="pa-4">
      <div v-for="n in 4" :key="n">
        <v-skeleton-loader type="article" class="mb-2" />
      </div>
    </div>

    <!-- Empty state -->
    <div v-else-if="!list.length" class="text-center py-10">
      <v-icon icon="mdi-notebook-edit-outline" size="64" class="mb-3 text-medium-emphasis" style="opacity:0.3" />
      <div class="text-h6 font-weight-medium text-medium-emphasis mb-1">还没有随笔</div>
      <div class="text-body-2 text-medium-emphasis" style="opacity:0.6">
        记录每一天的学习感悟、灵感碎片
      </div>
    </div>

    <!-- Grouped journal entries -->
    <div v-else class="pa-3">
      <template v-for="group in groupedJournals" :key="group.label">
        <!-- Group header -->
        <div class="d-flex align-center ga-2 mb-2 mt-2">
          <div class="journal-date-divider" />
          <span class="text-caption font-weight-semibold text-medium-emphasis text-no-wrap">{{ group.label }}</span>
          <div class="journal-date-divider flex-grow-1" />
        </div>

        <div
          v-for="j in group.items"
          :key="j.id"
          class="journal-entry pa-3 mb-1 rounded"
        >
          <div class="d-flex align-start ga-3">
            <div class="flex-grow-1" style="min-width:0">
              <div class="journal-content text-body-1">
                {{ j.content }}
              </div>
              <div class="d-flex align-center ga-3 mt-2">
                <v-chip
                  v-if="j.mood"
                  size="x-small"
                  variant="tonal"
                  color="primary"
                >
                  {{ j.mood }}
                </v-chip>
                <span class="text-caption text-medium-emphasis">
                  {{ relativeTime(j.createdAt) }}
                </span>
                <span class="text-caption text-medium-emphasis" style="opacity:0.5">
                  {{ formatLocalTime(j.createdAt) }}
                </span>
              </div>
            </div>
            <v-btn
              icon="mdi-delete-outline"
              size="small"
              variant="text"
              color="error"
              density="compact"
              class="flex-shrink-0"
              @click.stop="askDelete(j)"
            />
          </div>
        </div>
      </template>
    </div>
  </v-card>

  <!-- ====== Delete Dialog ====== -->
  <v-dialog v-model="deleteOpen" max-width="440">
    <v-card>
      <v-card-title class="text-subtitle-1 font-weight-semibold">删除随笔</v-card-title>
      <v-divider />
      <v-card-text class="pt-4">
        确认删除这条随笔吗？删除后不可恢复。
      </v-card-text>
      <v-divider />
      <v-card-actions class="d-flex justify-end ga-2">
        <v-btn variant="text" @click="deleteOpen = false">取消</v-btn>
        <v-btn color="error" variant="tonal" :loading="deleteBusy" @click="confirmDelete">删除</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.journal-write-card {
  border-left: 4px solid rgb(var(--v-theme-primary));
}

.journal-date-divider {
  height: 1px;
  background: rgba(var(--v-theme-on-surface), 0.08);
  min-width: 20px;
}

.journal-entry {
  transition: background 0.15s;
  border-radius: 10px;
}
.journal-entry:hover {
  background: rgba(var(--v-theme-on-surface), 0.025);
}

.journal-content {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
}

</style>
