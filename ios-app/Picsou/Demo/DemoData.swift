import Foundation

/// Canned dashboard data for the demo build. Mirrors the shape of the web app's
/// `src/demo/data/dashboard.ts`, plus a loan liability so every section is populated.
enum DemoData {

    private static let assets: [(name: String, color: String, balance: Double, type: String, holdings: Bool)] = [
        ("Livret A",       "#4F46E5", 12000,    "SAVINGS",  false),
        ("PEA",            "#10B981", 15500,    "PEA",      true),
        ("Compte courant", "#F59E0B", 3200,     "CHECKING", false),
        ("Bitcoin",        "#EF4444", 8900,     "CRYPTO",   true),
        ("Assurance-vie",  "#8B5CF6", 6262.35,  "OTHER",    false),
    ]

    private static let loanBalance: Double = 18000
    private static let loanMonthly: Double = 650

    static func dashboard(range: TimeRange) -> DashboardResponse {
        let totalAssets = assets.reduce(0) { $0 + $1.balance }
        let netWorth = totalAssets - loanBalance

        let distribution = assets.enumerated().map { index, account in
            DistributionItem(
                accountId: Int64(index + 1),
                name: account.name,
                color: account.color,
                balanceEur: Decimal(account.balance),
                percentage: (account.balance / totalAssets * 1000).rounded() / 10,
                accountType: account.type,
                hasHoldings: account.holdings
            )
        }

        let liabilities = [
            LiabilityEntry(
                accountId: 99,
                name: "Prêt immobilier",
                color: "#DC2626",
                balanceEur: Decimal(loanBalance),
                percentage: 100,
                accountType: "LOAN",
                hasHoldings: false,
                monthlyPayment: Decimal(loanMonthly),
                percentPaid: 42.5
            )
        ]

        let goals = [
            GoalProgress(id: 1, name: "Fonds d'urgence", targetAmount: 15000, currentTotal: 12000, percentComplete: 80),
            GoalProgress(id: 2, name: "Vacances Japon",  targetAmount: 5000,  currentTotal: 1500,  percentComplete: 30),
        ]

        return DashboardResponse(
            totalNetWorth: Decimal(netWorth),
            totalLiabilities: Decimal(loanBalance),
            totalMonthlyPayment: Decimal(loanMonthly),
            netWorthHistory: history(netWorth: netWorth, range: range),
            distribution: distribution,
            liabilities: liabilities,
            goalSummaries: goals
        )
    }

    private static func history(netWorth: Double, range: TimeRange) -> [NetWorthPoint] {
        // 12 monthly points ramping to the current net worth; invested tracks a little below total.
        let totals: [Double] = [20000, 20800, 21500, 22300, 22100, 23400, 24200, 24900, 25600, 26400, 27100, netWorth]
        let invested: [Double] = [16000, 16500, 17000, 17500, 17500, 18500, 19000, 19500, 20000, 20600, 21000, 21400]

        let calendar = Calendar(identifier: .gregorian)
        let now = Date()
        let startOfMonth = calendar.date(from: calendar.dateComponents([.year, .month], from: now)) ?? now

        var points: [NetWorthPoint] = []
        for i in 0..<totals.count {
            let monthOffset = -(totals.count - 1 - i)
            guard let date = calendar.date(byAdding: .month, value: monthOffset, to: startOfMonth) else { continue }
            let total = Decimal(totals[i])
            let inv = Decimal(invested[i])
            points.append(NetWorthPoint(
                date: DateParsing.localDate.string(from: date),
                total: total,
                invested: inv,
                pnl: total - inv
            ))
        }

        return Array(points.suffix(max(2, monthsToKeep(range))))
    }

    private static func monthsToKeep(_ range: TimeRange) -> Int {
        switch range {
        case .day, .week, .month: return 2
        case .quarter: return 4
        case .ytd: return Calendar(identifier: .gregorian).component(.month, from: Date())
        case .year, .all: return 12
        }
    }
}
