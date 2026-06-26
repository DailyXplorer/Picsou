# Feature: Optional AI transaction categorization

> Last updated: 2026-06-26

## Context

The deterministic categorization pipeline (rules + offline brand knowledge base) only categorizes
merchants it already knows; the long tail of unknown merchants stays in the inbox for manual
sorting. This feature adds an **opt-in** LLM categorizer that absorbs that long tail, with a
swappable provider (local Ollama / external OpenAI-compatible / Claude) and member-tunable
autonomy. It is **off by default** and never overrides a rule or manual choice.

## How it works

AI is a **fallback layer**, not a replacement. The deterministic pipeline runs first and always
wins; the LLM is only asked about transactions still uncategorized afterwards, and only when the
member has enabled it. It runs as a **batch pass** (not inline during sync, which must stay fast and
offline), triggered on demand from the inbox.

For each uncategorized transaction the model is given the member's **own category slugs** (its
taxonomy), a few **few-shot examples** from the member's recent manual choices, and the cleaned
`merchantLabel` + amount. It returns a slug + confidence as **structured JSON**. The member's
`AiCategorizationMode` and confidence threshold then decide what happens:

- `AUTO_ALL` — always set the category.
- `AUTO_HIGH_CONFIDENCE` (default) — set it when `confidence ≥ threshold`, else store a suggestion.
- `SUGGEST` — only store a suggestion.

A stored suggestion lives on the transaction (`ai_suggested_category_id` + `ai_confidence`) so the
inbox can render "AI: Transport · 92%" and pre-select that category — accepting is one click.

### Key files

- `backend/.../port/TransactionCategorizerPort.java` — the provider-agnostic port (+ its records)
- `backend/.../adapter/SpringAiCategorizer.java` — Spring AI `ChatClient` implementation
- `backend/.../adapter/NoopCategorizer.java` — default when no provider is configured
- `backend/.../config/AiCategorizationConfig.java` — picks the bean from `ObjectProvider<ChatModel>`
- `backend/.../service/budget/CategorizationService.java` — `aiCategorizeUncategorized(memberId)`
- `backend/.../controller/TransactionCategorizationController.java` — `POST /api/transactions/categorize-ai`
- `backend/.../resources/db/migration/V41__ai_categorization.sql` — settings + suggestion columns
- `frontend/.../pages/budget/ManageTab.tsx` — `AiCategorizationCard` (toggle + mode + sensitivity)
- `frontend/.../pages/budget/CategorizeTab.tsx` — suggestion chip + "Categorize with AI" button

### Flow

```
sync / import ─► deterministic pipeline (rules → brand KB) ─► still uncategorized?
                                                                     │
member clicks "Categorize with AI" ──► POST /transactions/categorize-ai
                                                                     │
   for each uncategorized tx: SpringAiCategorizer.categorize(label+amount, member slugs, few-shot)
                                                                     │
        confidence ≥ threshold (or AUTO_ALL) ── yes ─► set categoryRef
                                                └─ no ─► store suggestion (inbox chip)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Spring AI `ChatClient` | Already a dependency (MCP); one interface for all 3 providers | Hand-rolled WebClient adapters; LangChain4j |
| Fallback over uncategorized only | Preserves the precise deterministic pipeline; minimizes inference | LLM as the whole engine |
| Choices keyed by category `slug` | Stable round-trip + human-readable labels for a small model | Keying by category id |
| `spring.ai.model.chat=none` default | Boots with no model, AI fully OFF, provider opt-in | Always-on / a separate feature flag |
| Suggestion stored on the transaction | Inbox renders without re-running inference each load | Recompute on every inbox load |

## Gotchas / Pitfalls

- **Never inline in sync.** `autoCategorize` runs per-transaction during import and must stay
  offline/fast — the LLM only runs in the separate `aiCategorizeUncategorized` batch.
- **The `categoryRef != null` guard is sacred.** The batch only ever iterates the already-
  uncategorized set; a model answer for a slug the member doesn't have is ignored.
- **Provider selection is config, not code.** Set `AI_CATEGORIZATION_PROVIDER` =
  `ollama|openai|anthropic` plus that provider's base-url / api-key / model. All non-chat model
  types are pinned to `none` so adding the three starters never spins up an unused client at boot.
- **Do not bump Spring AI past 1.0.x** (targets Boot 3.4 / Spring 6.2; 1.1.x conflicts — see pom).
- **Only slugged categories are offered** to the model in v1 (the default taxonomy); user-created
  categories without a slug are not AI-targetable yet.

## Tests

- `CategorizationServiceTest` — `aiCategorize_*` cases: each mode, the threshold boundary, unknown
  slug ignored, model abstain ignored, disabled = no-op (and never calls the model).
- `BudgetSeedWriteOnReadPostgresTest` — boots the full context with the three provider starters
  present and runs `V41` on real Postgres (verifies `none` keeps startup clean).
- `CategorizeTab.test.tsx` — suggestion pre-selects the dropdown + renders the chip; the
  "Categorize with AI" button is gated on the setting.

## Links

- ADR: [2026-06-26-ai-transaction-categorization.md](../decisions/2026-06-26-ai-transaction-categorization.md)
- Related: [budget-rules.md](./budget-rules.md) Phase 3 (`RuleSuggestionPort`, a sibling idea)
