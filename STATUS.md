# STATUS: OAuth2 + Budget MCP tools

Brief: `docs/briefs/oauth2-budget-mcp.md`. All work is inside `backend/src/main/java/com/picsou/mcp/`
and its tests, plus the two lines in `McpToolConfig` that wire the new components — nothing else touched.

## Done

- **Scopes.java** — all 13 new scopes added to `Scopes.ALL` (2 OAuth2 + 11 Budget).
- **OAuth2 tools** (`OAuth2Tools`, `@Component`) — 2 of the 3 requested tools:
  - `get_oauth2_configuration` (`oauth2:discover`) — issuer/authorize/token/JWKS endpoints, client id,
    PKCE-required flag. Built from the real `AuthorizationServerSettings` + `OAuthClientProperties` beans.
  - `get_oauth2_session_status` (`oauth2:session-status`) — the calling access-key's own name, scopes,
    created/last-used/expiry timestamps (from `AccessKeyService.list` + `AccessKeyAuthentication.getKeyId()`),
    plus the owning member's MFA status (`MfaService.isEnabled`). Proven to never return another key's row.
  - `request_oauth2_token` — **not built**, see "Deliberately not built" below.
- **Budget tools** (`BudgetTools`, `@Component`) — all 11 requested scopes, 22 tools: categories
  (list/get/create/update/delete), rules (list/get/create/update/delete/apply-to-transactions),
  transactions (list with date range + optional category or uncategorized-only filter, update-category),
  recurring (list/get, read-only), envelopes (list/get/create/update/delete/set-allocation), and a
  composed dashboard (cashflow + top-5 categories + 30-day upcoming subscriptions).
- **McpToolConfig** — both components wired into the single `ToolCallbackProvider` bean.
- **Tests, all green** (`mvn test`, 778 run — see "Pre-existing failures" below):
  - `OAuth2ToolsTest` (4), `BudgetToolsTest` (26) — delegation + not-found + member-isolation cases.
  - `McpToolCatalogTest` — updated `EXPECTED_TOOLS`, `TOOL_CLASSES`, and `buildProvider()`; added a
    reviewed exception to the auth/credential name guard for the two `oauth2:`-prefixed tool names
    (they legitimately contain "auth" as a substring of "oauth2", not the forbidden operation).
  - `ScopesTest` — asserts the full 23-scope allowlist.
  - `OAuth2AndBudgetScopeDenialTest` (new, 13 parameterized cases) — every new scope denies when
    absent, mirroring `ScopeEnforcementAspectTest`'s pattern.

## Deliberately not built: `request_oauth2_token`

The brief assumes an "OAuth2 service/controller" that this tool could delegate to, scoped to the key
owner. That doesn't exist, and building a plausible substitute would be actively misleading:

- The authorization server (`AuthorizationServerConfig`) is Spring's stock
  `OAuth2AuthorizationServerConfigurer` serving **one** first-party **public** client (`picsou-ios`,
  `ClientAuthenticationMethod.NONE`, PKCE mandatory, no client secret) for the native iOS app's
  custom-scheme redirect (`picsou://callback`). There is no per-user or per-MCP-client registration,
  and no service layer — the token endpoint is auto-configured by the Spring Security AS starter.
- The token endpoint only accepts an authorization code (+ PKCE verifier) obtained by a browser
  completing the existing cookie-based login (`CookieBridgeAuthenticationFilter` → SPA login
  redirect), or a refresh token from a prior grant. An MCP tool call is authenticated by a `psk_`
  **access-key**, a completely separate principal type from this flow (Property A: keys authenticate
  only `/mcp/**`, and the AS chain isn't part of `/mcp/**` either way) — it has no code or verifier to
  hand the token endpoint on the caller's behalf, and no legitimate one to mint.
- Even as a bare HTTP proxy to `POST /oauth2/token` (no server-side logic change, just forwarding),
  the tool would have no real caller: a client that already completed the browser PKCE handshake can
  call `/oauth2/token` directly over plain HTTP — it does not need an MCP tool, and does not have an
  access-key yet at that point in the flow to authenticate the MCP call in the first place.

Recommendation: either (a) drop `oauth2:token` from `Scopes.ALL` since no tool will ever honour it —
I left it in per the brief's explicit "13 new scopes" checklist item, but it is currently dead — or
(b) if there's a concrete scenario for claude.ai to mint its own OAuth2 tokens, design it as its own
piece (likely a new registered client + grant type), not a wrapper around the iOS app's flow. Worth
a Chancellerie decision before anyone builds it.

## Simplifications from the brief (all tool-layer choices, no service changes)

- `apply_rule_to_transactions` re-runs the **whole** categorization pipeline over every uncategorized
  transaction (`CategorizationService.recategorizeUncategorized`), not one specific rule over one
  date range — no service method exists for a scoped re-run, and adding one is a service change
  (out of scope per the brief).
- `set_envelope_allocation` changes the money limit only. The `Budget` model has no percentage field;
  it's a thin wrapper over `update_budget_envelope` that keeps the current category.
- `bulk_categorize_transactions` was not built — the brief marked it optional ("if different from
  rule application"), and `update_budget_transaction` + `apply_rule_to_transactions` already cover
  the two real paths (manual single assignment, pipeline re-run).
- `list_budget_transactions`'s `kind` (INCOME/EXPENSE/TRANSFER) filter was dropped — `TransactionResponse`
  doesn't carry the category's kind, only its id/name, and no repository query filters by it.
  `categoryId` + date range + `uncategorizedOnly` are supported.
- `get_budget_dashboard` is composed in the tool layer (`CashflowService.compute` +
  `CashflowFlowService.spendingByCategory` top-5 + `RecurringSeriesService.upcoming` for 30 days) —
  no single backend endpoint backs the Overview page (it's assembled client-side in the UI too).

## Pre-existing failures (not touched, not caused by this work)

`mvn test` on this branch fails 3 tests + 3 errors before and after this change, all outside `mcp/`:
`RevolutPocketServiceTest` (2), `SyncServicePocketTest` (1), `CashflowFlowServiceTest` (3, NPE on
`Transaction.getAccount()` in `categoryDetail`). Verified by stashing this work (`git stash -u`) and
re-running the same three classes — identical failures on the unmodified branch.

## Not done

- Feature note (`docs/features/mcp-budget-oauth2.md`) — left for the orchestrator/documenter per
  the team's usual close-out step, since it should also record the `request_oauth2_token` decision
  above once ratified.
