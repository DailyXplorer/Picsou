import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useLoginWithRememberMe } from '@/features/mfa/hooks'
import { useAppStore } from '@/stores/app-store'
import { safeRedirect, isOAuthAuthorizeRedirect } from '@/lib/utils'
import { getErrorStatus, getErrorDetail } from '@/lib/errors'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { Eye, EyeOff, Loader2 } from 'lucide-react'

export function LoginPage() {
  const { t } = useTranslation()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [rememberMe, setRememberMe] = useState(false)
  const [showPw, setShowPw] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const loginMutation = useLoginWithRememberMe()
  const demoMode = useAppStore(s => s.demoMode)
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const redirect = safeRedirect(searchParams.get('redirect'))

  // A /oauth2/ target is the backend authorize endpoint (native app login) — hand off with a
  // full-page navigation so the server resumes the flow; everything else is an in-app SPA route.
  const finishLogin = (target: string) => {
    if (isOAuthAuthorizeRedirect(target)) window.location.assign(target)
    else navigate(target, { replace: true })
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      const result = await loginMutation.mutateAsync({ username, password, rememberMe })
      if (result.mfaRequired) {
        // Branch off to /login/mfa — the mfa_challenge cookie is now set.
        // Forward both the post-MFA redirect target and the rememberMe flag
        // so the user's preference survives the second hop. (Server-side, the
        // remember_me claim was already encoded into the mfa_challenge JWT —
        // the URL param is purely UI state for the trust-device checkbox.)
        const params = new URLSearchParams()
        if (redirect && redirect !== '/') params.set('redirect', redirect)
        if (rememberMe) params.set('rememberMe', '1')
        const qs = params.toString()
        navigate(`/login/mfa${qs ? `?${qs}` : ''}`)
        return
      }
      finishLogin(redirect)
    } catch (err: unknown) {
      const status = getErrorStatus(err)
      const ax = err as { response?: unknown; message?: string }
      if (!ax.response) {
        setError(`Impossible de contacter le serveur (${ax.message ?? 'Network Error'})`)
      } else if (status === 429) {
        setError('Trop de tentatives, réessayez dans quelques minutes')
      } else if (status === 401) {
        setError(t('auth.error'))
      } else {
        setError(`Erreur ${status} — ${getErrorDetail(err) ?? ax.message}`)
      }
    }
  }

  return (
    <div className="min-h-screen bg-background flex items-center justify-center">
      <div className="max-w-sm w-full mx-4 flex flex-col gap-4">
        <Card>
          <CardHeader className="items-center text-center">
            <CardTitle className="text-xl">{t('auth.login')}</CardTitle>
            <CardDescription className="mt-0.5">{t('auth.loginTagline', 'Picsou')}</CardDescription>
          </CardHeader>

          <CardContent>
            <form onSubmit={handleSubmit} className="flex flex-col gap-4">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="username">{t('auth.username')}</Label>
                <Input
                  id="username"
                  type="text"
                  value={username}
                  onChange={e => setUsername(e.target.value)}
                  autoComplete="username"
                  required
                  placeholder="admin"
                />
              </div>

              <div className="flex flex-col gap-1.5">
                <Label htmlFor="password">{t('auth.password')}</Label>
                <div className="relative">
                  <Input
                    id="password"
                    type={showPw ? 'text' : 'password'}
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    autoComplete="current-password"
                    required
                    placeholder="••••••••"
                    className="pr-9"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPw(v => !v)}
                    className="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground transition-colors"
                    aria-label={showPw ? t('auth.hidePassword', 'Hide password') : t('auth.showPassword', 'Show password')}
                  >
                    {showPw ? <EyeOff size={16} /> : <Eye size={16} />}
                  </button>
                </div>
              </div>

              <div className="flex items-start gap-2">
                <input
                  id="rememberMe"
                  type="checkbox"
                  checked={rememberMe}
                  onChange={e => setRememberMe(e.target.checked)}
                  className="mt-0.5 h-4 w-4 rounded"
                />
                <div className="flex flex-col">
                  <Label htmlFor="rememberMe" className="text-sm cursor-pointer">
                    {t('auth.rememberMe')}
                  </Label>
                  <p className="text-xs text-muted-foreground">{t('auth.rememberMeDesc')}</p>
                </div>
              </div>

              {error && (
                <p className="text-sm font-medium text-destructive">{error}</p>
              )}

              <Button type="submit" disabled={loginMutation.isPending} className="w-full mt-1">
                {loginMutation.isPending && (
                  <Loader2 size={16} className="animate-spin" />
                )}
                {loginMutation.isPending ? t('auth.loggingIn') : t('auth.loginButton')}
              </Button>
            </form>
          </CardContent>
        </Card>

        {demoMode && (
          <Card className="border-muted bg-muted/40">
            <CardContent className="pt-4 pb-4">
              <p className="text-sm font-medium text-foreground">{t('auth.demoMode')}</p>
              <p className="text-xs text-muted-foreground mt-0.5">{t('auth.demoModeDesc')}</p>
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  )
}
