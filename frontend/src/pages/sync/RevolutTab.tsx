import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { RefreshCw, LogOut, CreditCard, ShieldCheck } from 'lucide-react'
import { useRevolutSessionStatus, useSyncRevolut, useClearRevolutSession } from '@/features/sync/hooks'
import { formatDateTime } from '@/lib/utils'

/**
 * Revolut has no phone+PIN form: login happens in a server-side sidecar
 * browser (see docs/features/revolut-sidecar.md §3.5) that the user completes
 * by hand, once. This tab only surfaces session status + sync/disconnect —
 * the interactive enrolment view is a follow-up (see TODO below).
 */
export function RevolutTab() {
  const { t } = useTranslation()
  const [enrolmentStarted, setEnrolmentStarted] = useState(false)

  const { data: sessionStatus, isLoading: statusLoading } = useRevolutSessionStatus()
  const syncMutation = useSyncRevolut()
  const clearMutation = useClearRevolutSession()

  const isConnected = sessionStatus?.isActive ?? false

  function handleClearSession() {
    clearMutation.mutate(undefined, {
      onSuccess: () => setEnrolmentStarted(false),
    })
  }

  if (statusLoading) {
    return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  }

  return (
    <div className="space-y-6">
      {/* Session status card */}
      <Card size="sm">
        <CardContent className="py-4">
          <div className="flex items-center gap-3">
            {isConnected ? (
              <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">
                {t('sync.revolut.sessionActive')}
              </Badge>
            ) : (
              <Badge variant="outline">{t('sync.revolut.noSession')}</Badge>
            )}
            {isConnected && sessionStatus?.expiresAt && (
              <span className="text-sm text-muted-foreground">
                {t('sync.revolut.expiresAt')} {formatDateTime(sessionStatus.expiresAt)}
              </span>
            )}
          </div>
        </CardContent>
      </Card>

      {isConnected ? (
        <div className="flex flex-wrap gap-3">
          <Button onClick={() => syncMutation.mutate()} disabled={syncMutation.isPending}>
            <RefreshCw />
            {t('sync.revolut.sync')}
          </Button>

          <Button variant="destructive" onClick={handleClearSession} disabled={clearMutation.isPending}>
            <LogOut />
            {t('sync.revolut.clearSession')}
          </Button>
        </div>
      ) : (
        <Card size="sm">
          <CardContent className="space-y-4 py-4">
            <div className="flex items-start gap-3">
              <ShieldCheck className="size-5 text-muted-foreground shrink-0 mt-0.5" />
              <p className="text-sm text-muted-foreground">{t('sync.revolut.assistedNote')}</p>
            </div>

            {!enrolmentStarted ? (
              <Button onClick={() => setEnrolmentStarted(true)}>
                <CreditCard />
                {t('sync.revolut.connect')}
              </Button>
            ) : (
              <div className="rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">
                {/* TODO(revolut): embed noVNC interactive login here once the sidecar exposes a live view */}
                {t('sync.revolut.enrolmentPending')}
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  )
}
