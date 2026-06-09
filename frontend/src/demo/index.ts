import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import type { GoalProgress } from '@/types/api'
import { mockAccounts } from './data/accounts'
import { mockDashboard } from './data/dashboard'
import { mockGoals } from './data/goals'
import { mockHoldings } from './data/holdings'
import { mockTransactions } from './data/transactions'
import { mockExchangeStatuses, mockWalletStatuses, mockRequisitions } from './data/sync-status'
import {
  mockActivity,
  mockAllocation,
  mockBudgetSettings,
  mockBudgets,
  mockCalendar,
  mockCashflow,
  mockCategories,
  mockCategoryDetail,
  mockFlow,
  mockRecurring,
  mockRules,
  mockSpendingByCategory,
  mockUncategorized,
} from './data/budget'
import type { CashflowPeriod } from '@/types/api'

function randomDelay(): number {
  return 200 + Math.random() * 400
}

type MockHandler = (config: InternalAxiosRequestConfig) => unknown

const handlers = new Map<string, MockHandler>()

function key(method: string, url: string): string {
  const normalized = url.split('?')[0].replace(/\/$/, '')
  return `${method.toUpperCase()} ${normalized}`
}

// Auth
handlers.set(key('POST', '/auth/login'), () => ({ username: 'demo' }))
handlers.set(key('POST', '/auth/refresh'), () => ({ username: 'demo' }))

// Family — the sidebar profile switcher fetches members on every authenticated
// route, so an unhandled call here (which would fall back to `{}`) breaks the
// whole shell via `members.filter`. Return a small, realistic family: the demo
// admin (not switchable) plus one managed member the admin can impersonate.
handlers.set(key('GET', '/family/members'), () => [
  { id: 1, displayName: 'Demo', avatarColor: '#6366f1', managed: false, hasLogin: true, activated: true, loginName: 'demo', mfaEnabled: false },
  { id: 2, displayName: 'Léa', avatarColor: '#ec4899', managed: true, hasLogin: false, activated: false, loginName: null, mfaEnabled: false },
])

// Dashboard
handlers.set(key('GET', '/dashboard'), () => mockDashboard)

// Accounts
handlers.set(key('GET', '/accounts'), () => mockAccounts)
for (let i = 1; i <= 7; i++) {
  handlers.set(key('GET', `/accounts/${i}`), () => mockAccounts[i - 1])
}

// Account CRUD
handlers.set(key('POST', '/accounts'), (config) => {
  const body = JSON.parse(config.data || '{}')
  return {
    id: Date.now(),
    name: body.name ?? 'New Account',
    type: body.type ?? 'CHECKING',
    provider: body.provider ?? null,
    currency: body.currency ?? 'EUR',
    currentBalance: body.currentBalance ?? 0,
    currentBalanceEur: body.currentBalance ?? 0,
    lastSyncedAt: null,
    isManual: body.isManual ?? true,
    color: body.color ?? '#6366f1',
    ticker: body.ticker ?? null,
    createdAt: new Date().toISOString(),
  }
})
handlers.set(key('PUT', '/accounts/1'), (config) => {
  const body = JSON.parse(config.data || '{}')
  return { ...mockAccounts[0], ...body }
})
handlers.set(key('DELETE', '/accounts/1'), () => ({}))

// Account details: holdings for PEA (id=2), Compte Titres (id=3), Crypto (id=6)
handlers.set(key('GET', '/accounts/2/holdings'), () => mockHoldings[2] ?? [])
handlers.set(key('GET', '/accounts/3/holdings'), () => mockHoldings[3] ?? [])
handlers.set(key('GET', '/accounts/6/holdings'), () => mockHoldings[6] ?? [])

// Account details: transactions for all accounts
for (let i = 1; i <= 7; i++) {
  handlers.set(key('GET', `/accounts/${i}/transactions`), () => mockTransactions[i] ?? [])
}

// Security insight (asset type + ETF composition). Mirrors the backend
// SecurityInsightResponse: { ticker, assetType, composition | null }.
const demoStockTickers = ['AAPL', 'MSFT', 'AMZN', 'NVDA']
const demoCryptoTickers = ['BTC', 'ETH', 'SOL']
const demoEtfCompositions: Record<string, { companies: [string, number][]; countries: [string, number][]; sectors: [string, number][] }> = {
  IWDA: {
    companies: [['Apple', 5.1], ['Microsoft', 4.4], ['Nvidia', 4.0], ['Amazon', 2.7], ['Meta Platforms', 1.9], ['Alphabet A', 1.7], ['Alphabet C', 1.5], ['Broadcom', 1.3], ['Eli Lilly', 0.9], ['JPMorgan Chase', 0.8]],
    countries: [['US', 70.8], ['JP', 6.0], ['GB', 3.7], ['FR', 3.1], ['CA', 3.0], ['CH', 2.6], ['DE', 2.3], ['AU', 1.8]],
    sectors: [['technology', 24.1], ['financial_services', 16.4], ['healthcare', 11.2], ['industrials', 10.7], ['consumer_cyclical', 10.2], ['communication_services', 7.6], ['consumer_defensive', 6.1], ['energy', 4.0], ['basic_materials', 3.6], ['utilities', 2.7]],
  },
  EUNL: {
    companies: [['Apple', 7.1], ['Microsoft', 6.6], ['Nvidia', 6.1], ['Amazon', 3.8], ['Meta Platforms', 2.6], ['Alphabet A', 2.3], ['Alphabet C', 2.0], ['Broadcom', 1.8], ['Berkshire Hathaway', 1.6], ['Eli Lilly', 1.3]],
    countries: [['US', 100.0]],
    sectors: [['technology', 31.2], ['financial_services', 13.1], ['healthcare', 11.6], ['consumer_cyclical', 10.3], ['communication_services', 9.1], ['industrials', 8.6], ['consumer_defensive', 5.9], ['energy', 3.7], ['utilities', 2.5], ['basic_materials', 2.2]],
  },
}

function demoInsight(ticker: string) {
  if (demoStockTickers.includes(ticker)) {
    return { ticker, assetType: 'STOCK', composition: null }
  }
  if (demoCryptoTickers.includes(ticker)) {
    return { ticker, assetType: 'CRYPTO', composition: null }
  }
  const comp = demoEtfCompositions[ticker]
  if (comp) {
    const toSlices = (pairs: [string, number][]) => pairs.map(([label, percent]) => ({ label, percent }))
    return {
      ticker,
      assetType: 'ETF',
      composition: {
        companies: toSlices(comp.companies),
        countries: toSlices(comp.countries),
        sectors: toSlices(comp.sectors),
        source: 'Boursorama',
        asOf: new Date().toISOString().split('T')[0],
      },
    }
  }
  return { ticker, assetType: 'UNKNOWN', composition: null }
}

for (const ticker of [...demoStockTickers, ...demoCryptoTickers, ...Object.keys(demoEtfCompositions)]) {
  handlers.set(key('GET', `/securities/${ticker}/insight`), () => demoInsight(ticker))
}

// Account details: history for multiple accounts (12 months each)
function generateHistory(startBalances: number[]) {
  const now = new Date()
  const points: { id: number; date: string; balance: number }[] = []
  const months = startBalances.length

  for (let i = 0; i < months; i++) {
    const d = new Date(now.getFullYear(), now.getMonth() - (months - 1 - i), 1)
    points.push({
      id: 100 + i,
      date: d.toISOString().split('T')[0],
      balance: startBalances[i],
    })
  }

  return points
}

// LEP: slow steady growth (savings account)
handlers.set(key('GET', '/accounts/1/history'), () => generateHistory(
  [6100, 6250, 6400, 6500, 6650, 6800, 6950, 7100, 7200, 7400, 7600, 7800]))

// PEA: moderate growth with some dips
handlers.set(key('GET', '/accounts/2/history'), () => generateHistory(
  [8200, 8600, 9100, 8800, 9400, 9900, 10200, 10800, 11200, 11600, 12000, 12450.5]))

// Compte Titres: more volatile
handlers.set(key('GET', '/accounts/3/history'), () => generateHistory(
  [5800, 6200, 6700, 6400, 6900, 7200, 7500, 7100, 7600, 7900, 8100, 8320.75]))

// Checking BNP: fluctuates around salary cycle
handlers.set(key('GET', '/accounts/4/history'), () => generateHistory(
  [1200, 2800, 1500, 3100, 1800, 2600, 1400, 2900, 1700, 2500, 2100, 2340.2]))

// Checking BoursoBank: smaller balance, fluctuates
handlers.set(key('GET', '/accounts/5/history'), () => generateHistory(
  [800, 1100, 950, 1300, 1050, 1200, 900, 1350, 1100, 1250, 1400, 1580.9]))

// Crypto: volatile, strong upward trend
handlers.set(key('GET', '/accounts/6/history'), () => generateHistory(
  [1800, 2100, 2400, 1900, 2600, 2800, 3100, 2700, 3400, 3600, 3900, 4250]))

// Livret A: slow steady growth
handlers.set(key('GET', '/accounts/7/history'), () => generateHistory(
  [4200, 4320, 4440, 4560, 4620, 4740, 4800, 4920, 4980, 5040, 5080, 5120]))

// Goals
handlers.set(key('GET', '/goals'), () => mockGoals)
for (let i = 1; i <= 3; i++) {
  handlers.set(key('GET', `/goals/${i}`), () => mockGoals[i - 1])
  handlers.set(key('GET', `/goals/${i}/months`), () => generateMockMonths(mockGoals[i - 1]))
  handlers.set(key('POST', `/goals/${i}/history/extend`), () => mockGoals[i - 1])
  handlers.set(key('POST', `/goals/${i}/history/extend/month`), () => mockGoals[i - 1])
}
handlers.set(key('POST', '/goals'), (config) => {
  const body = JSON.parse(config.data || '{}')
  return {
    ...mockGoals[0],
    id: Date.now(),
    name: body.name ?? 'New Goal',
    targetAmount: body.targetAmount ?? 0,
    deadline: body.deadline ?? '2026-01-01',
    accounts: (body.accountIds ?? []).map((id: number) => mockAccounts.find(a => a.id === id)).filter(Boolean),
    currentTotal: 0,
    percentComplete: 0,
    monthsLeft: 6,
    monthlyNeeded: 0,
    avgMonthlyContribution: null,
    isOnTrack: true,
    surplus: 0,
  }
})
for (let i = 1; i <= 3; i++) {
  handlers.set(key('PUT', `/goals/${i}`), (config) => {
    const body = JSON.parse(config.data || '{}')
    return {
      ...mockGoals[i - 1],
      name: body.name ?? mockGoals[i - 1].name,
      targetAmount: body.targetAmount ?? mockGoals[i - 1].targetAmount,
      deadline: body.deadline ?? mockGoals[i - 1].deadline,
      accounts: (body.accountIds ?? mockGoals[i - 1].accounts.map(a => a.id))
        .map((id: number) => mockAccounts.find(a => a.id === id)).filter(Boolean),
    }
  })
}
handlers.set(key('DELETE', '/goals/1'), () => null)
handlers.set(key('DELETE', '/goals/2'), () => null)
handlers.set(key('DELETE', '/goals/3'), () => null)

// Sync
handlers.set(key('GET', '/sync/status'), () => mockRequisitions)
handlers.set(key('GET', '/sync/institutions'), () => [
  { id: 'BNP_PARIBAS', name: 'BNP Paribas', bic: 'BNPAFRPP', logoUrl: null, country: 'FR' },
  { id: 'BOURSOBANK', name: 'BoursoBank', bic: 'BNPAFRPP', logoUrl: null, country: 'FR' },
])

// Crypto exchange
handlers.set(key('GET', '/crypto/exchange/status'), () => mockExchangeStatuses)

// Crypto wallet
handlers.set(key('GET', '/crypto/wallet'), () => mockWalletStatuses)

// Sync - initiate
handlers.set(key('POST', '/sync/initiate'), () => ({
  requisitionId: 'demo-req-' + Date.now(),
  authLink: 'https://demo.enablebanking.com/auth?demo=true',
}))

// Sync - complete
handlers.set(key('POST', '/sync/complete'), () => ([
  { id: 100, name: 'Demo Bank Account', type: 'CHECKING' as const, provider: 'Demo Bank', currency: 'EUR', currentBalance: 5000, currentBalanceEur: 5000, lastSyncedAt: new Date().toISOString(), isManual: false, color: '#3b82f6', ticker: null, createdAt: new Date().toISOString() }
]))

// Sync - retry
handlers.set(key('POST', '/sync/1/retry'), () => [])

// Sync - delete
handlers.set(key('DELETE', '/sync/1'), () => null)

// Trade Republic - session status
handlers.set(key('GET', '/tr/status'), () => ({ isActive: false, expiresAt: null }))

// Trade Republic - initiate auth
handlers.set(key('POST', '/tr/auth/initiate'), () => ({ processId: 'demo-tr-process' }))

// Trade Republic - complete auth
handlers.set(key('POST', '/tr/auth/complete'), () => [])

// Trade Republic - sync
handlers.set(key('POST', '/tr/sync'), () => [])

// Trade Republic - import CSV
handlers.set(key('POST', '/tr/import'), () => [])

// Trade Republic - logout
handlers.set(key('POST', '/tr/logout'), () => null)

// Crypto exchange - add
handlers.set(key('POST', '/crypto/exchange'), () => ({
  id: Date.now(), name: 'Binance', type: 'CRYPTO' as const, provider: 'BINANCE', currency: 'USDT', currentBalance: 0, currentBalanceEur: 0, lastSyncedAt: null, isManual: false, color: '#f59e0b', ticker: null, createdAt: new Date().toISOString()
}))

// Crypto exchange - sync
handlers.set(key('POST', '/crypto/exchange/1/sync'), () => [])

// Crypto exchange - remove
handlers.set(key('DELETE', '/crypto/exchange/1'), () => null)

// Crypto wallet - add
handlers.set(key('POST', '/crypto/wallet'), () => ({
  id: Date.now(), name: 'ETH Wallet', type: 'CRYPTO' as const, provider: null, currency: 'ETH', currentBalance: 0, currentBalanceEur: 0, lastSyncedAt: null, isManual: false, color: '#8b5cf6', ticker: 'ETH', createdAt: new Date().toISOString()
}))

// Crypto wallet - sync
handlers.set(key('POST', '/crypto/wallet/1/sync'), () => [])

// Crypto wallet - remove
handlers.set(key('DELETE', '/crypto/wallet/1'), () => null)

// Finary - configured
handlers.set(key('GET', '/finary/configured'), () => true)

// Finary - preview file
handlers.set(key('POST', '/finary/preview'), () => ({
  accounts: [
    { finaryName: 'Compte Courant', finaryInstitution: 'BoursoBank', finaryCategory: 'checking', suggestedType: 'CHECKING' as const, currentBalance: 2500, nativeCurrency: 'EUR', transactionCount: 42 },
    { finaryName: 'PEA', finaryInstitution: 'BoursoBank', finaryCategory: 'pea', suggestedType: 'PEA' as const, currentBalance: 8000, nativeCurrency: 'EUR', transactionCount: 15 },
  ],
  existingPicsouAccounts: [],
  totalTransactionCount: 57,
  fileToken: 'demo-file-token',
}))

// Finary - import
handlers.set(key('POST', '/finary/import'), () => ({
  accountsCreated: 1,
  accountsMapped: 1,
  accountsSkipped: 0,
  snapshotsCreated: 3,
  transactionsImported: 57,
  importedAccounts: [
    { id: 100, name: 'PEA Finary', type: 'PEA' as const, currentBalance: 8000, color: '#10b981' },
  ],
}))

// Finary - API sync preview
handlers.set(key('POST', '/finary/api-sync/preview'), () => ({
  accounts: [
    { finaryName: 'Compte Courant', finaryInstitution: 'BoursoBank', finaryCategory: 'checking', suggestedType: 'CHECKING' as const, currentBalance: 2500, nativeCurrency: 'EUR', transactionCount: 42 },
  ],
  existingPicsouAccounts: [],
  totalTransactionCount: 42,
  syncToken: 'demo-sync-token',
}))

// Finary - API sync execute
handlers.set(key('POST', '/finary/api-sync/execute'), () => ({
  accountsCreated: 0,
  accountsMapped: 1,
  accountsSkipped: 0,
  snapshotsCreated: 2,
  transactionsImported: 42,
  importedAccounts: [],
}))

// ── Budget module ─────────────────────────────────────────────────────────────
// Read endpoints serve the mock fixtures; mutations echo a plausible object so the
// optimistic UI flows. Demo state is not persisted — refetches return the fixtures.

// Categories
handlers.set(key('GET', '/categories'), () => mockCategories)
handlers.set(key('POST', '/categories'), (config) => {
  const body = JSON.parse(config.data || '{}')
  return {
    id: Date.now(), name: body.name ?? 'Catégorie', kind: body.kind ?? 'EXPENSE',
    color: body.color ?? '#6366f1', icon: body.icon ?? null,
    isDefault: false, archived: false, sortOrder: 99, parentId: body.parentId ?? null,
  }
})
for (const c of mockCategories) {
  handlers.set(key('PUT', `/categories/${c.id}`), (config) => ({
    ...c, ...JSON.parse(config.data || '{}'),
  }))
  handlers.set(key('DELETE', `/categories/${c.id}`), () => ({}))
  handlers.set(key('POST', `/categories/${c.id}/unarchive`), () => ({ ...c, archived: false }))
}

// Categorization rules
handlers.set(key('GET', '/categorization-rules'), () => mockRules)
handlers.set(key('POST', '/categorization-rules'), (config) => {
  const body = JSON.parse(config.data || '{}')
  const cat = mockCategories.find((c) => c.id === body.categoryId)
  return {
    id: Date.now(), matchType: body.matchType ?? 'COUNTERPARTY', pattern: body.pattern ?? '',
    categoryId: body.categoryId ?? 0, categoryName: cat?.name ?? '', priority: body.priority ?? 0,
    source: 'USER',
  }
})
for (const r of mockRules) {
  handlers.set(key('PUT', `/categorization-rules/${r.id}`), (config) => ({
    ...r, ...JSON.parse(config.data || '{}'),
  }))
  handlers.set(key('DELETE', `/categorization-rules/${r.id}`), () => ({}))
}
handlers.set(key('POST', '/categorization-rules/recategorize'), () => ({ categorized: 4 }))

// To-categorize inbox
handlers.set(key('GET', '/transactions/uncategorized'), () => mockUncategorized)
for (const tx of mockUncategorized) {
  handlers.set(key('PUT', `/transactions/${tx.id}/category`), () => ({}))
}

// Envelopes
handlers.set(key('GET', '/budgets'), () => mockBudgets)
handlers.set(key('POST', '/budgets'), (config) => {
  const body = JSON.parse(config.data || '{}')
  const cat = mockCategories.find((c) => c.id === body.categoryId)
  const limit = body.monthlyLimit ?? 0
  return {
    id: Date.now(), categoryId: body.categoryId ?? 0, categoryName: cat?.name ?? 'Catégorie',
    categoryKind: cat?.kind ?? 'EXPENSE', categoryColor: cat?.color ?? null, categoryIcon: null,
    monthlyLimit: limit, spent: 0, remaining: limit, percent: 0, overBudget: false, rollup: false,
    cycleStart: mockBudgetSettings.currentCycleStart, cycleEnd: mockBudgetSettings.currentCycleEnd,
  }
})
for (const b of mockBudgets) {
  handlers.set(key('PUT', `/budgets/${b.id}`), (config) => {
    const body = JSON.parse(config.data || '{}')
    const limit = body.monthlyLimit ?? b.monthlyLimit
    return { ...b, monthlyLimit: limit, remaining: Math.round((limit - b.spent) * 100) / 100,
      percent: limit > 0 ? Math.round((b.spent / limit) * 100) : 0, overBudget: b.spent > limit }
  })
  handlers.set(key('DELETE', `/budgets/${b.id}`), () => ({}))
}

// Settings (payday cycle)
handlers.set(key('GET', '/budget/settings'), () => mockBudgetSettings)
handlers.set(key('PUT', '/budget/settings'), (config) => {
  const body = JSON.parse(config.data || '{}')
  return {
    ...mockBudgetSettings,
    cycleStartDay: body.cycleStartDay ?? mockBudgetSettings.cycleStartDay,
    logoFetchEnabled: body.logoFetchEnabled ?? mockBudgetSettings.logoFetchEnabled,
  }
})

// Cashflow & allocation (period comes from the query string)
handlers.set(key('GET', '/cashflow'), (config) =>
  mockCashflow(((config.params?.period as CashflowPeriod) ?? 'CYCLE')))
handlers.set(key('GET', '/cashflow/flow'), (config) =>
  mockFlow(((config.params?.period as CashflowPeriod) ?? 'CYCLE')))
handlers.set(key('GET', '/allocation'), (config) =>
  mockAllocation(((config.params?.period as CashflowPeriod) ?? 'CYCLE')))

// Spending breakdown & per-category drill (one handler per known category id)
handlers.set(key('GET', '/spending/by-category'), (config) =>
  mockSpendingByCategory(((config.params?.period as CashflowPeriod) ?? 'CYCLE')))
for (const c of mockCategories) {
  handlers.set(key('GET', `/spending/category/${c.id}`), (config) =>
    mockCategoryDetail(c.id, ((config.params?.period as CashflowPeriod) ?? 'CYCLE')))
}

// Recurring series
handlers.set(key('GET', '/recurring'), () => mockRecurring)
handlers.set(key('GET', '/recurring/calendar'), (config) =>
  mockCalendar(Number(config.params?.horizonDays ?? 60)))
handlers.set(key('POST', '/recurring'), (config) => {
  const body = JSON.parse(config.data || '{}')
  const cat = mockCategories.find((c) => c.id === body.categoryId)
  return {
    id: Date.now(), label: body.label ?? 'Récurrent', counterparty: body.counterparty ?? null,
    expectedAmount: body.expectedAmount ?? 0, cadence: body.cadence ?? 'MONTHLY', status: 'CONFIRMED',
    nextDueDate: body.nextDueDate ?? null, lastSeenDate: null, categoryId: body.categoryId ?? null,
    categoryName: cat?.name ?? null, categoryColor: cat?.color ?? null, categoryIcon: null,
  }
})
handlers.set(key('GET', '/recurring/activity'), () => mockActivity)
for (const s of mockRecurring) {
  handlers.set(key('PUT', `/recurring/${s.id}`), (config) => ({ ...s, ...JSON.parse(config.data || '{}') }))
  handlers.set(key('POST', `/recurring/${s.id}/confirm`), () => ({ ...s, status: 'CONFIRMED' }))
  handlers.set(key('POST', `/recurring/${s.id}/ignore`), () => ({ ...s, status: 'IGNORED' }))
  handlers.set(key('DELETE', `/recurring/${s.id}`), () => ({}))
  // Context-aware undo, mirroring the backend: acknowledge a price step (keep the new amount,
  // clear the alert) or reject a silent auto-confirm (send the series back to IGNORED).
  handlers.set(key('POST', `/recurring/${s.id}/undo`), () =>
    s.priceChangedAt != null
      ? { ...s, previousAmount: null, priceChangedAt: null }
      : { ...s, status: 'IGNORED', autoConfirmed: false })
}
handlers.set(key('POST', '/recurring/detect'), () => ({ detected: 2 }))

function generateMockMonths(goal: GoalProgress) {
  const start = new Date('2025-01-01')
  const end = new Date(goal.deadline)
  const months: { yearMonth: string; objective: number; actual: number | null; manualActual: number | null; override: number | null; effective: number | null }[] = []
  const current = new Date(start)
  const now = new Date()
  while (current <= end) {
    const ym = `${current.getFullYear()}-${String(current.getMonth() + 1).padStart(2, '0')}`
    const isPast = current <= now
    const actual = isPast ? Math.round((goal.monthlyNeeded * (0.7 + Math.random() * 0.6)) * 100) / 100 : null
    months.push({
      yearMonth: ym,
      objective: goal.monthlyNeeded,
      actual,
      manualActual: null,
      override: null,
      effective: actual,
    })
    current.setMonth(current.getMonth() + 1)
  }
  return months
}

export function createDemoAdapter() {
  return (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    const k = key(config.method || 'GET', config.url || '')
    const handler = handlers.get(k)

    return new Promise((resolve) => {
      setTimeout(() => {
        resolve({
          data: handler ? handler(config) : {},
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
        } as AxiosResponse)
      }, randomDelay())
    })
  }
}
