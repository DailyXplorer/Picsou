import '@testing-library/jest-dom'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { CategorizeTab } from './CategorizeTab'

// Mutable state the mocked hooks read, so each test can vary settings/inbox.
const state = vi.hoisted(() => ({
  settings: { aiCategorizationEnabled: false } as { aiCategorizationEnabled: boolean },
  txs: [] as unknown[],
  categories: [] as unknown[],
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

// CurrencyDisplay reads the app store for locale/currency — irrelevant here; render it plainly.
vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value }: { value: number }) => <span>{value}</span>,
}))

vi.mock('@/features/budget/hooks', () => ({
  useUncategorized: () => ({ data: state.txs, isLoading: false, isError: false, refetch: vi.fn() }),
  useCategories: () => ({ data: state.categories }),
  useBudgetSettings: () => ({ data: state.settings }),
  useRecategorize: () => ({ mutate: vi.fn(), isPending: false }),
  useCategorizeAi: () => ({ mutate: vi.fn(), isPending: false }),
  useCategorize: () => ({ mutate: vi.fn(), isPending: false }),
  useMerchantLogoUrl: () => () => null,
}))

const TRANSPORT = {
  id: 4, name: 'Transport', kind: 'EXPENSE', color: null, icon: null,
  isDefault: true, archived: false, sortOrder: 3, parentId: null,
}

function tx(overrides: Record<string, unknown> = {}) {
  return {
    id: 9002, date: '2026-06-20', description: 'SNCF VOYAGEURS', amount: -68, type: null,
    category: null, nativeCurrency: 'EUR', createdAt: '2026-06-20', isManual: false,
    txType: 'WITHDRAWAL', ticker: null, quantity: null, pricePerUnit: null,
    categoryId: null, categoryName: null, counterparty: 'SNCF VOYAGEURS',
    merchantLabel: 'SNCF', merchantBrandId: null,
    aiSuggestedCategoryId: null, aiConfidence: null,
    ...overrides,
  }
}

describe('CategorizeTab — AI suggestions', () => {
  beforeEach(() => {
    state.settings = { aiCategorizationEnabled: false }
    state.txs = []
    state.categories = [TRANSPORT]
  })

  it('preselects the AI-suggested category and shows the suggestion chip', () => {
    state.txs = [tx({ aiSuggestedCategoryId: 4, aiConfidence: 92 })]
    render(<CategorizeTab />)

    // The category <select> is preselected to the suggested category id.
    expect((screen.getByRole('combobox') as HTMLSelectElement).value).toBe('4')
    // The suggestion chip is rendered.
    expect(screen.getByText('budget.categorize.aiSuggested')).toBeInTheDocument()
  })

  it('does not preselect or show a chip when there is no suggestion', () => {
    state.txs = [tx()]
    render(<CategorizeTab />)

    expect((screen.getByRole('combobox') as HTMLSelectElement).value).toBe('')
    expect(screen.queryByText('budget.categorize.aiSuggested')).not.toBeInTheDocument()
  })

  it('shows the "Categorize with AI" button only when AI categorization is enabled', () => {
    state.txs = [tx()]

    const { rerender } = render(<CategorizeTab />)
    expect(screen.queryByText('budget.categorize.categorizeAi')).not.toBeInTheDocument()

    state.settings = { aiCategorizationEnabled: true }
    rerender(<CategorizeTab />)
    expect(screen.getByText('budget.categorize.categorizeAi')).toBeInTheDocument()
  })
})
