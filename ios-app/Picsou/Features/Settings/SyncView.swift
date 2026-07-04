import SwiftUI

/// Bank-sync settings: the list of bank connections (GET /api/sync/status) with status, last-synced,
/// retry (for a failed link) and swipe-to-delete. Adding / reconnecting a bank uses the web OAuth flow.
struct SyncView: View {
    @Environment(AppState.self) private var appState
    @State private var connections: [BankConnection] = []
    @State private var loading = true
    @State private var failed = false
    @State private var retrying: Int64?

    private var dataSource: SyncDataSource { appState.makeSyncDataSource() }

    var body: some View {
        List {
            Section {
                if loading {
                    HStack { ProgressView(); Text("Chargement…").foregroundStyle(Theme.mutedForeground) }
                } else if failed {
                    Text("Impossible de charger les connexions.").foregroundStyle(Theme.mutedForeground)
                } else if connections.isEmpty {
                    Text("Aucune connexion bancaire.").foregroundStyle(Theme.mutedForeground)
                } else {
                    ForEach(connections) { connection in
                        connectionRow(connection)
                            .swipeActions {
                                Button(role: .destructive) { delete(connection.id) } label: {
                                    Label("Supprimer", systemImage: "trash")
                                }
                            }
                    }
                }
            } header: {
                Text("Connexions bancaires")
            } footer: {
                Text("Pour ajouter ou reconnecter une banque, utilise l'app web (redirection sécurisée de la banque).")
            }
        }
        .navigationTitle("Synchronisation")
        .navigationBarTitleDisplayMode(.inline)
        .tint(Theme.brand)
        .task { await load() }
    }

    private func connectionRow(_ connection: BankConnection) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 8) {
                Text(connection.institutionName ?? "Banque")
                    .font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground)
                Spacer()
                statusBadge(connection.status)
            }
            Text("Synchronisé \(relative(connection.lastSyncedAt))")
                .font(Theme.font(12.5)).foregroundStyle(Theme.mutedForeground)
            if connection.status == "FAILED" {
                Button { retry(connection.id) } label: {
                    HStack(spacing: 6) {
                        if retrying == connection.id { ProgressView() }
                        Text("Réessayer").font(Theme.font(13, .semibold))
                    }
                }
                .disabled(retrying == connection.id)
                .padding(.top, 2)
            }
        }
        .padding(.vertical, 2)
    }

    private func statusBadge(_ status: String) -> some View {
        let (label, color): (String, Color) = switch status {
        case "LINKED": ("Connectée", Theme.positive)
        case "FAILED": ("Échec", Theme.destructive)
        case "EXPIRED": ("Expirée", Color(hex: "#F59E0B") ?? .orange)
        default: ("En attente", Theme.mutedForeground)
        }
        return Text(label)
            .font(Theme.font(11, .bold))
            .foregroundStyle(color)
            .padding(.horizontal, 8).padding(.vertical, 2)
            .background(color.opacity(0.14), in: Capsule())
    }

    private func load() async {
        loading = true
        failed = false
        do { connections = try await dataSource.connections() }
        catch { failed = true }
        loading = false
    }

    private func retry(_ id: Int64) {
        retrying = id
        Task {
            try? await dataSource.retry(id: id)
            retrying = nil
            await load()
        }
    }

    private func delete(_ id: Int64) {
        Task {
            try? await dataSource.delete(id: id)
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
