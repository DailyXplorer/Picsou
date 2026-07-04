import SwiftUI

@MainActor
@Observable
final class BudgetViewModel {
    enum State { case loading; case loaded(CashflowSummary, [BudgetEnvelope]); case failed(String) }
    private(set) var state: State = .loading
    private let dataSource: BudgetDataSource
    private let onAuthExpired: () -> Void

    init(dataSource: BudgetDataSource, onAuthExpired: @escaping () -> Void) {
        self.dataSource = dataSource
        self.onAuthExpired = onAuthExpired
    }

    func load() async {
        do {
            let cashflow = try await dataSource.cashflow()
            let budgets = try await dataSource.budgets()
            state = .loaded(cashflow, budgets)
        } catch {
            if (error as? APIError) == .unauthorized { onAuthExpired(); return }
            state = .failed((error as? APIError)?.errorDescription ?? "Impossible de charger le budget.")
        }
    }
}

/// Budget tab: the cycle's income / expense / net, then per-category budget envelopes.
struct BudgetView: View {
    @Environment(AppState.self) private var appState
    @State private var vm: BudgetViewModel?

    var body: some View {
        Group {
            if let vm {
                BudgetContent(vm: vm)
            } else {
                ProgressView().controlSize(.large)
            }
        }
        .task {
            if vm == nil {
                vm = BudgetViewModel(dataSource: appState.makeBudgetDataSource(),
                                     onAuthExpired: { appState.signOut() })
            }
            await vm?.load()
        }
    }
}

private struct BudgetContent: View {
    let vm: BudgetViewModel

    var body: some View {
        switch vm.state {
        case .loading:
            ProgressView().controlSize(.large).frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded(let cashflow, let budgets):
            loaded(cashflow, budgets)
        case .failed(let message):
            Text(message).font(Theme.font(15)).foregroundStyle(Theme.mutedForeground)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func loaded(_ cashflow: CashflowSummary, _ budgets: [BudgetEnvelope]) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("Budget")
                    .font(Theme.font(32, .heavy)).tracking(Theme.tracking(32))
                    .foregroundStyle(Theme.foreground)

                cashflowCard(cashflow)

                VStack(alignment: .leading, spacing: 11) {
                    SectionLabel("Enveloppes")
                    if budgets.isEmpty {
                        Text("Aucune enveloppe de budget. Crée-les sur le web pour suivre tes dépenses par catégorie.")
                            .font(Theme.font(14)).foregroundStyle(Theme.mutedForeground)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(16).cardOutline()
                    } else {
                        VStack(spacing: 0) {
                            ForEach(Array(budgets.enumerated()), id: \.element.id) { index, env in
                                if index > 0 { Rectangle().fill(Theme.border).frame(height: 1) }
                                envelopeRow(env)
                            }
                        }
                        .cardOutline()
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 24)
        }
        .refreshable { await vm.load() }
    }

    private func cashflowCard(_ cashflow: CashflowSummary) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            if let label = cycleLabel(cashflow.from, cashflow.to) {
                Text(label.uppercased())
                    .font(Theme.font(11, .bold)).tracking(0.4).foregroundStyle(Theme.mutedForeground)
            }
            HStack(spacing: 0) {
                stat("Revenus", cashflow.income, Theme.positive)
                Divider().frame(height: 34).overlay(Theme.border)
                stat("Dépenses", cashflow.expense, Theme.foreground)
                Divider().frame(height: 34).overlay(Theme.border)
                stat("Net", cashflow.net, cashflow.net >= 0 ? Theme.positive : Theme.destructive)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .picsouCard()
    }

    private func stat(_ label: String, _ amount: Decimal, _ color: Color) -> some View {
        VStack(spacing: 4) {
            Text(label.uppercased())
                .font(Theme.font(10, .bold)).tracking(0.3).foregroundStyle(Theme.mutedForeground)
            Text(Money.format(amount))
                .font(Theme.font(17, .heavy)).monospacedDigit().foregroundStyle(color)
                .minimumScaleFactor(0.7).lineLimit(1)
        }
        .frame(maxWidth: .infinity)
    }

    private func envelopeRow(_ env: BudgetEnvelope) -> some View {
        let pct = min(1.0, max(0, env.percent.doubleValue / 100))
        return VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 10) {
                Circle().fill(Color.account(env.categoryColor ?? "#6366f1")).frame(width: 10, height: 10)
                Text(env.categoryName).font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground)
                Spacer(minLength: 8)
                Text("\(Money.format(env.spent)) / \(Money.format(env.monthlyLimit))")
                    .font(Theme.font(13, .semibold)).monospacedDigit()
                    .foregroundStyle(env.overBudget ? Theme.destructive : Theme.mutedForeground)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Theme.muted)
                    Capsule().fill(env.overBudget ? Theme.destructive : Theme.brand)
                        .frame(width: max(4, geo.size.width * pct))
                }
            }
            .frame(height: 6)
        }
        .padding(14)
    }

    private func cycleLabel(_ from: String?, _ to: String?) -> String? {
        guard let from, let to,
              let start = DateParsing.localDate.date(from: from),
              let end = DateParsing.localDate.date(from: to) else { return nil }
        let f = DateFormatter(); f.locale = Locale(identifier: "fr_FR"); f.dateFormat = "d MMM"
        return "Cycle \(f.string(from: start)) – \(f.string(from: end))"
    }
}
