import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NumericInput } from '@/components/shared/NumericInput'
import { DateInput } from '@/components/shared/DateInput'
import { Label } from '@/components/ui/label'
import { parseAmount } from '@/lib/utils'
import { Loader2 } from 'lucide-react'
import type { AccountType, TransactionRequest } from '@/types/api'
import { accountsApi } from '@/features/accounts/api'
import { QUERY_STALE_TIMES } from '@/lib/constants'

const INVESTMENT_TYPES: AccountType[] = ['PEA', 'COMPTE_TITRES', 'CRYPTO']

type InitialValues = TransactionRequest & { id?: number }

const today = () => new Date().toISOString().split('T')[0]

interface AddTransactionModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  accountId: number
  accountType: AccountType
  onSubmit: (data: TransactionRequest) => Promise<void>
  isLoading?: boolean
  initialValues?: InitialValues
}

export function AddTransactionModal({ open, onOpenChange, initialValues, ...rest }: AddTransactionModalProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{initialValues ? 'Modifier la transaction' : 'Ajouter une transaction'}</DialogTitle>
        </DialogHeader>
        {/* Remount a fresh form each time the dialog opens (key changes per
            edited transaction) so initial state derives straight from
            `initialValues` — no populate/reset effect needed. */}
        {open && (
          <TransactionForm
            key={initialValues?.id ?? 'new'}
            onOpenChange={onOpenChange}
            initialValues={initialValues}
            {...rest}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

interface TransactionFormProps {
  onOpenChange: (open: boolean) => void
  accountId: number
  accountType: AccountType
  onSubmit: (data: TransactionRequest) => Promise<void>
  isLoading?: boolean
  initialValues?: InitialValues
}

function TransactionForm({ onOpenChange, accountId, accountType, onSubmit, isLoading, initialValues }: TransactionFormProps) {
  const isInvestment = INVESTMENT_TYPES.includes(accountType)
  const isInvestmentTx = initialValues?.txType === 'BUY' || initialValues?.txType === 'SELL'

  const { data: holdings } = useQuery({
    queryKey: ['accounts', accountId, 'holdings'],
    queryFn: () => accountsApi.holdings(accountId),
    staleTime: QUERY_STALE_TIMES.accountDetail,
    enabled: isInvestment && !!accountId,
  })

  // Shared state — initialized from initialValues (edit) or sensible defaults (add)
  const [date, setDate] = useState(() => (initialValues?.date ? String(initialValues.date) : today()))
  const [description, setDescription] = useState(() => (!isInvestmentTx ? (initialValues?.description ?? '') : ''))
  const [error, setError] = useState<string | null>(null)

  // Cash fields
  const [txDirection, setTxDirection] = useState<'deposit' | 'withdrawal'>(() =>
    !isInvestmentTx && initialValues?.amount != null && Number(initialValues.amount) < 0 ? 'withdrawal' : 'deposit'
  )
  const [cashAmount, setCashAmount] = useState(() =>
    !isInvestmentTx && initialValues?.amount != null ? String(Math.abs(Number(initialValues.amount))) : ''
  )

  // Investment fields
  const [investType, setInvestType] = useState<'BUY' | 'SELL'>(() => (isInvestmentTx ? (initialValues!.txType as 'BUY' | 'SELL') : 'BUY'))
  const [ticker, setTicker] = useState(() => (isInvestmentTx ? (initialValues?.ticker ?? '') : ''))
  const [name, setName] = useState(() => (isInvestmentTx ? (initialValues?.name ?? '') : ''))
  const [quantity, setQuantity] = useState(() => (isInvestmentTx && initialValues?.quantity != null ? String(initialValues.quantity) : ''))
  const [pricePerUnit, setPricePerUnit] = useState(() => (isInvestmentTx && initialValues?.pricePerUnit != null ? String(initialValues.pricePerUnit) : ''))

  // Auto-fill the name from an existing holding when the ticker matches —
  // done in the change handler (not an effect) so it stays out of render.
  function handleTickerChange(value: string) {
    setTicker(value)
    if (holdings && value) {
      const match = holdings.find(h => h.ticker.toUpperCase() === value.toUpperCase())
      if (match?.name) setName(match.name)
    }
  }

  const total = quantity && pricePerUnit
    ? (parseAmount(quantity) * parseAmount(pricePerUnit)).toFixed(2)
    : '—'

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    let data: TransactionRequest

    if (isInvestment) {
      const qty = parseAmount(quantity)
      const price = parseAmount(pricePerUnit)
      const amount = investType === 'BUY' ? -(qty * price) : (qty * price)
      data = {
        date,
        description: name || (investType === 'BUY' ? `Achat ${ticker}` : `Vente ${ticker}`),
        amount,
        txType: investType,
        ticker: ticker.toUpperCase(),
        name: name.trim() || undefined,
        quantity: qty,
        pricePerUnit: price,
      }
    } else {
      const raw = parseAmount(cashAmount)
      const amount = txDirection === 'deposit' ? Math.abs(raw) : -Math.abs(raw)
      data = {
        date,
        description,
        amount,
        txType: txDirection === 'deposit' ? 'DEPOSIT' : 'WITHDRAWAL',
      }
    }

    try {
      await onSubmit(data)
      onOpenChange(false)
    } catch {
      setError('Une erreur est survenue. Veuillez réessayer.')
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      {/* Date */}
      <div className="space-y-1">
        <Label>Date</Label>
        <DateInput value={date} onChange={setDate} required />
      </div>

      {isInvestment ? (
        <>
          {/* BUY / SELL toggle */}
          <div className="flex gap-2">
            {(['BUY', 'SELL'] as const).map(type => (
              <Button
                key={type}
                type="button"
                variant={investType === type ? 'default' : 'outline'}
                size="sm"
                onClick={() => setInvestType(type)}
              >
                {type === 'BUY' ? 'Achat' : 'Vente'}
              </Button>
            ))}
          </div>
          <div className="space-y-1">
            <Label>Ticker ou ISIN</Label>
            <Input placeholder="IWDA.AS, IE00B4L5Y983, BTC…" value={ticker} onChange={e => handleTickerChange(e.target.value)} required />
          </div>
          <div className="space-y-1">
            <Label>Nom <span className="text-muted-foreground text-xs">(optionnel)</span></Label>
            <Input placeholder="Ex : iShares Core MSCI World" value={name} onChange={e => setName(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label>Quantité</Label>
            <NumericInput value={quantity} onChange={e => setQuantity(e.target.value)} required />
          </div>
          <div className="space-y-1">
            <Label>Prix unitaire (€)</Label>
            <NumericInput value={pricePerUnit} onChange={e => setPricePerUnit(e.target.value)} required />
          </div>
          <p className="text-sm text-muted-foreground">Total : {total} €</p>
        </>
      ) : (
        <>
          {/* DEPOSIT / WITHDRAWAL toggle */}
          <div className="flex gap-2">
            <Button
              type="button"
              variant={txDirection === 'deposit' ? 'default' : 'outline'}
              size="sm"
              onClick={() => setTxDirection('deposit')}
            >
              + Dépôt
            </Button>
            <Button
              type="button"
              variant={txDirection === 'withdrawal' ? 'default' : 'outline'}
              size="sm"
              onClick={() => setTxDirection('withdrawal')}
            >
              − Retrait
            </Button>
          </div>
          <div className="space-y-1">
            <Label>Description</Label>
            <Input value={description} onChange={e => setDescription(e.target.value)} required />
          </div>
          <div className="space-y-1">
            <Label>Montant (€)</Label>
            <NumericInput value={cashAmount} onChange={e => setCashAmount(e.target.value)} required />
          </div>
        </>
      )}

      {error && <p className="text-sm text-destructive">{error}</p>}

      <DialogFooter>
        <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Annuler</Button>
        <Button type="submit" disabled={isLoading}>
          {isLoading && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
          {initialValues ? 'Enregistrer' : 'Ajouter'}
        </Button>
      </DialogFooter>
    </form>
  )
}
