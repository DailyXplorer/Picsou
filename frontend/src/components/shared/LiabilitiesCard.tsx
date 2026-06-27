import { useTranslation } from 'react-i18next'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Progress } from '@/components/ui/progress'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import type { DashboardData } from '@/types/api'

interface Props {
  liabilities: DashboardData['liabilities']
  totalMonthlyPayment: number | null
}

export function LiabilitiesCard({ liabilities, totalMonthlyPayment }: Props) {
  const { t } = useTranslation()
  const totalDebt = liabilities.reduce((sum, l) => sum + l.balanceEur, 0)

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('dashboard.liabilities')}</CardTitle>
        <CardDescription className="flex flex-wrap gap-x-4 gap-y-1">
          <span>
            {t('dashboard.totalLiabilities')}:{' '}
            <span className="font-medium text-destructive">
              <CurrencyDisplay value={totalDebt} />
            </span>
          </span>
          {totalMonthlyPayment !== null && (
            <span>
              {t('dashboard.monthlyPayment')}:{' '}
              <span className="font-medium text-foreground">
                <CurrencyDisplay value={totalMonthlyPayment} />/mo
              </span>
            </span>
          )}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {liabilities.map((loan) => (
          <div
            key={loan.accountId}
            className="flex flex-col gap-1.5 rounded-2xl bg-muted/40 px-4 py-3"
          >
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-2 min-w-0">
                <span
                  className="size-2 shrink-0 rounded-full"
                  style={{ background: loan.color }}
                />
                <span className="truncate text-sm font-medium">{loan.name}</span>
              </div>
              <span className="shrink-0 text-sm font-semibold text-destructive">
                <CurrencyDisplay value={loan.balanceEur} />
              </span>
            </div>

            {loan.percentPaid !== null ? (
              <div className="flex items-center gap-2">
                <Progress
                  value={loan.percentPaid}
                  className="h-1.5 flex-1 [&_[data-slot=progress-indicator]]:bg-primary/60"
                />
                <span className="shrink-0 text-xs text-muted-foreground">
                  {Math.round(loan.percentPaid)}%
                  {loan.monthlyPayment !== null && (
                    <> · <CurrencyDisplay value={loan.monthlyPayment} />/mo</>
                  )}
                </span>
              </div>
            ) : (
              <div className="flex items-center gap-1.5">
                <span
                  aria-label="Parameters not configured"
                  className="flex size-3.5 shrink-0 items-center justify-center rounded-full border border-muted-foreground/30 text-[9px] text-muted-foreground/40"
                >
                  i
                </span>
                <span className="text-xs italic text-muted-foreground/50">
                  {t('dashboard.loanParamsUnconfigured')}
                </span>
              </div>
            )}
          </div>
        ))}
      </CardContent>
    </Card>
  )
}
