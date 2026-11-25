<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, getGpus, getOwnRequests, submitOrdinaryRequest,
  type FieldError, type Gpu, type OrdinaryRequest } from './api'

const props = defineProps<{ disabled: boolean }>()
type LoadState = 'loading' | 'ready' | 'unavailable'
const catalogState = ref<LoadState>('loading')
const listState = ref<LoadState>('loading')
const gpus = ref<Gpu[]>([])
const requests = ref<OrdinaryRequest[]>([])
const selectedGpuIds = ref<string[]>([])
const durationHours = ref('24')
const submitting = ref(false)
const fieldError = ref<FieldError>()
const submitFailure = ref('')
const created = ref<OrdinaryRequest>()

const shanghaiTime = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
})

function formatTime(value: string) {
  return shanghaiTime.format(new Date(value))
}

async function refreshCatalog() {
  catalogState.value = 'loading'
  try {
    gpus.value = await getGpus()
    catalogState.value = 'ready'
  } catch {
    catalogState.value = 'unavailable'
  }
}

async function refreshRequests() {
  listState.value = 'loading'
  try {
    requests.value = await getOwnRequests()
    listState.value = 'ready'
  } catch {
    listState.value = 'unavailable'
  }
}

async function submit() {
  if (submitting.value || props.disabled || catalogState.value !== 'ready' || listState.value === 'loading') return
  fieldError.value = undefined
  submitFailure.value = ''
  created.value = undefined
  if (!selectedGpuIds.value.length) {
    fieldError.value = { field: 'gpuIds', message: '请至少选择一张 GPU。' }
    return
  }
  if (!/^[0-9]+$/.test(durationHours.value) || Number(durationHours.value) < 1 || Number(durationHours.value) > 72) {
    fieldError.value = { field: 'durationHours', message: '预计时长须为 1 至 72 的整数小时。' }
    return
  }
  submitting.value = true
  try {
    created.value = await submitOrdinaryRequest(selectedGpuIds.value, durationHours.value)
    selectedGpuIds.value = []
    await refreshRequests()
  } catch (cause) {
    if (cause instanceof ApiError && cause.fieldError?.message) {
      fieldError.value = cause.fieldError
    } else if (cause instanceof ApiError && cause.status === 401) {
      submitFailure.value = '登录已失效，请重新登录。'
    } else if (cause instanceof ApiError && cause.status === 403) {
      submitFailure.value = '无法通过权限或安全校验，请重新登录后重试。'
    } else if (cause instanceof ApiError && cause.status === 503) {
      submitFailure.value = '保存申请失败，请稍后重试。'
    } else {
      submitFailure.value = '未收到提交成功确认。请刷新“我的申请”核对后再重试。'
    }
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void refreshCatalog()
  void refreshRequests()
})
</script>

<template>
  <section class="ordinary-requests" aria-labelledby="request-title">
    <h2 id="request-title">普通申请</h2>
    <p>选择指定 GPU，免审批入队。申请受理后待分配，尚未取得使用名额。</p>
    <p v-if="catalogState === 'loading'" role="status">正在加载可选 GPU…</p>
    <div v-else-if="catalogState === 'unavailable'">
      <p class="error" role="alert">无法读取可选 GPU。</p>
      <button type="button" @click="refreshCatalog">重试加载 GPU</button>
    </div>
    <form method="post" @submit.prevent="submit">
      <fieldset :disabled="submitting || disabled || catalogState !== 'ready' || listState === 'loading'">
        <legend>提交普通申请</legend>
        <fieldset class="gpu-choices" :aria-describedby="fieldError?.field === 'gpuIds' ? 'gpu-choice-error' : undefined">
          <legend>指定 GPU（可多选）</legend>
          <label v-for="gpu in gpus" :key="gpu.id">
            <input v-model="selectedGpuIds" type="checkbox" name="gpuIds" :value="gpu.id" />
            GPU {{ gpu.id }}
          </label>
        </fieldset>
        <p v-if="fieldError?.field === 'gpuIds'" id="gpu-choice-error" class="error" role="alert">
          {{ fieldError.message }}
        </p>
        <label for="duration-hours">预计使用时长（整数小时）</label>
        <input id="duration-hours" v-model="durationHours" name="durationHours" type="number"
          min="1" max="72" step="1" required
          :aria-invalid="fieldError?.field === 'durationHours' || undefined"
          :aria-describedby="fieldError?.field === 'durationHours' ? 'duration-error' : 'duration-help'" />
        <p id="duration-help">1–72 小时；1 天按连续 24 小时计算。</p>
        <p v-if="fieldError?.field === 'durationHours'" id="duration-error" class="error" role="alert">
          {{ fieldError.message }}
        </p>
        <button type="submit">{{ submitting ? '正在提交…' : '提交普通申请' }}</button>
      </fieldset>
    </form>
    <p v-if="submitFailure" class="error" role="alert">{{ submitFailure }}</p>
    <div v-if="created" class="receipt" role="status">
      <p>申请已受理，所选 {{ created.queueItems.length }} 张 GPU 均待分配。</p>
      <p>申请编号：{{ created.id }}</p>
      <p>接收序号：{{ created.receiveSeq }}</p>
    </div>

    <h2 id="own-requests-title">我的申请</h2>
    <p>提交时间按上海时间（Asia/Shanghai）显示；每次主动提交都会建立独立申请。</p>
    <button type="button" :disabled="listState === 'loading' || submitting" @click="refreshRequests">
      {{ listState === 'unavailable' ? '重试加载我的申请' : '刷新我的申请' }}
    </button>
    <p v-if="listState === 'loading'" role="status">正在加载我的申请…</p>
    <p v-else-if="listState === 'unavailable'" class="error" role="alert">无法读取我的申请，请重试。</p>
    <p v-else-if="requests.length === 0">尚无申请，请选择 GPU 提交普通申请。</p>
    <ol v-else class="request-list" aria-labelledby="own-requests-title">
      <li v-for="request in requests" :key="request.id">
        <dl>
          <dt>申请编号</dt><dd>{{ request.id }}</dd>
          <dt>接收序号</dt><dd>{{ request.receiveSeq }}</dd>
          <dt>提交时间</dt><dd><time :datetime="request.submittedAt">{{ formatTime(request.submittedAt) }}</time></dd>
          <dt>预计时长</dt><dd>{{ request.durationHours }} 小时</dd>
        </dl>
        <ul class="waiting-items">
          <li v-for="item in request.queueItems" :key="item.id">GPU {{ item.gpuId }}：待分配</li>
        </ul>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.ordinary-requests { display: grid; gap: 0.75rem; border-top: 1px solid #d4dde7; padding-top: 1rem; }
h2 { margin: 0; font-size: 1.2rem; }
legend { margin-bottom: 0.5rem; font-weight: 700; }
.gpu-choices { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.gpu-choices label { display: flex; gap: 0.5rem; align-items: center; }
.gpu-choices input { width: 1.1rem; height: 1.1rem; margin: 0; padding: 0; }
.receipt { padding: 0.75rem; background: #edf5ed; overflow-wrap: anywhere; }
.request-list { display: grid; gap: 1rem; padding: 0; margin: 0; list-style: none; }
.request-list > li { border: 1px solid #d4dde7; border-radius: 0.5rem; padding: 0.75rem; }
.waiting-items { margin: 0.75rem 0 0; padding-left: 1.25rem; }
</style>
