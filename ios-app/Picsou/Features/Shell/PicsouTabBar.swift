import SwiftUI

/// Bottom tab bar shell (5 tabs, Home active). Phase 1 ships only the dashboard, so the other tabs
/// are shown for structure but inert; they light up in later phases.
///
/// On iOS 26+ it renders as a floating **Liquid Glass** pill (as in the design template); on older
/// systems it falls back to a standard material bar with a top hairline.
struct PicsouTabBar: View {
    var body: some View {
        if #available(iOS 26.0, *) {
            glassBar
        } else {
            standardBar
        }
    }

    private var items: some View {
        HStack(spacing: 0) {
            item("house.fill", "Accueil", active: true)
            item("chart.pie.fill", "Budget", active: false)
            item("creditcard.fill", "Actifs", active: false)
            item("target", "Objectifs", active: false)
            item("gearshape.fill", "Réglages", active: false)
        }
    }

    /// Pre-iOS 26: full-width material bar with a top hairline.
    private var standardBar: some View {
        items
            .padding(.top, 10)
            .padding(.horizontal, 4)
            .background(.ultraThinMaterial)
            .overlay(alignment: .top) {
                Rectangle().fill(Theme.border).frame(height: 0.5)
            }
    }

    /// iOS 26+: floating Liquid Glass pill.
    @available(iOS 26.0, *)
    private var glassBar: some View {
        items
            .padding(.vertical, 9)
            .padding(.horizontal, 6)
            .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
            .padding(.horizontal, 14)
            .padding(.bottom, 6)
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
