import { api } from '@/lib/api-client'

/**
 * Safe projection of an access-key (mirrors backend `AccessKeyResponse`). The raw
 * secret and its hash are NEVER part of this shape — the secret is returned exactly
 * once, at creation, inside {@link AccessKeyCreated}.
 */
export interface AccessKey {
  id: number
  name: string
  keyPrefix: string
  scopes: string[]
  lastUsedAt: string | null
  expiresAt: string | null
  revokedAt: string | null
  createdAt: string
}

/** Returned once at creation: the one-time plaintext `secret` plus the persisted metadata. */
export interface AccessKeyCreated {
  secret: string
  key: AccessKey
}

/** Body for `POST /access-keys` (mirrors backend `AccessKeyCreateRequest`). */
export interface CreateAccessKeyInput {
  name: string
  scopes: string[]
  /** ISO-8601 instant; omit / null for a key that never expires. Must be in the future. */
  expiresAt?: string | null
}

export const accessKeysApi = {
  list: () => api.get<AccessKey[]>('/access-keys').then((r) => r.data),

  create: (data: CreateAccessKeyInput) =>
    api.post<AccessKeyCreated>('/access-keys', data).then((r) => r.data),

  revoke: (id: number) => api.delete(`/access-keys/${id}`),
}
