import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { RefreshCw, LogOut, Smartphone, Lock, Loader2, AlertTriangle } from 'lucide-react'
import { useRevolutStatus, useSyncRevolut, useForgetRevolut } from '@/features/sync/hooks'
import { formatApiError, isTimeoutError } from '@/lib/errors'
import { formatDateTime } from '@/lib/utils'

export function RevolutTab() {
  const { t } = useTranslation()
  const [phoneNumber, setPhoneNumber] = useState('')
  const [passcode, setPasscode] = useState('')
  const [remember, setRemember] = useState(false)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const { data: status, isLoading: statusLoading } = useRevolutStatus()
  const syncMutation = useSyncRevolut()
  const forgetMutation = useForgetRevolut()

  const remembered = status?.remembered ?? false
  const isSyncing = syncMutation.isPending

  function formatSyncError(err: unknown): string {
    if (isTimeoutError(err)) return t('sync.revolut.approvalTimeout')
    return formatApiError(err, t)
  }

  function handleQuickSync() {
    setErrorMsg(null)
    syncMutation.mutate(undefined, {
      onError: (err) => setErrorMsg(formatSyncError(err)),
    })
  }

  function handleFormSync(e: React.FormEvent) {
    e.preventDefault()
    setErrorMsg(null)
    syncMutation.mutate(
      { phoneNumber, passcode, remember },
      {
        onSuccess: () => {
          setPhoneNumber('')
          setPasscode('')
        },
        onError: (err) => setErrorMsg(formatSyncError(err)),
      },
    )
  }

  function handleForget() {
    forgetMutation.mutate()
  }

  if (statusLoading) {
    return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  }

  return (
    <div className="space-y-6">
      {/* Session status card */}
      <Card size="sm">
        <CardContent className="py-4">
          <div className="flex flex-wrap items-center gap-3">
            {remembered ? (
              <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">
                {t('sync.revolut.connected')}
              </Badge>
            ) : (
              <Badge variant="outline">{t('sync.revolut.notConnected')}</Badge>
            )}
            {status?.lastSyncedAt && (
              <span className="text-sm text-muted-foreground">
                {t('sync.revolut.lastSync')}: {formatDateTime(status.lastSyncedAt)}
              </span>
            )}
          </div>
        </CardContent>
      </Card>

      {/* Error state */}
      {errorMsg && (
        <Card size="sm" className="border-destructive/30">
          <CardContent className="py-4">
            <div className="flex items-center gap-3">
              <AlertTriangle className="size-5 text-destructive shrink-0" />
              <p className="text-sm text-destructive flex-1">{errorMsg}</p>
              <Button size="sm" variant="outline" onClick={() => setErrorMsg(null)}>
                {t('sync.banks.retry')}
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Waiting for mobile approval — the sync call blocks up to ~5min server-side */}
      {isSyncing && (
        <Card size="sm">
          <CardContent className="flex items-center gap-3 py-4">
            <Loader2 className="size-5 animate-spin text-muted-foreground shrink-0" />
            <p className="text-sm text-muted-foreground">{t('sync.revolut.approveOnPhone')}</p>
          </CardContent>
        </Card>
      )}

      {remembered ? (
        <div className="flex flex-wrap gap-3">
          <Button onClick={handleQuickSync} disabled={isSyncing}>
            {isSyncing ? <Loader2 className="animate-spin" /> : <RefreshCw />}
            {isSyncing ? t('sync.revolut.syncing') : t('sync.revolut.sync')}
          </Button>

          <Button variant="destructive" onClick={handleForget} disabled={isSyncing || forgetMutation.isPending}>
            <LogOut />
            {t('sync.revolut.forget')}
          </Button>
        </div>
      ) : (
        <form onSubmit={handleFormSync} className="space-y-4">
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="revolut-phone">
                  <Smartphone className="size-4 inline-block mr-1" />
                  {t('sync.revolut.phone')}
                </Label>
                <Input
                  id="revolut-phone"
                  type="tel"
                  value={phoneNumber}
                  onChange={(e) => setPhoneNumber(e.target.value)}
                  required
                  disabled={isSyncing}
                  placeholder="+33..."
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="revolut-passcode">
                  <Lock className="size-4 inline-block mr-1" />
                  {t('sync.revolut.passcode')}
                </Label>
                <Input
                  id="revolut-passcode"
                  type="password"
                  inputMode="numeric"
                  maxLength={6}
                  value={passcode}
                  onChange={(e) => setPasscode(e.target.value)}
                  required
                  disabled={isSyncing}
                />
              </div>

              <label className="flex items-center gap-2 cursor-pointer">
                <Checkbox
                  checked={remember}
                  onCheckedChange={(checked) => setRemember(checked === true)}
                  disabled={isSyncing}
                />
                <span className="text-sm text-muted-foreground">{t('sync.revolut.remember')}</span>
              </label>

              <Button type="submit" disabled={isSyncing} className="w-full">
                {isSyncing && <Loader2 className="size-4 animate-spin" />}
                {isSyncing ? t('sync.revolut.syncing') : t('sync.revolut.sync')}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}
    </div>
  )
}
