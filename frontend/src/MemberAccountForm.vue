<script setup lang="ts">
import { ref } from 'vue'
import { ApiError, createMember, type FieldError, type Identity } from './api'

defineProps<{ disabled: boolean }>()

const loginName = ref('')
const displayName = ref('')
const password = ref('')
const busy = ref(false)
const error = ref<FieldError>()
const failure = ref('')
const created = ref<Identity>()

async function submit() {
  busy.value = true
  error.value = undefined
  failure.value = ''
  created.value = undefined

  try {
    const pending = createMember(loginName.value, displayName.value, password.value)
    password.value = ''
    created.value = await pending
    loginName.value = ''
    displayName.value = ''
  } catch (cause) {
    if (cause instanceof ApiError && cause.fieldError?.message) {
      error.value = cause.fieldError
    } else if (cause instanceof ApiError && cause.status === 401) {
      failure.value = '登录已失效，请重新登录。'
    } else if (cause instanceof ApiError && cause.status === 403) {
      failure.value = '无法通过权限或安全校验，请重新登录后重试。'
    } else {
      failure.value = '创建成员账号失败，请稍后重试。'
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <form method="post" @submit.prevent="submit">
    <fieldset :disabled="busy || disabled">
      <legend>创建成员账号</legend>

      <label for="member-login">登录名</label>
      <input id="member-login" v-model="loginName" required
        :aria-invalid="error?.field === 'loginName' || undefined"
        :aria-describedby="error?.field === 'loginName' ? 'member-login-error' : undefined" />
      <p v-if="error?.field === 'loginName'" id="member-login-error" class="error" role="alert">
        {{ error.message }}
      </p>

      <label for="member-name">显示姓名</label>
      <input id="member-name" v-model="displayName" required
        :aria-invalid="error?.field === 'displayName' || undefined"
        :aria-describedby="error?.field === 'displayName' ? 'member-name-error' : undefined" />
      <p v-if="error?.field === 'displayName'" id="member-name-error" class="error" role="alert">
        {{ error.message }}
      </p>

      <label for="member-password">初始口令</label>
      <input id="member-password" v-model="password" type="password" autocomplete="new-password" required
        :aria-invalid="error?.field === 'password' || undefined"
        :aria-describedby="error?.field === 'password' ? 'member-password-error' : undefined" />
      <p v-if="error?.field === 'password'" id="member-password-error" class="error" role="alert">
        {{ error.message }}
      </p>

      <button type="submit">{{ busy ? '正在创建…' : '创建成员账号' }}</button>
    </fieldset>
    <p v-if="failure" class="error" role="alert">{{ failure }}</p>
    <p v-if="created" role="status">已创建成员账号 {{ created.loginName }}（{{ created.displayName }}）。</p>
  </form>
</template>

<style scoped>
form {
  border-top: 1px solid #d4dde7;
  padding-top: 1rem;
}

legend {
  margin-bottom: 0.75rem;
  padding: 0;
  font-weight: 700;
}

form > p {
  margin-top: 0.75rem;
}
</style>
