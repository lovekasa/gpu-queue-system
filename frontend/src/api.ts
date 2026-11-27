export interface Identity {
  id: string
  loginName: string
  displayName: string
  role: 'ADMIN' | 'MEMBER'
}

export interface Gpu {
  id: string
  model: string
}

export interface FieldError {
  field: 'loginName' | 'displayName' | 'password' | 'gpuIds' | 'durationHours'
  message: string
}

export type AllocationStatus = 'UNSTARTED' | 'IN_USE' | 'ANOMALY' | 'OVERDUE' | 'ENDED'

export interface QueueItem {
  id: string
  gpuId: string
  status: 'WAITING' | 'ALLOCATED'
  allocationId?: string
  allocatedAt?: string
  expiresAt?: string
  startedAt?: string
  notStartedAnomalyAt?: string
  endedAt?: string
  endReason?: string
  allocationStatus?: AllocationStatus
}

export interface OrdinaryRequest {
  id: string
  submittedAt: string
  receiveSeq: string
  durationHours: number
  queueItems: QueueItem[]
  requestType?: 'ORDINARY' | 'EMERGENCY'
  emergencyStatus?: 'PENDING' | 'APPROVED' | 'REJECTED'
  emergencyReviewedAt?: string
  emergencyReviewReason?: string
}

export interface AdminEmergencyRequest {
  id: string
  memberId: string
  memberName: string
  submittedAt: string
  receiveSeq: string
  durationHours: number
  requestType: 'EMERGENCY'
  emergencyStatus: 'PENDING' | 'APPROVED' | 'REJECTED'
  emergencyReviewedAt?: string
  emergencyReviewReason?: string
  queueItems: QueueItem[]
}

export interface EmergencyReviewResult {
  request: AdminEmergencyRequest
  alreadyReviewed: boolean
}

export interface Allocation {
  id: string
  requestId: string
  queueItemId: string
  gpuId: string
  durationHours: number
  allocatedAt: string
  expiresAt: string
  startedAt?: string
  notStartedAnomalyAt?: string
  endedAt?: string
  endReason?: string
  status: AllocationStatus
}

export interface StartAllocationResult {
  allocation: Allocation
  alreadyStarted: boolean
}

export interface ReturnAllocationResult {
  allocation: Allocation
  alreadyEnded: boolean
}

export interface InboxMessage {
  id: string
  transitionSeq: string
  type: 'ALLOCATED' | 'UNSTARTED_EXPIRED' | 'ANOMALY' | 'OVERDUE' | 'VERIFIED_RELEASED' | 'EMERGENCY_APPROVED' | 'EMERGENCY_REJECTED' | 'QUEUE_REORDERED' | 'RENEWAL_APPROVED' | 'RENEWAL_REJECTED' | 'ADMIN_EXTENDED'
  requestId: string
  allocationId?: string
  gpuId?: string
  title: string
  body: string
  allocatedAt?: string
  expiresAt?: string
  createdAt: string
  readAt?: string
}

export interface MarkMessageReadResult {
  message: InboxMessage
  alreadyRead: boolean
}

export interface PublicQueueEntry {
  position: number
  memberName: string
}

export interface PublicGpuQueue {
  gpuId: string
  waiting: PublicQueueEntry[]
}

interface Csrf {
  headerName: string
  token: string
}

export class ApiError extends Error {
  constructor(readonly status: number, readonly fieldError?: FieldError) {
    super(`HTTP ${status}`)
  }
}

async function request(path: string, options?: RequestInit): Promise<Response> {
  const response = await fetch(`/api/${path}`, options)
  if (!response.ok) {
    const isFieldError = (response.status === 400 || response.status === 409)
      && response.headers.get('content-type')?.includes('application/json')
    const error = isFieldError ? await response.json().catch(() => undefined) : undefined
    throw new ApiError(response.status, error)
  }
  return response
}

async function post(path: string, body: URLSearchParams | null = null): Promise<Response> {
  const response = await request('csrf')
  const csrf: Csrf = await response.json()
  return request(path, { method: 'POST', headers: { [csrf.headerName]: csrf.token }, body })
}

export async function getIdentity(): Promise<Identity> {
  const response = await request('me')
  return response.json()
}

export async function getGpus(): Promise<Gpu[]> {
  const response = await request('gpus')
  return response.json()
}

export async function getOwnRequests(): Promise<OrdinaryRequest[]> {
  const response = await request('requests')
  return response.json()
}

export async function getOwnAllocations(): Promise<Allocation[]> {
  const response = await request('allocations')
  return response.json()
}

export async function getOwnMessages(): Promise<InboxMessage[]> {
  const response = await request('messages')
  return response.json()
}

export async function markInboxMessageRead(messageId: string): Promise<MarkMessageReadResult> {
  const response = await post(`messages/${encodeURIComponent(messageId)}/read`)
  return response.json()
}

export async function getPublicQueues(): Promise<PublicGpuQueue[]> {
  const response = await request('queues')
  return response.json()
}

export async function confirmStartAllocation(allocationId: string): Promise<StartAllocationResult> {
  const response = await post(`allocations/${encodeURIComponent(allocationId)}/start`)
  return response.json()
}

export async function confirmReturnAllocation(allocationId: string): Promise<ReturnAllocationResult> {
  const response = await post(`allocations/${encodeURIComponent(allocationId)}/return`)
  return response.json()
}

export async function submitOrdinaryRequest(
  gpuIds: string[],
  durationHours: string,
  emergency = false,
): Promise<OrdinaryRequest> {
  const body = new URLSearchParams({ durationHours })
  gpuIds.forEach(id => body.append('gpuIds', id))
  if (emergency) {
    body.append('emergency', 'true')
  }
  const response = await post('requests', body)
  return response.json()
}

export async function logIn(username: string, password: string): Promise<void> {
  await post('login', new URLSearchParams({ username, password }))
}

export async function logOut(): Promise<void> {
  await post('logout')
}

export interface PendingVerificationAllocation {
  id: string
  requestId: string
  queueItemId: string
  gpuId: string
  memberDisplayName: string
  durationHours: number
  allocatedAt: string
  expiresAt: string
  startedAt?: string
  notStartedAnomalyAt?: string
  status: 'ANOMALY' | 'OVERDUE'
}

export interface VerifiedReleaseResult {
  allocationId: string
  gpuId: string
  endedAt?: string
  endReason?: string
  alreadyEnded: boolean
}

export async function getPendingVerificationAllocations(): Promise<PendingVerificationAllocation[]> {
  const response = await request('admin/allocations/pending-verification')
  return response.json()
}

export async function submitAdminVerifiedRelease(
  allocationId: string,
  seenStatus: 'ANOMALY' | 'OVERDUE',
  verified: boolean,
  reason: string,
): Promise<VerifiedReleaseResult> {
  const body = new URLSearchParams({
    seenStatus,
    verified: verified ? 'true' : 'false',
    reason,
  })
  const response = await post(`admin/allocations/${encodeURIComponent(allocationId)}/verified-release`, body)
  return response.json()
}

export async function getPendingEmergencyRequests(): Promise<AdminEmergencyRequest[]> {
  const response = await request('admin/requests/emergency-pending')
  return response.json()
}

export async function reviewEmergencyRequest(
  requestId: string,
  decision: 'APPROVE' | 'REJECT',
  reason: string,
): Promise<EmergencyReviewResult> {
  const body = new URLSearchParams({ decision, reason })
  const response = await post(`admin/requests/${encodeURIComponent(requestId)}/emergency-review`, body)
  return response.json()
}

export interface AdminQueueItem {
  position: number
  queueItemId: string
  requestId: string
  memberName: string
  durationHours: number
  submittedAt: string
  receiveSeq: string
  approvedEmergency: boolean
  boostedOver72Hours: boolean
}

export interface AdminGpuQueue {
  gpuId: string
  waiting: AdminQueueItem[]
}

export interface ReorderResult {
  gpuId: string
  changed: boolean
  waiting: AdminQueueItem[]
}

export async function getAdminQueues(): Promise<AdminGpuQueue[]> {
  const response = await request('admin/queues')
  return response.json()
}

export async function reorderAdminQueue(
  gpuId: string,
  targetQueueItemId: string,
  targetPosition: number,
  seenQueueItemIds: string[],
  reason: string,
): Promise<ReorderResult> {
  const body = new URLSearchParams({
    targetQueueItemId,
    targetPosition: String(targetPosition),
    reason,
  })
  seenQueueItemIds.forEach(id => body.append('seenQueueItemIds', id))
  const response = await post(`admin/queues/${encodeURIComponent(gpuId)}/reorder`, body)
  return response.json()
}

export interface RenewalRequest {
  id: string
  allocationId: string
  gpuId: string
  memberId: string
  memberName: string
  currentExpiresAt: string
  requestedExpiresAt: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  approvedExpiresAt?: string
  reviewedAt?: string
  reviewReason?: string
  createdAt: string
}

export interface RenewalReviewResult {
  renewal: RenewalRequest
  alreadyReviewed: boolean
}

export async function submitRenewalRequest(allocationId: string, requestedExpiresAt: string): Promise<RenewalRequest> {
  const response = await post(
    `allocations/${encodeURIComponent(allocationId)}/renewals`,
    new URLSearchParams({ requestedExpiresAt }),
  )
  return response.json()
}

export async function getOwnRenewals(): Promise<RenewalRequest[]> {
  const response = await request('renewals')
  return response.json()
}

export async function getPendingRenewals(): Promise<RenewalRequest[]> {
  const response = await request('admin/renewals/pending')
  return response.json()
}

export async function reviewRenewalRequest(
  renewalId: string,
  decision: 'APPROVE' | 'REJECT',
  reason: string,
  approvedExpiresAt?: string,
  seenExpiresAt?: string,
): Promise<RenewalReviewResult> {
  const body = new URLSearchParams({ decision, reason })
  if (approvedExpiresAt) body.set('approvedExpiresAt', approvedExpiresAt)
  if (seenExpiresAt) body.set('seenExpiresAt', seenExpiresAt)
  const response = await post(`admin/renewals/${encodeURIComponent(renewalId)}/review`, body)
  return response.json()
}

export interface DirectExtendResult {
  allocationId: string
  gpuId: string
  expiresAt: string
  alreadyExtended: boolean
}

export async function getAdminActiveAllocations(): Promise<PendingVerificationAllocation[]> {
  const response = await request('admin/allocations/active')
  return response.json()
}

export async function submitAdminDirectExtend(
  allocationId: string,
  seenStatus: string,
  seenExpiresAt: string,
  newExpiresAt: string,
  reason: string,
): Promise<DirectExtendResult> {
  const body = new URLSearchParams({ seenStatus, seenExpiresAt, newExpiresAt, reason })
  const response = await post(`admin/allocations/${encodeURIComponent(allocationId)}/extend`, body)
  return response.json()
}

export async function createMember(loginName: string, displayName: string, password: string): Promise<Identity> {
  const response = await post('admin/members', new URLSearchParams({ loginName, displayName, password }))
  return response.json()
}
