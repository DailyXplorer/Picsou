import SwiftUI

/// Bottom tab bar shell (5 tabs, Home active). Phase 1 ships only the dashboard, so the other tabs
/// are shown for structure but inert; they light up in later phases.
struct PicsouTabBar: View {
    var body: some View {
        HStack(spacing: 0) {
            item("house.fill", "Accueil", active: true)
            item("chart.pie.fill", "Budget", active: false)
            item("creditcard.fill", "Actifs", active: false)
            item("target", "Objectifs", active: false)
            item("gearshape.fill", "Réglages", active: false)
        }
        .padding(.top, 10)
        .padding(.horizontal, 4)
        .background(.ultraThinMaterial)
        .overlay(alignment: .top) {
            Rectangle().fill(Theme.border).frame(height: 0.5)
        }
    }

    private func item(_ icon: String, _ label: String, active: Bool) -> some View {
        VStack(spacing: 4) {
            Image(systemName: icon).font(.system(size: 21))
            Text(label).font(Theme.font(10, .semibold))
        }
        .foregroundStyle(active ? Theme.brand : Theme.mutedForeground)
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
    }
}
