import { defineStore } from 'pinia'

let nextId = 1

export const useNotifyStore = defineStore('notify', {
  state: () => ({
    items: [],
  }),
  actions: {
    push(message, type = 'info', timeout = 4500) {
      const id = nextId++
      this.items.push({
        id,
        open: true,
        message: String(message ?? ''),
        type,
        timeout,
      })
      return id
    },
    close(id) {
      const item = this.items.find((x) => x.id === id)
      if (item) item.open = false
    },
    remove(id) {
      this.items = this.items.filter((x) => x.id !== id)
    },
    info(message, timeout) {
      return this.push(message, 'info', timeout)
    },
    success(message, timeout) {
      return this.push(message, 'success', timeout)
    },
    error(message, timeout) {
      return this.push(message, 'error', timeout)
    },
  },
})
