<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getPublicQueues, type PublicGpuQueue } from './api'

type LoadState = 'loading' | 'ready' | 'unavailable'

const state = ref<LoadState>('loading')
const queues = ref<PublicGpuQueue[]>([])

async function refresh() {
  state.value = 'loading'
  try {
    queues.value = await getPublicQueues()
    state.value = 'ready'
  } catch {
    state.value = 'unavailable'
  }
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <section class="public-queues" aria-labelledby="public-queues-title">
    <h2 id="public-queues-title">各 GPU 公开等待队列</h2>
    <p>公开队列仅展示当前在排成员的姓名与顺序，不展示他人申请详情、时长或名额信息。</p>
    <button type="button" :disabled="state === 'loading'" @click="refresh">
      {{ state === 'unavailable' ? '重试加载公开队列' : '刷新公开队列' }}
    </button>
    <p v-if="state === 'loading'" role="status">正在加载公开等待队列…</p>
    <p v-else-if="state === 'unavailable'" class="error" role="alert">无法读取公开等待队列，请重试。</p>
    <ul v-else class="queue-cards">
      <li v-for="queue in queues" :key="queue.gpuId">
        <strong>GPU {{ queue.gpuId }}</strong>
        <p v-if="queue.waiting.length === 0">当前无等待成员</p>
        <ol v-else class="waiting-names">
          <li v-for="entry in queue.waiting" :key="entry.position">
            第 {{ entry.position }} 位：{{ entry.memberName }}
          </li>
        </ol>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.public-queues { display: grid; gap: 0.75rem; border-top: 1px solid #d4dde7; padding-top: 1rem; }
h2 { margin: 0; font-size: 1.2rem; }
.queue-cards { display: grid; gap: 0.5rem; margin: 0; padding: 0; list-style: none; }
.queue-cards > li { border: 1px solid #d4dde7; border-radius: 0.5rem; padding: 0.65rem 0.75rem; }
.waiting-names { margin: 0.35rem 0 0; padding-left: 1.25rem; }
</style>
