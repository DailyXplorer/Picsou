import SwiftUI

struct LiabilitiesSection: View {
    let entries: [LiabilityEntry]
    let totalMonthlyPayment: Decimal?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Passifs").font(.headline)
                Spacer()
                if let monthly = totalMonthlyPayment {
                    Text("\(Money.format(monthly))/mois")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }

            ForEach(entries) { entry in
                HStack(spacing: 10) {
                    Circle()
                        .fill(Color.account(entry.color))
                        .frame(width: 10, height: 10)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(entry.name).font(.subheadline)
                        if let paid = entry.percentPaid {
                            ProgressView(value: min(max(paid, 0), 100), total: 100)
                                .tint(Color.account(entry.color))
                        }
                    }
                    Spacer(minLength: 8)
                    Text(Money.format(entry.balanceEur))
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.red)
                }
            }
        }
    }
}
