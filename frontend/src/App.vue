<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, getIdentity, logIn, logOut, type Identity } from './api'

type SessionState = 'loading' | 'unavailable' | 'signed-out' | 'signed-in'

const sessionState = ref<SessionState>('loading')
const identity = ref<Identity>()
const username = ref('')
const password = ref('')
const busy = ref(false)
const errorMessage = ref('')

async function refreshIdentity() {
  sessionState.value = 'loading'
  try {
    identity.value = await getIdentity()
    sessionState.value = 'signed-in'
  } catch (error) {
    identity.value = undefined
    if (error instanceof ApiError && error.status === 401) {
      sessionState.value = 'signed-out'
    } else {
      sessionState.value = 'unavailable'
    }
  }
}

async function submitLogin() {
  busy.value = true
  errorMessage.value = ''
  const loginRequest = logIn(username.value, password.value)
  password.value = ''

  try {
    await loginRequest
    await refreshIdentity()
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      errorMessage.value = '登录名或口令不正确。'
    } else if (error instanceof ApiError && error.status === 403) {
      errorMessage.value = '安全校验未通过，请重新登录。'
    } else {
      errorMessage.value = '登录失败，请稍后重试。'
    }
  } finally {
    busy.value = false
  }
}

async function submitLogout() {
  busy.value = true
  errorMessage.value = ''
  try {
    await logOut()
  } catch (error) {
    if (!(error instanceof ApiError && error.status === 401)) {
      errorMessage.value = '退出失败，请重试。'
      return
    }
  } finally {
    busy.value = false
  }
  identity.value = undefined
  username.value = ''
  sessionState.value = 'signed-out'
}

onMounted(refreshIdentity)
</script>

<template>
  <main class="panel" aria-labelledby="page-title">
    <h1 id="page-title">GPU 名额管理</h1>

    <p v-if="sessionState === 'loading'" role="status">正在检查登录状态…</p>

    <div v-else-if="sessionState === 'unavailable'" class="actions">
      <p class="error" role="alert">无法读取登录状态。</p>
      <button type="button" @click="refreshIdentity">重试</button>
    </div>

    <form v-else-if="sessionState === 'signed-out'" method="post" @submit.prevent="submitLogin">
      <fieldset :disabled="busy">
        <label for="username">登录名</label>
        <input id="username" v-model="username" name="username" autocomplete="username" required />

        <label for="password">口令</label>
        <input
          id="password"
          v-model="password"
          name="password"
          type="password"
          autocomplete="current-password"
          required
        />

        <p v-if="errorMessage" class="error" role="alert">{{ errorMessage }}</p>
        <button type="submit">{{ busy ? '正在登录…' : '登录' }}</button>
      </fieldset>
    </form>

    <div v-else-if="identity" class="actions">
      <dl>
        <dt>姓名</dt>
        <dd>{{ identity.displayName }}</dd>
        <dt>登录名</dt>
        <dd>{{ identity.loginName }}</dd>
        <dt>角色</dt>
        <dd>{{ identity.role === 'ADMIN' ? '算力管理员' : '成员' }}</dd>
      </dl>
      <p v-if="errorMessage" class="error" role="alert">{{ errorMessage }}</p>
      <button type="button" :disabled="busy" @click="submitLogout">
        {{ busy ? '正在退出…' : '退出登录' }}
      </button>
    </div>
  </main>
</template>
