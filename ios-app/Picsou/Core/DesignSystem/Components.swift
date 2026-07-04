import SwiftUI

/// Card surface: card fill + hairline border + continuous rounding (shadcn `Card`).
struct PicsouCard: ViewModifier {
    var padding: CGFloat = 16
    func body(content: Content) -> some View {
        content
            .padding(padding)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous)
                    .strokeBorder(Theme.border, lineWidth: 1)
            )
    }
}

extension View {
    func picsouCard(padding: CGFloat = 16) -> some View { modifier(PicsouCard(padding: padding)) }
}

/// Uppercase, tracked, muted section label ("ACTIFS", "OBJECTIF").
struct SectionLabel: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text.uppercased())
            .font(Theme.font(12, .bold))
            .tracking(0.6)
            .foregroundStyle(Theme.mutedForeground)
    }
}

/// Circular initials/person avatar.
struct Avatar: View {
    var initials: String?
    var size: CGFloat = 38

    var body: some View {
        Group {
            if let initials {
                Text(initials).font(Theme.font(size * 0.37, .bold))
            } else {
                Image(systemName: "person.fill").font(.system(size: size * 0.42))
            }
        }
        .foregroundStyle(Theme.mutedForeground)
        .frame(width: size, height: size)
        .background(Theme.muted, in: Circle())
    }
}

/// Small muted pill naming an account type.
struct AccountTypeBadge: View {
    let type: AccountType
    var body: some View {
        Text(type.label)
            .font(Theme.font(11, .semibold))
            .foregroundStyle(Theme.mutedForeground)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(Theme.muted, in: Capsule())
    }
}

/// Change chip: green on plain surfaces, translucent white on a colored (hero) surface.
struct DeltaPill: View {
    let amount: String
    var pct: String?
    var positive: Bool = true
    var onColor: Bool = false

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: positive ? "arrowtriangle.up.fill" : "arrowtriangle.down.fill")
                .font(.system(size: 9))
            Text(amount).font(Theme.font(13, .semibold)).monospacedDigit()
            if let pct {
                Text("· \(pct)").font(Theme.font(13, .semibold)).opacity(0.85)
            }
        }
        .foregroundStyle(onColor ? Color.white : (positive ? Theme.positive : Theme.destructive))
        .padding(.horizontal, 10)
        .frame(height: 26)
        .background(
            onColor ? AnyShapeStyle(Color.white.opacity(0.22))
                    : AnyShapeStyle(positive ? Theme.positiveSurface : Theme.destructive.opacity(0.14)),
            in: Capsule()
        )
    }
}

/// Rounded track + brand fill progress bar (value 0…1).
struct ProgressBar: View {
    let value: Double
    var height: CGFloat = 8

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.muted)
                Capsule().fill(Theme.brand)
                    .frame(width: max(0, min(value, 1)) * geo.size.width)
            }
        }
        .frame(height: height)
    }
}
