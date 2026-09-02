import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { Transaction } from '@/types/api'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Separator } from '@/components/ui/separator'
import { Trash2, Pencil } from 'lucide-react'
import { cn, localeFromLanguage } from '@/lib/utils'

interface TransactionsListProps {
  transactions: Transaction[]
  onDelete?: (txId: number) => void
  onEdit?: (tx: Transaction) => void
}

type TransactionType = NonNullable<Transaction['txType']>

const TRANSACTION_TYPE_LABEL_KEYS = {
  DEPOSIT: 'accounts.deposit',
  WITHDRAWAL: 'accounts.withdrawal',
  BUY: 'accounts.buy',
  SELL: 'accounts.sell',
  DIVIDEND: 'accounts.dividend',
  FEE: 'accounts.fee',
} satisfies Record<TransactionType, string>

export function TransactionsList({ transactions, onDelete, onEdit }: TransactionsListProps) {
  const { t, i18n } = useTranslation()
  const [search, setSearch] = useState('')
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)

  const filtered = search
    ? transactions.filter(tr => {
        const normalizedSearch = search.toLocaleLowerCase(locale)
        const displayedDescription = transactionDescription(tr, t).toLocaleLowerCase(locale)
        return (
          displayedDescription.includes(normalizedSearch) ||
          tr.description.toLocaleLowerCase(locale).includes(normalizedSearch)
        )
      })
    : transactions
  const showYear = new Set(filtered.map(tr => tr.date.slice(0, 4))).size > 1

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
            <p className="mb-2 text-sm font-medium text-muted-foreground">
              {formatTransactionDate(date, locale, showYear)}
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
                  <div className="min-w-0 flex-1 flex items-center gap-2">
                    <p className="truncate text-sm font-medium">{transactionDescription(tr, t)}</p>
                    {tr.isManual && (
                      <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground shrink-0">
                        {t('accounts.manual')}
                      </span>
                    )}
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
                        className="text-muted-foreground hover:text-foreground"
                        onClick={() => onEdit(tr)}
                      >
                        <Pencil className="size-4" />
                      </Button>
                    )}
                    {onDelete && tr.isManual && (
                      <Button
                        variant="ghost"
                        size="icon"
                        className="text-muted-foreground hover:text-destructive"
                        onClick={() => onDelete(tr.id)}
                      >
                        <Trash2 className="size-4" />
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

/** Builds a localized fallback only for manual instrument rows that have no display name. */
function transactionDescription(transaction: Transaction, translate: (key: string) => string): string {
  if (!transaction.isManual || !transaction.ticker?.trim() || transaction.name?.trim() || transaction.txType === null) {
    return transaction.description
  }

  return `${translate(TRANSACTION_TYPE_LABEL_KEYS[transaction.txType])} ${transaction.ticker}`
}

function formatTransactionDate(date: string, locale: string, showYear: boolean): string {
  const transactionDate = new Date(`${date}T00:00:00`)
  const label = new Intl.DateTimeFormat(locale, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    ...(showYear ? { year: 'numeric' } : {}),
  }).format(transactionDate)
  return label.charAt(0).toLocaleUpperCase(locale) + label.slice(1)
}
