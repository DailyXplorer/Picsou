import Foundation

/// Mirrors the backend `Requisition` (GET /api/sync/status) — an Enable Banking bank connection.
struct BankConnection: Decodable, Identifiable, Equatable {
    let id: Int64
    let institutionName: String?
    let institutionId: String?
    let status: String          // CREATED | LINKED | EXPIRED | FAILED
    let authLink: String?
    let lastSyncedAt: String?
}

/// Bank-connection listing + safe actions (retry a failed link, delete). Linking / reconnecting a
/// bank needs the web OAuth flow, so it's intentionally not offered here.
protocol SyncDataSource: Sendable {
    func connections() async throws -> [BankConnection]
    func retry(id: Int64) async throws
    func delete(id: Int64) async throws
}

struct LiveSyncDataSource: SyncDataSource {
    let api: APIClient

    func connections() async throws -> [BankConnection] { try await api.get("api/sync/status") }
    func retry(id: Int64) async throws { let _: [Account] = try await api.post("api/sync/\(id)/retry") }
    func delete(id: Int64) async throws { _ = try await api.delete("api/sync/\(id)") }
}

struct DemoSyncDataSource: SyncDataSource {
    func connections() async throws -> [BankConnection] {
        try? await Task.sleep(nanoseconds: 200_000_000)
        return DemoData.bankConnections()
    }
    func retry(id: Int64) async throws {}
    func delete(id: Int64) async throws {}
}
