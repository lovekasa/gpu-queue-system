<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ApiError,
  confirmReturnAllocation,
  confirmStartAllocation,
  getGpus,
  getOwnAllocations,
  getOwnMessages,
  getOwnRequests,
  markInboxMessageRead,
  submitOrdinaryRequest,
  type Allocation,
  type AllocationStatus,
  type FieldError,
  type Gpu,
  type InboxMessage,
  type OrdinaryRequest,
} from './api'

const props = defineProps<{ disabled: boolean }>()
type LoadState = 'loading' | 'ready' | 'unavailable'

const catalogState = ref<LoadState>('loading')
const requestsState = ref<LoadState>('loading')
const allocationsState = ref<LoadState>('loading')
const messagesState = ref<LoadState>('loading')

const gpus = ref<Gpu[]>([])
const requests = ref<OrdinaryRequest[]>([])
const allocations = ref<Allocation[]>([])
const messages = ref<InboxMessage[]>([])

const selectedGpuIds = ref<string[]>([])
const durationHours = ref('24')
const isEmergency = ref(false)
const submitting = ref(false)
const fieldError = ref<FieldError>()
const submitFailure = ref('')
const created = ref<OrdinaryRequest>()
const startingAllocationId = ref<string | null>(null)
const returningAllocationId = ref<string | null>(null)
const markingMessageId = ref<string | null>(null)
const startNotice = ref('')
const startError = ref('')
const messageError = ref('')

let catalogInFlight = false
let requestsInFlight = false
let allocationsInFlight = false
let messagesInFlight = false

const anyPanelLoading = computed(() =>
  requestsState.value === 'loading'
  || allocationsState.value === 'loading'
  || messagesState.value === 'loading',
)

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

function formatAllocationStatus(status?: AllocationStatus, endReason?: string) {
  if (status === 'UNSTARTED') return '获配未开始'
  if (status === 'IN_USE') return '已开始使用'
  if (status === 'ANOMALY') return '未开始异常（待核实）'
  if (status === 'OVERDUE') return '已逾期（保留名额）'
  if (status === 'ENDED') {
    if (endReason === 'RETURNED') return '已归还'
    if (endReason === 'REVOKED') return '管理员主动撤销'
    if (endReason === 'UNSTARTED_EXPIRED') return '未开始到期已释放'
    if (endReason === 'VERIFIED_RELEASED') return '管理员核实已释放'
    return '已结束'
  }
  return status ?? '未知状态'
}

async function refreshCatalog() {
  if (catalogInFlight) return
  catalogInFlight = true
  catalogState.value = 'loading'
  try {
    gpus.value = await getGpus()
    catalogState.value = 'ready'
  } catch {
    catalogState.value = 'unavailable'
  } finally {
    catalogInFlight = false
  }
}

async function refreshRequests() {
  if (requestsInFlight) return
  requestsInFlight = true
  requestsState.value = 'loading'
  try {
    requests.value = await getOwnRequests()
    requestsState.value = 'ready'
  } catch {
    requestsState.value = 'unavailable'
  } finally {
    requestsInFlight = false
  }
}

async function refreshAllocations() {
  if (allocationsInFlight) return
  allocationsInFlight = true
  allocationsState.value = 'loading'
  try {
    allocations.value = await getOwnAllocations()
    allocationsState.value = 'ready'
  } catch {
    allocationsState.value = 'unavailable'
  } finally {
    allocationsInFlight = false
  }
}

async function refreshMessages() {
  if (messagesInFlight) return
  messagesInFlight = true
  messagesState.value = 'loading'
  try {
    messages.value = await getOwnMessages()
    messagesState.value = 'ready'
  } catch {
    messagesState.value = 'unavailable'
  } finally {
    messagesInFlight = false
  }
}

async function confirmStart(allocation: Allocation) {
  if (startingAllocationId.value !== null || submitting.value || props.disabled || allocationsState.value === 'loading') return
  startingAllocationId.value = allocation.id
  startNotice.value = ''
  startError.value = ''
  try {
    const result = await confirmStartAllocation(allocation.id)
    const startedTimeText = result.allocation.startedAt ? formatTime(result.allocation.startedAt) : ''
    startNotice.value = result.alreadyStarted
      ? `GPU ${allocation.gpuId}（名额 ${allocation.id}）此前已确认开始，保留首次开始时间 ${startedTimeText}。`
      : `已确认开始使用 GPU ${allocation.gpuId}（名额 ${allocation.id}，开始时间 ${startedTimeText}，到期时间不变；请自行登录服务器运行任务）。`
    await Promise.all([refreshAllocations(), refreshRequests()])
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 409) {
      startError.value = `GPU ${allocation.gpuId}（名额 ${allocation.id}）已结束或已失效，未修改当前名额，请刷新查看最新状态。`
      await Promise.all([refreshAllocations(), refreshRequests()])
    } else if (cause instanceof ApiError && cause.status === 401) {
      startError.value = '登录已失效，请重新登录。'
    } else if (cause instanceof ApiError && cause.status === 403) {
      startError.value = '无法通过权限或安全校验，请重新登录后重试。'
    } else if (cause instanceof ApiError && cause.status === 404) {
      startError.value = '未找到该本人名额，请刷新“我的名额”后重试。'
    } else if (cause instanceof ApiError && cause.status === 503) {
      startError.value = '确认开始保存失败，请稍后重试。'
    } else {
      startError.value = '未收到确认开始结果，请刷新“我的名额”核对后再重试。'
    }
  } finally {
    startingAllocationId.value = null
  }
}

async function confirmReturn(allocation: Allocation) {
  if (returningAllocationId.value !== null || startingAllocationId.value !== null || submitting.value || props.disabled || allocationsState.value === 'loading') return
  returningAllocationId.value = allocation.id
  startNotice.value = ''
  startError.value = ''
  try {
    const result = await confirmReturnAllocation(allocation.id)
    const endedTimeText = result.allocation.endedAt ? formatTime(result.allocation.endedAt) : ''
    startNotice.value = result.alreadyEnded
      ? `GPU ${allocation.gpuId}（名额 ${allocation.id}）此前已结束，保留首次结束时间 ${endedTimeText}。`
      : `已归还 GPU ${allocation.gpuId}（名额 ${allocation.id}，归还时间 ${endedTimeText}）；若有合法等待项已自动按序交接。`
    await Promise.all([refreshAllocations(), refreshRequests(), refreshMessages()])
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 401) {
      startError.value = '登录已失效，请重新登录。'
    } else if (cause instanceof ApiError && cause.status === 403) {
      startError.value = '无法通过权限或安全校验，请重新登录后重试。'
    } else if (cause instanceof ApiError && cause.status === 404) {
      startError.value = '未找到该本人名额，请刷新“我的名额”后重试。'
    } else if (cause instanceof ApiError && cause.status === 503) {
      startError.value = '归还操作保存失败，名额状态未变更，请稍后重试。'
    } else {
      startError.value = '未收到归还确认结果，请刷新“我的名额”核对后再重试。'
    }
  } finally {
    returningAllocationId.value = null
  }
}

async function markRead(message: InboxMessage) {
  if (markingMessageId.value !== null || props.disabled || messagesState.value === 'loading') return
  markingMessageId.value = message.id
  messageError.value = ''
  try {
    await markInboxMessageRead(message.id)
    await refreshMessages()
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 401) {
      messageError.value = '登录已失效，请重新登录。'
    } else if (cause instanceof ApiError && cause.status === 403) {
      messageError.value = '无法通过权限或安全校验，请重新登录后重试。'
    } else {
      messageError.value = '标记已读失败，请重试。'
    }
  } finally {
    markingMessageId.value = null
  }
}

async function submit() {
  if (submitting.value || props.disabled || catalogState.value !== 'ready' || anyPanelLoading.value) return
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
    created.value = await submitOrdinaryRequest(selectedGpuIds.value, durationHours.value, isEmergency.value)
    selectedGpuIds.value = []
    isEmergency.value = false
    await Promise.all([refreshRequests(), refreshAllocations(), refreshMessages()])
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
      submitFailure.value = '未收到提交成功确认。请刷新“我的申请”和“我的名额”核对后再重试。'
    }
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void refreshCatalog()
  void refreshRequests()
  void refreshAllocations()
  void refreshMessages()
})
</script>

<template>
  <section class="ordinary-requests" aria-labelledby="request-title">
    <h2 id="request-title">普通申请</h2>
    <p>选择指定 GPU，免审批入队并按各卡当前顺序分配可用名额；已被占用的卡保持待分配。名额仅记录使用权，不代表硬件实际空闲或训练任务已启动。</p>
    <p v-if="catalogState === 'loading'" role="status">正在加载可选 GPU…</p>
    <div v-else-if="catalogState === 'unavailable'">
      <p class="error" role="alert">无法读取可选 GPU。</p>
      <button type="button" :disabled="disabled" @click="refreshCatalog">重试加载 GPU</button>
    </div>
    <form method="post" @submit.prevent="submit">
      <fieldset :disabled="submitting || disabled || catalogState !== 'ready' || anyPanelLoading">
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
        <input
          id="duration-hours"
          v-model="durationHours"
          name="durationHours"
          type="number"
          min="1"
          max="72"
          step="1"
          required
          :aria-invalid="fieldError?.field === 'durationHours' || undefined"
          :aria-describedby="fieldError?.field === 'durationHours' ? 'duration-error' : 'duration-help'"
        />
        <p id="duration-help">1–72 小时；1 天按连续 24 小时计算，获配卡从各自分配时刻独立计算到期时间。</p>
        <p v-if="fieldError?.field === 'durationHours'" id="duration-error" class="error" role="alert">
          {{ fieldError.message }}
        </p>
        <label>
          <input v-model="isEmergency" type="checkbox" name="emergency" />
          紧急申请（审核前按普通规则排队，批准后尚在等待的卡进入已批准紧急优先级组）
        </label>
        <button type="submit">{{ submitting ? '正在提交…' : (isEmergency ? '提交紧急申请' : '提交普通申请') }}</button>
      </fieldset>
    </form>
    <p v-if="submitFailure" class="error" role="alert">{{ submitFailure }}</p>
    <div v-if="created" class="receipt" role="status">
      <p>申请已处理，请按卡查看当前结果（名额仅表示使用权记录，不代表硬件实际空闲或训练任务已启动）：</p>
      <p>申请编号：{{ created.id }}</p>
      <p>接收序号：{{ created.receiveSeq }}</p>
      <ul class="waiting-items">
        <li v-for="item in created.queueItems" :key="item.id">
          <template v-if="item.status === 'ALLOCATED' && item.allocationId && item.allocatedAt && item.expiresAt">
            GPU {{ item.gpuId }}：已获配（{{ formatAllocationStatus(item.allocationStatus) }}）｜名额编号 {{ item.allocationId }}｜分配时间
            <time :datetime="item.allocatedAt">{{ formatTime(item.allocatedAt) }}</time>｜到期时间
            <time :datetime="item.expiresAt">{{ formatTime(item.expiresAt) }}</time>
          </template>
          <template v-else-if="item.status === 'WAITING'">
            GPU {{ item.gpuId }}：待分配
          </template>
          <template v-else>
            GPU {{ item.gpuId }}：名额状态读取异常
          </template>
        </li>
      </ul>
    </div>

    <h2 id="own-allocations-title">我的名额</h2>
    <p>名额仅记录指定 GPU 的使用权，不代表服务器硬件实际空闲，也不表示训练任务已启动；使用前请先按约定确认开始。时间按上海时间（Asia/Shanghai）显示，各卡独立计算到期时间。</p>
    <button
      type="button"
      :disabled="allocationsState === 'loading' || submitting || disabled"
      @click="refreshAllocations"
    >
      {{ allocationsState === 'unavailable' ? '重试加载我的名额' : '刷新我的名额' }}
    </button>
    <p v-if="startError" class="error" role="alert">{{ startError }}</p>
    <p v-if="startNotice" class="receipt" role="status">{{ startNotice }}</p>
    <p v-if="allocationsState === 'loading'" role="status">正在加载我的名额…</p>
    <p v-else-if="allocationsState === 'unavailable'" class="error" role="alert">无法读取我的名额，请重试。</p>
    <p v-else-if="allocations.length === 0">当前暂无已获配名额。</p>
    <ol v-else class="record-list" aria-labelledby="own-allocations-title">
      <li v-for="allocation in allocations" :key="allocation.id">
        <dl>
          <dt>指定 GPU</dt><dd>GPU {{ allocation.gpuId }}</dd>
          <dt>名额状态</dt><dd>{{ formatAllocationStatus(allocation.status) }}</dd>
          <dt>名额编号</dt><dd>{{ allocation.id }}</dd>
          <dt>申请编号</dt><dd>{{ allocation.requestId }}</dd>
          <dt>预计时长</dt><dd>{{ allocation.durationHours }} 小时</dd>
          <dt>分配时间</dt><dd><time :datetime="allocation.allocatedAt">{{ formatTime(allocation.allocatedAt) }}</time></dd>
          <dt>到期时间</dt><dd><time :datetime="allocation.expiresAt">{{ formatTime(allocation.expiresAt) }}</time></dd>
          <template v-if="allocation.startedAt">
            <dt>开始时间</dt><dd><time :datetime="allocation.startedAt">{{ formatTime(allocation.startedAt) }}</time></dd>
          </template>
        </dl>
        <div class="quota-actions">
          <button
            v-if="allocation.status === 'UNSTARTED' || allocation.status === 'ANOMALY'"
            type="button"
            :disabled="startingAllocationId !== null || returningAllocationId !== null || submitting || disabled"
            @click="confirmStart(allocation)"
          >
            {{ startingAllocationId === allocation.id ? '正在确认开始…' : '确认开始使用' }}
          </button>
          <button
            type="button"
            :disabled="startingAllocationId !== null || returningAllocationId !== null || submitting || disabled"
            @click="confirmReturn(allocation)"
          >
            {{ returningAllocationId === allocation.id ? '正在归还…' : '确认完成并归还' }}
          </button>
        </div>
      </li>
    </ol>

    <h2 id="own-requests-title">我的申请</h2>
    <p>时间按上海时间（Asia/Shanghai）显示；整份申请按每张指定 GPU 独立显示待分配或已获配状态，每次主动提交都会建立独立申请。</p>
    <button
      type="button"
      :disabled="requestsState === 'loading' || submitting || disabled"
      @click="refreshRequests"
    >
      {{ requestsState === 'unavailable' ? '重试加载我的申请' : '刷新我的申请' }}
    </button>
    <p v-if="requestsState === 'loading'" role="status">正在加载我的申请…</p>
    <p v-else-if="requestsState === 'unavailable'" class="error" role="alert">无法读取我的申请，请重试。</p>
    <p v-else-if="requests.length === 0">尚无申请，请选择 GPU 提交普通申请。</p>
    <ol v-else class="record-list" aria-labelledby="own-requests-title">
      <li v-for="request in requests" :key="request.id">
        <dl>
          <dt>申请编号</dt><dd>{{ request.id }}</dd>
          <dt>接收序号</dt><dd>{{ request.receiveSeq }}</dd>
          <dt>提交时间</dt><dd><time :datetime="request.submittedAt">{{ formatTime(request.submittedAt) }}</time></dd>
          <dt>预计时长</dt><dd>{{ request.durationHours }} 小时</dd>
          <template v-if="request.requestType === 'EMERGENCY'">
            <dt>紧急审核</dt>
            <dd>
              {{
                request.emergencyStatus === 'PENDING'
                  ? '紧急待审核（审核前按普通队列参与排序与分配）'
                  : request.emergencyStatus === 'APPROVED'
                    ? '紧急已批准'
                    : '紧急已拒绝（保留原普通队列顺序）'
              }}
              <template v-if="request.emergencyReviewReason">
                ｜理由：{{ request.emergencyReviewReason }}
              </template>
            </dd>
          </template>
        </dl>
        <ul class="waiting-items">
          <li v-for="item in request.queueItems" :key="item.id">
            <template v-if="item.status === 'ALLOCATED' && item.allocationId && item.allocatedAt && item.expiresAt">
              GPU {{ item.gpuId }}：已获配（{{ formatAllocationStatus(item.allocationStatus, item.endReason) }}）｜名额编号 {{ item.allocationId }}｜分配时间
              <time :datetime="item.allocatedAt">{{ formatTime(item.allocatedAt) }}</time>｜到期时间
              <time :datetime="item.expiresAt">{{ formatTime(item.expiresAt) }}</time>
              <template v-if="item.startedAt">
                ｜开始时间 <time :datetime="item.startedAt">{{ formatTime(item.startedAt) }}</time>
              </template>
              <template v-if="item.endedAt">
                ｜结束时间 <time :datetime="item.endedAt">{{ formatTime(item.endedAt) }}</time>
              </template>
            </template>
            <template v-else-if="item.status === 'WAITING'">
              GPU {{ item.gpuId }}：待分配
            </template>
            <template v-else>
              GPU {{ item.gpuId }}：名额状态读取异常
            </template>
          </li>
        </ul>
      </li>
    </ol>

    <h2 id="inbox-messages-title">站内消息</h2>
    <p>展示本人通知，仅在明确点击“标记已读”后记录已读时间；查询列表不会自动标记已读。时间按上海时间（Asia/Shanghai）显示。</p>
    <p v-if="messageError" class="error" role="alert">{{ messageError }}</p>
    <button
      type="button"
      :disabled="messagesState === 'loading' || submitting || disabled"
      @click="refreshMessages"
    >
      {{ messagesState === 'unavailable' ? '重试加载站内消息' : '刷新站内消息' }}
    </button>
    <p v-if="messagesState === 'loading'" role="status">正在加载站内消息…</p>
    <p v-else-if="messagesState === 'unavailable'" class="error" role="alert">无法读取站内消息，请重试。</p>
    <p v-else-if="messages.length === 0">暂无站内消息。</p>
    <ol v-else class="record-list" aria-labelledby="inbox-messages-title">
      <li v-for="message in messages" :key="message.id">
        <p class="message-title"><strong>{{ message.title }}</strong></p>
        <p class="message-body">{{ message.body }}</p>
        <dl>
          <dt>已读状态</dt>
          <dd>
            <template v-if="message.readAt">
              已读（<time :datetime="message.readAt">{{ formatTime(message.readAt) }}</time>）
            </template>
            <template v-else>未读</template>
          </dd>
          <dt>消息编号</dt><dd>{{ message.id }}</dd>
          <dt>转换序号</dt><dd>{{ message.transitionSeq }}</dd>
          <template v-if="message.gpuId">
            <dt>指定 GPU</dt><dd>GPU {{ message.gpuId }}</dd>
          </template>
          <dt>申请编号</dt><dd>{{ message.requestId }}</dd>
          <template v-if="message.allocationId">
            <dt>名额编号</dt><dd>{{ message.allocationId }}</dd>
          </template>
          <template v-if="message.allocatedAt">
            <dt>分配时间</dt><dd><time :datetime="message.allocatedAt">{{ formatTime(message.allocatedAt) }}</time></dd>
          </template>
          <template v-if="message.expiresAt">
            <dt>到期时间</dt><dd><time :datetime="message.expiresAt">{{ formatTime(message.expiresAt) }}</time></dd>
          </template>
          <dt>通知时间</dt><dd><time :datetime="message.createdAt">{{ formatTime(message.createdAt) }}</time></dd>
        </dl>
        <div v-if="!message.readAt">
          <button
            type="button"
            :disabled="markingMessageId !== null || disabled"
            @click="markRead(message)"
          >
            {{ markingMessageId === message.id ? '正在标记…' : '标记已读' }}
          </button>
        </div>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.ordinary-requests { display: grid; gap: 0.75rem; border-top: 1px solid #d4dde7; padding-top: 1rem; }
h2 { margin: 0.5rem 0 0; font-size: 1.2rem; }
#request-title { margin-top: 0; }
legend { margin-bottom: 0.5rem; font-weight: 700; }
.gpu-choices { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.gpu-choices label { display: flex; gap: 0.5rem; align-items: center; }
.gpu-choices input { width: 1.1rem; height: 1.1rem; margin: 0; padding: 0; }
.receipt { display: grid; gap: 0.35rem; padding: 0.75rem; border-radius: 0.5rem; background: #edf5ed; overflow-wrap: anywhere; }
.record-list { display: grid; gap: 1rem; padding: 0; margin: 0; list-style: none; }
.record-list > li { display: grid; gap: 0.5rem; border: 1px solid #d4dde7; border-radius: 0.5rem; padding: 0.75rem; overflow-wrap: anywhere; }
.waiting-items { display: grid; gap: 0.35rem; margin: 0.35rem 0 0; padding-left: 1.25rem; overflow-wrap: anywhere; }
.message-title, .message-body { overflow-wrap: anywhere; }
</style>
