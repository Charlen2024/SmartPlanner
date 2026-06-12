import { createApp } from 'vue'
import './style.css'
import App from './App.vue'

import '@mdi/font/css/materialdesignicons.css'
import 'highlight.js/styles/github-dark.css'
import 'vuetify/styles'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import { VDateInput } from 'vuetify/labs/VDateInput'
import * as directives from 'vuetify/directives'
import { createPinia } from 'pinia'
import { createRouter } from './router'
import { setupApi } from './plugins/api'

let savedTheme = localStorage.getItem('theme') || 'spLight'
if (savedTheme === 'vibeLight') savedTheme = 'spLight'
if (savedTheme === 'vibeDark') savedTheme = 'spDark'
localStorage.setItem('theme', savedTheme)

const vuetify = createVuetify({
  components: { ...components, VDateInput },
  directives,
  defaults: {
    VCard: { rounded: 'xl' },
    VBtn: { rounded: 'lg' },
    VTextField: { rounded: 'lg' },
    VSelect: { rounded: 'lg' },
    VDateInput: { rounded: 'lg' },
  },
  locale: {
    locale: 'zhHans',
    fallback: 'en',
  },
  theme: {
    defaultTheme: savedTheme,
    themes: {
      spLight: {
        dark: false,
        colors: {
          primary: '#2563EB',
          secondary: '#F59E0B',
          background: '#F8FAFC',
          surface: '#FFFFFF',
          'on-surface': '#0F172A',
        },
      },
      spDark: {
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
