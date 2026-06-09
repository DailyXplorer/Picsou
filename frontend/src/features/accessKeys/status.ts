export type KeyStatus = 'active' | 'expired' | 'revoked'

/**
 * Derives a key's display status from its lifecycle timestamps.
 *
 * Revocation wins over expiry (a revoked key is "revoked" even if also past its
 * expiry); an expiry at or before `now` counts as expired. Mirrors the backend's
 * `AccessKey#isUsable` precedence so the UI badge never contradicts what the
 * server will accept.
 */
export function keyStatus(
  key: { revokedAt: string | null; expiresAt: string | null },
  now: Date = new Date(),
): KeyStatus {
  if (key.revokedAt) return 'revoked'
  if (key.expiresAt && new Date(key.expiresAt).getTime() <= now.getTime()) return 'expired'
  return 'active'
}
