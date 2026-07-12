import type { DashboardData } from '@/types/api'
import { mockAccounts } from './accounts'
import { mockGoals } from './goals'

function generateNetWorthHistory(): { date: string; total: number; invested: number; pnl: number }[] {
  const baseValues = [32000, 33500, 34200, 35100, 34800, 36400, 37200, 38100, 39500, 40200, 41000, 41862]
  const investedValues = [30000, 31500, 32000, 32800, 32800, 34000, 34500, 35000, 36000, 36500, 37000, 37500]
  const now = new Date()
  const months: { date: string; total: number; invested: number; pnl: number }[] = []

  for (let i = 11; i >= 0; i--) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
    const total = baseValues[11 - i]
    const invested = investedValues[11 - i]
    months.push({
      date: d.toISOString().split('T')[0],
      total,
      invested,
      pnl: total - invested,
    })
  }

  return months
}

export const mockDashboard: DashboardData = {
  totalNetWorth: 41862.35,
  totalLiabilities: 8500,
  netWorthHistory: generateNetWorthHistory(),
  distribution: mockAccounts.map(a => ({
    accountId: a.id,
    name: a.name,
    color: a.color,
    balanceEur: a.currentBalanceEur,
    percentage: Math.round((a.currentBalanceEur / 41862.35) * 1000) / 10,
    accountType: a.type,
    hasHoldings: ['PEA', 'COMPTE_TITRES', 'CRYPTO'].includes(a.type),
  })),
  // One loan so demo mode exercises the liabilities card (issue #18).
  liabilities: [
    {
      accountId: 100,
      name: 'Car loan',
      color: '#ef4444',
      balanceEur: 8500,
      percentage: 100,
      accountType: 'LOAN',
      hasHoldings: false,
    },
  ],
  goalSummaries: mockGoals,
}
