/**
 * The access-key scope vocabulary, mirroring the backend allowlist
 * `com.picsou.mcp.Scopes#ALL`. The UI must offer exactly these — a scope the
 * backend doesn't recognise would make key creation fail with HTTP 400.
 *
 * Read scopes end in `:read`; everything else (`:write` / `:trigger`) mutates.
 */
export const ALL_SCOPES = [
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
] as const

export type Scope = (typeof ALL_SCOPES)[number]

export type ScopeGroup = 'read' | 'write'

/** Classifies a scope: `:read` → read, anything else (`:write` / `:trigger`) → write. */
export function scopeGroup(scope: string): ScopeGroup {
  return scope.endsWith(':read') ? 'read' : 'write'
}

/** i18n-safe key for a scope: `accounts:read` → `accounts_read`. */
export function scopeI18nKey(scope: string): string {
  return scope.replace(':', '_')
}

export const READ_SCOPES: Scope[] = ALL_SCOPES.filter((s) => scopeGroup(s) === 'read')
export const WRITE_SCOPES: Scope[] = ALL_SCOPES.filter((s) => scopeGroup(s) === 'write')
