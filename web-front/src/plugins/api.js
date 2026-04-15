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
    (err) => {
      const status = err?.response?.status
      if (status === 401) {
        localStorage.removeItem('accessToken')
        localStorage.removeItem('refreshToken')
      }
      return Promise.reject(err)
    },
  )
}

export default api
