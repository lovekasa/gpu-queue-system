<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  ApiError,
  getPendingVerificationAllocations,
  submitAdminVerifiedRelease,
  type PendingVerificationAllocation,
} from './api'

const props = defineProps<{ disabled: boolean }>()
type LoadState = 'loading' | 'ready' | 'unavailable'

const state = ref<LoadState>('loading')
const items = ref<PendingVerificationAllocation[]>([])
const reasons = ref<Record<string, string>>({})
const verifiedFlags = ref<Record<string, boolean>>({})
const releasingId = ref<string | null>(null)
const notice = ref('')
const errorMessage = ref('')

const shanghaiTime = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
})

function formatTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : shanghaiTime.format(date)
}

async function refresh() {
  state.value = 'loading'
  try {
    items.value = await getPendingVerificationAllocations()
    state.value = 'ready'
  } catch {
    state.value = 'unavailable'
  }
}

async function release(item: PendingVerificationAllocation) {
  if (releasingId.value !== null || props.disabled || state.value === 'loading') return
  notice.value = ''
  errorMessage.value = ''
  const verified = Boolean(verifiedFlags.value[item.id])
  const reason = (reasons.value[item.id] ?? '').trim()
  if (!verified) {
    errorMessage.value = '请先勾选确认已完成核实。'
    return
  }
  if (!reason) {
    errorMessage.value = '请填写核实释放理由。'
    return
  }
  releasingId.value = item.id
  try {
    const result = await submitAdminVerifiedRelease(item.id, item.status, verified, reason)
    notice.value = result.alreadyEnded
      ? `GPU ${item.gpuId}（名额 ${item.id}）此前已结束，未重复释放。`
      : `已核实并释放 GPU ${item.gpuId}（名额 ${item.id}），若有合法等待项已自动按序交接。`
    delete reasons.value[item.id]
    delete verifiedFlags.value[item.id]
    await refresh()
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 409) {
      errorMessage.value = `GPU ${item.gpuId}（名额 ${item.id}）状态已发生变化，已刷新列表，请重新核实。`
      await refresh()
    } else if (cause instanceof ApiError && cause.fieldError?.message) {
      errorMessage.value = cause.fieldError.message
    } else if (cause instanceof ApiError && cause.status === 503) {
      errorMessage.value = '核实释放保存失败，名额状态未变更，请稍后重试。'
    } else {
      errorMessage.value = '核实释放失败，请刷新列表后重试。'
    }
  } finally {
    releasingId.value = null
  }
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <section class="admin-verify" aria-labelledby="admin-verify-title">
    <h2 id="admin-verify-title">异常与逾期核实释放</h2>
    <p>异常（24 小时未开始）需核实名额可释放；逾期（已开始到期）需核实任务已完成。释放操作将写入审计并通知原持有者。</p>
    <button type="button" :disabled="state === 'loading' || releasingId !== null || disabled" @click="refresh">
      {{ state === 'unavailable' ? '重试加载待核实名额' : '刷新待核实名额' }}
    </button>
    <p v-if="errorMessage" class="error" role="alert">{{ errorMessage }}</p>
    <p v-if="notice" class="receipt" role="status">{{ notice }}</p>
    <p v-if="state === 'loading'" role="status">正在加载待核实名额…</p>
    <p v-else-if="state === 'unavailable'" class="error" role="alert">无法读取待核实名额，请重试。</p>
    <p v-else-if="items.length === 0">当前没有待核实的异常或逾期名额。</p>
    <ol v-else class="verify-list" aria-labelledby="admin-verify-title">
      <li v-for="item in items" :key="item.id">
        <dl>
          <dt>指定 GPU</dt><dd>GPU {{ item.gpuId }}</dd>
          <dt>持有成员</dt><dd>{{ item.memberDisplayName }}</dd>
          <dt>当前状态</dt><dd>{{ item.status === 'ANOMALY' ? '未开始异常' : '已开始逾期' }}</dd>
          <dt>名额编号</dt><dd>{{ item.id }}</dd>
          <dt>分配时间</dt><dd><time :datetime="item.allocatedAt">{{ formatTime(item.allocatedAt) }}</time></dd>
          <dt>到期时间</dt><dd><time :datetime="item.expiresAt">{{ formatTime(item.expiresAt) }}</time></dd>
        </dl>
        <label class="verify-check">
          <input v-model="verifiedFlags[item.id]" type="checkbox" :disabled="releasingId !== null || disabled" />
          {{ item.status === 'ANOMALY' ? '已核实该未开始名额可释放' : '已核实任务已完成、可释放该逾期名额' }}
        </label>
        <label>
          核实释放理由
          <input
            v-model="reasons[item.id]"
            type="text"
            maxlength="500"
            placeholder="请填写核实结果与释放理由"
            :disabled="releasingId !== null || disabled"
          />
        </label>
        <button
          type="button"
          :disabled="releasingId !== null || disabled"
          @click="release(item)"
        >
          {{ releasingId === item.id ? '正在核实释放…' : '核实并释放' }}
        </button>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.admin-verify { display: grid; gap: 0.75rem; border-top: 1px solid #d4dde7; padding-top: 1rem; }
h2 { margin: 0; font-size: 1.2rem; }
.receipt { padding: 0.75rem; border-radius: 0.5rem; background: #edf5ed; overflow-wrap: anywhere; }
.verify-list { display: grid; gap: 1rem; padding: 0; margin: 0; list-style: none; }
.verify-list > li { display: grid; gap: 0.5rem; border: 1px solid #d4dde7; border-radius: 0.5rem; padding: 0.75rem; }
.verify-check { display: flex; gap: 0.5rem; align-items: center; }
.verify-check input { width: 1.1rem; height: 1.1rem; margin: 0; }
</style>
