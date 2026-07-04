import { useEffect } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore } from '@/stores/app-store'
import { LoadingSkeleton } from '@/components/shared/LoadingSkeleton'
import { useSessionProbe } from './hooks'

export function RequireAuth({ children }: { children: React.ReactNode }) {
  const isAuthenticated = useAuthStore(s => s.isAuthenticated)
  const login = useAuthStore(s => s.login)
  const demoMode = useAppStore(s => s.demoMode)

  const probe = useSessionProbe(!demoMode && !isAuthenticated)

  useEffect(() => {
    if (probe.isSuccess) login(probe.data)
  }, [probe.isSuccess, probe.data, login])

  if (demoMode || isAuthenticated) return <>{children}</>
  if (probe.isPending || probe.isSuccess) return <LoadingSkeleton />

  return <Navigate to="/login" replace />
}

export function PublicOnly({ children }: { children: React.ReactNode }) {
  const isAuthenticated = useAuthStore(s => s.isAuthenticated)
  const demoMode = useAppStore(s => s.demoMode)

  if (demoMode || isAuthenticated) return <Navigate to="/" replace />

  return <>{children}</>
}

export function RequireAdmin({ children }: { children: React.ReactNode }) {
  const user = useAuthStore(s => s.user)
  if (!user) return <Navigate to="/login" replace />
  if (user.role !== 'ADMIN') return <Navigate to="/error/403" replace />
  return <>{children}</>
}
