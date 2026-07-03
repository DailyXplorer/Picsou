import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useVerifyMfa } from '@/features/mfa/hooks'
import { safeRedirect, isOAuthAuthorizeRedirect } from '@/lib/utils'
import { getErrorStatus, getErrorDetail } from '@/lib/errors'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { Loader2 } from 'lucide-react'

/**
 * Second step of the MFA login flow. Reached only after `/auth/login`
 * returned `mfaRequired:true`; relies on the mfa_challenge HttpOnly cookie
 * the server set on that response. No `from`-state validation here — the
 * server enforces the challenge cookie's existence and freshness.
 */
export function MfaChallengePage() {
  const { t } = useTranslation()
  const verify = useVerifyMfa()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const redirect = safeRedirect(searchParams.get('redirect'))
  const rememberMeFromUrl = searchParams.get('rememberMe') === '1'

  const [code, setCode] = useState('')
  const [isRecoveryCode, setIsRecoveryCode] = useState(false)
  // Trust-device checkbox: pre-checked iff the user ticked "Remember Me" on the
  // login form. Asking again here would be redundant, but they can opt out
  // before submitting the second factor.
  const [trustDevice, setTrustDevice] = useState(rememberMeFromUrl)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      await verify.mutateAsync({ code, isRecoveryCode, trustDevice })
      // A /oauth2/ target (native app login) is server-handled — full-navigate to resume the
      // OAuth flow; any other target is an in-app SPA route.
      if (isOAuthAuthorizeRedirect(redirect)) window.location.assign(redirect)
      else navigate(redirect, { replace: true })
    } catch (err: unknown) {
      const status = getErrorStatus(err)
      const ax = err as { response?: unknown; message?: string }
      if (!ax.response) {
        setError(`Network error (${ax.message ?? 'Network Error'})`)
      } else if (status === 401) {
        // Most likely the mfa_challenge cookie expired (default 5 minutes)
        // or the user lingered on this page. Send them back to /login.
        setError(t('auth.mfaSessionExpired'))
        setTimeout(() => navigate('/login', { replace: true }), 1500)
      } else if (status === 429) {
        setError(t('auth.mfaTooManyAttempts'))
      } else if (status === 400) {
        setError(t('auth.mfaInvalidCode'))
      } else {
        setError(`${status} — ${getErrorDetail(err) ?? ax.message}`)
      }
    }
  }

  const codeInputId = 'mfa-code'
  const codePattern = isRecoveryCode ? undefined : '\\d{6}'
  const codeMaxLength = isRecoveryCode ? 16 : 6
  const codeInputMode = isRecoveryCode ? 'text' : 'numeric'

  return (
    <div className="min-h-screen bg-background flex items-center justify-center">
      <div className="max-w-sm w-full mx-4 flex flex-col gap-4">
        <Card>
          <CardHeader className="items-center text-center">
            <CardTitle className="text-xl">{t('auth.mfaTitle')}</CardTitle>
            <CardDescription className="mt-0.5">
              {isRecoveryCode ? t('auth.mfaRecoveryLabel') : t('auth.mfaDesc')}
            </CardDescription>
          </CardHeader>

          <CardContent>
            <form onSubmit={handleSubmit} className="flex flex-col gap-4">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor={codeInputId}>
                  {isRecoveryCode ? t('auth.mfaRecoveryLabel') : t('auth.mfaCodeLabel')}
                </Label>
                <Input
                  id={codeInputId}
                  type="text"
                  inputMode={codeInputMode}
                  pattern={codePattern}
                  maxLength={codeMaxLength}
                  value={code}
                  onChange={e => setCode(e.target.value)}
                  autoComplete="one-time-code"
                  autoFocus
                  required
                  placeholder={isRecoveryCode ? '12345678' : '123456'}
                  className="text-center text-lg tracking-widest font-mono"
                />
              </div>

              <button
                type="button"
                onClick={() => {
                  setIsRecoveryCode(v => !v)
                  setCode('')
                  setError(null)
                }}
                className="text-xs text-muted-foreground hover:text-foreground underline-offset-4 hover:underline self-start"
              >
                {isRecoveryCode ? t('auth.mfaUseTotp') : t('auth.mfaUseRecovery')}
              </button>

              <div className="flex items-start gap-2">
                <input
                  id="trustDevice"
                  type="checkbox"
                  checked={trustDevice}
                  onChange={e => setTrustDevice(e.target.checked)}
                  className="mt-0.5 h-4 w-4 rounded"
                />
                <div className="flex flex-col">
                  <Label htmlFor="trustDevice" className="text-sm cursor-pointer">
                    {t('auth.mfaTrustDevice')}
                  </Label>
                  <p className="text-xs text-muted-foreground">{t('auth.mfaTrustDeviceDesc')}</p>
                </div>
              </div>

              {error && (
                <p className="text-sm font-medium text-destructive">{error}</p>
              )}

              <Button type="submit" disabled={verify.isPending || code.length === 0} className="w-full mt-1">
                {verify.isPending && <Loader2 size={16} className="animate-spin" />}
                {verify.isPending ? t('auth.mfaVerifying') : t('auth.mfaVerifyButton')}
              </Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
