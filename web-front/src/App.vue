<script setup>
import { watch } from 'vue'
import { useTheme } from 'vuetify'

const theme = useTheme()

function syncBodyBg() {
  const bg = theme.global.current.value.colors.background
  document.documentElement.style.background = bg
  document.body.style.background = bg
}
syncBodyBg()
watch(() => theme.global.name.value, syncBodyBg)
</script>

<template>
  <v-app>
    <router-view />
  </v-app>
</template>
