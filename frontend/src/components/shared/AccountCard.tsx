import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { TriangleAlert } from 'lucide-react'
import type { Account } from '@/types/api'
import { Card, CardContent } from '@/components/ui/card'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { AccountTypeBadge } from '@/components/shared/AccountTypeBadge'
import { Avatar, AvatarImage, AvatarFallback } from '@/components/ui/avatar'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { formatCurrency, formatDate, formatTimeAgo, localeFromLanguage } from '@/lib/utils'

interface AccountCardProps {
  account: Account
  onClick?: () => void
}

/**
 * Synced accounts whose data is older than this are flagged: live prices keep
 * the numbers moving, so without an explicit signal a dead provider session
 * (e.g. Trade Republic) looks perfectly healthy.
 */
const SYNC_STALE_THRESHOLD_MS = 48 * 60 * 60 * 1000

function AccountAvatar({ logoUrl, color }: { logoUrl: string | null; color: string }) {
  return (
    <Avatar className="mt-1 size-10 shrink-0 bg-white">
      {logoUrl && <AvatarImage src={logoUrl} alt="" className="object-contain p-1" />}
      <AvatarFallback style={{ backgroundColor: color }} />
    </Avatar>
  )
}

export function AccountCard({ account, onClick }: AccountCardProps) {
  const { t, i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const isLoan = account.type === 'LOAN'
  const isRealEstate = account.type === 'REAL_ESTATE'

  // Mount-time snapshot via lazy initializer (Date.now() in render is rejected
  // by the compiler); a 48h threshold does not need a ticking clock.
  const [now] = useState(() => Date.now())
  const isSyncStale =
    !account.isManual &&
    account.lastSyncedAt != null &&
    now - new Date(account.lastSyncedAt).getTime() > SYNC_STALE_THRESHOLD_MS

  const pnl = isRealEstate && account.realEstate
    ? account.currentBalanceEur - account.realEstate.purchasePrice
    : null
  const pnlPct = isRealEstate && account.realEstate && account.realEstate.purchasePrice > 0
    ? ((pnl! / account.realEstate.purchasePrice) * 100).toFixed(1)
    : null

  return (
    <Card
      className="cursor-pointer transition-colors hover:bg-muted/20"
      onClick={onClick}
    >
      <CardContent className="flex items-start gap-3 p-4">
        <AccountAvatar logoUrl={account.logoUrl} color={account.color} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate font-medium">{account.name}</span>
            <AccountTypeBadge type={account.type} />
          </div>
          {account.provider && (
            <p className="text-xs text-muted-foreground">{account.provider}</p>
          )}
          <div className="mt-2">
            <CurrencyDisplay
              value={isLoan ? -account.currentBalanceEur : account.currentBalanceEur}
              currency={account.currency}
              className={`text-lg font-semibold ${isLoan ? 'text-red-500' : ''}`}
            />
          </div>
          {isRealEstate && pnl !== null && (
            <p className={`mt-1 text-xs ${pnl >= 0 ? 'text-emerald-500' : 'text-red-500'}`}>
              {pnl >= 0 ? '+' : ''}{formatCurrency(pnl, 'EUR', locale)}
              {pnlPct !== null && ` (${pnl >= 0 ? '+' : ''}${pnlPct}%)`}
            </p>
          )}
          {isLoan && account.debt && (
            <p className="mt-1 text-xs text-muted-foreground">
              {t('debt.borrowedAmount')}: {formatCurrency(account.debt.borrowedAmount, 'EUR', locale)}
            </p>
          )}
          {account.lastSyncedAt && (
            isSyncStale ? (
              <TooltipProvider>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <p className="mt-1 flex items-center gap-1 text-xs text-amber-600 dark:text-amber-400">
                      <TriangleAlert className="size-3 shrink-0" />
                      {t('accounts.syncStale', { time: formatTimeAgo(account.lastSyncedAt) })}
                    </p>
                  </TooltipTrigger>
                  <TooltipContent className="max-w-xs">{t('accounts.syncStaleTooltip')}</TooltipContent>
                </Tooltip>
              </TooltipProvider>
            ) : (
              <p className="mt-1 text-xs text-muted-foreground">
                {t('accounts.lastSync')}: {formatDate(account.lastSyncedAt)}
              </p>
            )
          )}
        </div>
      </CardContent>
    </Card>
  )
}
