import Foundation

/// Codable mirror of the backend `DashboardResponse` (GET /api/dashboard). Monetary fields decode
/// as `Decimal`; unknown JSON keys (e.g. `NetWorthPoint.accounts`) are ignored by synthesized
/// Decodable, so only the fields the screen needs are declared.
struct DashboardResponse: Decodable, Equatable {
    let totalNetWorth: Decimal
    let totalLiabilities: Decimal
    let totalMonthlyPayment: Decimal?
    let netWorthHistory: [NetWorthPoint]
    let distribution: [DistributionItem]
    let liabilities: [LiabilityEntry]
    let goalSummaries: [GoalProgress]
}

struct NetWorthPoint: Decodable, Equatable, Identifiable {
    let date: String        // "yyyy-MM-dd"
    let total: Decimal
    let invested: Decimal
    let pnl: Decimal

    var id: String { date }
    var day: Date? { DateParsing.localDate.date(from: date) }
    var totalDouble: Double { total.doubleValue }
}

struct DistributionItem: Decodable, Equatable, Identifiable {
    let accountId: Int64
    let name: String
    let color: String
    let balanceEur: Decimal
    let percentage: Double
    let accountType: String
    let hasHoldings: Bool

    var id: Int64 { accountId }
    var type: AccountType { AccountType(raw: accountType) }
    var balanceDouble: Double { balanceEur.doubleValue }
}

struct LiabilityEntry: Decodable, Equatable, Identifiable {
    let accountId: Int64
    let name: String
    let color: String
    let balanceEur: Decimal
    let percentage: Double
    let accountType: String
    let hasHoldings: Bool
    let monthlyPayment: Decimal?
    let percentPaid: Double?

    var id: Int64 { accountId }
    var type: AccountType { AccountType(raw: accountType) }
}

/// Goal summary. Only the fields the dashboard renders are required; the rest are optional so a
/// slightly different server payload never fails the whole decode.
struct GoalProgress: Decodable, Equatable, Identifiable {
    let id: Int64
    let name: String
    let targetAmount: Decimal?
    let currentTotal: Decimal?
    let percentComplete: Double?
}
