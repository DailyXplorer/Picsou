# Feature: Embedded MCP server + scoped access-keys

> Last updated: 2026-06-05

## Context

Picsou exposes a rich, member-scoped REST API, but until now an external **app** (an AI
assistant / MCP client such as Claude Desktop) had no way to read or act on a user's finances —
auth was JWT-in-HttpOnly-cookie only, with no API-key concept. This feature serves Picsou over
the **Model Context Protocol** so an AI app can analyse and help manage finances, gated by
**access-keys** that each member creates in Settings and whose **scopes** they control. A key is
hard-bound to its owner's data and can only ever reach a small, curated, audited set of tools.

## How it works

The MCP server is **embedded in the Spring backend** (Spring AI MCP, HTTP+SSE transport). A
second authentication principal — a bearer **access-key** (`psk_…`) — sits alongside the cookie.
An app points an MCP client at `https://<host>/mcp` with `Authorization: Bearer psk_…`; the
`@Tool` methods resolve the key owner's member and delegate to the existing, already
member-scoped services, so member isolation is automatic.

Three security properties are guaranteed structurally (not by per-call checks):

- **A — Keys authenticate ONLY `/mcp/**`, never `/api/**`.** `AccessKeyAuthFilter.shouldNotFilter()`
  returns `true` for any non-`/mcp` path, so a `psk_` token presented to `/api/**` is never even
  validated → 401. A key therefore cannot bypass the curated surface by calling excluded REST
  endpoints directly (e.g. bank-auth).
- **B — No impersonation for keys.** The principal is the owning `AppUser` (so `UserContext` works
  unchanged), but the `Authentication` is a distinct `AccessKeyAuthentication`;
  `UserContext.getMemberIdOverride()` short-circuits to `null` for that type, so the admin
  `?memberId=` override can never apply to a key — even an admin-owned one.
- **C — Keys never carry `ROLE_ADMIN`.** Authorities are scope strings only → `/api/admin/**` and
  any `isAdmin()`-gated logic stay unreachable.

### Key files

**Backend — auth & data**
- `mcp/AccessKeyService.java` — issue / validate / list / revoke keys; SHA-256 hashing, throttled `last_used_at`.
- `mcp/AccessKeyUsageRecorder.java` — `REQUIRES_NEW` best-effort `last_used_at` writer (off the hot path).
- `mcp/Scopes.java` — the scope vocabulary (`domain:action`) and the `ALL` allowlist.
- `mcp/ScopeSetConverter.java` — `Set<String>` ↔ space-delimited column.
- `config/AccessKeyAuthentication.java` — the `Authentication` a key runs as (principal = owner `AppUser`; authorities = scopes).
- `config/AccessKeyAuthFilter.java` — validates the Bearer key for `/mcp/**` only; per-key Bucket4j throttle (429).
- `config/SecurityConfig.java` — registers the filter (4th, anchored to `UsernamePasswordAuthenticationFilter`) and `requestMatchers("/mcp/**").authenticated()`.
- `service/UserContext.java` — Property B guard at the top of `getMemberIdOverride()`.
- `model/AccessKey.java` + `repository/AccessKeyRepository.java` + `db/migration/V37__access_keys.sql`.

**Backend — MCP surface**
- `config/McpToolConfig.java` — the single `ToolCallbackProvider` bean; the one place tools are wired.
- `mcp/tools/{Account,Transaction,Goal,Insight,Sync}Tools.java` — the `@Tool` methods, each gated by `@RequiresScope`.
- `mcp/RequiresScope.java` + `mcp/ScopeEnforcementAspect.java` + `exception/MissingScopeException.java` — scope enforcement (AOP) and its clean error.
- `controller/AccessKeyController.java` + `dto/AccessKey{CreateRequest,Response,CreatedResponse}.java` — self-service management REST API under `/api/access-keys`.
- `config/RateLimitConfig.java` — `mcpKeyBuckets`, `accessKeyCreateBuckets`, and the bucket factories.
- `resources/application.yml` — `spring.ai.mcp.server.*` (HTTP+SSE, `SYNC`, `/mcp` + `/mcp/message`, `MCP_ENABLED` gate, instructions string).

**Frontend**
- `features/accessKeys/{api,hooks,scopes,status}.ts` — TanStack Query layer + scope/status helpers.
- `pages/settings/sections/AccessKeysSection.tsx` — the Settings UI (list, create dialog, one-time secret reveal, connect-your-client block, revoke).
- `i18n/locales/{en,fr}.json` — the `accessKeys.*` namespace.

### Flow

```
AI app / MCP client ──HTTP  GET /mcp (SSE)  +  POST /mcp/message──▶ Spring backend
   │  Authorization: Bearer psk_…
   │
   │  Security filter chain (all anchored to UsernamePasswordAuthenticationFilter):
   │    SetupFilter → JwtAuthenticationFilter → PersistentTokenAuthFilter → AccessKeyAuthFilter
   │      • AccessKeyAuthFilter.shouldNotFilter == true for any non-/mcp path           (Property A)
   │      • validate(psk_…): prefix lookup → constant-time SHA-256 compare → usable? → owner active?
   │      • per-key Bucket4j throttle (429 on overflow)
   │      • set AccessKeyAuthentication(owner AppUser, scope authorities)            (Properties B/C)
   ▼
 Spring AI MCP endpoint  /mcp   (SYNC)
   ▼
 @Tool method  ──@RequiresScope──▶ ScopeEnforcementAspect (throws MissingScopeException if absent)
   ▼
 userContext.currentMemberId()  ← AccessKeyAuthentication ⇒ override refused (Property B)
   ▼
 existing service (findByIdAndMemberId(...)) → member-isolated data
```

Key creation goes the other way, over the cookie-authenticated management API:

```
WebApp (Settings) ──cookie──▶ POST /api/access-keys {name, scopes, expiresAt?}
   ▼  validate scopes ⊆ Scopes.ALL (400 on unknown) · per-member create throttle
 AccessKeyService.create → psk_ + 32 base62 chars · store SHA-256 only
   ▼
 201 { rawSecret (shown ONCE), key }     ← the only time the secret exists in plaintext
```

## Tool catalogue

Every tool acts only on the key owner's own data; writes are restricted to **manual** records and
**refresh-existing-sync** triggers. `McpToolCatalogTest` pins this exact set.

| Scope | Tools |
|-------|-------|
| `accounts:read` | `list_accounts`, `get_account`, `get_account_holdings`, `get_account_balance_history` |
| `transactions:read` | `list_account_transactions` |
| `goals:read` | `list_goals`, `get_goal`, `get_goal_monthly_entries` |
| `dashboard:read` | `get_dashboard`, `get_net_worth_history`, `get_profit_and_loss` |
| `family:read` | `get_family_dashboard` |
| `prices:read` | `get_price` |
| `accounts:write` | `create_manual_account`, `update_account`, `delete_account`, `add_balance_snapshot`, `upsert_holding`, `delete_holding` |
| `transactions:write` | `add_transaction`, `update_transaction`, `delete_transaction` |
| `goals:write` | `create_goal`, `update_goal`, `delete_goal`, `set_goal_month_contribution` |
| `sync:trigger` | `trigger_bank_sync`, `trigger_broker_sync`, `trigger_crypto_exchange_sync`, `trigger_crypto_wallet_sync` |

**Never exposed** (no `@Tool` exists, so no scope can reach them): authentication / credential
flows, connecting a new bank / broker / exchange / wallet, MFA, admin settings, member management,
and GDPR data export.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| MCP server **embedded** in the backend | Tools call existing member-scoped services in-process; one deployable; smallest new attack surface | Standalone MCP sidecar proxying the REST API (extra hop, duplicated auth, more to secure) |
| **Access-key** principal, separate from the JWT cookie | A distinct `Authentication` type is the seam for Properties B/C; keys are revocable and scope-limited without touching the login | Reuse cookie/JWT for MCP (no scoping, full surface, no per-app revoke) |
| **SHA-256 + constant-time compare** for key hashes | The secret is high-entropy (~190 bits), so a fast hash is safe; enables O(1) prefix lookup then `MessageDigest.isEqual` | bcrypt (needed for low-entropy passwords; here it only adds latency to the hot auth path) |
| **HTTP+SSE** transport (`/mcp` stream + `/mcp/message`) | The only transport Spring AI 1.0.3 / MCP SDK 0.10.0 ship; clients reach it via `mcp-remote` | Streamable HTTP (not available on the pinned version — see the ADR) |
| **Curated** write surface (manual records + resync only) | An AI app should never initiate credential/auth flows or touch admin/MFA/export | Expose the full REST surface as tools (uncontrolled blast radius) |
| Scopes as one **space-delimited column** via `@Convert` | Read in full on every auth, never queried individually; no join table | Join table (a query per auth for data that's always read whole) |
| Per-key + per-member **in-memory Bucket4j** throttles | Single-instance self-host; matches the existing `RateLimitConfig` pattern | Distributed rate store (unwarranted for a self-hosted single instance) |

## Gotchas / Pitfalls

- **`SyncTools` must be named `@Component("picsouSyncTools")`.** Spring AI's
  `McpServerAutoConfiguration` already defines a bean named `syncTools` (the SYNC server's tool-spec
  list). A `@Component` defaulting to `syncTools` collides with it and aborts the context
  (bean-definition overriding is disabled). The other tool components use unreserved default names.
- **Transport is HTTP+SSE, not Streamable HTTP.** The endpoints are `GET /mcp` (SSE stream) and
  `POST /mcp/message` (messages). Both are under `/mcp/**` so Property A's prefix check covers them.
  Do not "upgrade" to Streamable HTTP without bumping Spring AI past 1.0.x (which conflicts with the
  Boot 3.4.9 / Spring 6.2 pins — see `pom.xml`).
- **Property B lives in `UserContext`, not the filter.** The guard is the first statement in
  `getMemberIdOverride()`: if the current `Authentication` is an `AccessKeyAuthentication`, return
  `null` *before* `isAdmin()` can open the `?memberId=` override. Removing it would let an
  admin-owned key impersonate another member via a query param.
- **Scope enforcement is authority-based, not type-based** (`ScopeEnforcementAspect`). A cookie
  principal carries only `ROLE_*`, never a scope, so even if a tool were somehow reached over a
  cookie it would fail the scope check — defense in depth on top of Property A. This needs
  `spring-boot-starter-aop`; the **denial** test fails loudly if proxying is ever off.
- **`tools/list` advertises every tool name regardless of the key's scopes** (names + schemas only,
  no data). A scope a key lacks fails at call time with a clear "missing scope" error. Scope-filtered
  advertisement is a possible later enhancement.
- **`last_used_at` is throttled** (≤ once per key per ~5 min, via a `REQUIRES_NEW` write) and is
  best-effort — a failure there is logged and never breaks authentication.
- **`MCP_ENABLED`** (default `true`) gates the whole server. `SetupFilter` still blocks `/mcp` until
  first-launch setup completes.

## Tests

Backend (H2, `mvn test`):
- `mcp/AccessKeyServiceTest` — generate/format, SHA-256 hashing, validate (unknown / forged / revoked / expired / inactive owner), scope validation, member-scoped revoke.
- `mcp/AccessKeyUsageRecorderTest` — throttled `last_used_at` write.
- `mcp/ScopesTest`, `mcp/ScopeSetConverterTest` — vocabulary + converter round-trip.
- `mcp/ScopeEnforcementAspectTest` — **denial** when the required scope is absent.
- `mcp/tools/McpToolCatalogTest` — **curation guard**: pins the exact advertised tool set (no auth/credential/admin tool).
- `mcp/tools/{Account,Transaction,Goal,Insight,Sync}ToolsTest` — delegation + member-scoping per tool.
- `config/AccessKeyAuthFilterTest` — Property A (key on `/api/**` ⇒ not authenticated; on `/mcp` ⇒ authenticated), Property C (scope authorities only), throttle 429.
- `service/UserContextTest` — Property B (`AccessKeyAuthentication` ⇒ override returns `null`, even for an admin-owned key).
- `controller/AccessKeyControllerTest` — create/list/revoke, one-time secret, unknown-scope 400, member isolation, create throttle.
- `model/AccessKeyTest` — `isUsable` (revoked / expired / live).

Frontend (`bunx vitest run`):
- `features/accessKeys/scopes.test.ts` — scope grouping, i18n-key mapping, and a **vocabulary guard** asserting the frontend list equals backend `Scopes.ALL`.
- `features/accessKeys/status.test.ts` — `keyStatus` (revoked > expired > active, boundary at "now").

## Links

- ADR: [Access-key auth + embedded MCP server](../decisions/2026-06-05-access-key-auth-and-embedded-mcp.md)
- Related: [Multi-account family system](./multi-account-family.md), [2FA (TOTP) and Remember Me](./mfa-and-remember-me.md), [CORS & cookie security](./security-cors-cookies.md)
