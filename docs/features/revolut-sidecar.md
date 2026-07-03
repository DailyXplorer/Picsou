# Design Spec: Revolut Sidecar Connector

> Status: 📝 Draft design (2026-07-02) — not yet implemented
> Author: brainstorming session (reverse-engineering recon done live against real account)
> Supersedes for Revolut: the PSD2 pockets reconstruction ([ADR 2026-06-28](../decisions/2026-06-28-revolut-pockets-reconstruction.md)) once shipped.

## 1. Goal

Sync **all** Revolut assets into Picsou without going through Enable Banking / PSD2, which
only exposes current accounts (`CACC`) and cannot see vaults, multi-currency pockets, crypto,
commodities, or the investing/trading products. Build a browser-session sidecar (like `tr-auth`)
that owns a logged-in Revolut web session and feeds a Java adapter.

Scope chosen by the user: **all surfaces** (personal app + Invest + Revolut X crypto), with the
sidecar as the **primary** source for Revolut and Enable Banking kept connected as a **fallback**.

## 2. Surface taxonomy (recon 2026-07-02)

Revolut is not one web app but several, on separate domains, sharing the `/api/retail/...` API
family and one SSO (`sso.revolut.com`, OAuth 2.0 + PKCE). Each surface has its own per-request
auth requirements.

| Surface | Domain | Assets | Auth difficulty |
|---|---|---|---|
| Personal app | `app.revolut.com` | current accounts, pockets (multi-currency), vaults, transactions, cards, IBAN | **Low — cracked** |
| Trading terminal | `invest.revolut.com` | stocks/ETF, "RoboTrading", portfolios | Medium — extra in-memory headers |
| Revolut X | `exchange.revolut.com` | crypto | Not yet inspected |
| Business/Pro | `business.revolut.com` | pro/business accounts | Not yet inspected (user has a `pro` pocket) |

## 3. Authentication model

### 3.1 Login (all surfaces) — human-in-the-loop
- OAuth 2.0 + PKCE against `sso.revolut.com/signin` (`response_type=code`, `code_challenge_method=S256`).
- `POST /api/retail/signin` completes and sets an **httpOnly session cookie**.
- Login requires phone + passcode + mobile-app confirmation → **not automatable**. The user logs in
  once inside the sidecar's Chromium; the sidecar then persists the resulting session (see §4).

### 3.2 Session token
- The access/session token lives **only in an httpOnly cookie** — verified absent from
  localStorage, sessionStorage, IndexedDB, and JS-visible cookies.
- `GET /api/retail/token/info` → `{ userId, expireAt, lifetime, scopes }`.
- **Access-token lifetime ≈ 4 minutes** (`lifetime` ≈ 226 s). The app refreshes it continuously
  via **`PUT /api/retail/token`** (authenticated by the httpOnly refresh cookie).
- **Refresh-cookie lifetime is unknown** (httpOnly, not JS-readable). Measure at build time via
  Playwright `context.cookies()`. This is the single biggest unknown for unattended sync.

### 3.3 Per-request headers (differ per surface)
- **app.revolut.com**: httpOnly cookie + `x-device-id` (= `revo_device_id` cookie, a 36-char UUID)
  + `x-browser-application: WEB_CLIENT` + `x-client-version`. **Confirmed**: cookie + these headers
  → `200`; cookie alone → `401` (missing `x-device-id` was the sole cause).
- **invest.revolut.com**: needs a **longer, in-memory-derived** `x-device-id` (different value than
  the cookie) **plus** `x-registered-identity: <userId>` and `browser-session-id: <uuid>`. Cookie +
  the short device-id → `401`.
- These extra values are computed by the app's JS at runtime and are not in any persistent store.

### 3.4 Why we do NOT reconstruct headers or hook JS
- Reconstructing the derived headers would be brittle reverse-engineering.
- Hooking `window.fetch` fails: the bundle holds a **private `fetch` reference** captured at load
  (verified — override not intercepted).
- **Decision:** the sidecar reads/replays at the **Playwright network layer**, below the app JS.

### 3.5 Login feasibility & chosen enrolment (spike, 2026-07-02)

A Task-0 spike drove the login with Playwright (headless and headful-under-Xvfb). Findings:

- **No hard bot-wall at page load.** Both headless and headful render the real login page
  (`sso.revolut.com/signin`); hCaptcha/reCAPTCHA SDKs are present but not triggered at landing.
  This is unlike Bourso (plain httpx, blocked by JS challenges) — a real browser executes the JS.
- **The hard part is NEW-DEVICE enrolment.** A fresh browser context (no `revo_device_id`) hits the
  phone-number step + device verification. A *known* device (persisted `revo_device_id`) skips
  straight to passcode-only — trivial. So the difficulty is one-time enrolment, not recurring login.
- **Revolut aggressively rate-limits repeated WEB logins.** After ~7 rapid automated attempts the web
  channel throttled: captchas everywhere and `"Mauvais code d'accès ou numéro"` on web while the SAME
  passcode still worked in the mobile app (account not locked — web login throttled, clears in hours).
  **Operational rule: the sidecar must NOT retry logins aggressively; enrolment is a rare, deliberate,
  human-initiated action, and auth failures must back off, never loop.**
- **Recurring-sync mechanism is proven** (cookie + `x-device-id` + `PUT /token` + retail data harvest,
  from the recon).
- **Chosen enrolment approach — one-time ASSISTED headful login:** the user completes the login by
  hand in a headful browser (validated: native headful Chromium displays fine on the host), the run
  captures `context.storage_state()`, and the sidecar **reuses that storageState headless** for all
  recurring syncs. This supersedes the earlier "enter phone+passcode in Picsou, auto-drive headless"
  idea for the first login — headless auto-enrolment of a new device is not worth fighting (finicky
  form-driving + rate-limit risk). Day-to-day it stays hands-off; only initial setup needs one login.
  The sidecar's Docker image therefore needs a headful path for enrolment (X server / Xvfb or a
  host-run helper) in addition to the headless sync path.

### 3.6 Breakthrough & final model — Camoufox + on-demand (2026-07-03)

The assumptions in §3.5 were partly wrong. What we then established end-to-end against the real account:

- **Vanilla Playwright Chromium is the blocker, not the account.** Revolut's anti-bot fingerprints and
  blocks a plain Playwright Chromium (captcha loops, `"Mauvais code d'accès"`), but the user's own
  Firefox-based browser logs in fine. Fix: **Camoufox** (stealth Firefox, C++-level fingerprint spoof).
  With Camoufox the login is **fully automated** (auto-fill phone + passcode; the user only approves
  the push on their phone) — no assisted/noVNC window needed. Camoufox 0.4.11 requires
  `playwright==1.55.0` (1.60+ breaks its Juggler protocol).
- **Session = a PERSISTENT Camoufox profile per member** (`user_data_dir` reused across launches).
  Reusing the same profile keeps fingerprint + cookies stable; replaying from fresh per-launch
  instances (random fingerprint) gets the session invalidated.
- **Data mapping validated + fixed** against the real account: `/api/retail/wallets` is a dict keyed
  by account type (not a list); balances are integer minor units (÷100); money-box balance is nested
  `balance.amount`+`balance.currency`; money-box pockets are deduped out of the current-account list;
  non-fiat (crypto)/MERCHANT/REVX_FIAT pockets are filtered.
- **Session longevity is short and not yet cleanly measured.** Even with active keep-alive the session
  died in ~6 min — but this was measured after ~10 rapid logins in one day, which very likely got the
  account flagged (a fresh morning session lived 30+ min). True TTL must be measured after a ~24 h
  cool-down. **Decision: ship ON-DEMAND sync now** — `POST /sync {phoneNumber, passcode, memberId}`
  reuses a still-live profile session (no approval) or does a fresh login (mobile approval) then
  harvests immediately. Daily unattended auto-sync stays gated until the true TTL is known (if long,
  a keep-alive loop makes it free; the sidecar's session-reuse path already benefits automatically).
- **Credentials are the user's choice**: the sync form offers "remember (AES-GCM encrypted)" vs
  re-enter each time. The sidecar never stores credentials; Java does, only if the user opts in.

Working dev helpers (gitignored) live in `services/revolut-auth/`: `capture_login.py`,
`auto_login.py`, `validate_*.py`, `keepalive_test.py`.

## 4. Architecture

Sibling of `services/tr-auth/`. New service `services/revolut-auth/` (Python + FastAPI + Playwright
+ Chromium, `python:3.12-slim`, Chromium-only — reuse the [tr-auth slim-image ADR](../decisions/2026-04-25-tr-auth-sidecar-slim-image.md)).

```
┌─────────────────────────┐         ┌──────────────────────────────┐
│ Java backend            │  HTTP   │ revolut-auth sidecar (:8002) │
│  RevolutSyncService     │────────▶│  FastAPI + Playwright        │
│  RevolutAdapter         │         │   - owns logged-in context   │
│   (behind a port)       │◀────────│   - encrypted storageState   │
└─────────────────────────┘  JSON   │   - PUT /token refresh loop  │
                                     │   - network-layer harvest    │
                                     └──────────────────────────────┘
```

### 4.1 Sidecar responsibilities
1. **Login capture**: launch Chromium, user completes SSO login, then `context.storageState()` is
   persisted (cookie jar incl. httpOnly session + refresh cookies + `revo_device_id` device binding).
2. **Persistence**: the `storageState` blob is returned to Java and stored **encrypted at rest**
   (AES-256-GCM via `CryptoEncryption`, same as TR tokens — see [encryption-at-rest.md](./encryption-at-rest.md)).
   On sync, Java hands the blob back to the sidecar to restore the context.
3. **Stable device identity**: reuse the same `revo_device_id` across restarts; a rotating device id
   risks session invalidation / new-device challenge.
4. **Refresh**: before each sync cycle, `PUT /api/retail/token` to mint a fresh 4-min access token.
5. **Data fetch — two mechanisms**:
   - *Harvest* (default): navigate the SPA to the relevant views and capture the app's own API
     responses via `page.on('response')`. These are guaranteed authenticated (the app makes them).
   - *Replay*: for endpoints not auto-triggered, capture a live request's headers via
     `context.route`/`page.on('request')` and replay with `context.request` (`x-device-id`,
     `x-registered-identity`, `browser-session-id` as captured). Playwright sees real headers below
     the JS layer.
7. **Sequential surface harvest**: Revolut appears to enforce a single active web session (see §8).
   The sidecar must harvest one surface, then re-establish auth before the next — or use isolated
   `BrowserContext`s per surface — never hold all surfaces logged in at once.
6. **Endpoints exposed to Java** (per surface, returning normalized JSON):
   - `POST /session` (store/restore storageState), `GET /session/status`
   - `GET /accounts` (personal: wallets/pockets + money-boxes + IBAN)
   - `GET /transactions?from=` (personal, per pocket, paginated)
   - `GET /investing` (invest: trading accounts + portfolio positions)
   - `GET /crypto` (Revolut X)

### 4.2 Java side (ports & adapters — see [ADR ports-and-adapters](../decisions/2026-01-01-ports-and-adapters.md))
- New `RevolutPort` with records `RevolutAccount`, `RevolutPocket`, `RevolutHolding`, `RevolutTxn`.
- `RevolutAdapter` calls the sidecar and maps to domain types.
- `RevolutSyncService`: orchestrates auth, background initial sync (daemon thread +
  `TransactionTemplate`, same pattern as `TradeRepublicSyncService`), scheduled resync via
  `SchedulerService.dailyBankSync()`.
- `RevolutController` under `/api/revolut/`.
- Session entity `RevolutSession` (encrypted storageState, deleteAll-before-save single-session).

## 5. Data model mapping (Revolut → Picsou)

| Revolut object | Endpoint | Picsou target |
|---|---|---|
| Pocket (per currency in a wallet) | `GET /api/retail/wallets`, `/user/current/wallet` | `Account` (CHECKING/multi-ccy) |
| Money-box (PERSONAL / PERSONAL_JOINT) | `GET /api/retail/user/current/money-boxes` | `Account` (SAVINGS/vault) |
| IBAN | `GET /api/retail/bank-accounts/account-details?...&walletId=` | `Account.iban` (dedup key) |
| Pocket transactions | `GET /api/retail/user/current/transactions/last?internalPocketId=&to=` | `Transaction` (ingestion → Budget) |
| Card transactions | `GET /api/retail/user/current/transactions/card/<id>` | `Transaction` |
| Trading account + portfolio | `GET /api/retail/trading/accounts`, `/trading-access/portfolios/<id>` | `Account` (COMPTE_TITRES) + `Holding[]` |
| Trading positions | `GET /api/retail/trading/v2/users/<id>/SECURITY/allocation` | `Holding` (per instrument) |
| Instrument name/ISIN | `GET /api/retail/instruments/<id>/details`, `POST /instruments/tickers` | `Holding.ticker` (reuse ISIN→ticker + `HoldingDedup`) |
| Crypto (Revolut X) | TBD (recon phase 3) | `Holding`/crypto account |

Reuse existing machinery: `HoldingDedup::vwapMerge` for holdings, `CategorizationService.apply`
for transactions (feeds [budget.md](./budget.md)), ISIN→ticker conversion for instruments.

## 6. Relationship to Enable Banking (user choice: sidecar primary, EB fallback)

- The Revolut sidecar is the **primary** source for Revolut; Enable Banking stays connected as a
  **fallback** (if the sidecar session dies, EB still syncs current accounts).
- **Dedup by IBAN**: `SyncService.upsertAccount()` already matches by IBAN first (see
  [bank-sync.md](./bank-sync.md) §account-matching). Revolut sidecar accounts carry the IBAN from
  `bank-accounts/account-details`, so EB and sidecar accounts for the same current account collapse
  to one row. Provenance is tracked so the sidecar wins when both are present.
- The PSD2 pockets reconstruction ([ADR 2026-06-28](../decisions/2026-06-28-revolut-pockets-reconstruction.md))
  becomes redundant once the sidecar ships (real money-box objects replace the heuristic).

## 7. Phasing

- **Phase 1 — `app.revolut.com`**: accounts, pockets, vaults, transactions, IBAN. Auth cracked,
  endpoints mapped. Ships the bulk of the value. Also stands up the sidecar skeleton + Java port.
- **Phase 2 — `invest.revolut.com`**: trading accounts + portfolio holdings. Endpoints mapped; auth
  handled by the network-layer harvest/replay (no new mechanism needed, only a per-surface capture).
- **Phase 3 — `exchange.revolut.com` (Revolut X)**: crypto. Needs a short recon (endpoints unknown).
- **Phase 4 (optional) — `business.revolut.com`**: pro accounts, if the user has business assets.

Each phase reuses the same sidecar + port; a phase adds a surface module + a mapping.

## 8. Security & risk

- **Encryption at rest**: storageState (cookies) encrypted with AES-256-GCM, like TR tokens.
- **Device binding**: keep `revo_device_id` stable; treat it as a secret.
- **ToS / ban risk**: this scrapes a private API. Mitigants: user's own account, human-in-the-loop
  login, low request volume, WEB_CLIENT user-agent parity. Accepted tradeoff (precedent: `tr-auth`,
  Trade Republic WebSocket). A temporary session block is possible and must degrade gracefully to EB.
- **Refresh-cookie longevity**: the deciding factor for unattended sync; measure early. If short,
  fall back to periodic manual re-login (like TR's ~2 h re-auth UX) + EB carrying the gap.
- **Single-session invalidation & concurrent user activity** (observed 2026-07-02): Revolut appears
  to allow only one active web session. Opening a second surface (`invest`/`exchange`/`business`,
  which trigger an `acr_values=rev:mfa` step-up) invalidated the live `app.revolut.com` session —
  the app dropped to `/logged-out` and `token/info` returned `401` across domains. The user browsing
  their own Revolut on the web can likewise log the sidecar out. The sidecar must detect
  `401`/`/logged-out` and degrade gracefully to the EB fallback. Device binding survives:
  `revo_device_id` / `revo_hardware_id` persist across logout; only the session/refresh cookies are
  cleared, so re-login reuses the same device identity.
- **No credential storage**: phone/passcode never touch Picsou (entered only in the sidecar browser
  during interactive login), same as TR.

## 9. Open questions (to resolve during build)
1. Refresh-cookie lifetime (measure via `context.cookies()`).
2. **Partial finding (2026-07-02):** surfaces are NOT freely concurrent — opening a second surface
   invalidated the first (single active session; see §8). Verify whether isolated `BrowserContext`s
   per surface avoid this, or whether harvest must be strictly sequential with re-auth between
   surfaces. This shapes the sidecar's sync loop.
3. Crypto (Revolut X) endpoint shapes — needs its own recon pass.
4. Multi-member: single-user single-session (like TR) for v1; multi-member later.

## 10. Testing
- Unit: mapping (pocket→account, money-box→savings, allocation→holdings VWAP), IBAN dedup vs EB.
- Wiring: `RevolutSyncServiceTest` (background sync, refresh path, session-expired → fallback).
- Sidecar: contract tests against recorded JSON fixtures (no live account in CI).
- Manual: end-to-end against the real account (login, sync, refresh, restart persistence).

## Links
- Precedent ADR: [tr-auth slim sidecar](../decisions/2026-04-25-tr-auth-sidecar-slim-image.md)
- Precedent feature: [Trade Republic Sync](./trade-republic.md)
- Related: [Bank Sync](./bank-sync.md), [Encryption at rest](./encryption-at-rest.md), [Budget](./budget.md)
- Superseded on ship: [Revolut pockets reconstruction](../decisions/2026-06-28-revolut-pockets-reconstruction.md)
