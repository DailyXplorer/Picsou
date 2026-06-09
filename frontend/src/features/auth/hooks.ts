import { useMutation, useQueryClient } from '@tanstack/react-query'
import { authApi } from './api'
import { useAuthStore } from '@/stores/auth-store'
import { resetClientState } from '@/lib/reset-client-state'

// The interactive login path lives in features/mfa/hooks.ts
// (`useLoginWithRememberMe` / `useVerifyMfa`) because login is MFA-aware. This
// file only owns logout; both crossings funnel through `resetClientState` so no
// per-user cache or impersonation target survives the auth boundary.
export function useLogout() {
  const logout = useAuthStore(s => s.logout)
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => authApi.logout(),
    onSettled: () => {
      logout()
      resetClientState(queryClient)
    },
  })
}
