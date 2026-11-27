<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ApiError, getPendingRenewals, reviewRenewalRequest, type RenewalRequest } from './api'

const props = defineProps<{ disabled?: boolean }>()

const renewals = ref<RenewalRequest[]>([])
const loading = ref(true)
const loadError = ref('')
const busyId = ref('')
const feedback = ref('')
const actionError = ref('')
const reasons = reactive<Record<string, string>>({})
const approvedTimes = reactive<Record<string, string>>({})

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

async function loadPending() {
  loading.value = true
  loadError.value = ''
  try {
    const list = await getPendingRenewals()
    renewals.value = list
    for (const item of list) {
      if (!approvedTimes[item.id]) {
        approvedTimes[item.id] = item.requestedExpiresAt
      }
    }
  } catch {
    loadError.value = '暂时无法读取待审核续期申请，请稍后重试。'
  } finally {
    loading.value = false
  }
}

async function submitReview(item: RenewalRequest, decision: 'APPROVE' | 'REJECT') {
  const reason = (reasons[item.id] ?? '').trim()
  if (!reason) {
    actionError.value = '请先填写非空审核理由。'
    return
  }
  busyId.value = item.id
  actionError.value = ''
  feedback.value = ''
  try {
    const result = await reviewRenewalRequest(
      item.id,
      decision,
      reason,
      decision === 'APPROVE' ? (approvedTimes[item.id] ?? item.requestedExpiresAt) : undefined,
      item.currentExpiresAt,
    )
    feedback.value = result.alreadyReviewed
      ? `续期申请 ${item.id} 已有相同审核结论（${result.renewal.status}）。`
      : decision === 'APPROVE'
        ? `已批准 ${item.memberName} 的 GPU ${item.gpuId} 续期申请。`
        : `已拒绝 ${item.memberName} 的 GPU ${item.gpuId} 续期申请，原到期时间保持不变。`
    delete reasons[item.id]
    await loadPending()
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      actionError.value = error.fieldError?.message ?? '名额期限或状态已变化，请刷新后重试。'
      await loadPending()
    } else if (error instanceof ApiError && error.fieldError?.message) {
      actionError.value = error.fieldError.message
    } else {
      actionError.value = '提交续期审核失败，请稍后重试。'
    }
  } finally {
    busyId.value = ''
  }
}

onMounted(loadPending)
</script>

<template>
  <section aria-labelledby="admin-renewal-title">
    <h2 id="admin-renewal-title">待审核续期申请</h2>
    <p v-if="loading" role="status">正在读取待审核续期申请…</p>
    <div v-else-if="loadError" class="actions">
      <p class="error" role="alert">{{ loadError }}</p>
      <button type="button" :disabled="props.disabled" @click="loadPending">重试</button>
    </div>
    <p v-else-if="renewals.length === 0">当前没有待审核的续期申请。</p>
    <div v-else>
      <p v-if="feedback" role="status">{{ feedback }}</p>
      <p v-if="actionError" class="error" role="alert">{{ actionError }}</p>
      <ul>
        <li v-for="item in renewals" :key="item.id">
          <div>
            <strong>{{ item.memberName }}</strong> · GPU {{ item.gpuId }} · 当前到期 {{ formatTime(item.currentExpiresAt) }}
            · 期望到期 {{ formatTime(item.requestedExpiresAt) }}
          </div>
          <div>
            <label :for="`renewal-time-${item.id}`">批准到期时间（ISO-8601 UTC）</label>
            <input
              :id="`renewal-time-${item.id}`"
              v-model="approvedTimes[item.id]"
              type="text"
              :disabled="props.disabled || busyId === item.id"
            />
          </div>
          <div>
            <label :for="`renewal-reason-${item.id}`">审核理由</label>
            <input
              :id="`renewal-reason-${item.id}`"
              v-model="reasons[item.id]"
              type="text"
              maxlength="500"
              placeholder="请输入批准或拒绝理由（必填）"
              :disabled="props.disabled || busyId === item.id"
            />
          </div>
          <div class="actions">
            <button
              type="button"
              :disabled="props.disabled || busyId === item.id"
              @click="submitReview(item, 'APPROVE')"
            >
              批准续期
            </button>
            <button
              type="button"
              :disabled="props.disabled || busyId === item.id"
              @click="submitReview(item, 'REJECT')"
            >
              拒绝续期
            </button>
          </div>
        </li>
      </ul>
    </div>
  </section>
</template>
