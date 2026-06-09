import { describe, it, expect } from 'vitest'
import {
  ALL_SCOPES,
  READ_SCOPES,
  WRITE_SCOPES,
  scopeGroup,
  scopeI18nKey,
} from './scopes'

describe('scopeGroup', () => {
  it('classifies every :read scope as read', () => {
    for (const s of [
      'accounts:read',
      'transactions:read',
      'goals:read',
      'dashboard:read',
      'prices:read',
      'family:read',
    ]) {
      expect(scopeGroup(s)).toBe('read')
    }
  })

  it('classifies :write and :trigger scopes as write', () => {
    for (const s of ['accounts:write', 'transactions:write', 'goals:write', 'sync:trigger']) {
      expect(scopeGroup(s)).toBe('write')
    }
  })
})

describe('scopeI18nKey', () => {
  it('replaces the domain:action colon with an underscore', () => {
    expect(scopeI18nKey('accounts:read')).toBe('accounts_read')
    expect(scopeI18nKey('sync:trigger')).toBe('sync_trigger')
  })
})

describe('scope vocabulary', () => {
  // Guard: the UI must offer exactly the scopes the backend honours. If either side
  // drifts, key creation would 400 on an unknown scope — this fails loud instead.
  it('mirrors the backend allowlist exactly (com.picsou.mcp.Scopes.ALL)', () => {
    expect([...ALL_SCOPES].sort()).toEqual(
      [
        'accounts:read',
        'transactions:read',
        'goals:read',
        'dashboard:read',
        'prices:read',
        'family:read',
        'accounts:write',
        'transactions:write',
        'goals:write',
        'sync:trigger',
      ].sort(),
    )
  })

  it('partitions ALL_SCOPES into read and write with no overlap or omission', () => {
    expect([...READ_SCOPES, ...WRITE_SCOPES].sort()).toEqual([...ALL_SCOPES].sort())
    expect(READ_SCOPES.some((s) => WRITE_SCOPES.includes(s))).toBe(false)
  })
})
