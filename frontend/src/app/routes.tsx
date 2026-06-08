import { createBrowserRouter } from 'react-router-dom'
import { RequireAuth, PublicOnly, RequireAdmin } from '@/features/auth/guards'
import { RequireSetup, SetupOnly } from '@/features/setup/guards'
import { AppLayout } from '@/components/layout/AppLayout'
import '@/pages/setup/setup.css'
import {
  LoginPage,
  MfaChallengePage,
  DashboardPage,
  AccountsPage,
  AccountDetailPage,
  GoalsPage,
  GoalCalendarPage,
  BudgetLayout,
  BudgetOverviewPage,
  SpendingPage,
  CategoryDetailPage,
  SubscriptionsPage,
  EnvelopesPage,
  ReviewPage,
  BudgetSettingsPage,
  SyncPage,
  SettingsPage,
  ActivationPage,
  FamilyDashboardPage,
  FamilySettingsPage,
  AdminPage,
  SetupLayout,
  SetupStepIntro,
  SetupStepAdmin,
  SetupStepSecurity,
  SetupStepIntegrations,
  SetupStepComplete,
  SetupStepEnableBanking,
  SetupStepBoursoBank,
  SetupStepTradeRepublic,
  SetupStepFinary,
  SetupStepCrypto,
  NotFoundPage,
  ForbiddenPage,
  ServerErrorPage,
  SuspensePage,
} from './lazy-pages'

export const router = createBrowserRouter([
  {
    path: '/login',
    element: (
      <PublicOnly>
        <SuspensePage>
          <LoginPage />
        </SuspensePage>
      </PublicOnly>
    ),
  },
  {
    // /login/mfa is also for unauthenticated visitors — the user is mid-login
    // (mfa_challenge cookie set, access_token NOT yet set), so PublicOnly applies.
    path: '/login/mfa',
    element: (
      <PublicOnly>
        <SuspensePage>
          <MfaChallengePage />
        </SuspensePage>
      </PublicOnly>
    ),
  },
  {
    path: '/error/403',
    element: (
      <SuspensePage>
        <ForbiddenPage />
      </SuspensePage>
    ),
  },
  {
    path: '/error/500',
    element: (
      <SuspensePage>
        <ServerErrorPage />
      </SuspensePage>
    ),
  },
  {
    path: '/setup',
    element: (
      <SetupOnly>
        <SuspensePage>
          <SetupLayout />
        </SuspensePage>
      </SetupOnly>
    ),
    children: [
      { index: true, element: <SuspensePage><SetupStepIntro /></SuspensePage> },
      { path: 'admin', element: <SuspensePage><SetupStepAdmin /></SuspensePage> },
      { path: 'security', element: <SuspensePage><SetupStepSecurity /></SuspensePage> },
      { path: 'integrations', element: <SuspensePage><SetupStepIntegrations /></SuspensePage> },
      { path: 'integrations/enablebanking', element: <SuspensePage><SetupStepEnableBanking /></SuspensePage> },
      { path: 'integrations/boursobank', element: <SuspensePage><SetupStepBoursoBank /></SuspensePage> },
      { path: 'integrations/traderepublic', element: <SuspensePage><SetupStepTradeRepublic /></SuspensePage> },
      { path: 'integrations/finary', element: <SuspensePage><SetupStepFinary /></SuspensePage> },
      { path: 'integrations/crypto', element: <SuspensePage><SetupStepCrypto /></SuspensePage> },
      { path: 'done', element: <SuspensePage><SetupStepComplete /></SuspensePage> },
    ],
  },
  {
    path: '/',
    element: (
      <RequireSetup>
        <RequireAuth>
          <AppLayout />
        </RequireAuth>
      </RequireSetup>
    ),
    children: [
      { index: true, element: <SuspensePage><DashboardPage /></SuspensePage> },
      { path: 'accounts', element: <SuspensePage><AccountsPage /></SuspensePage> },
      { path: 'accounts/:id', element: <SuspensePage><AccountDetailPage /></SuspensePage> },
      { path: 'goals', element: <SuspensePage><GoalsPage /></SuspensePage> },
      { path: 'goals/:id/calendar', element: <SuspensePage><GoalCalendarPage /></SuspensePage> },
      {
        path: 'budget',
        element: <SuspensePage><BudgetLayout /></SuspensePage>,
        children: [
          { index: true, element: <SuspensePage><BudgetOverviewPage /></SuspensePage> },
          { path: 'spending', element: <SuspensePage><SpendingPage /></SuspensePage> },
          { path: 'spending/:categoryId', element: <SuspensePage><CategoryDetailPage /></SuspensePage> },
          { path: 'subscriptions', element: <SuspensePage><SubscriptionsPage /></SuspensePage> },
          { path: 'envelopes', element: <SuspensePage><EnvelopesPage /></SuspensePage> },
          { path: 'review', element: <SuspensePage><ReviewPage /></SuspensePage> },
          { path: 'settings', element: <SuspensePage><BudgetSettingsPage /></SuspensePage> },
        ],
      },
      { path: 'sync', element: <SuspensePage><SyncPage /></SuspensePage> },
      { path: 'sync/callback', element: <SuspensePage><SyncPage /></SuspensePage> },
      { path: 'settings', element: <SuspensePage><SettingsPage /></SuspensePage> },
      { path: 'family', element: <SuspensePage><FamilyDashboardPage /></SuspensePage> },
      { path: 'settings/family', element: <SuspensePage><FamilySettingsPage /></SuspensePage> },
      { path: 'admin', element: <SuspensePage><RequireAdmin><AdminPage /></RequireAdmin></SuspensePage> },
    ],
  },
  {
    path: '/activate/:token',
    element: (
      <SuspensePage>
        <ActivationPage />
      </SuspensePage>
    ),
  },
  {
    path: '*',
    element: (
      <SuspensePage>
        <NotFoundPage />
      </SuspensePage>
    ),
  },
])
