import { createApp } from 'vue'
import './style.css'
import App from './App.vue'

import '@mdi/font/css/materialdesignicons.css'
import 'vuetify/styles'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import { createPinia } from 'pinia'
import { createRouter } from './router'
import { setupApi } from './plugins/api'

const savedTheme = localStorage.getItem('theme') || 'vibeLight'

const vuetify = createVuetify({
  components,
  directives,
  defaults: {
    VCard: { rounded: 'xl' },
    VBtn: { rounded: 'lg' },
    VTextField: { rounded: 'lg' },
    VSelect: { rounded: 'lg' },
  },
  theme: {
    defaultTheme: savedTheme,
    themes: {
      vibeLight: {
        dark: false,
        colors: {
          primary: '#2563EB',
          secondary: '#F59E0B',
          background: '#F8FAFC',
          surface: '#FFFFFF',
          'on-surface': '#0F172A',
        },
      },
      vibeDark: {
        dark: true,
        colors: {
          primary: '#3B82F6',
          secondary: '#FBBF24',
          background: '#0B1020',
          surface: '#0F172A',
          'on-surface': '#E5E7EB',
        },
      },
    },
  },
})

const app = createApp(App)
app.use(createPinia())
setupApi()
app.use(createRouter())
app.use(vuetify)
app.mount('#app')
