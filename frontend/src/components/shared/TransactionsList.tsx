import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { Transaction } from '@/types/api'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { MerchantAvatar } from '@/components/shared/MerchantAvatar'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Separator } from '@/components/ui/separator'
import { Trash2, Pencil } from 'lucide-react'
import { cn } from '@/lib/utils'

interface TransactionsListProps {
  transactions: Transaction[]
  onDelete?: (txId: number) => void
  onEdit?: (tx: Transaction) => void
}

export function TransactionsList({ transactions, onDelete, onEdit }: TransactionsListProps) {
  const { t } = useTranslation()
  const [search, setSearch] = useState('')

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

  return (
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
              {new Date(date).toLocaleDateString('fr-FR', { weekday: 'short', day: 'numeric', month: 'short' })}
            </p>
            <div className="space-y-0.5">
              {grouped[date].map((tr, rowIdx) => (
                <div
                  key={tr.id}
                  className={cn(
                    'flex items-center justify-between rounded-xl px-4 py-3 transition-colors',
                    'hover:bg-muted/60',
                    rowIdx % 2 === 0 ? 'bg-muted/20' : 'bg-transparent',
                  )}
                >
                  <div className="min-w-0 flex-1 flex items-center gap-3">
                    <MerchantAvatar label={tr.merchantLabel || tr.description} size="sm" />
                    <div className="min-w-0 flex items-center gap-2">
                      <p className="truncate text-sm font-medium">{tr.merchantLabel || tr.description}</p>
                      {tr.isManual && (
                        <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground shrink-0">
                          Manuel
                        </span>
                      )}
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
              ))}
            </div>
          </div>
        ))}
      </CardContent>
    </Card>
  )
}
