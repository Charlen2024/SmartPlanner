import { defineStore } from 'pinia'
import api from '../plugins/api'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    me: null,
    loading: false,
    lastError: '',
  }),
  getters: {
    isAuthed: () => Boolean(localStorage.getItem('accessToken')),
  },
  actions: {
    async register(username, password) {
      this.loading = true
      this.lastError = ''
      try {
        const res = await api.post('/auth/register', { username, password })
        const token = res?.data?.data?.accessToken
        if (!token) {
          const msg = res?.data?.message || 'register failed'
          throw new Error(msg)
        }
        localStorage.setItem('accessToken', token)
        const refresh = res?.data?.data?.refreshToken
        if (refresh) localStorage.setItem('refreshToken', refresh)
        await this.fetchMe()
        return true
      } catch (e) {
        const status = e?.response?.status
        const msg = e?.response?.data?.message || e?.message || '注册失败'
        this.lastError = status ? `${status} ${msg}` : msg
        throw e
      } finally {
        this.loading = false
      }
    },
    async login(username, password) {
      this.loading = true
      this.lastError = ''
      try {
        const res = await api.post('/auth/login', { username, password })
        const token = res?.data?.data?.accessToken
        if (!token) {
          const msg = res?.data?.message || 'login failed'
          throw new Error(msg)
        }
        localStorage.setItem('accessToken', token)
        const refresh = res?.data?.data?.refreshToken
        if (refresh) localStorage.setItem('refreshToken', refresh)
        await this.fetchMe()
        return true
      } catch (e) {
        const status = e?.response?.status
        const msg = e?.response?.data?.message || e?.message || '登录失败'
        this.lastError = status ? `${status} ${msg}` : msg
        throw e
      } finally {
        this.loading = false
      }
    },
    logout() {
      localStorage.removeItem('accessToken')
      localStorage.removeItem('refreshToken')
      this.me = null
    },
    async fetchMe() {
      try {
        const res = await api.get('/auth/me')
        this.me = res?.data?.data ?? null
        return this.me
      } catch (e) {
        const status = e?.response?.status
        const msg = e?.response?.data?.message || e?.message || 'fetch me failed'
        this.lastError = status ? `${status} ${msg}` : msg
        throw e
      }
    },
  },
})
