import axios from 'axios'

const api = axios.create({
  // baseURL: '/api',//这是内网API地址，如果不需要外网访问请解除注释
    baseURL: '/api',//这是外网API地址
  timeout: 60000,
})

export function setupApi() {
  api.interceptors.request.use((config) => {
    const token = localStorage.getItem('accessToken')
    if (token) {
      config.headers = config.headers ?? {}
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  })

  api.interceptors.response.use(
    (res) => res,
    async (err) => {
      const status = err?.response?.status
      const original = err?.config
      if (status !== 401 || !original) {
        return Promise.reject(err)
      }

      if (original.url && String(original.url).includes('/auth/')) {
        localStorage.removeItem('accessToken')
        localStorage.removeItem('refreshToken')
        return Promise.reject(err)
      }

      if (original._retry) {
        localStorage.removeItem('accessToken')
        localStorage.removeItem('refreshToken')
        return Promise.reject(err)
      }

      const refreshToken = localStorage.getItem('refreshToken')
      if (!refreshToken) {
        localStorage.removeItem('accessToken')
        return Promise.reject(err)
      }

      original._retry = true
      try {
        const refreshRes = await axios.post('/api/auth/refresh', { refreshToken }, { timeout: 15000 })
        const newAccess = refreshRes?.data?.data?.accessToken
        const newRefresh = refreshRes?.data?.data?.refreshToken
        if (!newAccess) {
          localStorage.removeItem('accessToken')
          localStorage.removeItem('refreshToken')
          return Promise.reject(err)
        }
        localStorage.setItem('accessToken', newAccess)
        if (newRefresh) localStorage.setItem('refreshToken', newRefresh)

        original.headers = original.headers ?? {}
        original.headers.Authorization = `Bearer ${newAccess}`
        return api(original)
      } catch (e) {
        localStorage.removeItem('accessToken')
        localStorage.removeItem('refreshToken')
        return Promise.reject(err)
      }
    },
  )
}

export default api
