import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Inbox, Sparkles } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Switch } from '@/components/ui/switch'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { MerchantAvatar } from '@/components/shared/MerchantAvatar'
import { Skeleton } from '@/components/ui/skeleton'
import {
  useBudgetSettings,
  useCategories,
  useCategorize,
  useCategorizeAi,
  useMerchantLogoUrl,
  useRecategorize,
  useUncategorized,
} from '@/features/budget/hooks'
import { formatDate, getLocale } from '@/lib/utils'
import type { Category, UncategorizedTransaction } from '@/types/api'

function InboxRow({ tx, categories }: {
  tx: UncategorizedTransaction
  categories: Category[]
}) {
  const { t } = useTranslation()
  const categorize = useCategorize()
  const logoUrlFor = useMerchantLogoUrl()
  // Preselect the AI suggestion (if any) so accepting it is a single click on "Assign".
  const [categoryId, setCategoryId] = useState<number | ''>(tx.aiSuggestedCategoryId ?? '')
  const [createRule, setCreateRule] = useState(true)

  const suggested =
    tx.aiSuggestedCategoryId != null
      ? categories.find((c) => c.id === tx.aiSuggestedCategoryId) ?? null
      : null

  function assign() {
    if (categoryId === '') return
    categorize.mutate({ id: tx.id, data: { categoryId: Number(categoryId), createRule } })
  }

  return (
    <Card>
      <CardContent className="py-4">
        <div className="flex items-start justify-between gap-3">
          <div className="flex min-w-0 items-center gap-3">
            <MerchantAvatar
              label={tx.merchantLabel || tx.counterparty || tx.description}
              logoUrl={logoUrlFor(tx.merchantBrandId)}
            />
            <div className="min-w-0">
              <p className="truncate font-medium">
                {tx.merchantLabel || tx.counterparty || tx.description}
              </p>
              <p className="text-xs text-muted-foreground">{formatDate(tx.date, getLocale())}</p>
            </div>
          </div>
          <span className={`shrink-0 font-semibold tabular-nums ${
            tx.amount >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-foreground'}`}>
            <CurrencyDisplay value={tx.amount} showSign />
          </span>
        </div>
        {suggested && (
          <div className="mt-2">
            <Badge variant="outline" className="gap-1 border-primary/40 text-primary">
              <Sparkles className="size-3" />
              {t('budget.categorize.aiSuggested', {
                name: suggested.name,
                confidence: tx.aiConfidence ?? 0,
              })}
            </Badge>
          </div>
        )}
        <div className="mt-3 flex flex-col gap-3 sm:flex-row sm:items-center">
          <select
            value={categoryId}
            onChange={(e) => setCategoryId(e.target.value === '' ? '' : Number(e.target.value))}
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus:border-ring sm:max-w-56"
          >
            <option value="">{t('budget.categorize.selectCategory')}</option>
            {categories.filter((c) => !c.archived).map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
          <div className="flex items-center gap-2">
            <Switch id={`rule-${tx.id}`} checked={createRule} onCheckedChange={setCreateRule} />
            <Label htmlFor={`rule-${tx.id}`} className="text-xs text-muted-foreground">
              {t('budget.categorize.learnRule')}
            </Label>
          </div>
          <Button size="sm" className="sm:ml-auto" onClick={assign}
            disabled={categoryId === '' || categorize.isPending}>
            {t('budget.categorize.assign')}
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

export function CategorizeTab() {
  const { t } = useTranslation()
  const { data: txs, isLoading, isError, refetch } = useUncategorized()
  const { data: categories } = useCategories()
  const { data: settings } = useBudgetSettings()
  const recategorize = useRecategorize()
  const categorizeAi = useCategorizeAi()

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">{t('budget.categorize.subtitle')}</p>
        <div className="flex flex-wrap items-center gap-2">
          {settings?.aiCategorizationEnabled && (
            <Button size="sm" onClick={() => categorizeAi.mutate()}
              disabled={categorizeAi.isPending}>
              <Sparkles className="size-4" /> {t('budget.categorize.categorizeAi')}
            </Button>
          )}
          <Button size="sm" variant="outline" onClick={() => recategorize.mutate()}
            disabled={recategorize.isPending}>
            <Sparkles className="size-4" /> {t('budget.categorize.recategorize')}
          </Button>
        </div>
      </div>

      {isLoading && (
        <div className="space-y-3">
          <Skeleton className="h-28 w-full rounded-xl" />
          <Skeleton className="h-28 w-full rounded-xl" />
        </div>
      )}

      {!isLoading && isError && (
        <ErrorState message={t('budget.categorize.error')} onRetry={() => void refetch()} />
      )}

      {!isLoading && !isError && (txs?.length ?? 0) === 0 && (
        <EmptyState icon={<Inbox className="size-10" />}
          title={t('budget.categorize.empty')}
          description={t('budget.categorize.emptyHint')} />
      )}

      {!isLoading && !isError && (txs?.length ?? 0) > 0 && (
        <div className="space-y-3">
          {txs!.map((tx) => (
            <InboxRow key={tx.id} tx={tx} categories={categories ?? []} />
          ))}
        </div>
      )}
    </div>
  )
}
