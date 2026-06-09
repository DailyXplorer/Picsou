import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { AxiosAdapter } from 'axios'

// zustand's `persist` (profile-store) needs a working localStorage; jsdom here
// doesn't provide one, so install a tiny in-memory shim before importing stores.
function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => void m.set(k, String(v)),
    removeItem: (k) => void m.delete(k),
    clear: () => m.clear(),
    key: (i) => [...m.keys()][i] ?? null,
    get length() { return m.size },
  } as Storage
}
vi.stubGlobal('localStorage', memoryStorage())
vi.stubGlobal('sessionStorage', memoryStorage())

const { api } = await import('./api-client')
const { useAuthStore } = await import('@/stores/auth-store')
const { useProfileStore } = await import('@/stores/profile-store')

/**
 * The request interceptor must only attach `?memberId=` for admins (the backend
 * ignores the override for non-admins and rejects it for activated members). This
 * stops a stale persisted `activeMemberId` on a shared browser from ever scoping a
 * regular member's requests to someone else's data.
 */
describe('api-client memberId interceptor', () => {
  let captured: Record<string, unknown> | undefined

  beforeEach(() => {
    captured = undefined
    // Capture the outgoing params instead of hitting the network.
    const echoAdapter: AxiosAdapter = async (config) => {
      captured = config.params
      return {
        data: null,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }
    api.defaults.adapter = echoAdapter
  })

  afterEach(() => {
    useAuthStore.getState().logout()
    useProfileStore.getState().reset()
  })

  function asUser(role: 'ADMIN' | 'MEMBER') {
    useAuthStore.getState().login({ username: 'u', role, memberId: 1, displayName: 'U' })
  }

  it('attaches memberId when an admin is impersonating a managed profile', async () => {
    asUser('ADMIN')
    useProfileStore.getState().setActiveMember(5)
    await api.get('/dashboard')
    expect(captured?.memberId).toBe(5)
  })

  it('does NOT attach memberId for a non-admin, even with a stale activeMemberId', async () => {
    asUser('MEMBER')
    useProfileStore.getState().setActiveMember(5)
    await api.get('/dashboard')
    expect(captured?.memberId).toBeUndefined()
  })

  it('does NOT attach memberId when no profile is active', async () => {
    asUser('ADMIN')
    useProfileStore.getState().reset()
    await api.get('/dashboard')
    expect(captured?.memberId).toBeUndefined()
  })
})
