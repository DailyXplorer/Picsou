import SwiftUI

/// Security settings: active persistent sessions (GET /api/auth/sessions) with per-session revoke and
/// "log out everywhere else". 2FA management lands in a later slice.
struct SecurityView: View {
    @Environment(AppState.self) private var appState
    @State private var sessions: [SessionInfo] = []
    @State private var loading = true
    @State private var failed = false

    private var dataSource: SettingsDataSource { appState.makeSettingsDataSource() }

    var body: some View {
        List {
            Section("Double authentification") {
                HStack {
                    Label("2FA (TOTP)", systemImage: "lock.shield.fill")
                    Spacer()
                    Text("Bientôt").foregroundStyle(Theme.mutedForeground)
                }
            }
            Section("Sessions actives") {
                if loading {
                    HStack { ProgressView(); Text("Chargement…").foregroundStyle(Theme.mutedForeground) }
                } else if failed {
                    Text("Impossible de charger les sessions.").foregroundStyle(Theme.mutedForeground)
                } else {
                    ForEach(sessions) { session in
                        sessionRow(session)
                            .swipeActions {
                                if !session.current {
                                    Button(role: .destructive) { revoke(session.id) } label: {
                                        Label("Révoquer", systemImage: "trash")
                                    }
                                }
                            }
                    }
                    if sessions.contains(where: { !$0.current }) {
                        Button(role: .destructive) { revokeOthers() } label: {
                            Text("Déconnecter les autres appareils")
                        }
                    }
                }
            }
        }
        .navigationTitle("Sécurité")
        .navigationBarTitleDisplayMode(.inline)
        .tint(Theme.brand)
        .task { await load() }
    }

    private func sessionRow(_ session: SessionInfo) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 8) {
                Text(session.userAgent ?? "Appareil inconnu")
                    .font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground)
                if session.current {
                    Text("Actuelle").font(Theme.font(11, .bold))
                        .foregroundStyle(Theme.positive)
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background(Theme.positive.opacity(0.14), in: Capsule())
                }
            }
            Text("\(session.ipPrefix ?? "—") · vu \(relative(session.lastUsedAt))")
                .font(Theme.font(12.5)).foregroundStyle(Theme.mutedForeground)
        }
        .padding(.vertical, 2)
    }

    private func load() async {
        loading = true
        failed = false
        do { sessions = try await dataSource.sessions() }
        catch { failed = true }
        loading = false
    }

    private func revoke(_ id: Int64) {
        Task {
            try? await dataSource.revokeSession(id: id)
            await load()
        }
    }

    private func revokeOthers() {
        Task {
            try? await dataSource.revokeOtherSessions()
            await load()
        }
    }

    private func relative(_ iso: String?) -> String {
        guard let iso, let date = ISO8601DateFormatter().date(from: iso) else { return "—" }
        let f = RelativeDateTimeFormatter()
        f.locale = Locale(identifier: "fr_FR")
        return f.localizedString(for: date, relativeTo: Date())
    }
}
