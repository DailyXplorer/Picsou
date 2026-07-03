import SwiftUI

struct GoalsSection: View {
    let goals: [GoalProgress]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Objectifs").font(.headline)

            ForEach(goals.prefix(3)) { goal in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(goal.name).font(.subheadline)
                        Spacer()
                        if let pct = goal.percentComplete {
                            Text(Percent.format(pct))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                    ProgressView(value: min(max(goal.percentComplete ?? 0, 0), 100), total: 100)
                    if let current = goal.currentTotal, let target = goal.targetAmount {
                        Text("\(Money.format(current)) / \(Money.format(target))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
    }
}
