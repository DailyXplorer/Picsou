# Feature: Revolut Sidecar Connector

> Last updated: 2026-07-03 (on-demand Camoufox model + per-member profile-lock serialization)
> Status: ⚠️ Code shipped + unit-tested; NOT yet tested live end-to-end and the sidecar
> Docker image is not yet built (`camoufox fetch` pulls ~700 MB Firefox at build time).

## Context

Enable Banking (PSD2) only exposes Revolut current accounts (`CACC`) — not vaults, multi-currency
pockets, crypto or investments. Revolut has no personal API, so this connector drives the Revolut
**web app** (`app.revolut.com`) through a browser sidecar and reads its internal `/api/retail/...`
endpoints, to sync all Revolut assets. Phase 1 = `app.revolut.com` (current accounts, pockets,
vaults, transactions); Invest/crypto are separate future phases (endpoints noted under Gotchas).

## How it works

The `services/revolut-auth/` sidecar (Python + FastAPI + **Camoufox** = stealth Firefox) owns a
**persistent browser profile per member** and exposes ONE data endpoint:

- `POST /sync {phoneNumber, passcode, memberId}` — first tries to **reuse the member's still-live
  profile session** (headless, no login, no mobile approval); if that session is dead/absent it does
  an **automated login** (Camoufox auto-fills phone + passcode; the user approves the push on their
  phone) then harvests. Returns `{accounts: [...]}` or `408 APPROVAL_TIMEOUT` / `401 SESSION_EXPIRED`
  / `409 SYNC_IN_PROGRESS` (a sync for that member is already running). The call can block up to
  ~5 min waiting for mobile approval.

Auth (established by live recon): the retail API needs the httpOnly session cookie (kept in the
Camoufox profile) **plus** header `x-device-id` (= the JS-readable `revo_device_id` cookie) +
`x-browser-application: WEB_CLIENT` + `x-client-version: 100.0`. Access tokens live ~4 min and are
refreshed with `PUT /api/retail/token`. All API calls run in-page (`page.evaluate(fetch...)`), below
the app's own JS, which can't be hooked.

Java maps pockets → `CHECKING` sub-accounts (parent = wallet), money-boxes → `SAVINGS`, dedups
against Enable Banking by IBAN, and ingests transactions (feeds Budget). Credentials are stored
encrypted (AES-256-GCM) only if the user ticks "remember"; otherwise they're passed per sync and not
stored. Revolut is the **primary** source; Enable Banking stays as a fallback for the current account.

### Key files

- `services/revolut-auth/main.py` — sidecar: `/sync`, Camoufox launch, login auto-fill, harvest,
  per-member serialization + stale profile-lock clearing.
- `services/revolut-auth/tests/test_profile_lock.py` — regression tests (concurrent-sync 409 +
  stale-lock removal); run `.venv/bin/python tests/test_profile_lock.py` (no pytest needed).
- `services/revolut-auth/Dockerfile` — Camoufox image (Firefox deps + Xvfb + `camoufox fetch`).
- `services/revolut-auth/requirements.txt` — `camoufox[geoip]==0.4.11`, `playwright==1.55.0`.
- `backend/.../port/RevolutPort.java` — `sync(phoneNumber, passcode, memberId)` + data records.
- `backend/.../adapter/RevolutAdapter.java` — WebClient → sidecar `/sync` (330 s timeout); maps
  sidecar `401`/`408`/`409` → `SESSION_EXPIRED`/`APPROVAL_TIMEOUT`/`SYNC_IN_PROGRESS`.
- `backend/.../service/RevolutSyncService.java` — orchestration, IBAN-first upsert, remembered creds.
- `backend/.../controller/RevolutController.java` — `/api/revolut/{sync,status,session}`.
- `backend/.../model/RevolutSession.java` — per-member row: encrypted creds (optional), `rememberCredentials`, `lastSyncedAt`.
- `backend/.../service/RevolutPocketService.java` — PSD2 pocket reconstruction; stands down once the sidecar has synced.
- `backend/.../db/migration/V48__revolut_session.sql`, `V49__revolut_session_credentials.sql`.
- `frontend/src/pages/sync/RevolutTab.tsx` — phone+passcode+remember form / one-click sync / "approve on phone" state.
- `frontend/src/features/sync/{api.ts,hooks.ts}`, `types/api.ts`, `AddAccountModal.tsx`, `SyncAllModal.tsx`.

### Flow

```
RevolutTab (phone+passcode+remember) → POST /api/revolut/sync
  → RevolutSyncService.sync(memberId, phone, passcode, remember)
      → RevolutAdapter → sidecar POST /sync
            reuse live profile session ─ yes → harvest → accounts
                                        └ no → auto-login (user approves push) → harvest → accounts
      → upsertAccount (IBAN-first dedup vs Enable Banking) + ingestTransactions (→ Budget)
      → upsert RevolutSession (lastSyncedAt; encrypted creds iff remember)
SchedulerService.dailyBankSync → resyncIfSessionActive (only if creds remembered; reuses live session or no-ops)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| **Camoufox** (stealth Firefox) | Revolut's anti-bot fingerprints & blocks a vanilla Playwright Chromium; a Firefox engine is accepted (the user's own Firefox browser logs in fine) | Playwright Chromium (blocked); patchright/Chromium (still Chromium); plain httpx (Bourso's approach — dies on JS challenges) |
| **Persistent Camoufox profile per member** | Keeps fingerprint **and** cookies stable across launches, which is what keeps a session alive | Storing a `storageState` blob and replaying it from fresh instances (random fingerprint → session invalidated) |
| **On-demand `/sync`** (reuse-or-login) | The captured session is short-lived; sync when the user asks, reuse a live session if present | Stored-session daily unattended sync (blocked until TTL is measured) |
| `playwright==1.55.0` | Camoufox 0.4.11's Firefox speaks an older Juggler protocol | 1.60+ (breaks with `Browser.setDefaultViewport`/`setContrast` errors) |
| Optional remembered creds (user's choice) | Convenience (1-click) vs security (re-enter) — the user decides | Always store / never store |
| Guard PSD2 reconstruction on `RevolutSession.lastSyncedAt` | Stand down ONLY once the sidecar produced real pockets | `provider='Revolut'` account check (matches EB-synced wallets too → breaks EB-only users) |

## Gotchas / Pitfalls

- **Don't hammer logins.** Many rapid logins get the account flagged (captchas, "Mauvais code" on web
  while the mobile app still works, short-lived sessions). Sessions are a rare, deliberate action.
- **Session longevity is unknown.** With active keep-alive the session still died in ~6 min — but that
  was measured while the account was flagged from over-testing (a fresh morning session lived 30+ min).
  Measure the true TTL after a ~24 h cool-down before deciding if unattended daily sync (keep-alive
  loop) is viable. On-demand sync works regardless.
- **Balances are integer MINOR units** (cents): `12345` == `123.45` → `_minor_to_major` divides by 100.
- **`/api/retail/wallets` is a dict keyed by account type** (`{PERSONAL:[...], PERSONAL_JOINT:[...],
  YOUTH:[], ...}`), NOT a list, and NOT under a `wallets` key. Each wallet has a `pockets` list.
- **Money-box balance is nested**: `{"amount": <cents>, "currency": "EUR"}` (not a flat number).
- **Pocket filtering**: money-box pockets are excluded by their `pocket.id` (else vaults appear twice —
  once CHECKING, once SAVINGS); `MERCHANT`/`REVX_FIAT` and non-fiat (crypto) pockets are dropped.
- **6-digit passcode auto-submits** — do not click "Continuer" after typing it (that hung `auto_login`).
- **One Firefox per profile — serialize per member.** A persistent Camoufox profile can be opened by
  only ONE Firefox process at a time. Two `/sync` calls for the same member (double-click, or the
  daily scheduler overlapping a manual sync) used to launch two browsers on the same profile and the
  second died with `TargetClosedError` / "Firefox is already running, but is not responding" → HTTP
  500. Fixed with a per-member `asyncio.Lock` (fast-fail `409` when one is already running; the single
  uvicorn worker makes an in-process lock sufficient) **plus** clearing stale `lock`/`.parentlock`
  files before each launch (a browser killed mid-login — container restart, OOM — leaves them and
  wedges every later sync until the volume is cleaned by hand).
- **The sidecar login runs headful under Xvfb** (Camoufox `headless=False`), so the image installs
  `xvfb` and runs uvicorn under `xvfb-run`. Profiles persist on the `revolut_profiles` docker volume.
- **Camoufox needs Firefox system libs** (gtk/dbus-glib/xtst/…), different from Chromium.
- **Future phases (Invest / Revolut X)** are separate surfaces with stricter auth: `invest.revolut.com`
  (`/api/retail/trading/accounts`, `/trading-access/portfolios/<id>`, `/trading/v2/users/<id>/SECURITY/allocation`,
  `/trading/transactions`) and `exchange.revolut.com` (Revolut X crypto). Both need extra in-memory headers
  (`x-registered-identity`, `browser-session-id`, a longer `x-device-id`) captured via the browser.
- Dev helpers (gitignored) in `services/revolut-auth/`: `capture_login.py` (manual), `auto_login.py`
  (auto-fill), `validate_harvest.py` / `validate_persistent.py`, `keepalive_test.py`.

## Tests

- `backend/.../service/RevolutSyncServiceTest.java` — sync mapping (pockets/vaults), IBAN dedup vs
  Enable Banking, transaction dedup, remembered-vs-not credentials, always-upsert session marker row.
- Live end-to-end (login + harvest against a real account) is still pending the account cool-down.
- Note: 6 tests are red independently of this feature (`CashflowFlowServiceTest` ×3,
  `RevolutPocketServiceTest` ×2, `SyncServicePocketTest` ×1 — pre-existing `refreshPocketBalance`
  save-count / budget NPE issues; verified via `git stash`).

## Links

- Precedent ADR: [tr-auth slim sidecar](../decisions/2026-04-25-tr-auth-sidecar-slim-image.md)
- Related: [Bank Sync](./bank-sync.md), [Trade Republic](./trade-republic.md),
  [Encryption at rest](./encryption-at-rest.md), [Budget](./budget.md)
- Partly supersedes: [Revolut pockets reconstruction](../decisions/2026-06-28-revolut-pockets-reconstruction.md)
  (stands down once the sidecar has synced)
