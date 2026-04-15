<script setup>
import { computed, ref } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useRouter } from 'vue-router'

const router = useRouter()
const auth = useAuthStore()

const tab = ref('login')
const username = ref('demo')
const password = ref('demo123')
const password2 = ref('')
const error = ref('')

const isRegister = computed(() => tab.value === 'register')

async function onLogin() {
  error.value = ''
  try {
    await auth.login(username.value, password.value)
    router.push('/')
  } catch (e) {
    error.value = auth.lastError || '登录失败'
  }
}

async function onRegister() {
  error.value = ''
  if (!username.value || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  if (password.value !== password2.value) {
    error.value = '两次密码不一致'
    return
  }
  try {
    await auth.register(username.value, password.value)
    router.push('/')
  } catch (e) {
    error.value = auth.lastError || '注册失败'
  }
}
</script>

<template>
  <v-main class="login-main">
    <v-container class="fill-height" style="max-width: 960px">
      <v-row align="center" justify="center">
        <v-col cols="12" md="6" class="d-none d-md-block">
          <div class="text-h3 font-weight-bold mb-3">Smart Planner</div>
          <div class="text-body-1 mb-6" style="opacity: 0.85">把目标拆解成任务，把任务安排进日程，用打卡形成习惯。</div>
          <v-card variant="tonal" color="primary" class="pa-4 rounded-lg">
            <div class="text-subtitle-2 font-weight-semibold mb-1">提示</div>
            <div class="text-body-2">默认账号：demo / demo123</div>
          </v-card>
        </v-col>
        <v-col cols="12" md="5">
          <v-card class="pa-2 rounded-xl" elevation="10">
            <v-tabs v-model="tab" density="compact" class="px-2">
              <v-tab value="login">登录</v-tab>
              <v-tab value="register">注册</v-tab>
            </v-tabs>
            <v-card-text>
              <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>
              <v-text-field v-model="username" label="用户名" prepend-inner-icon="mdi-account" variant="outlined" />
              <v-text-field v-model="password" label="密码" type="password" prepend-inner-icon="mdi-lock" variant="outlined" />
              <v-text-field
                v-if="isRegister"
                v-model="password2"
                label="确认密码"
                type="password"
                prepend-inner-icon="mdi-lock-check"
                variant="outlined"
              />
            </v-card-text>
            <v-card-actions class="px-4 pb-4">
              <v-btn
                v-if="!isRegister"
                color="primary"
                size="large"
                block
                :loading="auth.loading"
                @click="onLogin"
              >进入</v-btn>
              <v-btn
                v-else
                color="primary"
                size="large"
                block
                :loading="auth.loading"
                @click="onRegister"
              >创建账号</v-btn>
            </v-card-actions>
          </v-card>
        </v-col>
      </v-row>
    </v-container>
  </v-main>
</template>

<style scoped>
.login-main {
  min-height: 100vh;
  background:
    radial-gradient(900px 520px at 15% 10%, rgba(var(--v-theme-primary), 0.24), transparent 55%),
    radial-gradient(900px 520px at 85% 0%, rgba(var(--v-theme-secondary), 0.18), transparent 55%),
    linear-gradient(180deg, rgba(var(--v-theme-background), 1), rgba(var(--v-theme-background), 1));
}
</style>
