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
            GoalProgress(id: 1, name: "Fonds d'urgence", targetAmount: 15000, currentTotal: 12000,
                         percentComplete: 80, deadline: "2026-12-01", monthlyNeeded: 960, monthsLeft: 5,
                         isOnTrack: true, accounts: [GoalAccountRef(id: 1)]),
            GoalProgress(id: 2, name: "Vacances Japon", targetAmount: 5000, currentTotal: 1500,
                         percentComplete: 30, deadline: "2026-09-01", monthlyNeeded: 700, monthsLeft: 5,
                         isOnTrack: false, accounts: [GoalAccountRef(id: 3)]),
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

    // MARK: - Account detail / transactions (Slice 1)

    private static let loanId: Int64 = 99

    static func account(id: Int64) -> Account {
        if id == loanId {
            return Account(id: loanId, name: "Prêt immobilier", accountType: "LOAN",
                           provider: "Crédit Agricole", currency: "EUR",
                           currentBalance: -18000, currentBalanceEur: -18000,
                           lastSyncedAt: "2026-07-03T08:05:00Z", manual: false, color: "#DC2626",
                           ticker: nil, parentAccountId: nil,
                           debt: DebtInfo(borrowedAmount: 31000, interestRate: 1.9, monthlyPayment: 650,
                                          lenderName: "Crédit Agricole", startDate: "2022-01-01", endDate: "2045-01-01"))
        }
        let index = max(0, min(Int(id) - 1, assets.count - 1))
        let a = assets[index]
        return Account(id: id, name: a.name, accountType: a.type,
                       provider: provider(for: a.type), currency: "EUR",
                       currentBalance: Decimal(a.balance), currentBalanceEur: Decimal(a.balance),
                       lastSyncedAt: "2026-07-03T08:05:00Z", manual: false, color: a.color,
                       ticker: nil, parentAccountId: nil, debt: nil)
    }

    static func holdings(id: Int64) -> [Holding] {
        switch id {
        case 2: return [
            Holding(ticker: "CW8", name: "Amundi MSCI World", quantity: 42,
                    currentValueEur: 9800, pnlEur: 1450, pnlPercent: 17.4, priceUpdatedAt: "2026-07-03T16:00:00Z"),
            Holding(ticker: "ESE", name: "BNP S&P 500", quantity: 18,
                    currentValueEur: 5700, pnlEur: 820, pnlPercent: 16.8, priceUpdatedAt: "2026-07-03T16:00:00Z"),
        ]
        case 4: return [
            Holding(ticker: "BTC", name: "Bitcoin", quantity: 0.14,
                    currentValueEur: 8900, pnlEur: 2100, pnlPercent: 30.9, priceUpdatedAt: "2026-07-03T16:00:00Z"),
        ]
        default: return []
        }
    }

    static func transactions(id: Int64) -> [Transaction] {
        let rows: [(String, Double, String?, String?)] = [
            ("Carrefour Market", -54.30, "Alimentation", "Carrefour"),
            ("Salaire", 2450.00, "Revenus", nil),
            ("Netflix", -13.49, "Abonnements", "Netflix"),
            ("SNCF", -78.00, "Transport", "SNCF"),
            ("Boulangerie du coin", -6.80, "Alimentation", nil),
            ("EDF", -89.00, "Énergie", "EDF"),
            ("Amazon", -34.99, "Shopping", "Amazon"),
            ("Pharmacie Centrale", -12.40, "Santé", nil),
        ]
        let name = account(id: id).name
        let calendar = Calendar(identifier: .gregorian)
        let now = Date()
        return rows.enumerated().map { i, row in
            let date = calendar.date(byAdding: .day, value: -i * 2, to: now) ?? now
            return Transaction(id: Int64(i + 1), date: DateParsing.localDate.string(from: date),
                               description: row.0, amount: Decimal(row.1), nativeCurrency: "EUR",
                               manual: false, txType: row.1 < 0 ? "WITHDRAWAL" : "DEPOSIT",
                               categoryName: row.2, merchantLabel: row.3, counterparty: nil,
                               accountId: id, accountName: name)
        }
    }

    static func loanSchedule() -> LoanSchedule {
        var remaining = 18000.0
        let calendar = Calendar(identifier: .gregorian)
        let now = Date()
        var rows: [LoanInstallment] = []
        for n in 0..<12 {
            let interest = remaining * 0.019 / 12
            let capital = 650 - interest
            remaining -= capital
            let date = calendar.date(byAdding: .month, value: n, to: now) ?? now
            rows.append(LoanInstallment(number: n + 1, date: DateParsing.localDate.string(from: date),
                                        capital: Decimal(capital), interest: Decimal(interest),
                                        totalPayment: 650, remainingBalance: Decimal(max(0, remaining))))
        }
        return LoanSchedule(
            summary: LoanSummary(monthlyPayment: 650, remainingBalance: 18000, capitalRepaidPct: 42.5,
                                 endDate: "2045-01-01", paidInstallments: 102, totalInstallments: 240),
            schedule: rows)
    }

    static func makeTransaction(from request: TransactionRequest, accountId: Int64) -> Transaction {
        Transaction(id: Int64.random(in: 100_000...999_999), date: request.date,
                    description: request.description, amount: request.amount,
                    nativeCurrency: request.currency ?? "EUR", manual: true, txType: request.txType,
                    categoryName: nil, merchantLabel: nil, counterparty: nil,
                    accountId: accountId, accountName: account(id: accountId).name)
    }

    static func accountsList() -> [Account] {
        (1...assets.count).map { account(id: Int64($0)) }
    }

    static func makeGoal(from request: GoalRequest, id: Int64) -> GoalProgress {
        GoalProgress(id: id, name: request.name, targetAmount: request.targetAmount,
                     currentTotal: 0, percentComplete: 0, deadline: request.deadline,
                     monthlyNeeded: nil, monthsLeft: nil, isOnTrack: false)
    }

    private static func provider(for type: String) -> String {
        switch type {
        case "PEA", "COMPTE_TITRES": return "Bourse Direct"
        case "CRYPTO": return "Ledger"
        case "SAVINGS", "LEP": return "Crédit Agricole"
        case "CHECKING": return "Boursorama"
        default: return "Manuel"
        }
    }
}
