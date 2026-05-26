<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'

const size = 4
const board = ref(Array(size * size).fill(0))
const score = ref(0)
const best = ref(Number(localStorage.getItem('bestScore2048') || 0) || 0)
const over = ref(false)

const cells = computed(() => board.value)

function idx(r, c) {
  return r * size + c
}

function emptyIndices() {
  const out = []
  for (let i = 0; i < board.value.length; i++) {
    if (!board.value[i]) out.push(i)
  }
  return out
}

function randomTileValue() {
  return Math.random() < 0.9 ? 2 : 4
}

function spawn() {
  const empties = emptyIndices()
  if (!empties.length) return false
  const i = empties[Math.floor(Math.random() * empties.length)]
  const next = board.value.slice()
  next[i] = randomTileValue()
  board.value = next
  return true
}

function canMove() {
  if (emptyIndices().length) return true
  for (let r = 0; r < size; r++) {
    for (let c = 0; c < size; c++) {
      const v = board.value[idx(r, c)]
      if (r + 1 < size && v === board.value[idx(r + 1, c)]) return true
      if (c + 1 < size && v === board.value[idx(r, c + 1)]) return true
    }
  }
  return false
}

function compressAndMerge(line) {
  const compact = line.filter((x) => x)
  const out = []
  let gained = 0
  for (let i = 0; i < compact.length; i++) {
    const a = compact[i]
    const b = compact[i + 1]
    if (a && b && a === b) {
      const v = a + b
      out.push(v)
      gained += v
      i++
    } else {
      out.push(a)
    }
  }
  while (out.length < size) out.push(0)
  return { line: out, gained }
}

function moveLeft() {
  let changed = false
  let gainedTotal = 0
  const next = board.value.slice()
  for (let r = 0; r < size; r++) {
    const line = []
    for (let c = 0; c < size; c++) line.push(next[idx(r, c)])
    const merged = compressAndMerge(line)
    gainedTotal += merged.gained
    for (let c = 0; c < size; c++) {
      const before = next[idx(r, c)]
      const after = merged.line[c]
      if (before !== after) changed = true
      next[idx(r, c)] = after
    }
  }
  return { next, changed, gained: gainedTotal }
}

function moveRight() {
  let changed = false
  let gainedTotal = 0
  const next = board.value.slice()
  for (let r = 0; r < size; r++) {
    const line = []
    for (let c = size - 1; c >= 0; c--) line.push(next[idx(r, c)])
    const merged = compressAndMerge(line)
    gainedTotal += merged.gained
    for (let c = size - 1, k = 0; c >= 0; c--, k++) {
      const before = next[idx(r, c)]
      const after = merged.line[k]
      if (before !== after) changed = true
      next[idx(r, c)] = after
    }
  }
  return { next, changed, gained: gainedTotal }
}

function moveUp() {
  let changed = false
  let gainedTotal = 0
  const next = board.value.slice()
  for (let c = 0; c < size; c++) {
    const line = []
    for (let r = 0; r < size; r++) line.push(next[idx(r, c)])
    const merged = compressAndMerge(line)
    gainedTotal += merged.gained
    for (let r = 0; r < size; r++) {
      const before = next[idx(r, c)]
      const after = merged.line[r]
      if (before !== after) changed = true
      next[idx(r, c)] = after
    }
  }
  return { next, changed, gained: gainedTotal }
}

function moveDown() {
  let changed = false
  let gainedTotal = 0
  const next = board.value.slice()
  for (let c = 0; c < size; c++) {
    const line = []
    for (let r = size - 1; r >= 0; r--) line.push(next[idx(r, c)])
    const merged = compressAndMerge(line)
    gainedTotal += merged.gained
    for (let r = size - 1, k = 0; r >= 0; r--, k++) {
      const before = next[idx(r, c)]
      const after = merged.line[k]
      if (before !== after) changed = true
      next[idx(r, c)] = after
    }
  }
  return { next, changed, gained: gainedTotal }
}

function applyMove(dir) {
  if (over.value) return
  let moved
  if (dir === 'left') moved = moveLeft()
  else if (dir === 'right') moved = moveRight()
  else if (dir === 'up') moved = moveUp()
  else if (dir === 'down') moved = moveDown()
  else return

  if (!moved.changed) return
  board.value = moved.next
  score.value += moved.gained
  if (score.value > best.value) {
    best.value = score.value
    localStorage.setItem('bestScore2048', String(best.value))
  }
  spawn()
  over.value = !canMove()
}

function restart() {
  board.value = Array(size * size).fill(0)
  score.value = 0
  over.value = false
  spawn()
  spawn()
}

function tileColor(v) {
  const map = {
    0: 'surface',
    2: '#eee4da',
    4: '#ede0c8',
    8: '#f2b179',
    16: '#f59563',
    32: '#f67c5f',
    64: '#f65e3b',
    128: '#edcf72',
    256: '#edcc61',
    512: '#edc850',
    1024: '#edc53f',
    2048: '#edc22e',
  }
  return map[v] || '#3c3a32'
}

function tileTextColor(v) {
  if (!v) return 'transparent'
  return v <= 4 ? '#776e65' : '#f9f6f2'
}

function onKeydown(e) {
  if (!e) return
  const k = e.key
  const map = {
    ArrowLeft: 'left',
    ArrowRight: 'right',
    ArrowUp: 'up',
    ArrowDown: 'down',
    a: 'left',
    d: 'right',
    w: 'up',
    s: 'down',
  }
  const dir = map[k]
  if (!dir) return
  e.preventDefault()
  applyMove(dir)
}

onMounted(() => {
  restart()
  window.addEventListener('keydown', onKeydown, { passive: false })
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <v-card class="pa-4">
    <div class="d-flex align-center flex-wrap ga-2">
      <div class="text-h5 font-weight-bold">2048</div>
      <v-spacer />
      <v-chip variant="tonal">得分：{{ score }}</v-chip>
      <v-chip variant="tonal">最高：{{ best }}</v-chip>
      <v-btn color="primary" variant="tonal" @click="restart">重新开始</v-btn>
    </div>

    <div class="text-caption mt-2" style="opacity: 0.75">
      键盘方向键或 WASD 操作
    </div>

    <v-alert v-if="over" type="warning" variant="tonal" class="mt-3">
      游戏结束。可以点击“重新开始”再来一局。
    </v-alert>

    <div class="mt-4 d-flex justify-center">
      <div
        class="game-grid"
        role="application"
        aria-label="2048"
      >
        <div v-for="(v, i) in cells" :key="i" class="game-cell">
          <div
            class="tile"
            :style="{
              background: tileColor(v),
              color: tileTextColor(v),
              transform: v ? 'scale(1)' : 'scale(0.98)',
            }"
          >
            <span v-if="v">{{ v }}</span>
          </div>
        </div>
      </div>
    </div>
  </v-card>
</template>

<style scoped>
.game-grid {
  width: min(420px, 92vw);
  aspect-ratio: 1;
  background: rgba(var(--v-theme-on-surface), 0.08);
  border-radius: 12px;
  padding: 10px;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}
.game-cell {
  background: rgba(var(--v-theme-on-surface), 0.06);
  border-radius: 10px;
  position: relative;
}
.tile {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  font-size: 28px;
  font-weight: 800;
  transition: transform 120ms ease, background 120ms ease;
}
</style>

