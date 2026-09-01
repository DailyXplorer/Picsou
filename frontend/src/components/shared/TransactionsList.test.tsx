import '@testing-library/jest-dom'
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { TransactionsList } from './TransactionsList'
import type { Transaction } from '@/types/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'fr', resolvedLanguage: 'fr' },
  }),
}))

function transaction(overrides: Partial<Transaction>): Transaction {
  return {
    id: 1,
    date: '2026-03-04',
    description: 'Transaction',
    amount: 10,
    type: null,
    category: null,
    nativeCurrency: 'EUR',
    isManual: false,
    txType: null,
    ticker: null,
    name: null,
    quantity: null,
    pricePerUnit: null,
    fees: null,
    ...overrides,
  }
}

describe('TransactionsList', () => {
  it('keeps single-year date headings compact', () => {
    render(<TransactionsList transactions={[transaction({ description: 'Single year' })]} />)

    expect(screen.getByText('Single year')).toBeInTheDocument()
    expect(screen.queryByText(/2026/)).not.toBeInTheDocument()
  })

  it('includes the year in every heading when the list spans multiple years', () => {
    render(
      <TransactionsList
        transactions={[
          transaction({ description: 'Recent transaction' }),
          transaction({ id: 2, date: '2025-03-04', description: 'Older transaction' }),
        ]}
      />,
    )

    expect(screen.getByText(/2026/)).toBeInTheDocument()
    expect(screen.getByText(/2025/)).toBeInTheDocument()
  })
})
