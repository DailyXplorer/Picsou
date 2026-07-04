import SwiftUI

/// Goals screen (Objectifs tab): the goals list with progress, from the "Objectifs & Dettes"
/// template. Reuses the dashboard payload's `goalSummaries`.
struct GoalsView: View {
    @Environment(AppState.self) private var appState
    @State private var vm: DashboardViewModel?

    var body: some View {
        Group {
            if let vm {
                GoalsContent(vm: vm)
            } else {
                ProgressView().controlSize(.large)
            }
        }
        .task {
            if vm == nil {
                vm = DashboardViewModel(
                    dataSource: appState.makeDashboardDataSource(),
                    onAuthExpired: { appState.signOut() }
                )
            }
            await vm?.load()
        }
    }
}

private struct GoalsContent: View {
    let vm: DashboardViewModel

    var body: some View {
        switch vm.state {
        case .loading:
            ProgressView().controlSize(.large)
        case .loaded(let data):
            loaded(data.goalSummaries)
        case .failed(let message):
            Text(message)
                .font(Theme.font(15))
                .foregroundStyle(Theme.mutedForeground)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func loaded(_ goals: [GoalProgress]) -> some View {
        let totalSaved = goals.reduce(Decimal(0)) { $0 + ($1.currentTotal ?? 0) }
        return ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Text("Objectifs")
                        .font(Theme.font(32, .heavy))
                        .tracking(Theme.tracking(32))
                        .foregroundStyle(Theme.foreground)
                    Spacer()
                    Image(systemName: "plus")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(Theme.mutedForeground)
                        .frame(width: 36, height: 36)
                        .background(Theme.muted, in: Circle())
                }
                Text("\(goals.count) objectif\(goals.count > 1 ? "s" : "") · \(Money.format(totalSaved)) épargnés")
                    .font(Theme.font(14))
                    .foregroundStyle(Theme.mutedForeground)
                    .padding(.bottom, 4)

                ForEach(goals) { goal in
                    GoalCard(goal: goal)
                }
                DashedAddCard(title: "Nouvel objectif")
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 24)
        }
    }
}

/// Dashed-border "add" affordance (new goal, etc.).
struct DashedAddCard: View {
    let title: String
    var body: some View {
        HStack(spacing: 9) {
            Image(systemName: "plus").font(.system(size: 15, weight: .semibold))
            Text(title).font(Theme.font(14.5, .semibold))
        }
        .foregroundStyle(Theme.mutedForeground)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 18)
        .overlay(
            RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous)
                .strokeBorder(Theme.border, style: StrokeStyle(lineWidth: 1.5, dash: [6]))
        )
    }
}
