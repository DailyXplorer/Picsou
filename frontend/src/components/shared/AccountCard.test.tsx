import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor } from '@testing-library/react'
import { AccountCard } from './AccountCard'
import type { Account } from '@/types/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    // CurrencyDisplay (rendered for the balance) resolves the locale/currency via i18n;
    // return the real values so Intl.NumberFormat gets a valid tag instead of the raw key.
    t: (key: string) =>
      key === 'common.locale' ? 'fr-FR' : key === 'common.currency' ? 'EUR' : key,
  }),
}))

/**
 * Radix's Avatar detects load failure via a synthetic `new Image()` instance,
 * not the rendered <img> element -- stub the global so tests can drive both
 * the success and failure paths deterministically.
 */
class MockImage {
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  private _src = ''
  private handlers: Record<string, Set<() => void>> = { load: new Set(), error: new Set() }

  // Radix Avatar (@radix-ui/react-avatar ≥1.1) subscribes via addEventListener, and only
  // after it has already assigned `src` — so dispatch on both the src set and each late
  // listener registration. Firing 'load'/'error' more than once is idempotent for Radix.
  addEventListener(type: string, handler: () => void) {
    this.handlers[type]?.add(handler)
    if (this._src) this.dispatch()
  }
  removeEventListener(type: string, handler: () => void) {
    this.handlers[type]?.delete(handler)
  }
  set src(value: string) {
    this._src = value
    this.dispatch()
  }
  get src() {
    return this._src
  }

  private dispatch() {
    const src = this._src
    queueMicrotask(() => {
      if (this._src !== src) return
      if (src.includes('broken')) {
        this.onerror?.()
        this.handlers.error.forEach((h) => h())
      } else {
        this.onload?.()
        this.handlers.load.forEach((h) => h())
      }
    })
  }
}

beforeEach(() => {
  vi.stubGlobal('Image', MockImage)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const baseAccount: Account = {
  id: 1,
  name: 'Compte Courant BNP',
  type: 'CHECKING',
  provider: 'BNP Paribas',
  currency: 'EUR',
  currentBalance: 1000,
  currentBalanceEur: 1000,
  lastSyncedAt: null,
  isManual: false,
  color: '#6366f1',
  ticker: null,
  logoUrl: null,
  createdAt: '2024-01-01T00:00:00Z',
}

describe('AccountCard', () => {
  it('renders a colored circle when the account has no logo', () => {
    const { container } = render(<AccountCard account={baseAccount} />)
    expect(container.querySelector('img')).not.toBeInTheDocument()
    const dot = container.querySelector('[style*="background-color"]')
    expect(dot).toHaveStyle({ backgroundColor: '#6366f1' })
  })

  it('renders the bank logo image when logoUrl loads successfully', async () => {
    const account = { ...baseAccount, logoUrl: 'https://logos.example/bnp.png' }
    const { container } = render(<AccountCard account={account} />)

    await waitFor(() => {
      const img = container.querySelector('img') as HTMLImageElement
      expect(img).toHaveAttribute('src', 'https://logos.example/bnp.png')
    })
  })

  it('falls back to the colored circle if the logo image fails to load', async () => {
    const account = { ...baseAccount, logoUrl: 'https://logos.example/broken.png' }
    const { container } = render(<AccountCard account={account} />)

    await waitFor(() => {
      expect(container.querySelector('img')).not.toBeInTheDocument()
      const dot = container.querySelector('[style*="background-color"]')
      expect(dot).toHaveStyle({ backgroundColor: '#6366f1' })
    })
  })
})
