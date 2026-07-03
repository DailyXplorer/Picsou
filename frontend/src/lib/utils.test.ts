import { describe, it, expect } from 'vitest'
import {
  cn,
  formatCurrency,
  formatDate,
  formatPercent,
  parseDate,
  isOAuthAuthorizeRedirect,
} from './utils'

describe('cn', () => {
  it('merges class names', () => {
    expect(cn('foo', 'bar')).toBe('foo bar')
  })

  it('handles conditional classes', () => {
    const enabled = false
    expect(cn('foo', enabled && 'bar', 'baz')).toBe('foo baz')
  })

  it('merges tailwind conflicts', () => {
    expect(cn('px-2', 'px-4')).toBe('px-4')
  })
})

describe('formatCurrency', () => {
  it('formats EUR in French locale', () => {
    const result = formatCurrency(1234.5, 'EUR', 'fr-FR')
    expect(result).toContain('1')
    expect(result).toContain('234')
  })

  it('formats zero', () => {
    const result = formatCurrency(0, 'EUR', 'fr-FR')
    expect(result).toContain('0')
  })

  it('formats negative values', () => {
    const result = formatCurrency(-500, 'EUR', 'fr-FR')
    expect(result).toContain('500')
  })

  it('degrades gracefully on an invalid currency code instead of throwing (issue #9)', () => {
    expect(() => formatCurrency(100, 'AMAT', 'fr-FR')).not.toThrow()
    const result = formatCurrency(100, 'AMAT', 'fr-FR')
    expect(result).toContain('AMAT')
    expect(result).toContain('100')
  })
})

describe('formatDate', () => {
  it('formats ISO date string', () => {
    const result = formatDate('2025-03-15', 'fr-FR')
    expect(result).toBeTruthy()
    expect(typeof result).toBe('string')
  })
})

describe('formatPercent', () => {
  it('formats percentage', () => {
    const result = formatPercent(0.5)
    expect(result).toContain('50')
  })
})

describe('parseDate', () => {
  it('parses dd/mm/yyyy in fr-FR locale mode', () => {
    expect(parseDate('15/03/2025', 'fr-FR', 'locale')).toBe('2025-03-15')
  })

  it('parses mm/dd/yyyy in en-US locale mode (day/month swapped)', () => {
    // 03/15 must be read month-first → March 15th, not "day 15 month 3".
    expect(parseDate('03/15/2025', 'en-US', 'locale')).toBe('2025-03-15')
  })

  it('parses dd-mm-yyyy in iso mode regardless of locale', () => {
    expect(parseDate('15-03-2025', 'en-US', 'iso')).toBe('2025-03-15')
    expect(parseDate('15-03-2025', 'fr-FR', 'iso')).toBe('2025-03-15')
  })

  it('accepts mixed separators (/, -, .)', () => {
    expect(parseDate('15.03.2025', 'fr-FR', 'locale')).toBe('2025-03-15')
    expect(parseDate('15-03-2025', 'fr-FR', 'locale')).toBe('2025-03-15')
  })

  it('expands 2-digit years into the 2000s', () => {
    expect(parseDate('15/03/25', 'fr-FR', 'locale')).toBe('2025-03-15')
  })

  it('rejects impossible calendar dates', () => {
    expect(parseDate('31/02/2025', 'fr-FR', 'locale')).toBeNull() // no Feb 31
    expect(parseDate('00/01/2025', 'fr-FR', 'locale')).toBeNull()
    expect(parseDate('15/13/2025', 'fr-FR', 'locale')).toBeNull() // month 13
  })

  it('rejects malformed input', () => {
    expect(parseDate('', 'fr-FR', 'locale')).toBeNull()
    expect(parseDate('not a date', 'fr-FR', 'locale')).toBeNull()
    expect(parseDate('15/03', 'fr-FR', 'locale')).toBeNull() // too few parts
    expect(parseDate(null, 'fr-FR', 'locale')).toBeNull()
  })

  // The contract that matters: parseDate is the exact inverse of formatDate, so a
  // value rendered in the input can always be read back to the same ISO string.
  it('round-trips formatDate → parseDate across formats and locales', () => {
    const iso = '2025-03-15'
    const cases: Array<{ locale: string; format: 'iso' | 'locale' }> = [
      { locale: 'fr-FR', format: 'locale' },
      { locale: 'en-US', format: 'locale' },
      { locale: 'fr-FR', format: 'iso' },
      { locale: 'en-US', format: 'iso' },
    ]
    for (const { locale, format } of cases) {
      const displayed = formatDate(iso, locale, format)
      expect(parseDate(displayed, locale, format), `${locale}/${format} → ${displayed}`).toBe(iso)
    }
  })
})

describe('isOAuthAuthorizeRedirect', () => {
  it('recognises backend OAuth2 authorize targets (full-page navigation, not SPA routing)', () => {
    expect(isOAuthAuthorizeRedirect('/oauth2/authorize')).toBe(true)
    expect(isOAuthAuthorizeRedirect('/oauth2/authorize?response_type=code&client_id=picsou-ios')).toBe(true)
    expect(isOAuthAuthorizeRedirect('/oauth2/token')).toBe(true)
    expect(isOAuthAuthorizeRedirect('/oauth2/')).toBe(true)
  })

  it('treats ordinary in-app SPA routes as client-side navigation', () => {
    expect(isOAuthAuthorizeRedirect('/')).toBe(false)
    expect(isOAuthAuthorizeRedirect('/dashboard')).toBe(false)
    expect(isOAuthAuthorizeRedirect('/login')).toBe(false)
    expect(isOAuthAuthorizeRedirect('')).toBe(false)
  })

  // The guard doubles as an open-redirect guard: only a literal same-origin `/oauth2/`
  // prefix triggers the full-page navigation, so protocol-relative and absolute-URL
  // targets — the classic open-redirect vectors — never qualify.
  it('rejects open-redirect vectors and lookalikes that are not a same-origin /oauth2/ path', () => {
    expect(isOAuthAuthorizeRedirect('//evil.com/oauth2/authorize')).toBe(false) // protocol-relative
    expect(isOAuthAuthorizeRedirect('https://evil.com/oauth2/authorize')).toBe(false) // absolute URL
    expect(isOAuthAuthorizeRedirect('/evil/oauth2/authorize')).toBe(false) // nested, wrong prefix
    expect(isOAuthAuthorizeRedirect('/oauth2')).toBe(false) // no trailing slash
    expect(isOAuthAuthorizeRedirect('/oauth2evil/authorize')).toBe(false) // prefix without the slash boundary
  })
})
