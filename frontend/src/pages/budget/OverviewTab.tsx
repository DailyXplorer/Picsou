import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { ArrowRight, CalendarClock, Inbox, PiggyBank, TrendingUp, Wallet } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/card'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import {
  useAllocation,
  useBudgets,
  useCashflow,
  useRecurringCalendar,
  useUncategorized,
} from '@/features/budget/hooks'
import { ColorDot } from './budget-utils'

function RecapCard({ icon: Icon, label, children, onClick }: {
  icon: LucideIcon
  label: string
  children: React.ReactNode
  onClick: () => void
}) {
  return (
    <button type="button" onClick={onClick} className="group text-left">
      <Card className="h-full transition-colors hover:bg-muted/50">
        <CardContent className="pt-6">
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Icon className="size-4" />
            <span>{label}</span>
            <ArrowRight className="ml-auto size-4 opacity-0 transition-opacity group-hover:opacity-100" />
          </div>
          <div className="mt-2">{children}</div>
        </CardContent>
      </Card>
    </button>
  )
}

export function OverviewTab() {
  const { t } = useTranslation()
  // Recap cards deep-link into the nested budget routes (relative to `/budget`).
  const navigate = useNavigate()
  const { data: cashflow } = useCashflow('CYCLE')
  const { data: budgets } = useBudgets()
  const { data: upcoming } = useRecurringCalendar(30)
  const { data: allocation } = useAllocation('CYCLE')
  const { data: uncategorized } = useUncategorized()

  const totalSpent = (budgets ?? []).reduce((s, b) => s + b.spent, 0)
  const totalLimit = (budgets ?? []).reduce((s, b) => s + b.monthlyLimit, 0)
  const overCount = (budgets ?? []).filter((b) => b.overBudget).length
  const upcomingTotal = (upcoming ?? []).reduce((s, o) => s + o.expectedAmount, 0)
  const topEnvelopes = [...(budgets ?? [])].sort((a, b) => b.percent - a.percent).slice(0, 4)

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <RecapCard icon={TrendingUp} label={t('budget.overview.netThisCycle')}
          onClick={() => navigate('spending')}>
          <p className={`text-2xl font-bold ${
            (cashflow?.net ?? 0) >= 0
              ? 'text-emerald-600 dark:text-emerald-400' : 'text-destructive'}`}>
            <CurrencyDisplay value={cashflow?.net ?? 0} showSign />
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {t('budget.overview.incomeVsExpense', {
              income: cashflow ? Math.round(cashflow.income) : 0,
              expense: cashflow ? Math.round(cashflow.expense) : 0,
            })}
          </p>
        </RecapCard>

        <RecapCard icon={Wallet} label={t('budget.overview.envelopes')}
          onClick={() => navigate('envelopes')}>
          <p className="text-2xl font-bold">
            <CurrencyDisplay value={totalSpent} /> <span className="text-sm text-muted-foreground">/ <CurrencyDisplay value={totalLimit} /></span>
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {overCount > 0
              ? t('budget.overview.overBudget', { count: overCount })
              : t('budget.overview.onTrack')}
          </p>
        </RecapCard>

        <RecapCard icon={CalendarClock} label={t('budget.overview.upcoming30')}
          onClick={() => navigate('subscriptions')}>
          <p className="text-2xl font-bold">
            <CurrencyDisplay value={upcomingTotal} showSign />
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {t('budget.overview.upcomingCount', { count: upcoming?.length ?? 0 })}
          </p>
        </RecapCard>

        <RecapCard icon={PiggyBank} label={t('budget.overview.investable')}
          onClick={() => navigate('envelopes')}>
          <p className="text-2xl font-bold">
            <CurrencyDisplay value={allocation?.totalStock ?? 0} />
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {t('budget.overview.contributedThisCycle', {
              amount: allocation ? Math.round(allocation.totalContributions) : 0,
            })}
          </p>
        </RecapCard>
      </div>

      {/* To-categorize nudge */}
      {(uncategorized?.length ?? 0) > 0 && (
        <button type="button" onClick={() => navigate('review')} className="block w-full text-left">
          <Card className="border-amber-500/40 bg-amber-500/5 transition-colors hover:bg-amber-500/10">
            <CardContent className="flex items-center gap-3 py-4">
              <Inbox className="size-5 text-amber-600 dark:text-amber-400" />
              <span className="text-sm font-medium">
                {t('budget.overview.toCategorize', { count: uncategorized!.length })}
              </span>
              <ArrowRight className="ml-auto size-4 text-muted-foreground" />
            </CardContent>
          </Card>
        </button>
      )}

      {/* Top envelopes preview */}
      {topEnvelopes.length > 0 && (
        <Card>
          <CardContent className="pt-6">
            <p className="mb-3 text-sm font-medium">{t('budget.overview.topEnvelopes')}</p>
            <div className="space-y-3">
              {topEnvelopes.map((b) => (
                <div key={b.id} className="space-y-1">
                  <div className="flex items-center gap-2 text-sm">
                    <ColorDot color={b.categoryColor} />
                    <span className="truncate">{b.categoryName}</span>
                    <span className="ml-auto tabular-nums text-muted-foreground">{b.percent}%</span>
                  </div>
                  <div className="h-1.5 w-full overflow-hidden rounded-md bg-muted">
                    <div className={`h-full rounded-md ${
                      b.overBudget ? 'bg-destructive' : b.percent >= 80 ? 'bg-amber-500' : 'bg-primary'}`}
                      style={{ width: `${Math.min(b.percent, 100)}%` }} />
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
