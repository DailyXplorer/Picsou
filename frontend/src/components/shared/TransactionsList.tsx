import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { Category, Transaction } from '@/types/api'
import { TransactionRow } from '@/components/shared/TransactionRow'
import { TransactionDetailSheet } from '@/components/shared/TransactionDetailSheet'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Separator } from '@/components/ui/separator'

interface TransactionsListProps {
  transactions: Transaction[]
  onDelete?: (txId: number) => void
  onEdit?: (tx: Transaction) => void
  logoUrlFor?: (brandId: number | null | undefined) => string | null
  categories?: Category[]
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
  const [selectedTx, setSelectedTx] = useState<Transaction | null>(null)

  const filtered = search
    ? transactions.filter(tr => {
        const q = search.toLowerCase()
        return (
          tr.description.toLowerCase().includes(q) ||
          (tr.merchantLabel ?? '').toLowerCase().includes(q)
        )
      })
    : transactions

  const grouped = filtered.reduce<Record<string, Transaction[]>>((acc, tr) => {
    if (!acc[tr.date]) acc[tr.date] = []
    acc[tr.date].push(tr)
    return acc
  }, {})

  const sortedDates = Object.keys(grouped).sort((a, b) => b.localeCompare(a))

  if (transactions.length === 0) return null

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
                {grouped[date].map((tr, rowIdx) => (
                  <TransactionRow
                    key={tr.id}
                    transaction={tr}
                    logoUrlFor={logoUrlFor}
                    index={rowIdx}
                    onClick={setSelectedTx}
                  />
                ))}
              </div>
            </div>
          ))}
        </CardContent>
      </Card>

      <TransactionDetailSheet
        transaction={selectedTx}
        open={selectedTx != null}
        onClose={() => setSelectedTx(null)}
        logoUrlFor={logoUrlFor}
        categories={categories}
        onCategorize={onCategorize}
        onEdit={onEdit}
        onDelete={onDelete}
      />
    </>
  )
}
