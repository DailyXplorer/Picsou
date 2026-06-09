# Changelog

All notable changes to Picsou are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] — 2026-06-09

Minor release: a complete **Budget & Cashflow** module — zero-config and "Apple-like". Fed by
Enable Banking transaction sync with a full manual fallback (it works with no synced bank at all),
it categorizes every transaction automatically by *brand* against an embedded, offline knowledge
base — before the user tags a single thing — and presents spending through a clean nested-route
information architecture.

### Added

- **Zero-config brand categorization.** An embedded, **offline** merchant knowledge base (~110
  common FR/EU brands) categorizes synced transactions automatically, from the very first sync,
  with no setup and no ML or external service. It slots in as a pure fallback behind the existing
  rule engine, so the precedence **manual choice > learned rule > brand KB > uncategorized** is
  never inverted and no per-member rows are written. A KB version bump (`kb_version`) re-categorizes
  in place without ever overriding a user's choice.
- **Clean merchant names everywhere.** A pure `MerchantNormalizer` strips payment-processor wrappers
  (`PAYPAL *…`, `SUMUP *…`), card/reference digits, and date noise into a canonical `merchant_label`,
  stamped on every transaction whether or not it ends up categorized.
- **Merchant avatars.** `MerchantAvatar` renders an initial-monogram with a deterministic, offline
  colour in every transaction list — no network by default.
- **Cashflow flow diagram.** Income sources → a central hub → spending categories, drawn as a
  **Sankey diagram** on ≥ `md` viewports and as compact **Flow Bars** on phones, internal transfers
  excluded. A per-category **drill** lists the transactions (and, for a parent, a per-child rollup).
- **Sub-categories.** Categories now form a one-level **tree** (`parent_id` + a stable `slug`).
  Aggregation stays leaf-only so a euro is never counted under both a parent and a child; a parent
  envelope rolls up its whole subtree, guarded against parent/child double-budgeting.
- **Recurring v2 with silent auto-confirm.** Detection keys on the canonical merchant identity (not
  the drifting raw counterparty), scores a confidence, and **silently auto-confirms** high-confidence
  series (≥ 3 regular occurrences, fixed amount, confidence ≥ 0.80). The safety net: a **price-change
  alert**, an **activity feed** of "what changed", and **per-item undo** — silent is never
  unexplained. `transaction.recurring_series_id` is now populated, linking each charge to its series.
- **New information architecture (`/budget/*`).** The single tabbed page became a `BudgetLayout` with
  nested routes — Overview, Spending (+ drill), Subscriptions, Envelopes, Settings — a segmented
  sub-nav on desktop and a bottom bar on mobile. **Review is contextual**: a banner on the Overview
  when there are items to correct, not a permanent tab.
- **Opt-in brand logos.** Off by default. When enabled, a server-side proxy
  (`GET /api/merchants/{id}/logo`) fetches logos from DuckDuckGo's keyless icon service behind a
  port/adapter, with an in-memory TTL cache, a per-IP rate limit, and a per-member gate; the
  monogram is always the fallback, and logos never feed categorization.

### Changed

- The budget section's single 7-tab page was replaced by the nested-route IA above, and recurring
  detection was rewritten around canonical merchant identity (it previously drifted on the raw
  bank counterparty and never auto-acted).

### Notes

- See [`docs/features/budget.md`](docs/features/budget.md) and the ADRs
  [merchant KB & budget IA](docs/decisions/2026-06-09-merchant-kb-and-budget-ia.md) (this redesign)
  and [budget cycle & categorization](docs/decisions/2026-06-02-budget-cycle-and-categorization.md)
  (the original foundation).
- Categorization stays **100% offline** — no ML, no third-party categorization API (privacy).
- Custom reports and the Picsou MCP server remain out of scope for this release.

### Database migrations

- **V33** — budget foundation (categories, rules, `budget_settings`)
- **V34** — envelopes / budgets
- **V35** — recurring series
- **V36** — budget categorization foundation (`category.parent_id`/`slug`,
  `transaction.merchant_label`, `budget_settings.kb_version`/`logo_fetch_enabled`)
- **V37** — merchant knowledge base (`merchant_brand`, `merchant_alias`,
  `transaction.merchant_brand_id`, ~110-brand seed)
- **V38** — recurring v2 (`confidence`, amount range, `is_variable`, `previous_amount`,
  `price_changed_at`; `(member_id, lower(label))` unique index)

## [1.0.2] — 2026-06-02

Patch release: fixes a **403** that blocked login and the setup wizard's **Origins**
step when Picsou is served over HTTPS behind a reverse proxy.

### Fixed

- **403 "Request failed with status code 403" over HTTPS behind a reverse proxy.**
  Picsou serves the SPA and the API from the same origin, so login/setup traffic is
  same-origin and should never hit CORS. But Spring's `CorsUtils.isCorsRequest()`
  compares the `Origin`'s scheme/host/port against the request's, and with no
  forwarded-headers handling the TLS-terminated backend saw `http` while the browser
  sent `https` — the scheme mismatch made same-origin requests look cross-origin and
  the fail-closed allow-list rejected them with `403`. The backend now trusts proxy
  headers (`server.forward-headers-strategy: framework`) and the bundled nginx
  preserves the upstream `X-Forwarded-Proto`/`Host`/`Port` instead of overwriting them
  with its own plain-HTTP `$scheme`. Same-origin HTTPS traffic is now correctly
  recognized and the wizard works without pre-seeding `ALLOWED_ORIGINS`.

  > Behind a reverse proxy, ensure it forwards `X-Forwarded-Proto: https`
  > (Caddy, Traefik, Nginx Proxy Manager and Cloudflare Tunnel do by default).

## [1.0.1] — 2026-06-02

Patch release: a crash-recovery fix for invalid account currencies, a dependency
security bump, and Docker image-tagging refinements.

### Fixed

- **Invalid currency codes no longer crash the whole app.** A manual account saved
  with a non-ISO-4217 code (e.g. `AMAT`) made `Intl.NumberFormat` throw a `RangeError`
  in `formatCurrency`; the throw bubbled to the root error boundary and blanked the
  entire UI, leaving the offending account impossible to reach, edit, or delete.
  `formatCurrency` now degrades to `"<amount> <code>"` instead of throwing (which also
  rescues any already-persisted bad account), the account form uses a curated currency
  dropdown with locale-aware labels, and the backend rejects unknown codes with a `400`
  via a new `@ValidCurrency` constraint (`0703274`). Closes #9.

### Security

- **Dependency advisories patched.** `bcprov-jdk18on` 1.78.1 → 1.84 and `poi-ooxml`
  5.3.0 → 5.4.0 (`5a7e776`).

### Changed

- **Docker image tagging.** `main` builds are now published as `latest` and version
  tags drop the `v` prefix (`5f887ce`); deployment docs run from the published GHCR
  images instead of building locally (`50dc0e1`).

## [1.0.0] — 2026-06-02

First stable release. Builds on the MVP (commit `37920d1`) with multi-member
families, 2FA + persistent sessions, a zero-config setup wizard, bank/broker/crypto
integrations, loan amortization, GDPR data export, savings-goal trajectories,
per-holding security insight, and hundreds of refinements across security,
internationalization, and mobile responsiveness.

### Added

#### Multi-account family
- **Family members & roles.** New `FamilyMember` entity, `UserRole` enum, sharing
  entities, and per-row `member_id` foreign keys across all financial tables
  (`d95b7b7`, `d7abdf2`).
- **Member-scoped services.** All services, controllers, and repositories scope
  queries by `memberId`, with admin override for impersonation (`68a97ba`,
  `2dbbeb6`).
- **`UserContext` helper.** Single source of truth for "who is the current user
  and which member are they viewing?" (`050c49d`, `c548b7f`).
- **Family API & UI.** `FamilyController`, `FamilyViewController`, family stores,
  sidebar, and pages for creating members, switching profiles, and viewing
  shared resources (`a5308e8`, `a3ee1c0`).
- **Username change with token rotation.** Settings → Profile lets a user rename
  themselves; access/refresh JWTs are re-issued so the session doesn't drop
  (`7f8c988`, `3454307`).
- **Admin members management.** Admins can invite, activate, reset password, and
  reset 2FA for other members (`6a3c978`, `36ef439`).
- **Create members from the Admin page.** Admin → Members can now create new
  users directly, deriving the login from the display name (`e305096`).
- **Delete members with an active account.** Admins can remove a member even
  when that member still owns an account, without hitting a
  `TransientObjectException` (`a55dfb2`, `682a6f3`).

#### Two-factor authentication & persistent sessions
- **TOTP 2FA.** `UserMfa`, recovery codes, anti-replay window, full enrolment
  wizard, and verification challenge page (`98243c5`, `ccddd88`, `6dd476a`,
  `a95d752`, `5f60ce5`, `c59a439`).
- **Remember Me / persistent sessions.** Rotating tokens with theft detection,
  silent re-login filter, and admin force-disable (`6c1ae82`, `a481128`,
  `5f724e6`, `7afd7d0`).
- **MFA-aware controllers.** `MfaController` (step-up reauth) and
  `SessionController` (list/revoke active sessions) (`0df1f68`, `7afd7d0`,
  `db40cf6`, `07a4fff`).
- **Stateless JWT invalidation on password change.** New `token_version` claim
  bumped on password change; persistent sessions revoked, refresh+access cookies
  re-issued (`056a900`).
- **Admin password recovery.** `ADMIN_RECOVERY_ENABLED=true` triggers an
  `ApplicationRunner` at boot that mints a 1-hour activation token for the admin
  account, bumps `tokenVersion` to invalidate active sessions, and prints the URL
  to the logs. The login page points the operator to the console reset link while
  recovery is active. Documented in `docs/features/admin-recovery.md` (`9297246`,
  `cd3d88e`).

#### Setup wizard (zero-config first launch)
- **Guided first-boot flow.** `SetupController`, `SetupService`, `SetupFilter`,
  audit log, and `AppSetting` key/value store let admins complete admin account,
  security (CORS, secure cookies, encryption key), and integrations setup with no
  env-var editing (`627533a`).
- **Per-integration mini-wizards.** Enable Banking (5-step keypair flow),
  BoursoBank, Trade Republic, Finary, and crypto-exchange setup pages (`627533a`).
- **CORS reload from environment.** `POST /api/admin/settings/cors/reload-from-env`
  plus a "Reload from environment" button in the admin Security panel — an escape
  hatch when the operator changes the public URL after the wizard ran (`9297246`).

#### Security insight (per holding)
- **Asset type + ETF composition.** `SecurityInsightService` classifies a holding
  (stock / ETF / …) and, for ETFs, surfaces top companies, country, and sector
  breakdowns inside the holding detail modal (`befb13d`).
- **Boursorama composition provider.** Composition is resolved and parsed from
  Boursorama (302-redirect resolution), the single source after the issuer-holdings
  approach was superseded; the port returns an aggregated `EtfComposition` and the
  per-issuer adapters were dropped (`68caf43`, `1f41932`, `dfb1407`, `ca49378`).
- **Stable i18n keys for sectors/countries.** Boursorama FR labels map to stable
  i18n keys with a graceful fallback to the raw label when a key is missing
  (`8bdfc4f`, `bdecde3`).
- **Block / line composition views.** A small per-section toggle switches each
  composition between labeled blocks and a slim colour bar + wrapping legend; the
  line view keeps the breakdown readable on phones (`a66f043`, `0225a25`).

#### Integrations
- **BoursoBank** *(disabled in 1.0.0)*. Python sidecar (Selenium-based),
  `BoursoAdapter`, `BoursoController`, sessions table, V23 migration shipped but
  the sidecar and all UI entry points are gated off until the integration is
  finished (`b953e55`).
- **Trade Republic compact portfolio.** `compactPortfolio` with `secAccNo` for
  portfolio sync; deduplication when multiple ISINs map to the same ticker
  (`91972b6`, `1b91abf`).
- **Finary.** Full Finary API import + auto-sync, holdings preservation across
  syncs, manual-transaction protection, daily scheduler, proactive TOTP detection
  in the Add Account modal (`6c9777e`, `93c7ee0`, `0328533`, `1faa1d8`, `0f4bf07`,
  `fbeb155`).
- **Powens** *(experimental, disabled in 1.0.0)*. `PowensBankConnector` ships in
  the codebase but is untested end-to-end; `@Primary` was removed so Enable
  Banking remains the canonical `BankConnectorPort` even when `POWENS_CLIENT_ID`
  is set (`6c9777e`).
- **Crypto exchanges.** Binance integration via `CryptoExchangePort` (`b70b2aa`,
  `aab51c4`).
- **On-chain wallets.** Bitcoin (xpub, zpub, output descriptors), Ethereum, and
  Solana via dedicated adapters (`b70b2aa`, `2eda584`, `4b51f32`).
- **Solana SPL token support.** `SolanaWalletAdapter` also calls
  `getTokenAccountsByOwner` and surfaces known stablecoins (USDC, EURC, USDT)
  alongside the native SOL balance. `WalletPort.fetchBalance` → `fetchBalances`
  returning `List<WalletBalance>`; Ethereum and Bitcoin adapters adapted to the
  list signature (`9297246`).
- **OpenFIGI ISIN → ticker resolver** for Yahoo Finance prices (`0bf43c6`).

#### Loans
- **On-the-fly amortization.** `LoanAmortizationService` derives the schedule from
  loan inputs without storing a row per month; the UI renders a progress card,
  monthly breakdown, cost summary, and amortization chart (`52aa8e5`).
- **Extra loan fields.** V27 migration adds APR, fees, and supporting columns on
  `Debt` (`52aa8e5`).

#### Manual transactions
- **CRUD endpoints.** `POST` / `DELETE /api/transactions/...`, with the manual
  flag preserved across re-syncs (`969ba10`, `93c7ee0`).
- **Investment transactions.** Holdings auto-fill; BUY/SELL drive position
  derivation via `HoldingComputeService` (`a12321a`, `4f534ac`, `9b144d8`,
  `210d758`).
- **AddTransactionModal** with hooks, error handling, and a TransactionsList
  delete action (`429eca0`, `b5b1d41`).

#### Goals
- **Donut year cards.** Year grid view rendered as donut cards using `oklch` CSS
  vars (`f30d77b`, `33bbcf5`, `a3f9168`).
- **Ideal target trajectory.** The goal detail chart draws an ideal-pace line
  anchored at the baseline, plus an at-current-pace projection, and crops to the
  goal's `createdAt` instead of the full history window; the X axis is now a true
  time scale so uneven date spans render cleanly (`a865c37`, `1fb6ff8`, `25ef6bd`,
  `1f4a9d4`).
- **`isOnTrack` from cumulative objective.** On-track status is derived from the
  cumulative past-month objective vs. effective contributions, giving the benefit
  of the doubt when there is no past data (`3f4ec38`).
- **Flexible contribution editing.** Comma- or point-decimal inputs, side-panel
  calendar editing, and history backfill for manual contributions (`55737ad`).

#### GDPR data export
- **Self-service export** of profile, accounts, holdings, transactions, goals,
  debts, wallets, sharing settings, bank connections, and balance snapshots in
  JSON + CSV, gated by re-authentication and rate-limited (`d0933ac`, `3f58502`,
  `9287d47`, `66a274a`, `22dd58b`).

#### History, debts, real estate
- **History/PnL endpoints**, debts, real estate metadata, error pages, mobile
  nav, sync modal (`dad9bea`).

#### Holdings UX
- **Edit & detail modals**, empty-chart state with intraday support (`de40df0`).

#### Other
- **Mandatory encryption** of secrets at rest (`6c1ae82`, `6c9777e`).
- **Theme persistence** across sessions (`6c9777e`).
- **All-in-one Docker image** with supervisord, dev hot-reload Dockerfiles,
  slimmer `tr-auth` sidecar (`cd651e6`).
- **GHCR publishing.** CI publishes per-branch images on every push and ships the
  `tr-auth` sidecar image alongside the main app (`24dfeb7`, `751160e`).

### Changed

- **Frontend rewrite** with shadcn/ui and a feature-based architecture (`33a384c`).
- **Branding** — the frontend package is now `picsou`, with a black-logo favicon
  (`4c4ca66`).
- **Icon migration** from hugeicons to `lucide-react` (`f3bb858`).
- **Locale-aware formatting** helpers via `getLocale()` (`3b9b0f4`); all remaining
  date displays routed through locale-aware formatters (`37b2cab`); date inputs
  honor the configured date-format setting via a hybrid native/desktop
  `DateInput` (`d66ff93`).
- **Centralized error formatting.** Backend messages are English and surfaced
  through a single friendly frontend formatter (`formatApiError`, which accepts an
  i18next `TFunction`) (`b682a0e`, `c04b4b0`).
- **Add Account modal** unified with embedded sync wizards (`7a96584`).
- **Layout, sidebar, dashboard, account detail** redesign + updated demo data
  (`001551f`).
- **Mobile responsiveness** across the whole app (`5017206`).
- **License** changed from MIT to Apache 2.0 + Commons Clause (`e9185c9`,
  `25825e5`).
- **Enable Banking setup wizard** — the redirect URI is shown before the
  credentials step so users can whitelist it before generating their Application
  ID / Key ID; the test step warns that EB only activates the application after a
  real bank account is connected (otherwise the test reports a misleading "invalid
  key"); SANDBOX vs PRODUCTION warning banner and a mandatory acknowledgement
  checkbox; PSD2 scope note (current accounts only) in step 1 and `BankSyncTab`;
  sign-in link updated to `https://enablebanking.com/sign-in/` (`9297246`).
- **Trade Republic Add Account** — the PIN field is masked (bullets) like a
  password input.
- **Setup wizard intro** — `HelloGreeting` cadence cut from ~19 s to ~7.8 s, with
  an explicit Skip button and a 5 s watchdog so a stalled font/i18n load can never
  block the wizard (`9297246`).

### Fixed

- **CORS with credentials.** Forbid wildcard origins; default to empty (no
  cross-origin); strip `*` in DB-loaded values; defense-in-depth at parse and
  Spring layers (`f8e92f7`).
- **Independent members private from impersonation (BOLA).** Admin impersonation
  can no longer reach independent members' data; debt `linkedAccountId` lookups
  are member-scoped (`e2ad075`, `cb7cf49`).
- **Goal contributions IDOR.** `/api/family/goals/{id}/contributions` checks
  ownership and sharing visibility (`7236faa`); new manual contribution entries
  are bound to the correct member (`45670e0`).
- **MFA error semantics.** An invalid TOTP / recovery code returns `400`, not
  `401`, so the client doesn't trigger a refresh loop (`68649c5`).
- **Deactivated accounts.** Login/refresh rejects deactivated accounts to stop a
  401 loop (`2780573`).
- **Deleted accounts no longer reappear after sync.** Accounts soft-delete via
  `@SQLRestriction("deleted_at IS NULL")`; sync upserts in `SyncService`,
  `TradeRepublicSyncService`, and `CryptoExchangeSyncService` refuse to resurrect
  removed accounts (`9297246`).
- **Add-account no longer 502s.** Enable Banking polling capped at 4.5 s (3 ×
  1.5 s) instead of 24 s; an unlinked session returns `[]` and the requisition is
  marked `FAILED` so the UI retry button takes over; nginx `/api` locations gained
  60 s read-timeout headroom (`9297246`).
- **FX conversion on prices.** Yahoo responses are FX-converted and the unsafe
  native-price fallback was removed (`f0e0ac1`).
- **Holdings.** VWAP deduplication, coherent live-price math, and correct
  capital-invested in the detail modal (`60c9a75`); null-quantity handling, Spring
  `@Transactional`, N+1 fix (`4f534ac`, `9b144d8`).
- **Dashboard net worth** reads invested capital from `balance_snapshot`
  (`d99e67b`).
- **Account balance edits.** Save errors surface in the month-end balance modal
  (`ecd2fcd`); `POST /{id}/history` is accepted for manual snapshots (`7922fb4`).
- **Family** — delete errors surface in the confirm dialog (`cb4e3a3`); admin
  shows "Administrateur" instead of "Compte indépendant" (`2d50db4`); lazy
  serialization, enum mapping, profile switching (`f152cb9`).
- **Finary** — `TOTP_REQUIRED` reported correctly when the session is flagged
  (`5dca6da`); auto-sync transaction handling and BoursoSync filter (`aaf770d`);
  show all bank connections in SyncAllModal (`ab4faa6`).
- **Auth cookies** SameSite=Lax for Safari iOS compatibility (`3e9e140`); CORS
  PATCH method allowed (`e25f57d`); detailed login error reporting + CORS trace
  logs (`2488771`, `4af867a`); local-network CORS via origin patterns (`2832da3`,
  `2859392`).
- **Wallet** — remove ticker from on-chain wallet accounts to prevent double price
  conversion (`916a128`).
- **Pre-existing test regression.** `SetupServiceTest` stubbed `findByUsername`
  while production calls `findByUsernameWithMember` (`9297246`).
- **Frontend** — TypeScript build errors (`7cb7a3d`); AddTransactionModal reset &
  holdings query (`b5b1d41`); export read-only transaction wrapping (`22dd58b`).

### Security

- **CORS hardening** (see *Fixed*): closed the wildcard-with-credentials
  vulnerability.
- **Broken-object-level-authorization (BOLA) hardening** for admin impersonation
  and member-scoped debt lookups (`e2ad075`, `cb7cf49`).
- **JWT invalidation on password change** via the `token_version` claim.
- **`SecureCookieProvider`** centralizes the environment-aware `Secure` flag for
  cookies (`9570661`).
- **Setup wizard** rejects `*` for CORS allowed origins (`627533a`).
- **Rate-limit buckets** for MFA challenge and data export (`a95d752`, `d0933ac`).
- **Persistent token theft detection** (rotating opaque tokens) (`6c1ae82`).

### Documentation

- **README & SECURITY.md** rewritten; license clarified (`e9185c9`, `25825e5`,
  `42ab61b`).
- **CLAUDE.md** files overhauled with conventions and "don'ts"; English-only rule
  enforced (`fb599a5`, `527a0a4`, `5c4d29d`).
- **IEEE-style 1.0.0 release docs** — SRS, SDD, SDS, STP, and User Manual under
  `docs/release/1.0.0/`.
- **ADRs** — first-launch wizard, tr-auth sidecar slim image, loan amortization on
  the fly, Yahoo FX conversion, and ETF composition via Boursorama (superseding the
  issuer-holdings approach) (`cd651e6`, `52aa8e5`, `627533a`, `ca49378`).
- **Feature notes** across bank sync, loans, setup wizard, intraday chart, manual
  transactions, CORS & cookies, accounts overview, ISIN→ticker, data export, MFA,
  multi-account family, admin recovery, and security insight.
- **Deployment** — GHCR image paths corrected (repo is `picsou-finance`)
  (`f5994f0`).

### Database migrations

- **V23** — `bourso_session` (`b953e55`)
- **V24** — manual transaction fields (`a33d53c`)
- **V25** — `setup_state` (`627533a`)
- **V26** — `setup_audit` (`627533a`)
- **V27** — loan extra fields (`52aa8e5`)
- **V28** — MFA + persistent sessions (`98243c5`)
- **V29** — `app_user.token_version` (`056a900`)
- **V30** — `account.deleted_at` soft-delete + index (`9297246`)
- **V31** — price-snapshot cleanup gate
- **V32** — `goal.history_start` (anchors trajectory charts)

[1.0.0]: https://github.com/Zoeille/picsou-finance/releases/tag/v1.0.0
