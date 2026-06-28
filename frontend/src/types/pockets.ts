// ─── Revolut pockets DTOs (mirrors backend RevolutPocketController DTOs) ───────

/**
 * A Revolut pocket detected from the bank sync that has not yet been given a
 * user-friendly name. The `transfers` list lets the user recognise which pocket
 * they are looking at (e.g. "the one that received 500 € on 2026-01-15").
 *
 * Backend route: GET /api/revolut-pockets/unnamed
 * Backend DTO:   UnnamedPocketResponse
 */
export interface UnnamedPocket {
  /** Account id of the pocket (same as Account.id for the pocket sub-account). */
  accountId: number
  /** Auto-generated placeholder, e.g. "Pocket ••89abfe" (last 6 of the uuid). */
  placeholderName: string
  /** Account id of the parent Revolut wallet. */
  parentAccountId: number
  /** Inbound transfers from the parent wallet — shown so the user can recognise
   *  which physical pocket this is. */
  transfers: Array<{ amount: number; date: string }>
}

/**
 * One name suggestion for a pocket, derived from a Revolut CSV export.
 * The backend reconciles names to pockets by matching transfer amount + date.
 *
 * Part of the CsvNamingResponse envelope (see below).
 */
export interface CsvNameSuggestion {
  /** Account id of the pocket this suggestion targets. */
  accountId: number
  /** Name derived from the Revolut CSV export. */
  suggestedName: string
  /** True when more than one pocket matches the amount+date heuristic — the user
   *  must confirm manually; suggestions are NEVER auto-applied. */
  uncertain: boolean
}

/**
 * Envelope returned by POST /api/revolut-pockets/csv-naming.
 * Backend DTO: CsvNamingResponse
 */
export interface CsvNamingResponse {
  suggestions: CsvNameSuggestion[]
}
