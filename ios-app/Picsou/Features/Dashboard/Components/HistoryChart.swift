import SwiftUI
import Charts

struct HistoryChart: View {
    let points: [NetWorthPoint]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Évolution").font(.headline)

            if points.count < 2 {
                Text("Pas encore assez de données pour tracer un graphe.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 180)
            } else {
                Chart(points) { point in
                    if let day = point.day {
                        AreaMark(x: .value("Date", day), y: .value("Total", point.totalDouble))
                            .interpolationMethod(.catmullRom)
                            .foregroundStyle(fill)
                        LineMark(x: .value("Date", day), y: .value("Total", point.totalDouble))
                            .interpolationMethod(.catmullRom)
                            .foregroundStyle(Color.accentColor)
                    }
                }
                .chartYAxis {
                    AxisMarks { value in
                        AxisGridLine()
                        AxisValueLabel {
                            if let amount = value.as(Double.self) {
                                Text(Money.format(Decimal(amount)))
                            }
                        }
                    }
                }
                .frame(height: 200)
            }
        }
    }

    private var fill: LinearGradient {
        LinearGradient(
            colors: [Color.accentColor.opacity(0.35), Color.accentColor.opacity(0.02)],
            startPoint: .top,
            endPoint: .bottom
        )
    }
}
