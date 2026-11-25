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

export interface OrdinaryRequest {
  id: string
  submittedAt: string
  receiveSeq: string
  durationHours: number
  queueItems: { id: string; gpuId: string; status: 'WAITING' }[]
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

export async function submitOrdinaryRequest(gpuIds: string[], durationHours: string): Promise<OrdinaryRequest> {
  const body = new URLSearchParams({ durationHours })
  gpuIds.forEach(id => body.append('gpuIds', id))
  const response = await post('requests', body)
  return response.json()
}

export async function logIn(username: string, password: string): Promise<void> {
  await post('login', new URLSearchParams({ username, password }))
}

export async function logOut(): Promise<void> {
  await post('logout')
}

export async function createMember(loginName: string, displayName: string, password: string): Promise<Identity> {
  const response = await post('admin/members', new URLSearchParams({ loginName, displayName, password }))
  return response.json()
}
