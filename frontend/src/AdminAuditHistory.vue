<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, getAdminAuditLogs, type AuditLogEntry } from './api'

const props = defineProps<{ disabled?: boolean }>()

const requestId = ref('')
const allocationId = ref('')
const logs = ref<AuditLogEntry[]>([])
const loading = ref(true)
const errorMessage = ref('')

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

async function loadLogs() {
  loading.value = true
  errorMessage.value = ''
  try {
    logs.value = await getAdminAuditLogs(requestId.value, allocationId.value)
  } catch (error) {
    if (error instanceof ApiError && error.fieldError?.message) {
      errorMessage.value = error.fieldError.message
    } else {
      errorMessage.value = '暂时无法读取干预历史，请稍后重试。'
    }
  } finally {
    loading.value = false
  }
}

onMounted(loadLogs)
</script>

<template>
  <section aria-labelledby="admin-audit-title">
    <h2 id="admin-audit-title">管理员干预历史追溯</h2>
    <div class="actions">
      <input v-model="requestId" type="text" placeholder="按申请 ID 筛选（可选）" :disabled="props.disabled || loading" />
      <input v-model="allocationId" type="text" placeholder="按名额 ID 筛选（可选）" :disabled="props.disabled || loading" />
      <button type="button" :disabled="props.disabled || loading" @click="loadLogs">查询历史</button>
    </div>
    <p v-if="loading" role="status">正在读取干预历史…</p>
    <p v-else-if="errorMessage" class="error" role="alert">{{ errorMessage }}</p>
    <p v-else-if="logs.length === 0">当前筛选条件下暂无记录。</p>
    <ol v-else>
      <li v-for="entry in logs" :key="entry.transitionSeq">
        <div>
          <strong>#{{ entry.transitionSeq }} {{ entry.action }}</strong>
          · {{ formatTime(entry.occurredAt) }}
          · 操作者：{{ entry.actorDisplayName ?? entry.actorType }}
          <span v-if="entry.gpuId"> · GPU {{ entry.gpuId }}</span>
        </div>
        <div v-if="entry.reason">理由：{{ entry.reason }}</div>
        <div>变更前：{{ entry.beforeState }} → 变更后：{{ entry.afterState }}</div>
      </li>
    </ol>
  </section>
</template>
