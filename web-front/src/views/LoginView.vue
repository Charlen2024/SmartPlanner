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
    <v-container class="fill-height" style="max-width: 1000px">
      <v-row align="center" justify="center">
        <!-- ====== Left Hero ====== -->
        <v-col cols="12" md="6" class="d-none d-md-flex flex-column justify-center">
          <div class="mb-6">
            <v-icon icon="mdi-lightbulb-on-outline" size="48" color="primary" class="mb-4" />
            <div class="text-h3 font-weight-bold mb-3">Smart Planner</div>
            <div class="text-body-1 mb-2" style="opacity:0.7; max-width: 380px; line-height:1.7">
              把目标拆解成任务，把任务安排进日程，<br />用打卡形成习惯。
            </div>
          </div>
          <v-card variant="tonal" color="primary" class="pa-3 rounded-lg" style="max-width: 340px">
            <div class="d-flex align-center ga-2">
              <v-icon icon="mdi-information-outline" size="18" />
              <span class="text-caption">默认账号 demo / demo123</span>
            </div>
          </v-card>
        </v-col>

        <!-- ====== Right Form ====== -->
        <v-col cols="12" sm="8" md="5">
          <!-- Mobile-only brand -->
          <div class="text-center d-md-none mb-6">
            <v-icon icon="mdi-lightbulb-on-outline" size="36" color="primary" class="mb-2" />
            <div class="text-h4 font-weight-bold">Smart Planner</div>
            <div class="text-body-2 mt-1" style="opacity:0.6">把目标拆解成任务，把任务安排进日程</div>
          </div>

          <v-card class="login-card rounded-xl" elevation="8">
            <v-tabs v-model="tab" density="comfortable" class="px-3 pt-2" color="primary">
              <v-tab value="login" class="text-body-2 font-weight-medium">登录</v-tab>
              <v-tab value="register" class="text-body-2 font-weight-medium">注册</v-tab>
            </v-tabs>
            <v-divider />

            <v-card-text class="pa-6">
              <v-alert v-if="error" type="error" variant="tonal" class="mb-4" density="compact" closable @click:close="error = ''">
                {{ error }}
              </v-alert>

              <v-text-field
                v-model="username"
                label="用户名"
                prepend-inner-icon="mdi-account-outline"
                variant="outlined"
                density="comfortable"
                class="mb-3"
                @keyup.enter="isRegister ? onRegister() : onLogin()"
              />
              <v-text-field
                v-model="password"
                label="密码"
                type="password"
                prepend-inner-icon="mdi-lock-outline"
                variant="outlined"
                density="comfortable"
                class="mb-3"
                @keyup.enter="isRegister ? onRegister() : onLogin()"
              />
              <v-text-field
                v-if="isRegister"
                v-model="password2"
                label="确认密码"
                type="password"
                prepend-inner-icon="mdi-lock-check-outline"
                variant="outlined"
                density="comfortable"
                class="mb-3"
                @keyup.enter="onRegister"
              />
            </v-card-text>

            <v-card-actions class="px-6 pb-6 pt-0">
              <v-btn
                v-if="!isRegister"
                color="primary"
                size="large"
                block
                height="48"
                :loading="auth.loading"
                @click="onLogin"
              >
                登录
              </v-btn>
              <v-btn
                v-else
                color="primary"
                size="large"
                block
                height="48"
                :loading="auth.loading"
                @click="onRegister"
              >
                创建账号
              </v-btn>
            </v-card-actions>

            <!-- Mobile-only tip -->
            <div class="text-center pb-4 d-md-none">
              <span class="text-caption" style="opacity:0.45">默认账号 demo / demo123</span>
            </div>
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
    linear-gradient(180deg, rgb(var(--v-theme-background)), rgb(var(--v-theme-background)));
}

.login-card {
  border: 1px solid rgba(var(--v-theme-on-surface), 0.06);
  transition: box-shadow 0.3s;
}
.login-card:hover {
  box-shadow: 0 12px 40px rgba(var(--v-theme-on-surface), 0.1) !important;
}
</style>
