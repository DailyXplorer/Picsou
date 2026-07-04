# Feature: Native iOS app (Phase 1 — auth + read-only dashboard)

> Last updated: 2026-07-03

## Context

A native SwiftUI iPhone client for a self-hosted Picsou instance, aiming for eventual parity with
the web app. **Phase 1** is the vertical slice that proves the whole stack end to end: OAuth2 +
PKCE login (reusing the existing web login + TOTP), Keychain token storage gated by Face ID, and a
read-only dashboard. Later phases (accounts/transactions, goals/debts, sync, settings, widgets) each
get their own spec.

## How it works

### Authentication (backend becomes an OAuth2 Authorization Server)

The app authenticates with **OAuth2 Authorization Code + PKCE** against the user's own instance. The
backend gained a second, higher-priority `SecurityFilterChain` (`AuthorizationServerConfig`,
`@Order(1)`) scoped to `/oauth2/**`; the existing stateless API chain is unchanged (`@Order(2)`).

- Tokens are **HS256-signed with the same `JWT_SECRET`** as the cookie flow (a symmetric
  `OctetSequenceKey` JWKSource) and an `OAuth2TokenCustomizer` stamps the exact claims the existing
  resource server expects (`type=access`, `uid`, `tv`, `sub`, `role`). The resource-server
  validation is unchanged; `JwtAuthenticationFilter` only gained an `Authorization: Bearer`
  transport for `/api/**`.
- The app never renders a login UI: `ASWebAuthenticationSession` opens `/oauth2/authorize`; when the
  request isn't already authenticated, `CookieBridgeAuthenticationFilter` + an entry point redirect
  the in-app browser to the SPA login (`/login?redirect=…`), which runs the untouched password +
  TOTP + Remember-Me flow and bounces back to `/oauth2/authorize`.
- The single public client `picsou-ios` (PKCE S256, `redirect_uri = picsou://callback`, consent
  skipped) and its authorizations live **in memory** — sessions drop on backend restart (the device
  re-authenticates silently); JDBC persistence is the documented later upgrade.

### iOS app (`ios-app/`)

SwiftUI, iOS 17+, **no third-party dependencies**. The Xcode project is generated from `project.yml`
with XcodeGen (not committed).

- `AppState` (`@Observable`) is a finite-state machine: `unconfigured → loggedOut → locked → ready`.
- `ServerConfig` stores the instance URL (validated via `/actuator/health`).
- `OAuthService` runs the PKCE flow and token/refresh grants; `TokenStore` persists the `TokenSet`
  in the Keychain (device-only); `BiometricGate` gates entry with Face ID.
- `APIClient` injects the Bearer token and refreshes once on a 401 (and proactively near expiry)
  via an actor-based single-flight (`TokenRefresher`); on terminal failure it asks `AppState` to
  sign out.
- The dashboard reads a single `GET /api/dashboard?range=` and renders net worth + PnL, a Swift
  Charts area history chart and allocation donut, accounts/liabilities lists, and goal progress.

### Demo mode

Mirrors the web app's `VITE_DEMO_MODE`: a **build flag**, not a runtime toggle. The `Picsou Demo`
scheme (a `Demo` build configuration) defines the `DEMO` compilation condition, read once by
`AppConfig.isDemo`. In a demo build, `AppState` boots straight to `.ready` (no server, no auth, no
Face ID) and `makeDashboardDataSource()` returns `DemoDashboardDataSource` — canned mock data
(`DemoData`) with every section populated — instead of `LiveDashboardDataSource` (the API). The
`DashboardDataSource` protocol is the only seam; the UI is identical, and a small "Démo" badge marks
the mode. The demo source/mocks compile in all configs (so they're testable); only the boot decision
is gated by `#if DEMO`.

### Design system

Ported from the "Picsou Design System" Claude Design project (`Core/DesignSystem/`). `Color(oklch:)`
converts the web tokens' exact OKLCH values to sRGB and `Color(light:dark:)` resolves per appearance,
so `Theme` mirrors the web `index.css` 1:1 (colors, radii, brand `#2563eb`, emerald charts); type is
SF Pro behind `Theme.font(...)` (swappable for Geist). Screens implement the project's two mobile
templates: the **dashboard** is Variant B (blue hero net-worth card + sparkline, goal card, condensed
assets list) over a bottom `PicsouTabBar` — a floating **Liquid Glass** pill on iOS 26+, a material
bar below. The **onboarding** flow (intro → server setup → login → Face ID lock) is the dark,
aurora-lit treatment with white pill CTAs (`Features/Onboarding/`). Verified on the iOS 17/26
simulator via the Demo scheme.

### Flow

```
iOS app ──ASWebAuthenticationSession──▶ /oauth2/authorize
   ▲ picsou://callback?code=…                │ (no cookie)
   │                                         ▼
   │                             /login?redirect=/oauth2/authorize…  (existing SPA login + TOTP)
   │                                         │ sets access_token cookie, navigates back
   │  code + PKCE verifier                   ▼
   └──── POST /oauth2/token ◀──── cookie bridge authenticates ──▶ auth code
             │ access + refresh (HS256 JWT)
             ▼
        Keychain ──▶ APIClient (Bearer) ──▶ GET /api/dashboard
```

## Key files

Backend:
- `backend/src/main/java/com/picsou/config/AuthorizationServerConfig.java` — AS chain, client, JWK, token customizer
- `backend/src/main/java/com/picsou/config/CookieBridgeAuthenticationFilter.java` — authorize-request auth from the cookie
- `backend/src/main/java/com/picsou/config/JwtTokenAuthenticator.java` — shared access-token validation (cookie + Bearer + bridge)
- `backend/src/main/java/com/picsou/config/JwtAuthenticationFilter.java` — cookie + `Bearer` transport on `/api/**`
- `backend/scripts/verify-oauth-pkce.sh` — curl smoke test of the full flow

iOS (`ios-app/Picsou/`): `App/AppState.swift`, `Core/Auth/{OAuthService,TokenStore,PKCE,BiometricGate}.swift`,
`Core/Networking/APIClient.swift`, `Core/Config/ServerConfig.swift`, `Features/Dashboard/*`, `Models/*`.

Infra: `docker/nginx.conf` + `frontend/nginx.conf` (`location /oauth2`); `frontend/src/pages/login/*`
+ `frontend/src/lib/utils.ts` (`isOAuthAuthorizeRedirect`).

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Backend as OAuth2 AS (Spring Authorization Server) | Standard, secure native login that reuses the login/TOTP UI | Bearer-login endpoint (re-implements MFA in-app); reuse cookies via URLSession (not idiomatic) |
| HS256 symmetric JWK from `JWT_SECRET` + token customizer | Existing resource-server validates AS tokens unchanged; one signing key | RSA JWKS (would need the API to validate a second key model) |
| Reuse SPA login via cookie bridge + entry-point redirect | No new login UI, MFA stays 100% server-side | Session-based form login with a re-plumbed TOTP step |
| `tv` read from the principal snapshot at authorize time | Password change → stale `tv` → API rejects → device logs out (correct revocation) | Reload `tv` on refresh (would defeat revocation) |
| XcodeGen `project.yml` | Reproducible, plain-text project; no `.pbxproj` churn | Commit the `.xcodeproj` |

## Gotchas / Pitfalls

- **nginx must proxy `/oauth2/**`.** It otherwise falls through to the SPA `index.html`. Added to both
  the all-in-one and split-compose nginx configs.
- **`SameSite=Lax` + HTTPS.** The redirect back to `/oauth2/authorize` is a top-level GET (Lax cookies
  are sent). `Secure` cookies require HTTPS in production; local dev needs `SECURE_COOKIES=false`.
- **In-memory authorizations** drop on backend restart — expected in Phase 1.
- **Money as `Decimal`** decodes from JSON numbers via `Double`; fine for 2-dp display, not exact
  arithmetic.
- **The `.xcodeproj` is generated** — run `xcodegen generate` after pulling; never edit it by hand.
- Two active ADRs previously set OAuth2 aside for their scopes (MFA, MCP) — the new ADR scopes those
  conclusions and does not reverse them.

## Tests

Backend (Mockito + AssertJ):
- `AuthorizationServerConfigTest` — AS-minted HS256 token validates through the existing resource-server path
- `JwtTokenAuthenticatorTest`, `JwtAuthenticationFilterTest`, `CookieBridgeAuthenticationFilterTest`

iOS (XCTest, run on the simulator):
- `PKCETests` (incl. the RFC 7636 S256 vector), `DashboardDecodingTests`, `APIClientTests`
  (401→refresh→retry, proactive near-expiry refresh), `DemoDashboardDataSourceTests`

## Links

- ADR: [2026-07-03 OAuth2 Authorization Server for the native app](../decisions/2026-07-03-oauth2-authorization-server-for-native-app.md)
- Related: [2FA (TOTP) and Remember Me](./mfa-and-remember-me.md), [MCP server + scoped access-keys](./mcp-server.md),
  [CORS & cookie security](./security-cors-cookies.md)
