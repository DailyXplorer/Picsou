export type AccountType =
  | 'LEP' | 'PEA' | 'COMPTE_TITRES' | 'CRYPTO' | 'CHECKING' | 'SAVINGS'
  | 'REAL_ESTATE' | 'LOAN' | 'OTHER'

export interface RealEstateMetadata {
  purchasePrice: number
  purchaseDate: string | null
  surfaceArea: number | null
  address: string | null
  propertyType: string | null
  rentalIncome: number | null
}

export interface DebtInfo {
  linkedAccountId: number | null
  linkedAccountName: string | null
  borrowedAmount: number
  interestRate: number | null
  monthlyPayment: number | null
  lenderName: string | null
  startDate: string | null
  endDate: string | null
  insuranceMonthly: number | null
  fileFees: number | null
}

export interface Account {
  id: number
  name: string
  type: AccountType
  provider: string | null
  currency: string
  currentBalance: number
  currentBalanceEur: number
  lastSyncedAt: string | null
  isManual: boolean
  color: string
  ticker: string | null
  createdAt: string
  realEstate?: RealEstateMetadata
  debt?: DebtInfo
  /** Set for Revolut pocket sub-accounts: the id of the parent Revolut wallet.
   *  Null / absent for regular top-level accounts. */
  parentAccountId?: number | null
  /** Stable external identifier (e.g. Revolut pocket UUID from "To EUR MB:<uuid>").
   *  Null / absent for regular accounts. */
  externalAccountId?: string | null
}

export interface AccountRequest {
  name: string
  type: AccountType
  provider?: string
  currency: string
  currentBalance?: number
  isManual: boolean
  color?: string
  ticker?: string
}

export interface RealEstateMetadataRequest {
  purchasePrice: number
  purchaseDate?: string
  surfaceArea?: number
  address?: string
  propertyType?: string
  rentalIncome?: number
}

export interface DebtRequest {
  linkedAccountId?: number | null
  borrowedAmount: number
  interestRate?: number
  monthlyPayment?: number
  lenderName?: string
  startDate?: string
  endDate?: string
  insuranceMonthly?: number
  fileFees?: number
}

export interface LoanInstallment {
  number: number
  date: string
  capital: number
  interest: number
  insurance: number
  totalPayment: number
  remainingBalance: number
}

export interface LoanSummary {
  totalInstallments: number
  paidInstallments: number
  remainingInstallments: number
  endDate: string | null
  monthlyPayment: number
  monthlyCapital: number
  monthlyInterest: number
  monthlyInsurance: number
  totalCost: number
  totalCapitalCost: number
  totalInterestCost: number
  totalInsuranceCost: number
  fileFees: number
  totalRepaid: number
  capitalRepaid: number
  interestRepaid: number
  insuranceRepaid: number
  remainingBalance: number
  capitalRepaidPct: number
}

export interface LoanScheduleResponse {
  summary: LoanSummary
  schedule: LoanInstallment[]
}

export interface BalanceSnapshot {
  id: number
  date: string
  balance: number
  investedAmount?: number
  createdAt?: string
}

export interface GoalProgress {
  id: number
  name: string
  targetAmount: number
  deadline: string
  createdAt: string
  historyStartMonth: string | null
  accounts: Account[]
  currentTotal: number
  percentComplete: number
  monthsLeft: number
  monthlyNeeded: number
  avgMonthlyContribution: number | null
  isOnTrack: boolean
  surplus: number
}

export interface GoalRequest {
  name: string
  targetAmount: number
  deadline: string
  accountIds: number[]
}

export interface GoalMonthEntry {
  yearMonth: string
  objective: number
  actual: number | null
  manualActual: number | null
  override: number | null
  effective: number | null
}

export interface DashboardData {
  totalNetWorth: number
  totalLiabilities: number
  totalMonthlyPayment: number | null
  netWorthHistory: { date: string; total: number; invested: number; pnl: number }[]
  distribution: {
    accountId: number
    name: string
    color: string
    balanceEur: number
    percentage: number
    accountType: string
    hasHoldings: boolean
  }[]
  liabilities: {
    accountId: number
    name: string
    color: string
    balanceEur: number
    percentage: number
    accountType: string
    hasHoldings: boolean
    monthlyPayment: number | null
    percentPaid: number | null
  }[]
  goalSummaries: GoalProgress[]
}

export interface Institution {
  id: string
  name: string
  bic: string | null
  logoUrl: string | null
  country: string
}

export interface HoldingResponse {
  ticker: string
  name: string | null
  quantity: number
  averageBuyIn: number | null
  currentPrice: number | null
  currentValueEur: number | null
  costBasisEur: number | null
  pnlEur: number | null
  pnlPercent: number | null
  priceUpdatedAt: string | null
}

// --- Security insight (asset type + ETF composition) ---
export type AssetType = 'ETF' | 'STOCK' | 'CRYPTO' | 'UNKNOWN'

export interface WeightedSlice {
  label: string
  percent: number
}

export interface EtfComposition {
  companies: WeightedSlice[]
  countries: WeightedSlice[]
  sectors: WeightedSlice[]
  source: string | null
  asOf: string | null
}

export interface SecurityInsight {
  ticker: string
  assetType: AssetType
  composition: EtfComposition | null
}

export type ExchangeType = 'BINANCE' | 'KRAKEN'
export type ChainType = 'SOLANA' | 'ETHEREUM' | 'BITCOIN'
export type FinaryMappingAction = 'SKIP' | 'MAP_EXISTING' | 'CREATE_NEW'

export interface ExchangeStatus {
  id: number
  exchangeType: ExchangeType
  status: string
  lastSyncedAt: string | null
}

export interface WalletStatus {
  id: number
  chain: ChainType
  address: string
  label: string | null
  lastSyncedAt: string | null
}

export interface TrSessionStatus {
  isActive: boolean
  expiresAt: string | null
}

export interface BoursoSessionStatus {
  isActive: boolean
  expiresAt: string | null
}

export interface BoursoAuthInitResponse {
  processId: string | null
  mfaRequired: boolean
  mfaType: string | null
  contact: string | null
}

export interface FinaryAccountPreview {
  finaryId: string
  finaryName: string
  finaryInstitution: string
  finaryCategory: string
  suggestedType: AccountType
  currentBalance: number
  nativeCurrency: string
  transactionCount: number
}

export interface FinaryPreviewResponse {
  accounts: FinaryAccountPreview[]
  existingPicsouAccounts: Account[]
  totalTransactionCount: number
  fileToken: string
  autoMapped?: boolean
  suggestedMappings?: FinaryAccountMapping[]
}

export interface FinaryConnectionStatus {
  connected: boolean
  sessionId: number | null
  status: string | null
  lastSyncedAt: string | null
  maskedEmail: string | null
}

export interface NewAccountDetails {
  name: string
  type: AccountType
  provider?: string
  currency: string
  color?: string
}

export interface FinaryAccountMapping {
  finaryId: string
  finaryName: string
  finaryCategory: string
  action: FinaryMappingAction
  targetAccountId?: number
  newAccount?: NewAccountDetails
}

export interface FinaryImportRequest {
  mappings: FinaryAccountMapping[]
  fileToken: string
}

export interface ImportedAccountSummary {
  id: number
  name: string
  type: AccountType
  currentBalance: number
  color: string
}

export interface FinaryImportResultResponse {
  accountsCreated: number
  accountsMapped: number
  accountsSkipped: number
  snapshotsCreated: number
  transactionsImported: number
  importedAccounts: ImportedAccountSummary[]
}

export interface FinaryAutoSyncResponse {
  status: 'OK' | 'NEEDS_MAPPING' | 'TOTP_REQUIRED' | 'NOT_CONNECTED'
  accountsSynced: number
  newAccountCount: number
}

export interface Transaction {
  id: number
  date: string
  description: string
  amount: number
  type: string | null
  category: string | null
  nativeCurrency: string
  isManual: boolean
  txType: 'DEPOSIT' | 'WITHDRAWAL' | 'BUY' | 'SELL' | 'DIVIDEND' | 'FEE' | null
  ticker: string | null
  name: string | null
  quantity: number | null
  pricePerUnit: number | null
  /** Clean merchant name derived offline from the raw bank fields (null until enriched). */
  merchantLabel?: string | null
  /** Matched brand id from the offline knowledge base, or null. */
  merchantBrandId?: number | null
}

export interface TransactionRequest {
  date: string          // ISO date "YYYY-MM-DD"
  description: string
  amount: number        // signed: positive=deposit, negative=withdrawal
  txType: 'DEPOSIT' | 'WITHDRAWAL' | 'BUY' | 'SELL' | 'DIVIDEND' | 'FEE' | null
  ticker?: string
  name?: string
  quantity?: number
  pricePerUnit?: number
  currency?: string
}

// ─── Budget & Cashflow module (mirrors com.picsou.dto.*) ─────────────────────

/** Drives cashflow/envelope/allocation behaviour. Transfers feed only allocation. */
export type CategoryKind = 'INCOME' | 'EXPENSE' | 'TRANSFER'
export type RuleMatchType = 'COUNTERPARTY' | 'KEYWORD'
export type RuleSource = 'USER' | 'AUTO'
export type RecurringCadence = 'WEEKLY' | 'BIWEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'YEARLY'
export type RecurringStatus = 'SUGGESTED' | 'CONFIRMED' | 'IGNORED'
/** Computed (never stored) urgency of a series' next due date — drives the late / due-soon badges. */
export type RecurringRuntimeStatus = 'LATE' | 'DUE_SOON' | 'SCHEDULED'
/** The kind of change surfaced in the recurring "what changed" activity feed. */
export type RecurringActivityType = 'AUTO_CONFIRMED' | 'PRICE_CHANGE'
export type AssetClass = 'CURRENT' | 'SAVINGS' | 'INVESTMENT' | 'OTHER'
export type CashflowPeriod = 'CYCLE' | 'YTD'

export interface Category {
  id: number
  name: string
  kind: CategoryKind
  color: string | null
  icon: string | null
  isDefault: boolean
  archived: boolean
  sortOrder: number
  /** Parent category id, or null for a top-level category (one level of nesting only). */
  parentId: number | null
}

export interface CategoryRequest {
  name: string
  kind: CategoryKind
  color?: string
  icon?: string
  sortOrder?: number
  /** Attach as a sub-category of this parent (must share kind, be a root). null/omit = top-level. */
  parentId?: number | null
}

export interface CategorizationRule {
  id: number
  matchType: RuleMatchType
  pattern: string
  categoryId: number
  categoryName: string
  priority: number
  source: RuleSource
}

export interface CategorizationRuleRequest {
  matchType: RuleMatchType
  pattern: string
  categoryId: number
  priority?: number
}

/** Assign a category to a transaction, optionally learning a rule from it. */
export interface CategorizeRequest {
  categoryId: number
  createRule: boolean
}

/** A transaction still missing a managed category (the "to categorize" inbox). */
export interface UncategorizedTransaction {
  id: number
  date: string
  description: string
  amount: number
  type: string | null
  category: string | null
  nativeCurrency: string
  createdAt: string
  isManual: boolean
  txType: 'DEPOSIT' | 'WITHDRAWAL' | 'BUY' | 'SELL' | 'DIVIDEND' | 'FEE' | null
  ticker: string | null
  quantity: number | null
  pricePerUnit: number | null
  categoryId: number | null
  categoryName: string | null
  counterparty: string | null
  /** Clean merchant name derived offline from the raw bank fields (null until enriched). */
  merchantLabel: string | null
  /** Matched brand id from the offline knowledge base, or null. */
  merchantBrandId: number | null
  /** AI-proposed category id pending the member's confirmation, or null when there is no suggestion. */
  aiSuggestedCategoryId: number | null
  /** Self-reported confidence (0–100) attached to the AI suggestion, or null. */
  aiConfidence: number | null
}

/** A monthly envelope with its current-cycle progress (computed on read). */
export interface Budget {
  id: number
  categoryId: number
  categoryName: string
  categoryKind: CategoryKind
  categoryColor: string | null
  categoryIcon: string | null
  monthlyLimit: number
  spent: number
  remaining: number
  percent: number
  overBudget: boolean
  /** True when the category is a parent — `spent` then covers its whole subtree. */
  rollup: boolean
  cycleStart: string
  cycleEnd: string
}

export interface BudgetRequest {
  categoryId: number
  monthlyLimit: number
}

/** How an AI category suggestion is applied. Mirrors the backend AiCategorizationMode enum. */
export type AiCategorizationMode = 'SUGGEST' | 'AUTO_HIGH_CONFIDENCE' | 'AUTO_ALL'

export interface BudgetSettings {
  cycleStartDay: number
  logoFetchEnabled: boolean
  /** Master opt-in for the optional AI categorizer (OFF by default). */
  aiCategorizationEnabled: boolean
  /** How an AI suggestion is applied: suggest-only / auto on high confidence / auto-all. */
  aiMode: AiCategorizationMode
  /** Sensitivity gate (0–100) for AUTO_HIGH_CONFIDENCE. */
  aiConfidenceThreshold: number
  currentCycleStart: string
  currentCycleEnd: string
}

export interface BudgetSettingsRequest {
  cycleStartDay: number
  logoFetchEnabled: boolean
  aiCategorizationEnabled: boolean
  aiMode: AiCategorizationMode
  aiConfidenceThreshold: number
}

/** Live status of an async AI categorization job. */
export interface AiJobStatus {
  running: boolean
  total: number
  processed: number
  applied: number
  suggested: number
  done: boolean
  error: string | null
}

export interface CashflowBucket {
  start: string
  end: string
  label: string
  income: number
  expense: number   // positive magnitude
  net: number
}

export interface CashflowResponse {
  period: CashflowPeriod
  from: string
  to: string
  income: number
  expense: number   // positive magnitude
  net: number
  series: CashflowBucket[]
}

/** Sankey node role — drives colour/position; HUB and SAVINGS/drawdown are synthetic. */
export type FlowNodeType = 'INCOME' | 'HUB' | 'EXPENSE' | 'SAVINGS'

/**
 * One node in the income→budget→expense Sankey. `key` is `cat:<id>` for a real category,
 * or a `__…__` sentinel for a synthetic node (hub, "other income", savings, drawdown,
 * uncategorized, rolled-up tail). Synthetic nodes carry `label`/`color` null and are
 * labelled/coloured on the frontend.
 */
export interface FlowNode {
  key: string
  label: string | null
  color: string | null
  type: FlowNodeType
}

/** A weighted edge: indices into the response's `nodes` array. */
export interface FlowLink {
  source: number
  target: number
  value: number
}

export interface CashflowFlowResponse {
  period: CashflowPeriod
  from: string
  to: string
  income: number
  expense: number   // positive magnitude
  net: number
  nodes: FlowNode[]
  links: FlowLink[]
}

/**
 * One row of the ranked expense breakdown. `categoryId`/`slug`/`name` null = uncategorized.
 * Rows are always leaf-scoped (no double-counting); `parent*` lets the client group a subtree.
 * `parentId` null = a root category or the uncategorized bucket.
 */
export interface CategorySpend {
  categoryId: number | null
  slug: string | null
  name: string | null
  color: string | null
  icon: string | null
  amount: number    // positive magnitude
  count: number
  share: number     // fraction of totalExpense, 0..1 (4 decimals)
  parentId: number | null
  parentName: string | null
  parentColor: string | null
}

export interface SpendingByCategoryResponse {
  period: CashflowPeriod
  from: string
  to: string
  totalExpense: number
  categories: CategorySpend[]
}

/** Per-child rollup shown above the transaction list when drilling a parent. `total` signed. */
export interface ChildSpend {
  categoryId: number
  name: string
  color: string | null
  icon: string | null
  total: number     // signed sum
  count: number
}

/**
 * A single category's transactions over the period (the spending drill page). When the
 * category is a parent, `total`/`count`/`transactions` span its whole subtree and `children`
 * carries the per-child rollup; for a leaf category `children` is empty.
 */
export interface SpendingDetailResponse {
  categoryId: number
  slug: string | null
  name: string
  color: string | null
  icon: string | null
  period: CashflowPeriod
  from: string
  to: string
  total: number     // signed sum
  count: number
  transactions: Transaction[]
  children: ChildSpend[]
}

export interface AllocationStock {
  assetClass: AssetClass
  amount: number
  percent: number
}

export interface AllocationContribution {
  accountId: number
  accountName: string
  assetClass: AssetClass
  color: string | null
  amount: number
}

export interface AllocationResponse {
  period: CashflowPeriod
  from: string
  to: string
  totalStock: number
  stock: AllocationStock[]
  totalContributions: number
  contributions: AllocationContribution[]
}

export interface RecurringSeries {
  id: number
  label: string
  counterparty: string | null
  expectedAmount: number   // signed
  cadence: RecurringCadence
  status: RecurringStatus
  nextDueDate: string | null
  lastSeenDate: string | null
  categoryId: number | null
  categoryName: string | null
  categoryColor: string | null
  categoryIcon: string | null
  // ── Detection v2 (M3) ──
  confidence: number | null          // 0–1; null for a manually-declared series
  amountMin: number | null           // observed amount envelope (signed)
  amountMax: number | null
  variable: boolean                  // amount legitimately drifts each period (e.g. a utility bill)
  previousAmount: number | null      // expected amount before the last price step
  priceChangedAt: string | null      // ISO date the expected amount last moved
  autoConfirmed: boolean             // confirmed silently by the detector, not the user
  runtimeStatus: RecurringRuntimeStatus
}

export interface RecurringSeriesRequest {
  label: string
  counterparty?: string
  expectedAmount: number
  cadence: RecurringCadence
  nextDueDate?: string
  categoryId?: number
}

export interface RecurringOccurrence {
  seriesId: number
  label: string
  counterparty: string | null
  expectedAmount: number
  dueDate: string
  categoryId: number | null
  categoryName: string | null
  categoryColor: string | null
  categoryIcon: string | null
}

/**
 * One entry in the recurring "what changed" activity feed — derived from series state, not a stored
 * log. {@link RecurringActivityType#PRICE_CHANGE} carries the pre-change `previousAmount`; an
 * {@link RecurringActivityType#AUTO_CONFIRMED} entry leaves it null. Each entry is reversible.
 */
export interface RecurringActivity {
  seriesId: number
  label: string
  type: RecurringActivityType
  occurredOn: string | null
  expectedAmount: number
  previousAmount: number | null
  cadence: RecurringCadence
  categoryId: number | null
  categoryName: string | null
  categoryColor: string | null
  categoryIcon: string | null
}

export interface AiCallLog {
  id: number
  createdAt: string
  memberId: number | null
  transactionId: number | null
  merchantLabel: string | null
  batchId: string | null
  provider: string
  model: string | null
  prompt: string | null
  response: string | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  latencyMs: number | null
  status: string
  error: string | null
  chosenSlug: string | null
  confidence: number | null
  applied: boolean
}

export interface AiCallLogPage {
  items: AiCallLog[]
  total: number
  totalTokens: number
}
