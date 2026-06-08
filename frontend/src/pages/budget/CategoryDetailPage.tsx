import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { ErrorState } from '@/components/shared/ErrorState'
import { TransactionsList } from '@/components/shared/TransactionsList'
import { useCategoryDetail } from '@/features/budget/hooks'
import type { CashflowPeriod } from '@/types/api'
import { FALLBACK_COLOR } from './budget-meta'
import { PeriodToggle } from './budget-utils'

/**
 * `/budget/spending/:categoryId` — one category's transactions over the period. Keyed by
 * id (not slug) because user-created categories have no slug. Sub-categories are M4; for
 * now this is the category header + a read-only transaction list (MerchantAvatar rows).
 */
export function CategoryDetailPage() {
  const { t } = useTranslation()
  const { categoryId } = useParams()
  const [period, setPeriod] = useState<CashflowPeriod>('CYCLE')
  const id = Number(categoryId)
  const { data, isLoading, isError, refetch } = useCategoryDetail(id, period)

  const backLink = (
    <Link
      to="/budget/spending"
      className="inline-flex items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
    >
      <ArrowLeft className="size-4" />
      {t('budget.detail.back')}
    </Link>
  )

  if (!Number.isFinite(id)) {
    return (
      <div className="space-y-4">
        {backLink}
        <ErrorState message={t('budget.detail.notFound')} />
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        {backLink}
        <PeriodToggle value={period} onChange={setPeriod} />
      </div>

      {isError && (
        <ErrorState message={t('budget.detail.error')} onRetry={() => refetch()} />
      )}

      {isLoading && !isError && (
        <>
          <Skeleton className="h-20 w-full rounded-xl" />
          <Skeleton className="h-64 w-full rounded-xl" />
        </>
      )}

      {!isLoading && !isError && data && (
        <>
          <Card>
            <CardContent className="flex items-center justify-between gap-4 pt-6">
              <div className="flex min-w-0 items-center gap-3">
                <span
                  className="inline-block size-3 shrink-0 rounded-full"
                  style={{ backgroundColor: data.color || FALLBACK_COLOR }}
                />
                <div className="min-w-0">
                  <p className="truncate text-lg font-semibold">{data.name}</p>
                  <p className="text-sm text-muted-foreground">
                    {t('budget.flow.transactionsCount', { count: data.count })}
                  </p>
                </div>
              </div>
              <div className="text-right">
                <p className="text-xs text-muted-foreground">{t('budget.detail.total')}</p>
                <CurrencyDisplay
                  value={data.total}
                  className="text-xl font-bold tabular-nums text-foreground"
                />
              </div>
            </CardContent>
          </Card>

          {data.count === 0 ? (
            <p className="py-8 text-center text-sm text-muted-foreground">
              {t('budget.detail.empty')}
            </p>
          ) : (
            <TransactionsList transactions={data.transactions} />
          )}
        </>
      )}
    </div>
  )
}
