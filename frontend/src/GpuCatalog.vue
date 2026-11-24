<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getGpus, type Gpu } from './api'

type CatalogState = 'loading' | 'ready' | 'unavailable'

const state = ref<CatalogState>('loading')
const gpus = ref<Gpu[]>([])

async function refresh() {
  state.value = 'loading'
  try {
    gpus.value = await getGpus()
    state.value = 'ready'
  } catch {
    state.value = 'unavailable'
  }
}

onMounted(refresh)
</script>

<template>
  <section class="catalog" aria-labelledby="gpu-title">
    <h2 id="gpu-title">GPU 目录</h2>
    <p>系统管理指定 GPU 的使用名额。名额记录不代表硬件真实占用。</p>
    <p>获配后，请先在网页确认开始，再自行登录服务器运行任务。</p>
    <p v-if="state === 'loading'" role="status">正在加载 GPU 目录…</p>
    <p v-else-if="state === 'unavailable'" class="error" role="alert">无法读取 GPU 目录，请重试。</p>
    <ul v-else class="gpu-list">
      <li v-for="gpu in gpus" :key="gpu.id">
        <strong>GPU {{ gpu.id }}</strong>
        <span>{{ gpu.model }}</span>
      </li>
    </ul>
    <button type="button" :disabled="state === 'loading'" @click="refresh">
      {{ state === 'unavailable' ? '重试' : '刷新目录' }}
    </button>
  </section>
</template>

<style scoped>
.catalog {
  display: grid;
  gap: 0.75rem;
}

h2 {
  margin: 0;
  font-size: 1.2rem;
}

.gpu-list {
  display: grid;
  gap: 0.5rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.gpu-list li {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 0.25rem 0.75rem;
  padding: 0.5rem 0.75rem;
  border: 1px solid #d4dde7;
  border-radius: 0.5rem;
}
</style>
