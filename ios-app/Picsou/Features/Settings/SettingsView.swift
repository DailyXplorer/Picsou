import SwiftUI

/// Settings home (Réglages tab): profile header + grouped setting rows. Phase 1 wires the working
/// "Déconnexion"; the other rows are the design's entries (their sub-screens land in a later phase).
struct SettingsView: View {
    @Environment(AppState.self) private var appState

    private var instanceHost: String { appState.serverConfig.baseURL?.host ?? "—" }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("Réglages")
                    .font(Theme.font(32, .heavy))
                    .tracking(Theme.tracking(32))
                    .foregroundStyle(Theme.foreground)

                profileHeader

                SettingsCard {
                    SettingsRow(icon: "person.fill", title: "Profil")
                    rowDivider
                    SettingsRow(icon: "lock.shield.fill", title: "Sécurité & 2FA")
                    rowDivider
                    SettingsRow(icon: "person.2.fill", title: "Partage famille")
                }

                SettingsCard {
                    SettingsRow(icon: "server.rack", title: "Serveur", value: instanceHost)
                    rowDivider
                    SettingsRow(icon: "arrow.triangle.2.circlepath", title: "Synchronisation", value: "il y a 12 min")
                    rowDivider
                    SettingsRow(icon: "bell.fill", title: "Notifications")
                }

                SettingsCard {
                    SettingsRow(icon: "paintbrush.fill", title: "Apparence", value: "Auto")
                    rowDivider
                    SettingsRow(icon: "eurosign.circle.fill", title: "Devise", value: "EUR")
                    rowDivider
                    SettingsRow(icon: "globe", title: "Langue", value: "Français")
                }

                SettingsCard {
                    SettingsRow(icon: "key.fill", title: "Clés d'accès MCP")
                }

                SettingsCard {
                    SettingsRow(icon: "rectangle.portrait.and.arrow.right",
                                title: "Déconnexion",
                                destructive: true,
                                showsChevron: false) { appState.signOut() }
                }

                Text("Picsou iOS 0.1.0 · serveur v1.1.0")
                    .font(Theme.font(12))
                    .foregroundStyle(Theme.mutedForeground.opacity(0.7))
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.top, 2)
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 24)
        }
    }

    private var profileHeader: some View {
        HStack(spacing: 14) {
            Avatar(initials: appState.isDemo ? "C" : nil, size: 56)
            VStack(alignment: .leading, spacing: 3) {
                Text(appState.isDemo ? "Chloé" : "Mon compte")
                    .font(Theme.font(18, .bold))
                    .foregroundStyle(Theme.foreground)
                Text(appState.isDemo ? "@chloe · Admin" : instanceHost)
                    .font(Theme.font(13))
                    .foregroundStyle(Theme.mutedForeground)
            }
            Spacer()
        }
        .padding(16)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous).strokeBorder(Theme.border, lineWidth: 1))
    }

    private var rowDivider: some View {
        Rectangle().fill(Theme.border).frame(height: 1).padding(.leading, 56)
    }
}

/// A grouped list card that stacks setting rows behind one rounded border.
struct SettingsCard<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(spacing: 0) { content }
            .background(Theme.card, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous).strokeBorder(Theme.border, lineWidth: 1))
    }
}

/// A single settings row: tinted icon tile, label, optional trailing value, chevron.
struct SettingsRow: View {
    let icon: String
    var tint: Color = Theme.brand
    let title: String
    var value: String?
    var destructive: Bool = false
    var showsChevron: Bool = true
    var action: (() -> Void)?

    var body: some View {
        Button { action?() } label: {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(destructive ? Theme.destructive : tint)
                    .frame(width: 30, height: 30)
                    .background((destructive ? Theme.destructive : tint).opacity(0.14),
                                in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                Text(title)
                    .font(Theme.font(15, .semibold))
                    .foregroundStyle(destructive ? Theme.destructive : Theme.foreground)
                Spacer(minLength: 8)
                if let value {
                    Text(value).font(Theme.font(13)).foregroundStyle(Theme.mutedForeground)
                }
                if showsChevron {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Theme.mutedForeground.opacity(0.6))
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
