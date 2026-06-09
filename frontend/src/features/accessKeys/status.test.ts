import { describe, it, expect } from 'vitest'
import { keyStatus } from './status'

const NOW = new Date('2026-06-04T12:00:00Z')

describe('keyStatus', () => {
  it('is revoked when revokedAt is set, even if also past expiry', () => {
    expect(
      keyStatus({ revokedAt: '2026-06-01T00:00:00Z', expiresAt: '2026-05-01T00:00:00Z' }, NOW),
    ).toBe('revoked')
  })

  it('is expired when expiresAt is in the past and not revoked', () => {
    expect(keyStatus({ revokedAt: null, expiresAt: '2026-06-01T00:00:00Z' }, NOW)).toBe('expired')
  })

  it('is active when expiresAt is in the future', () => {
    expect(keyStatus({ revokedAt: null, expiresAt: '2026-12-31T00:00:00Z' }, NOW)).toBe('active')
  })

  it('is active when there is no expiry', () => {
    expect(keyStatus({ revokedAt: null, expiresAt: null }, NOW)).toBe('active')
  })

  it('treats an expiry exactly at now as expired', () => {
    expect(keyStatus({ revokedAt: null, expiresAt: '2026-06-04T12:00:00Z' }, NOW)).toBe('expired')
  })
})
