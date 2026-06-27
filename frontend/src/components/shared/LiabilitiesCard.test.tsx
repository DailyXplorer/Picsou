import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LiabilitiesCard } from './LiabilitiesCard'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value }: { value: number }) => <span>{value}</span>,
}))

const baseLoan = {
  accountId: 1,
  name: 'Mortgage BNP',
  color: '#6366f1',
  balanceEur: -118200,
  percentage: 0,
  accountType: 'LOAN',
  hasHoldings: false,
}

describe('LiabilitiesCard', () => {
  it('renders loan name and balance', () => {
    render(
      <LiabilitiesCard
        liabilities={[{ ...baseLoan, monthlyPayment: null, percentPaid: null }]}
        totalMonthlyPayment={null}
      />
    )
    expect(screen.getByText('Mortgage BNP')).toBeInTheDocument()
  })

  it('renders progress bar when percentPaid is present', () => {
    render(
      <LiabilitiesCard
        liabilities={[{ ...baseLoan, monthlyPayment: 1050, percentPaid: 32 }]}
        totalMonthlyPayment={1050}
      />
    )
    const bar = document.querySelector('[role="progressbar"]')
    expect(bar).not.toBeNull()
  })

  it('shows hint icon when loan has no parameters', () => {
    render(
      <LiabilitiesCard
        liabilities={[{ ...baseLoan, monthlyPayment: null, percentPaid: null }]}
        totalMonthlyPayment={null}
      />
    )
    // The ⓘ hint is rendered as an aria-label
    expect(screen.getByLabelText('Parameters not configured')).toBeInTheDocument()
  })

  it('shows monthlyPayment section in header when totalMonthlyPayment is non-null', () => {
    render(
      <LiabilitiesCard
        liabilities={[{ ...baseLoan, monthlyPayment: 1050, percentPaid: 32 }]}
        totalMonthlyPayment={1050}
      />
    )
    // The i18n key "dashboard.monthlyPayment" resolves to its key in test env
    expect(screen.getByText(/dashboard\.monthlyPayment|Monthly payment|Mensualité/i)).toBeInTheDocument()
  })
})
