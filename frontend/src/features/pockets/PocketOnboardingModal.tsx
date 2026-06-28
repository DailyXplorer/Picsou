import { useState, useRef, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { AlertCircle, Upload } from 'lucide-react'
import { cn } from '@/lib/utils'
import { accountsApi } from '@/features/accounts/api'
import { useCsvNameSuggestions } from './hooks'
import type { UnnamedPocket, CsvNameSuggestion } from '@/types/pockets'
import type { Account } from '@/types/api'

interface PocketOnboardingModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Unnamed pockets returned by the backend, including their transfer history. */
  pockets: UnnamedPocket[]
  /** Full accounts list — needed to read the existing account fields before renaming. */
  accounts: Account[]
}

export function PocketOnboardingModal({
  open,
  onOpenChange,
  pockets,
  accounts,
}: PocketOnboardingModalProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const fileInputRef = useRef<HTMLInputElement>(null)

  // Keyed by pocket accountId
  const [names, setNames] = useState<Record<number, string>>({})
  const [suggestions, setSuggestions] = useState<CsvNameSuggestion[]>([])
  const [isDragging, setIsDragging] = useState(false)
  const [csvError, setCsvError] = useState<string | null>(null)
  const [isSaving, setIsSaving] = useState(false)
  const [wasOpen, setWasOpen] = useState(open)

  const uploadCsvMutation = useCsvNameSuggestions()

  // Reset state each time the dialog (re)opens — adjusting state during render
  // rather than in an effect avoids the cascading-render lint error.
  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) {
      setNames({})
      setSuggestions([])
      setCsvError(null)
    }
  }

  // Pre-fill name fields from non-uncertain CSV suggestions without overwriting
  // anything the user has already typed. Suggestions are matched by accountId.
  function applySuggestions(incoming: CsvNameSuggestion[]) {
    setSuggestions(incoming)
    setNames((prev) => {
      const next = { ...prev }
      for (const pocket of pockets) {
        const sug = incoming.find((s) => s.accountId === pocket.accountId)
        if (sug && !sug.uncertain && !prev[pocket.accountId]) {
          next[pocket.accountId] = sug.suggestedName
        }
      }
      return next
    })
  }

  async function handleFile(file: File) {
    setCsvError(null)
    try {
      const result = await uploadCsvMutation.mutateAsync(file)
      applySuggestions(result)
    } catch {
      setCsvError(t('pockets.csvError'))
    }
  }

  const handleDrop = useCallback(
    (e: React.DragEvent<HTMLDivElement>) => {
      e.preventDefault()
      setIsDragging(false)
      const file = e.dataTransfer.files[0]
      if (file) void handleFile(file)
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [pockets],
  )

  function handleDragOver(e: React.DragEvent<HTMLDivElement>) {
    e.preventDefault()
    setIsDragging(true)
  }

  function handleDragLeave() {
    setIsDragging(false)
  }

  function handleFileInputChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (file) void handleFile(file)
  }

  async function handleConfirm() {
    setIsSaving(true)
    try {
      await Promise.all(
        pockets
          .filter((p) => names[p.accountId]?.trim())
          .map((p) => {
            const account = accounts.find((a) => a.id === p.accountId)
            if (!account) return Promise.resolve()
            return accountsApi.update(p.accountId, {
              name: names[p.accountId].trim(),
              type: account.type,
              provider: account.provider ?? undefined,
              currency: account.currency,
              currentBalance: account.currentBalance,
              isManual: account.isManual,
              color: account.color,
            })
          }),
      )
      // Invalidate both the full accounts list and the unnamed-pockets query so
      // the banner disappears immediately after renaming.
      await queryClient.invalidateQueries({ queryKey: ['accounts'] })
      await queryClient.invalidateQueries({ queryKey: ['pockets', 'unnamed'] })
      onOpenChange(false)
    } finally {
      setIsSaving(false)
    }
  }

  const hasSomeName = pockets.some((p) => names[p.accountId]?.trim())

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg w-full max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t('pockets.onboardingTitle')}</DialogTitle>
          <DialogDescription>{t('pockets.onboardingDescription')}</DialogDescription>
        </DialogHeader>

        {/* One section per unnamed pocket */}
        <div className="space-y-4">
          {pockets.map((pocket) => {
            const suggestion = suggestions.find((s) => s.accountId === pocket.accountId)
            return (
              <div
                key={pocket.accountId}
                className="rounded-lg border bg-muted/30 p-4 space-y-3"
              >
                {/* Pocket identifier (placeholder name) */}
                <p className="text-sm font-medium text-muted-foreground">
                  {pocket.placeholderName}
                </p>

                {/* Transfer history — helps the user recognise the pocket */}
                {pocket.transfers.length > 0 && (
                  <div>
                    <p className="text-xs text-muted-foreground mb-1.5">
                      {t('pockets.transfersTitle')}
                    </p>
                    <ul className="space-y-1">
                      {pocket.transfers.map((tx, i) => (
                        <li key={i} className="flex items-center justify-between text-sm">
                          <span className="text-muted-foreground">{tx.date}</span>
                          <span className="font-medium text-emerald-600">
                            +{tx.amount.toLocaleString('fr-FR', {
                              style: 'currency',
                              currency: 'EUR',
                            })}
                          </span>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}

                {/* CSV suggestion badge — uncertain ones visually flagged */}
                {suggestion && (
                  <div className="flex items-center gap-2">
                    <Badge variant={suggestion.uncertain ? 'destructive' : 'secondary'}>
                      {t('pockets.csvSuggestedLabel')}
                    </Badge>
                    {suggestion.uncertain && (
                      <span className="text-xs text-muted-foreground flex items-center gap-1">
                        <AlertCircle className="size-3 text-destructive" />
                        {t('pockets.csvAmbiguous')}
                      </span>
                    )}
                  </div>
                )}

                {/* Name input */}
                <Input
                  placeholder={t('pockets.namePlaceholder')}
                  value={names[pocket.accountId] ?? ''}
                  onChange={(e) =>
                    setNames((prev) => ({ ...prev, [pocket.accountId]: e.target.value }))
                  }
                />
              </div>
            )
          })}
        </div>

        {/* CSV drop zone — optional, for name bootstrapping only */}
        <div
          className={cn(
            'mt-2 rounded-lg border-2 border-dashed p-4 text-center transition-colors cursor-pointer',
            isDragging
              ? 'border-primary bg-primary/5'
              : 'border-muted-foreground/25 hover:border-muted-foreground/50',
          )}
          onDrop={handleDrop}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onClick={() => fileInputRef.current?.click()}
          role="button"
          aria-label={t('pockets.csvDropzone')}
        >
          <input
            ref={fileInputRef}
            type="file"
            accept=".csv"
            className="hidden"
            onChange={handleFileInputChange}
          />
          <Upload
            className={cn(
              'mx-auto size-5 mb-1.5',
              isDragging ? 'text-primary' : 'text-muted-foreground',
            )}
          />
          <p className="text-sm text-muted-foreground">
            {isDragging ? t('pockets.csvDropzoneActive') : t('pockets.csvDropzone')}
          </p>
          <p className="text-xs text-muted-foreground/70 mt-1">
            {t('pockets.csvDropzoneHint')}
          </p>
        </div>

        {/* CSV parse error */}
        {csvError && (
          <p
            role="alert"
            className="rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive"
          >
            {csvError}
          </p>
        )}

        <DialogFooter className="flex-col-reverse sm:flex-row gap-2">
          <Button
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={isSaving}
          >
            {t('pockets.skipForNow')}
          </Button>
          <Button
            onClick={() => void handleConfirm()}
            disabled={!hasSomeName || isSaving}
          >
            {t('pockets.saveNames')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
