<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  ApiError,
  getPendingEmergencyRequests,
  reviewEmergencyRequest,
  type AdminEmergencyRequest,
} from './api'

const props = defineProps<{ disabled?: boolean }>()

const requests = ref<AdminEmergencyRequest[]>([])
const loading = ref(true)
const loadError = ref('')
const reasons = reactive<Record<string, string>>({})
const busyId = ref('')
const feedback = ref('')
const actionError = ref('')

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
    requests.value = await getPendingEmergencyRequests()
  } catch {
    loadError.value = '暂时无法读取待审核紧急申请，请稍后重试。'
  } finally {
    loading.value = false
  }
}

async function submitReview(req: AdminEmergencyRequest, decision: 'APPROVE' | 'REJECT') {
  const reason = (reasons[req.id] ?? '').trim()
  if (!reason) {
    actionError.value = '请先填写非空审核理由。'
    return
  }
  busyId.value = req.id
  actionError.value = ''
  feedback.value = ''
  try {
    const result = await reviewEmergencyRequest(req.id, decision, reason)
    if (result.alreadyReviewed) {
      feedback.value = `申请 ${req.id} 已有相同审核结论（${result.request.emergencyStatus}），无需重复处理。`
    } else {
      feedback.value = decision === 'APPROVE'
        ? `已批准 ${req.memberName} 的紧急申请（${req.id}），尚在等待的 GPU 已进入已批准紧急优先级组。`
        : `已拒绝 ${req.memberName} 的紧急待遇申请（${req.id}），尚在等待的 GPU 将保留原提交时间继续按普通队列排队。`
    }
    delete reasons[req.id]
    await loadPending()
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      actionError.value = error.fieldError?.message ?? '该申请已有相反审核结论，请刷新列表。'
      await loadPending()
    } else if (error instanceof ApiError && error.fieldError?.message) {
      actionError.value = error.fieldError.message
    } else {
      actionError.value = '提交审核失败，请稍后重试。'
    }
  } finally {
    busyId.value = ''
  }
}

onMounted(loadPending)
</script>

<template>
  <section aria-labelledby="admin-emergency-title">
    <h2 id="admin-emergency-title">待审核紧急申请</h2>
    <p v-if="loading" role="status">正在读取待审核紧急申请…</p>
    <div v-else-if="loadError" class="actions">
      <p class="error" role="alert">{{ loadError }}</p>
      <button type="button" :disabled="props.disabled" @click="loadPending">重试</button>
    </div>
    <p v-else-if="requests.length === 0">当前没有待审核的紧急申请。</p>
    <div v-else>
      <p v-if="feedback" role="status">{{ feedback }}</p>
      <p v-if="actionError" class="error" role="alert">{{ actionError }}</p>
      <ul>
        <li v-for="req in requests" :key="req.id">
          <div>
            <strong>{{ req.memberName }}</strong> · 预计 {{ req.durationHours }} 小时 · 提交于 {{ formatTime(req.submittedAt) }}
          </div>
          <div>
            申请卡状态：
            <span v-for="(item, idx) in req.queueItems" :key="item.id">
              {{ idx > 0 ? '，' : '' }}GPU {{ item.gpuId }}（{{ item.status === 'WAITING' ? '等待中' : '已获配' }}）
            </span>
          </div>
          <div>
            <label :for="`emergency-reason-${req.id}`">审核理由</label>
            <input
              :id="`emergency-reason-${req.id}`"
              v-model="reasons[req.id]"
              type="text"
              maxlength="500"
              placeholder="请输入批准或拒绝理由（必填）"
              :disabled="props.disabled || busyId === req.id"
            />
          </div>
          <div class="actions">
            <button
              type="button"
              :disabled="props.disabled || busyId === req.id"
              @click="submitReview(req, 'APPROVE')"
            >
              批准紧急
            </button>
            <button
              type="button"
              :disabled="props.disabled || busyId === req.id"
              @click="submitReview(req, 'REJECT')"
            >
              拒绝紧急
            </button>
          </div>
        </li>
      </ul>
    </div>
  </section>
</template>
