import Foundation

/// Mirrors backend `CashflowResponse` (GET /api/cashflow?period=CYCLE) — the cycle's income/expense/net.
struct CashflowSummary: Decodable, Equatable {
    let from: String?
    let to: String?
    let income: Decimal
    let expense: Decimal
    let net: Decimal
}

/// Mirrors backend `BudgetResponse` (GET /api/budgets) — one category envelope for the current cycle.
struct BudgetEnvelope: Decodable, Identifiable, Equatable {
    let id: Int64
    let categoryName: String
    let categoryColor: String?
    let categoryKind: String?
    let monthlyLimit: Decimal
    let spent: Decimal
    let remaining: Decimal
    let percent: Decimal
    let overBudget: Bool
    let cycleStart: String?
    let cycleEnd: String?
}
