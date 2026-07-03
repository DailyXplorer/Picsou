import SwiftUI

/// Read-only dashboard: net worth, history chart, allocation, accounts, liabilities and goals.
struct DashboardView: View {
    @Environment(AppState.self) private var appState
    @State private var vm: DashboardViewModel?

    var body: some View {
        NavigationStack {
            Group {
                switch vm?.state {
                case .none, .some(.loading):
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                case .some(.loaded(let data)):
                    content(data)
                case .some(.failed(let message)):
                    errorView(message)
                }
            }
            .navigationTitle("Patrimoine")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if appState.isDemo {
                        Text("Démo")
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(.tint.opacity(0.15), in: Capsule())
                            .foregroundStyle(.tint)
                    } else {
                        Menu {
                            Button("Se déconnecter", role: .destructive) { appState.signOut() }
                        } label: {
                            Image(systemName: "person.crop.circle")
                        }
                    }
                }
            }
        }
        .task {
            if vm == nil {
                vm = DashboardViewModel(
                    dataSource: appState.makeDashboardDataSource(),
                    onAuthExpired: { appState.signOut() }
                )
            }
            if case .loaded = vm?.state {} else { await vm?.load() }
        }
    }

    @ViewBuilder
    private func content(_ data: DashboardResponse) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                NetWorthHeader(data: data)
                if let vm {
                    RangeSelector(selected: vm.range) { vm.select($0) }
                }
                HistoryChart(points: data.netWorthHistory)
                AllocationSection(items: data.distribution)
                if !data.liabilities.isEmpty {
                    LiabilitiesSection(entries: data.liabilities, totalMonthlyPayment: data.totalMonthlyPayment)
                }
                if !data.goalSummaries.isEmpty {
                    GoalsSection(goals: data.goalSummaries)
                }
            }
            .padding()
        }
        .refreshable { await vm?.load() }
    }

    @ViewBuilder
    private func errorView(_ message: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.largeTitle)
                .foregroundStyle(.orange)
            Text(message)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Button("Réessayer") { Task { await vm?.load() } }
                .buttonStyle(.bordered)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
