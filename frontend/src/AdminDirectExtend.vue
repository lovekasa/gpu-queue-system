<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  ApiError,
  getAdminActiveAllocations,
  submitAdminDirectExtend,
  submitAdminProxyReturn,
  type PendingVerificationAllocation,
} from './api'

const props = defineProps<{ disabled?: boolean }>()

const allocations = ref<PendingVerificationAllocation[]>([])
const loading = ref(true)
const loadError = ref('')
const busyId = ref('')
const feedback = ref('')
const actionError = ref('')
const newTimes = reactive<Record<string, string>>({})
const reasons = reactive<Record<string, string>>({})
const proxyVerified = reactive<Record<string, boolean>>({})

const shanghaiTime = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

function formatTime(iso: string): string {
  return shanghaiTime.format(new Date(iso))
}

async function loadActive() {
  loading.value = true
  loadError.value = ''
  try {
    allocations.value = await getAdminActiveAllocations()
  } catch {
    loadError.value = '暂时无法读取当前有效名额，请稍后重试。'
  } finally {
    loading.value = false
  }
}

async function submitExtend(item: PendingVerificationAllocation) {
  const newExpiresAt = (newTimes[item.id] ?? '').trim()
  const reason = (reasons[item.id] ?? '').trim()
  if (!newExpiresAt) {
    actionError.value = '请填写新到期时间（ISO-8601 UTC）。'
    return
  }
  if (!reason) {
    actionError.value = '请填写非空延期理由。'
    return
  }
  busyId.value = item.id
  actionError.value = ''
  feedback.value = ''
  try {
    const result = await submitAdminDirectExtend(item.id, item.status, item.expiresAt, newExpiresAt, reason)
    feedback.value = result.alreadyExtended
      ? `GPU ${item.gpuId} 名额到期时间已为 ${formatTime(result.expiresAt)}，无需重复延期。`
      : `已直接延长 ${item.memberDisplayName} 的 GPU ${item.gpuId} 名额至 ${formatTime(result.expiresAt)}。`
    delete reasons[item.id]
    await loadActive()
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      actionError.value = error.fieldError?.message ?? '名额状态或到期时间已变化，已自动刷新。'
      await loadActive()
    } else if (error instanceof ApiError && error.fieldError?.message) {
      actionError.value = error.fieldError.message
    } else {
      actionError.value = '直接延期失败，请稍后重试。'
    }
  } finally {
    busyId.value = ''
  }
}

async function submitProxyReturn(item: PendingVerificationAllocation) {
  const reason = (reasons[item.id] ?? '').trim()
  if (!proxyVerified[item.id]) {
    actionError.value = '请先勾选确认已线下核实任务完成或未开始名额可释放。'
    return
  }
  if (!reason) {
    actionError.value = '请填写非空代归还理由。'
    return
  }
  busyId.value = item.id
  actionError.value = ''
  feedback.value = ''
  try {
    const result = await submitAdminProxyReturn(item.id, item.status, true, reason)
    feedback.value = result.alreadyEnded
      ? `GPU ${item.gpuId} 名额已结束（${result.endReason ?? 'ENDED'}），无需重复代归还。`
      : `已代 ${item.memberDisplayName} 归还 GPU ${item.gpuId} 名额并完成后续队列交接。`
    delete reasons[item.id]
    delete proxyVerified[item.id]
    await loadActive()
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      actionError.value = error.fieldError?.message ?? '名额状态已变化，已自动刷新。'
      await loadActive()
    } else if (error instanceof ApiError && error.fieldError?.message) {
      actionError.value = error.fieldError.message
    } else {
      actionError.value = '代归还失败，请稍后重试。'
    }
  } finally {
    busyId.value = ''
  }
}

onMounted(loadActive)
</script>

<template>
  <section aria-labelledby="admin-direct-extend-title">
    <h2 id="admin-direct-extend-title">管理员直接延长名额期限</h2>
    <p v-if="loading" role="status">正在读取当前有效名额…</p>
    <div v-else-if="loadError" class="actions">
      <p class="error" role="alert">{{ loadError }}</p>
      <button type="button" :disabled="props.disabled" @click="loadActive">重试</button>
    </div>
    <p v-else-if="allocations.length === 0">当前没有有效使用中的名额。</p>
    <div v-else>
      <button type="button" :disabled="props.disabled || loading" @click="loadActive">刷新有效名额</button>
      <p v-if="feedback" role="status">{{ feedback }}</p>
      <p v-if="actionError" class="error" role="alert">{{ actionError }}</p>
      <ul>
        <li v-for="item in allocations" :key="item.id">
          <div>
            <strong>GPU {{ item.gpuId }}</strong> · {{ item.memberDisplayName }} · 状态 {{ item.status }}
            · 当前到期 {{ formatTime(item.expiresAt) }}
          </div>
          <div class="actions">
            <input
              v-model="newTimes[item.id]"
              type="text"
              placeholder="新到期时间（ISO-8601 UTC）"
              :disabled="props.disabled || busyId === item.id"
            />
            <input
              v-model="reasons[item.id]"
              type="text"
              maxlength="500"
              placeholder="直接延期理由（必填）"
              :disabled="props.disabled || busyId === item.id"
            />
            <button
              type="button"
              :disabled="props.disabled || busyId === item.id"
              @click="submitExtend(item)"
            >
              直接延期
            </button>
            <label>
              <input v-model="proxyVerified[item.id]" type="checkbox" :disabled="props.disabled || busyId === item.id" />
              已线下核实完成/可释放
            </label>
            <button
              type="button"
              :disabled="props.disabled || busyId === item.id"
              @click="submitProxyReturn(item)"
            >
              代成员归还
            </button>
          </div>
        </li>
      </ul>
    </div>
  </section>
</template>
