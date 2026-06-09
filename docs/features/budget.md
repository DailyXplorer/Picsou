# Feature: Budget & Cashflow

> Last updated: 2026-06-09

## Context

Picsou tracked *net worth* (balances, goals) but did not pilot *spending*: Enable Banking
synced only account balances, and `Transaction.category` was a free-form string filled by the
Finary import alone. The Budget module turns categorized transactions into the pivot for the
spending views — overview, spending flow, recurring charges, envelopes, and savings allocation —
fed by Enable Banking transaction ingestion with a 100% manual fallback (the module works with no
synced bank at all).

The 1.1.0 redesign (see ADR [2026-06-09](../decisions/2026-06-09-merchant-kb-and-budget-ia.md))
makes the module **zero-config and "Apple-like"**: every synced transaction is categorized
automatically by *brand* against an embedded, offline knowledge base — before the user tags a
single thing — and the single 7-tab page became a nested-route section with a clean information
architecture.

## How it works

Everything still hangs off one pivot: the **categorized transaction**. The views are
aggregations over the `transaction` table sliced by the **pay cycle** and the category
**kind** (`INCOME` / `EXPENSE` / `TRANSFER`). What changed in 1.1.0:

1. **Every transaction gets a clean canonical name** (`merchant_label`) and a **brand link**
   (`merchant_brand_id`), stamped on ingestion regardless of whether a category is assigned.
2. **Categorization is automatic by brand**, with a strict, never-inverted precedence.
3. **Categories form a tree** (`parent_id`) and carry a stable `slug` — the join key between the
   global brand KB and a member's own categories.
4. **The UI is a nested-route section** (`/budget/*`) instead of one tabbed page.

### Information architecture (`/budget/*`)

A single `BudgetLayout` (segmented sub-nav on desktop, bottom bar on mobile — same pattern as
`/setup`) owns an `<Outlet/>` over nested routes (`app/routes.tsx`, nav in
`pages/budget/budget-nav.ts`):

| Route | Page | Content |
|-------|------|---------|
| `/budget` (index) | `BudgetOverviewPage` | Hero "left to spend", mini-flow, Review banner (only if items), upcoming subscriptions, top categories |
| `/budget/spending` | `SpendingPage` | Cashflow **flow diagram**: Sankey ≥ `md`, Flow Bars < `md` |
| `/budget/spending/:categoryId` | `CategoryDetailPage` | Per-category drill: transactions with `MerchantAvatar` |
| `/budget/subscriptions` | `SubscriptionsPage` | Recurring series **v2**: auto-confirm + runtime/price badges (`SubscriptionCard`), "what changed" feed + undo (`ActivityFeed`), upcoming agenda |
| `/budget/envelopes` | `EnvelopesPage` | Envelopes + allocation; a parent envelope rolls up its whole subtree (M4) |
| `/budget/review` | `ReviewPage` | Uncategorized inbox — **contextual, not a permanent destination** |
| `/budget/settings` | `BudgetSettingsPage` | Category management, pay cycle, logo opt-in |

> The drill route is keyed by **`categoryId`, not slug**: user-created categories have no slug
> (only seeded defaults do), so the id is the only reliable identifier.

Apple principle: **Review is a nudge, not a tab**. It surfaces as a banner on the Overview only
when there are items to correct; in the ideal "grandmother" case there is nothing to review.

### Zero-config categorization

The elegant lever already existed: `CategorizationService.apply()` **never overrides** an existing
category. The offline brand KB therefore slots in as a pure **fallback** after the rule engine,
touching neither the engine nor its invariant. Precedence, highest first:

```
USER rule  >  learned AUTO rule  >  brand KB fallback  >  uncategorized
```

The pipeline for one transaction is `CategorizationService.autoCategorize`:

1. **`enrich(tx)`** — `MerchantNormalizer.normalize(...)` derives the clean `merchant_label`;
   `MerchantKnowledgeBase.match(...)` stamps `merchant_brand_id`. **Always runs**, even if the
   transaction ends up uncategorized.
2. **Existing category?** → leave it (and the enrichment) alone — the `categoryRef != null` guard.
3. **Member's USER/AUTO rules** (`apply`) — first match by priority wins.
4. **Brand fallback** (`applyBrandFallback`) — resolve the matched brand's `default_category_slug`
   against the member's `categoriesBySlug`. **No per-member `BRAND` rows are ever written** — the
   KB is consulted directly in memory, so a member who never wrote a rule is still fully
   categorized.

`recategorizeUncategorized(member)` re-runs the whole pipeline over everything still uncategorized
— useful after a new rule, a fresh sync, or a **KB version bump** (`budget_settings.kb_version`).
It deliberately has no empty-rules early-out: the brand KB alone categorizes a rule-less member.

### Merchant normalization & the knowledge base

- **`MerchantNormalizer`** (`service/budget/MerchantNormalizer.java`) — a **pure, static,
  Spring-free** class, the single piece of intelligence behind clean names *and* brand matching.
  `normalize(counterparty, description)` strips the payment-processor wrapper (`PAYPAL *…`,
  `SUMUP *…` — keep what follows the last `*`), leading transaction-type noise (`CB`, `ACHAT`,
  `PRLV SEPA`, `VIREMENT`…), card/reference digit runs, date fragments and stray punctuation, then
  title-cases the result. `matchKey(label)` produces a lower-cased, **accent-stripped**,
  whitespace-collapsed key so matching never depends on casing or accents
  (`MCDONALD'S → mcdonalds`). 100% unit-tested in isolation.
- **`MerchantKnowledgeBase`** (`service/budget/MerchantKnowledgeBase.java`, `@Component`) — loads
  `merchant_brand` + `merchant_alias` **once** at startup into an **immutable `Snapshot`**
  published through a single `volatile` reference; `reload()` (also `@PostConstruct`) builds a new
  snapshot and swaps it atomically (a KB-version bump can call it). **Zero per-transaction I/O.**
  `match(matchKey)` tries multi-word **PHRASE** aliases first (longest pattern first, so
  `carrefour market` beats a bare `carrefour`), then single **WORD** aliases against each token,
  using word-boundary containment so `paul` ≠ `paula`.

### Schema (Flyway, member-owned where applicable)

- **`V36__budget_categorization_foundation.sql`** — shared foundation:
  - `category` `+ parent_id BIGINT` (self-FK `ON DELETE SET NULL`), `+ slug VARCHAR(60)`; unique
    index `(member_id, slug) WHERE slug IS NOT NULL`, index `(member_id, parent_id)`; backfills
    slugs on the seeded default categories.
  - `transaction` `+ merchant_label VARCHAR(255)`.
  - `budget_settings` `+ kb_version INT` (per-member KB gate), `+ logo_fetch_enabled BOOLEAN NOT
    NULL DEFAULT false` (logos are opt-in, off by default).
- **`V37__merchant_knowledge_base.sql`** — **global** (not member-scoped) brand KB:
  - `merchant_brand (id, slug UNIQUE, display_name, default_category_slug, color, monogram,
    logo_domain)`.
  - `merchant_alias (id, brand_id FK CASCADE, pattern, match_type VARCHAR)` — `match_type` is
    `WORD | PHRASE`; patterns are stored **pre-normalized** (lower-case, accent/apostrophe-free).
  - `transaction` `+ merchant_brand_id BIGINT` (FK `ON DELETE SET NULL`) + index.
  - **Seeds ~110 FR/EU brands** + a large alias set, mapping each to a `default_category_slug`.

### Cashflow flow diagram (Sankey)

- **`CashflowFlowService`** (`service/budget/CashflowFlowService.java`) — aggregates income sources
  → a central **hub** → expense categories (+ savings / drawdown / uncategorized sentinels) into a
  node/link graph, excluding `TRANSFER` exactly as `CashflowService` does. Conservation invariant
  (test-locked): `intoHub == outOfHub == max(income, expense)`.
- **Endpoints** (member-scoped, under `/api/`):
  - `GET /api/cashflow/flow?period=` → `CashflowFlowResponse` (nodes + links) — `CashflowController`.
  - `GET /api/spending/by-category?period=` → ranked expense list — `SpendingController`.
  - `GET /api/spending/category/{categoryId}?period=` → per-category drill — `SpendingController`.
- **Frontend**: `CashflowSankey` (Recharts `Sankey`, OKLCH `--chart-*` tokens) ≥ `md`; `FlowBars`
  (envelope-style progress bars) < `md`. They swap by **conditional mount** (not CSS hide) because
  Recharts `ResponsiveContainer` measures 0×0 inside `display:none`. Pure mapping logic lives in
  `components/shared/flow-utils.ts` (unit-tested), keeping the component files Fast-Refresh-clean.
- **`MerchantAvatar`** (`components/shared/MerchantAvatar.tsx`) — initial-monogram with a
  **deterministic colour derived from the name**, fully offline; switches to a proxied `<img>` only
  when `logo_fetch_enabled` (M5). Used across every transaction list.

### Sub-categories (the category tree)

Categories nest **one level deep** — a parent with leaf children (e.g. *Logement* → *Loyer*,
*Énergie*). Transactions **only ever attach to a leaf**; a parent is a pure grouping node. That
single rule is what keeps every total honest.

- **Settings** (`pages/budget/ManageTab.tsx`) — `CategoryForm` carries a *parent* `<select>` listing
  same-kind root categories; the list renders children indented under their parent with a
  *sub-category* badge. The selector is **disabled once a category already has children** (a parent
  can't become a child), mirroring the server's two-level cap.
- **Structural invariants** (enforced in `CategoryService`, test-locked in `CategoryServiceTest`):
  a parent and its children **share the same `kind`**; a category **cannot become its own ancestor**,
  and a parent that has children cannot itself be re-parented (so there are never grandchildren);
  **archiving a parent cascades to its children** (un-archiving likewise). `parent_id` is a self-FK
  `ON DELETE SET NULL`, so deleting a parent re-roots its children rather than cascade-deleting them.
- **The spending breakdown stays leaf-based** (`CashflowFlowService.spendingByCategory`). The server
  aggregates **strictly per leaf** and annotates each row with `parentId/parentName/parentColor`; the
  **client** (`SpendingPage.buildDisplay`) groups leaves under their parent and ranks each group at
  its rolled-up total. There is exactly one row per leaf, so no euro is ever counted under both a
  parent and a child — this is the double-counting firewall.
- **Parent drill** (`CashflowFlowService.categoryDetail`, `pages/budget/CategoryDetailPage.tsx`) is
  the *one* spending path that sums a subtree: anchored on a single category, it queries
  `category_id IN (parent + children)` and returns a per-child rollup (`children: ChildSpend[]`) above
  the subtree-wide transaction list; each child row drills one level deeper. A leaf drill has no
  children and falls back to the flat list.
- **Parent envelopes roll up** (`BudgetService.toResponse`, badge in `EnvelopesTab.tsx`). `rollup` is
  **computed on read** (`!children.isEmpty()`), so an envelope on a parent automatically scores its
  whole subtree the moment a child exists — no stored flag. To stop a child's spend being counted
  twice, `assertNoEnvelopeOverlap` forbids a parent **and** any of its children both holding an
  envelope.

No migration was needed: `category.parent_id` + `slug` shipped in `V36` (M0) as foundation; M4 made
the tree user-facing across Settings, the Spending breakdown, the drill, and Envelopes.

### Recurring v2 — detection, auto-confirm & the activity feed

The pre-1.1.0 detector drifted on the raw `counterparty` and never auto-acted. M3 rebuilds it
around the canonical `merchant_label` identity and lets it **confirm high-confidence series
silently**, with a safety net so silent ≠ unexplained.

- **`RecurringDetectionService`** (rewritten, pure/static core) — `analyse(transactions)` groups
  by normalized merchant identity (not raw counterparty), then scores a `Candidate`:
  `confidence = 0.45·regularity + 0.35·amountStability + 0.20·volume`. Amounts are classified
  `FIXED` (≤ 0.15 spread) / `VARIABLE` (≤ 0.40) / `UNSTABLE` (rejected). A series **auto-confirms**
  only with **≥ 3 occurrences, regular intervals, FIXED amount, and confidence ≥ 0.80**. `upsert`
  finds by `findByMemberIdAndLabelIgnoreCase` (the `(member_id, lower(label))` unique index),
  **never resurrects an `IGNORED` series**, auto-confirms only what is still `SUGGESTED`, and on a
  price step stamps `previousAmount` + `priceChangedAt`. `linkTransactions` finally populates
  `transaction.recurring_series_id` (previously always null).
- **Price-change detection** — `isPriceChange(prev, curr)` fires only on a step that is **both**
  ≥ 0.01 absolute **and** > 5% relative, so cent-level drift on a "fixed" subscription stays quiet.
- **Runtime status is computed, never stored** — `recurring_status` is a native PG enum
  (append-only), so `LATE` / `DUE_SOON` / `SCHEDULED` are derived in `RecurringSeriesResponse.from(s,
  today)` from `nextDueDate` (DUE_SOON window = 7 days). Same for the DTO-only
  `RecurringRuntimeStatus` / `RecurringActivityType` enums.
- **Activity feed (the safety net)** — `RecurringSeriesService.activity(member, today)` derives a
  newest-first "what changed" list from series state over a 60-day lookback (no new table): a recent
  `priceChangedAt` emits a `PRICE_CHANGE` entry, else a recent silently-auto-confirmed `CONFIRMED`
  series emits an `AUTO_CONFIRMED` entry (price change preferred; one entry per series). `undo(id)`
  is **context-aware and idempotent**: a price change is *acknowledged* (clear `previousAmount` +
  `priceChangedAt`, keep the new amount); a silent auto-confirm is *rejected* (→ `IGNORED`, so
  re-detection won't re-confirm it).
- **Endpoints** (member-scoped): `GET /api/recurring` (now carries the v2 fields + runtime status),
  `GET /api/recurring/activity`, `POST /api/recurring/{id}/undo`, plus the existing
  detect/confirm/ignore/CRUD.
- **Frontend**: `SubscriptionCard` renders the v2 signals (runtime chip, "variable amount" tag,
  silent-auto-confirm note with the confidence %, price-from→to line) over the existing
  confirm/ignore/delete actions; `ActivityFeed` renders the feed and **nothing at all when empty**
  (Apple principle — no permanent review chore). Both are mobile-responsive via `flex-wrap`. New
  TanStack hooks `useRecurringActivity` / `useUndoRecurring` invalidate `['budget','activity']`
  (also swept by `useDetectRecurring`).

### Key files

**Foundation (ingestion + categorization)**
- `model/Category.java` (`+ parentId` self-`@ManyToOne`, `+ slug`), `model/Transaction.java`
  (`+ merchantLabel`, `+ merchantBrandId`), `model/CategorizationRule.java`,
  `model/BudgetSettings.java`, `model/CategoryKind.java`
- **New**: `model/MerchantBrand.java`, `model/MerchantAlias.java` (+ Spring Data repos)
- `service/budget/MerchantNormalizer.java` (pure), `service/budget/MerchantKnowledgeBase.java`
  (`@Component`, in-memory snapshot)
- `service/budget/CategorizationService.java` — `autoCategorize` (enrich → rules → brand fallback),
  `learnRule`, `recategorizeUncategorized`, `categoriesBySlug`
- `service/budget/CategoryService.java` — CRUD + `ensureSeeded(member)` (seeds defaults + slugs);
  categories are archived, never deleted
- Ingestion: `service/SyncService.java` (dedup, then `autoCategorize`),
  `service/ManualTransactionService.java`

**Spending / flow:** `service/budget/CashflowFlowService.java`, `controller/CashflowController.java`
(`/flow`), `controller/SpendingController.java`, DTOs `CashflowFlowResponse`,
`SpendingByCategoryResponse`, `SpendingDetailResponse`.

**Recurring v2** (M3): `model/RecurringSeries.java` (+ `confidence`, `amountMin/Max`, `variable`,
`previousAmount`, `priceChangedAt`, `autoConfirmed`); `service/budget/RecurringDetectionService.java`
(identity/auto-confirm rewrite), `service/budget/RecurringSeriesService.java` (activity + undo +
runtime mapping), `controller/RecurringController.java`; DTOs `RecurringSeriesResponse` (extended),
**new** `RecurringActivityResponse`, `RecurringActivityType`, `RecurringRuntimeStatus`;
`repository/RecurringSeriesRepository.java`; migration `V38__recurring_v2.sql`.

**Envelopes / Allocation** (pre-1.1.0 logic under the new IA; parent-envelope subtree rollup +
overlap guard added in M4): `model/Budget.java` + `BudgetService` + `BudgetController`;
`service/budget/CashflowService.java`; `service/budget/AllocationService.java` + `AllocationController`.

**Sub-categories** (M4): `model/Category.java` (`parentId` self-FK), `service/budget/CategoryService.java`
(tree invariants, archive cascade), `CashflowFlowService` (leaf-based annotation + parent drill),
`BudgetService` (rollup); frontend `pages/budget/{ManageTab,SpendingPage,CategoryDetailPage,EnvelopesTab}.tsx`.

**Frontend:** `pages/budget/BudgetLayout.tsx` + `budget-nav.ts` + the nested pages above;
`pages/budget/{SubscriptionCard,ActivityFeed}.tsx` + `budget-meta.ts` (runtime/activity lookups);
`features/budget/{api,hooks}.ts` (TanStack Query, cascade invalidations rooted at `['budget']`);
`components/shared/{MerchantAvatar,CashflowSankey,FlowBars,flow-utils}.ts(x)`;
`types/api.ts`; demo mocks in `demo/data/budget.ts` + `demo/index.ts`.

### Flow

```
Enable Banking sync ─▶ SyncService.fetchTransactions ─▶ dedup ─▶ persist (isManual=false)
                                                                      │
                              CategorizationService.autoCategorize    │
                              enrich (label + brand) → rules → KB      │
                                                                      ▼
                  transaction (category_id, merchant_label, merchant_brand_id, counterparty)
                       │            │             │            │             │
                   Overview      Spending      Envelopes   Allocation    Recurring
                   (left to     (flow Sankey /  (cycle      (stock+flux)  (detect)
                    spend)       Flow Bars)      spent)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Embedded **offline** brand KB | Zero-config auto-categorization without ML; privacy-preserving for a self-hosted app | External / ML categorization service (ADR 2026-06-02) |
| KB as a **direct fallback** (no stored `BRAND` rows) | No per-member row proliferation; precedence preserved by run-order + the `categoryRef != null` guard alone | A `RuleSource.BRAND` rule per member-merchant |
| `enrich` always stamps label + brand | Clean names & brand links are universal, even for uncategorized transactions | Stamp only when categorized |
| In-memory KB snapshot, `volatile` swap | Thread-safe, zero per-transaction I/O, hot-reloadable on version bump | Query the DB per transaction |
| Category **tree** (`parent_id`) + `slug` | Sub-categories; stable join key between global brands and member categories | Flat category list / match on names |
| **Leaf-only** spend aggregation + client-side parent grouping | One row per leaf can't double-count under both parent and child; the client groups and ranks by rolled-up total | Server-side subtree rollups for the breakdown |
| Nested-route IA (`BudgetLayout`) | Clean, scalable navigation; Review becomes contextual | Single 7-tab page |
| Sankey ≥ `md`, Flow Bars < `md` (conditional mount) | Sankey is unreadable on phones; `ResponsiveContainer` is 0×0 under `display:none` | One chart for all sizes / CSS hide |
| Configurable `cycleStartDay` (1–28) | Budgets track the pay cycle, not the calendar month | Fixed calendar month |
| `CategoryKind` pivot (INCOME/EXPENSE/TRANSFER) | Transfers between own accounts must not count as spend/income | Single flat list |
| **Silent auto-confirm** of high-confidence recurring series | Zero-config "grandmother" UX — nothing to triage in the ideal case | Always leave detections as `SUGGESTED` for manual review |
| Safety net: activity feed + per-item undo + price alerts | Silent ≠ unexplained; every silent action is reversible and surfaced | Trust the math with no recourse |
| Recurring identity = canonical `merchant_label` | Stable grouping; raw `counterparty` drifts (card digits, cities, dates) | Group on raw bank string |
| Runtime status (`LATE`/`DUE_SOON`) computed in the DTO | `recurring_status` is an append-only PG enum; transient states aren't persisted | Store every transient state as an enum value |

## Gotchas / Pitfalls

- **Lazy seeding writes from a read path — the read method must be writable.**
  `CategoryService.findAll`/`ensureSeeded` and `BudgetSettingsService.get` create default rows on
  first access. The services are `@Transactional(readOnly = true)` at class level, and the seed
  runs via an *internal* call Spring's proxy **cannot intercept**, so it inherits the caller's
  transaction. The read methods are annotated `@Transactional` (read-write) on purpose, and seed
  helpers use `Propagation.REQUIRES_NEW` so external callers in a read-only transaction still get a
  writable one. `CategorizationService.loadContext`/`categoriesBySlug` are `@Transactional` for the
  same reason (they may trigger `ensureSeeded`). Drop an annotation and the first load 500s with
  Postgres `25006 cannot execute INSERT in a read-only transaction`. **H2 (the test profile)
  ignores read-only transactions and hides this — only the Dockerized Postgres stack surfaces it.**
- **The brand KB is read-only at match time** but `recategorizeUncategorized` *writes* — it must
  run in a writable transaction (same Postgres-only failure mode as above).
- **Sub-category totals never double-count because aggregation is leaf-only.** `spendingByCategory`
  returns one row per leaf (annotated with its parent) and the client groups; only single-anchored
  subtree ops — the parent drill (`category_id IN (parent + children)`) and a parent envelope's
  rollup — ever sum a subtree, and `assertNoEnvelopeOverlap` stops a parent *and* its child both
  holding an envelope. Add any new "spending by parent" query the same way (anchored on one node) or
  totals will drift. The tree is **two levels only** (`CategoryService` rejects grandchildren), and a
  transaction always lands on a **leaf** — never a parent.
- **`categoryRef != null` is the only thing protecting a user's choice.** Every categorization path
  goes through `apply`/`autoCategorize`, which short-circuits on an existing category. Don't add a
  path that assigns a category without that guard.
- **KB matching keys are pre-normalized.** Alias patterns in `merchant_alias` are stored
  lower-cased and accent/apostrophe-free; they are matched against `MerchantNormalizer.matchKey`,
  *not* the raw bank string or the display label. PHRASE aliases are tried before WORD so a
  sub-brand (`uber eats`) outranks its parent (`uber`).
- **Transfers are excluded** from cashflow/flow/envelopes but **feed allocation** — a transfer is a
  move between your own accounts, not spending.
- The cycle is **not** the calendar month; `cycleStartDay` 28 clamps to a short month's last day.
- Recurring detection needs **≥3 regular occurrences** with a stable amount; auto-confirm
  additionally requires a **FIXED** amount class and **confidence ≥ 0.80**. Identity is the
  canonical `merchant_label`, deduped via the `(member_id, lower(label))` unique index.
- **`IGNORED` is durable on purpose.** Re-detection (`upsert`) skips `IGNORED` series so a user's
  rejection — including an undone auto-confirm — is never silently re-confirmed on the next sync.
- **Activity-feed recency keys on domain `LocalDate`s, not audit `Instant`s.** `AuditableEntity`
  exposes `createdAt`/`updatedAt` as read-only (no setters, populated only by the JPA auditing
  listener), so they can't be set in pure-Mockito unit tests. The feed therefore keys recency on
  `lastSeenDate` / `priceChangedAt` (settable domain fields) — keep it that way or the service tests
  can't construct fixtures.
- **`undo` is context-aware *and* idempotent.** It branches on series state (price change →
  acknowledge; silent auto-confirm → reject), not on a client-supplied action, so a double-tap or a
  stale client can't drive it into a wrong state.

## Tests

- `MerchantNormalizerTest` — pure, real-world cases (`PAYPAL *SPOTIFY`, `CB CARREFOUR … PARIS`, …)
- `MerchantKnowledgeBaseTest` — PHRASE-before-WORD precedence, word-boundary matching, reload
- `CategorizationServiceTest` — brand fallback **after** USER/AUTO, `categoryRef` guard never
  overridden, `merchant_label` always stamped
- `CashflowFlowServiceTest` — hierarchy, conservation invariant, `TRANSFER` exclusion, **leaf rows
  annotated with their parent**, and **parent-drill child rollup** (incl. a child with zero spend)
- `CategoryServiceTest` — tree invariants: parent attach/reparent, same-`kind` rule, two-level cap
  (no grandchildren), archive/un-archive cascading to children
- `RecurringDetectionServiceTest` (rewritten for v2) — stable identity, confidence math at the
  auto-confirm threshold, FIXED/VARIABLE classification, price-step detection, `IGNORED` never
  resurrected, `recurring_series_id` populated
- `RecurringSeriesServiceTest` — runtime status relative to "today", activity feed (newest-first,
  price-change preferred, stale/user-confirmed excluded), context-aware undo
- `BudgetCycleTest`, `BudgetServiceTest` (incl. **parent-envelope subtree rollup** + the
  **overlap guard** rejecting a parent/child both budgeted), `CashflowServiceTest`,
  `AllocationServiceTest`, `SyncService` ingestion tests
- Frontend: `flow-utils.test.ts`, `MerchantAvatar.test.tsx`, `features/budget` hooks via
  `bunx vitest run`
- **Postgres write-on-read test** (planned M5): seed/recategorize in a read-only transaction (H2
  masks the rejection)

## Links

- Related ADRs: [merchant-kb-and-budget-ia](../decisions/2026-06-09-merchant-kb-and-budget-ia.md)
  (1.1.0 redesign), [budget-cycle-and-categorization](../decisions/2026-06-02-budget-cycle-and-categorization.md)
  (original foundation)
- Updated: [bank-sync](./bank-sync.md) (transaction ingestion now included)
