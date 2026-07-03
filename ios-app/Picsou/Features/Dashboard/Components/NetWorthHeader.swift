import SwiftUI

struct NetWorthHeader: View {
    let data: DashboardResponse

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Patrimoine net")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text(Money.format(data.totalNetWorth))
                .font(.system(size: 40, weight: .bold, design: .rounded))
                .contentTransition(.numericText())
            if let pnl = data.netWorthHistory.last?.pnl {
                let up = pnl >= 0
                Label(Money.formatSigned(pnl), systemImage: up ? "arrow.up.right" : "arrow.down.right")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(up ? .green : .red)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
