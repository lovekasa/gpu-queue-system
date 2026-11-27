<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ApiError, getAdminQueues, reorderAdminQueue, type AdminGpuQueue } from './api'

const props = defineProps<{ disabled?: boolean }>()

const queues = ref<AdminGpuQueue[]>([])
const loading = ref(true)
const loadError = ref('')
const busyGpuId = ref('')
const feedback = ref('')
const actionError = ref('')
const targetPos = reactive<Record<string, number>>({})
const reasons = reactive<Record<string, string>>({})

async function loadQueues() {
  loading.value = true
  loadError.value = ''
  try {
    queues.value = await getAdminQueues()
  } catch {
    loadError.value = '暂时无法读取管理端等待队列，请稍后重试。'
  } finally {
    loading.value = false
  }
}

async function moveItem(queue: AdminGpuQueue, queueItemId: string) {
  const pos = Number(targetPos[queueItemId])
  const reason = (reasons[queueItemId] ?? '').trim()
  if (!Number.isInteger(pos) || pos < 1 || pos > queue.waiting.length) {
    actionError.value = `目标位置必须为 1 至 ${queue.waiting.length} 的整数。`
    return
  }
  if (!reason) {
    actionError.value = '请先填写非空调序理由。'
    return
  }
  busyGpuId.value = queue.gpuId
  actionError.value = ''
  feedback.value = ''
  try {
    const seenIds = queue.waiting.map(item => item.queueItemId)
    const result = await reorderAdminQueue(queue.gpuId, queueItemId, pos, seenIds, reason)
    if (!result.changed) {
      feedback.value = `GPU ${queue.gpuId} 该等待项已在第 ${pos} 位，无需调整。`
    } else {
      feedback.value = `已调整 GPU ${queue.gpuId} 队列顺序，受影响的在排成员将收到站内通知。`
    }
    delete reasons[queueItemId]
    await loadQueues()
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      actionError.value = error.fieldError?.message ?? '队列顺序已变化，已为您刷新最新队列。'
      await loadQueues()
    } else if (error instanceof ApiError && error.fieldError?.message) {
      actionError.value = error.fieldError.message
    } else {
      actionError.value = '调序失败，请稍后重试。'
    }
  } finally {
    busyGpuId.value = ''
  }
}

onMounted(loadQueues)
</script>

<template>
  <section aria-labelledby="admin-reorder-title">
    <h2 id="admin-reorder-title">管理员等待队列调序</h2>
    <p v-if="loading" role="status">正在读取各卡等待队列…</p>
    <div v-else-if="loadError" class="actions">
      <p class="error" role="alert">{{ loadError }}</p>
      <button type="button" :disabled="props.disabled" @click="loadQueues">重试</button>
    </div>
    <div v-else>
      <button type="button" :disabled="props.disabled || loading" @click="loadQueues">刷新管理队列</button>
      <p v-if="feedback" role="status">{{ feedback }}</p>
      <p v-if="actionError" class="error" role="alert">{{ actionError }}</p>
      <div v-for="queue in queues" :key="queue.gpuId">
        <template v-if="queue.waiting.length > 0">
          <h3>GPU {{ queue.gpuId }}（{{ queue.waiting.length }} 人等待）</h3>
          <ol>
            <li v-for="item in queue.waiting" :key="item.queueItemId">
              <div>
                <strong>#{{ item.position }} {{ item.memberName }}</strong>
                · {{ item.durationHours }}h
                · 序号 {{ item.receiveSeq }}
                <span v-if="item.boostedOver72Hours"> [>72h提升]</span>
                <span v-else-if="item.approvedEmergency"> [紧急已批准]</span>
              </div>
              <div class="actions">
                <input
                  v-model.number="targetPos[item.queueItemId]"
                  type="number"
                  min="1"
                  :max="queue.waiting.length"
                  :placeholder="`目标位置(1-${queue.waiting.length})`"
                  :disabled="props.disabled || busyGpuId === queue.gpuId"
                />
                <input
                  v-model="reasons[item.queueItemId]"
                  type="text"
                  maxlength="500"
                  placeholder="调序理由（必填）"
                  :disabled="props.disabled || busyGpuId === queue.gpuId"
                />
                <button
                  type="button"
                  :disabled="props.disabled || busyGpuId === queue.gpuId"
                  @click="moveItem(queue, item.queueItemId)"
                >
                  移动
                </button>
              </div>
            </li>
          </ol>
        </template>
      </div>
    </div>
  </section>
</template>
