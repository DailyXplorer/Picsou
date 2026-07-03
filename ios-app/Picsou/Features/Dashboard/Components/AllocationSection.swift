import SwiftUI
import Charts

struct AllocationSection: View {
    let items: [DistributionItem]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Répartition").font(.headline)

            if items.isEmpty {
                Text("Aucun compte à afficher.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                Chart(items) { item in
                    SectorMark(
                        angle: .value("Solde", item.balanceDouble),
                        innerRadius: .ratio(0.62),
                        angularInset: 1.5
                    )
                    .cornerRadius(3)
                    .foregroundStyle(Color.account(item.color))
                }
                .frame(height: 200)

                VStack(spacing: 6) {
                    ForEach(items) { item in
                        HStack(spacing: 8) {
                            Circle()
                                .fill(Color.account(item.color))
                                .frame(width: 10, height: 10)
                            Text(item.name).font(.subheadline).lineLimit(1)
                            Spacer(minLength: 8)
                            Text(Percent.format(item.percentage))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            Text(Money.format(item.balanceEur))
                                .font(.subheadline.weight(.medium))
                        }
                    }
                }
            }
        }
    }
}
