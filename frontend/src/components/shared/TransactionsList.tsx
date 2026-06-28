import { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import type { Category, Transaction } from '@/types/api'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { MerchantAvatar } from '@/components/shared/MerchantAvatar'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Separator } from '@/components/ui/separator'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { Trash2, Pencil } from 'lucide-react'
import { cn } from '@/lib/utils'

function useIsMobile() {
  const [isMobile, setIsMobile] = useState(() =>
    typeof window !== 'undefined' ? window.innerWidth < 768 : false
  )
  useEffect(() => {
    function onResize() {
      setIsMobile(window.innerWidth < 768)
    }
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])
  return isMobile
}

interface TransactionsListProps {
  transactions: Transaction[]
  onDelete?: (txId: number) => void
  onEdit?: (tx: Transaction) => void
  /**
   * Optional opt-in logo URL builder (see `useMerchantLogoUrl`). Passed by budget callers
   * that have enabled logos; omitted everywhere else so this shared list stays monogram-only
   * and never triggers a budget-settings fetch outside the budget module.
   */
  logoUrlFor?: (brandId: number | null | undefined) => string | null
  /**
   * Category list for the inline category picker (synced transactions only).
   * When provided together with `onCategorize`, a clickable category chip appears on
   * every non-manual transaction row.
   */
  categories?: Category[]
  /**
   * Called when the user confirms a category change on a synced transaction.
   * The parent is responsible for calling the categorize mutation and invalidating
   * any account-level query caches.
   */
  onCategorize?: (txId: number, categoryId: number) => void
}

export function TransactionsList({
  transactions,
  onDelete,
  onEdit,
  logoUrlFor,
  categories,
  onCategorize,
}: TransactionsListProps) {
  const { t } = useTranslation()
  const [search, setSearch] = useState('')
  const [categorizingTxId, setCategorizingTxId] = useState<number | null>(null)
  const [pendingCategoryId, setPendingCategoryId] = useState<number | ''>('')
  const isMobile = useIsMobile()

  const filtered = search
    ? transactions.filter(tr => {
        const q = search.toLowerCase()
        return (
          tr.description.toLowerCase().includes(q) ||
          (tr.merchantLabel ?? '').toLowerCase().includes(q)
        )
      })
    : transactions

  // Group by date
  const grouped = filtered.reduce<Record<string, Transaction[]>>((acc, tr) => {
    const date = tr.date
    if (!acc[date]) acc[date] = []
    acc[date].push(tr)
    return acc
  }, {})

  const sortedDates = Object.keys(grouped).sort((a, b) => b.localeCompare(a))

  if (transactions.length === 0) return null

  function openPicker(tx: Transaction) {
    // Pre-select the current category when we can match it by name in the list.
    const match = categories?.find(c => c.name === tx.category)
    setPendingCategoryId(match ? match.id : '')
    setCategorizingTxId(tx.id)
  }

  function closePicker() {
    setCategorizingTxId(null)
    setPendingCategoryId('')
  }

  function confirmCategory() {
    if (categorizingTxId == null || pendingCategoryId === '') return
    onCategorize!(categorizingTxId, Number(pendingCategoryId))
    closePicker()
  }

  const pickerContent = (
    <div className="space-y-4">
      <select
        value={pendingCategoryId}
        onChange={(e) =>
          setPendingCategoryId(e.target.value === '' ? '' : Number(e.target.value))
        }
        className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus:border-ring"
      >
        <option value="">{t('budget.categorize.selectCategory')}</option>
        {(categories ?? [])
          .filter(c => !c.archived)
          .map(c => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
      </select>
      <div className="flex justify-end gap-2">
        <Button variant="outline" size="sm" onClick={closePicker}>
          {t('common.cancel')}
        </Button>
        <Button size="sm" disabled={pendingCategoryId === ''} onClick={confirmCategory}>
          {t('common.confirm')}
        </Button>
      </div>
    </div>
  )

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t('accounts.transactions')}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-0">
          <Input
            placeholder={t('common.search')}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="mb-4"
          />
          {sortedDates.map((date, dateIdx) => (
            <div key={date}>
              {dateIdx > 0 && <Separator className="my-3" />}
              <p className="mb-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">
                {new Date(date).toLocaleDateString('fr-FR', {
                  weekday: 'short',
                  day: 'numeric',
                  month: 'short',
                })}
              </p>
              <div className="space-y-0.5">
                {grouped[date].map((tr, rowIdx) => {
                  // Synced transactions can have their category changed inline; manual
                  // transactions go through the full edit modal (onEdit / onDelete).
                  const canCategorize = !tr.isManual && !!onCategorize && !!categories

                  return (
                    <div
                      key={tr.id}
                      className={cn(
                        'flex items-center justify-between rounded-xl px-4 py-3 transition-colors',
                        'hover:bg-muted/60',
                        rowIdx % 2 === 0 ? 'bg-muted/20' : 'bg-transparent',
                      )}
                    >
                      <div className="min-w-0 flex-1 flex items-center gap-3">
                        <MerchantAvatar
                          label={tr.merchantLabel || tr.description}
                          logoUrl={logoUrlFor?.(tr.merchantBrandId)}
                          size="sm"
                        />
                        <div className="min-w-0">
                          <div className="flex items-center gap-2">
                            <p className="truncate text-sm font-medium">
                              {tr.merchantLabel || tr.description}
                            </p>
                            {tr.isManual && (
                              <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground shrink-0">
                                Manuel
                              </span>
                            )}
                          </div>
                          {/* Category chip: clickable for synced transactions, plain text otherwise */}
                          {canCategorize ? (
                            <button
                              type="button"
                              onClick={() => openPicker(tr)}
                              title={t('accounts.changeCategory')}
                              className="mt-0.5 rounded px-1.5 py-0.5 text-xs text-muted-foreground hover:bg-muted hover:text-foreground transition-colors cursor-pointer"
                            >
                              {tr.category ?? t('accounts.uncategorized')}
                            </button>
                          ) : tr.category != null ? (
                            <span className="mt-0.5 block text-xs text-muted-foreground px-1.5 py-0.5">
                              {tr.category}
                            </span>
                          ) : null}
                        </div>
                      </div>
                      <div className="flex items-center gap-2 ml-4">
                        <CurrencyDisplay
                          value={tr.amount}
                          currency={tr.nativeCurrency}
                          className={cn(
                            'text-base font-semibold tabular-nums',
                            tr.amount >= 0 ? 'text-emerald-500' : 'text-foreground',
                          )}
                        />
                        {tr.isManual && onEdit && (
                          <Button
                            variant="ghost"
                            size="icon"
                            className="h-7 w-7 text-muted-foreground hover:text-foreground"
                            onClick={() => onEdit(tr)}
                          >
                            <Pencil size={14} />
                          </Button>
                        )}
                        {onDelete && tr.isManual && (
                          <Button
                            variant="ghost"
                            size="icon"
                            className="h-7 w-7 text-muted-foreground hover:text-destructive"
                            onClick={() => onDelete(tr.id)}
                          >
                            <Trash2 size={14} />
                          </Button>
                        )}
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>
          ))}
        </CardContent>
      </Card>

      {/* Category picker — Sheet (bottom) on mobile, Dialog on desktop */}
      {isMobile ? (
        <Sheet
          open={categorizingTxId != null}
          onOpenChange={(open) => {
            if (!open) closePicker()
          }}
        >
          <SheetContent
            side="bottom"
            className="px-4 pb-6 pt-4 max-h-[90dvh] overflow-y-auto"
          >
            <SheetHeader className="mb-4 p-0">
              <SheetTitle>{t('accounts.changeCategory')}</SheetTitle>
            </SheetHeader>
            {pickerContent}
          </SheetContent>
        </Sheet>
      ) : (
        <Dialog
          open={categorizingTxId != null}
          onOpenChange={(open) => {
            if (!open) closePicker()
          }}
        >
          <DialogContent className="sm:max-w-sm">
            <DialogHeader>
              <DialogTitle>{t('accounts.changeCategory')}</DialogTitle>
            </DialogHeader>
            {pickerContent}
          </DialogContent>
        </Dialog>
      )}
    </>
  )
}
