export interface Identity {
  id: string
  loginName: string
  displayName: string
  role: 'ADMIN' | 'MEMBER'
}

interface Csrf {
  headerName: string
  token: string
}

export class ApiError extends Error {
  constructor(readonly status: number) {
    super(`HTTP ${status}`)
  }
}

async function request(path: string, options?: RequestInit): Promise<Response> {
  const response = await fetch(`/api/${path}`, options)
  if (!response.ok) {
    throw new ApiError(response.status)
  }
  return response
}

async function post(path: string, body: URLSearchParams | null = null): Promise<void> {
  const response = await request('csrf')
  const csrf: Csrf = await response.json()
  await request(path, { method: 'POST', headers: { [csrf.headerName]: csrf.token }, body })
}

export async function getIdentity(): Promise<Identity> {
  const response = await request('me')
  return response.json()
}

export function logIn(username: string, password: string): Promise<void> {
  return post('login', new URLSearchParams({ username, password }))
}

export function logOut(): Promise<void> {
  return post('logout')
}
