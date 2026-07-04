import Foundation

/// Budget-tab data: cycle cashflow + budget envelopes. Live via the API, canned in the demo build.
protocol BudgetDataSource: Sendable {
    func cashflow() async throws -> CashflowSummary
    func budgets() async throws -> [BudgetEnvelope]
}

struct LiveBudgetDataSource: BudgetDataSource {
    let api: APIClient

    func cashflow() async throws -> CashflowSummary {
        try await api.get("api/cashflow", query: [URLQueryItem(name: "period", value: "CYCLE")])
    }
    func budgets() async throws -> [BudgetEnvelope] {
        try await api.get("api/budgets")
    }
}

struct DemoBudgetDataSource: BudgetDataSource {
    func cashflow() async throws -> CashflowSummary {
        try? await Task.sleep(nanoseconds: 250_000_000)
        return DemoData.cashflow()
    }
    func budgets() async throws -> [BudgetEnvelope] { DemoData.budgetEnvelopes() }
}
