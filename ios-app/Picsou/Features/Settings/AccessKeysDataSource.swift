import Foundation

/// Mirrors backend `AccessKeyResponse` (GET /api/access-keys). The secret is never returned here.
struct AccessKey: Decodable, Identifiable, Equatable {
    let id: Int64
    let name: String
    let keyPrefix: String
    let scopes: [String]
    let lastUsedAt: String?
    let expiresAt: String?
    let revokedAt: String?
    let createdAt: String?

    var isRevoked: Bool { revokedAt != nil }
}

/// Mirrors `AccessKeyCreatedResponse` — the one-time plaintext secret plus the created key.
struct AccessKeyCreated: Decodable, Equatable {
    let secret: String
    let key: AccessKey
}

struct AccessKeyCreateRequest: Encodable {
    let name: String
    let scopes: [String]
    let expiresAt: String?
}

/// The MCP scope allowlist (mirrors backend `mcp.Scopes.ALL`).
enum McpScope {
    static let all: [(id: String, label: String)] = [
        ("accounts:read", "Comptes — lecture"),
        ("transactions:read", "Transactions — lecture"),
        ("goals:read", "Objectifs — lecture"),
        ("dashboard:read", "Tableau de bord — lecture"),
        ("prices:read", "Cours — lecture"),
        ("family:read", "Famille — lecture"),
        ("accounts:write", "Comptes — écriture"),
        ("transactions:write", "Transactions — écriture"),
        ("goals:write", "Objectifs — écriture"),
        ("sync:trigger", "Déclencher une synchro"),
    ]
    static func label(_ id: String) -> String { all.first { $0.id == id }?.label ?? id }
}

protocol AccessKeysDataSource: Sendable {
    func list() async throws -> [AccessKey]
    func create(name: String, scopes: [String]) async throws -> AccessKeyCreated
    func revoke(id: Int64) async throws
}

struct LiveAccessKeysDataSource: AccessKeysDataSource {
    let api: APIClient

    func list() async throws -> [AccessKey] { try await api.get("api/access-keys") }
    func create(name: String, scopes: [String]) async throws -> AccessKeyCreated {
        try await api.post("api/access-keys", body: AccessKeyCreateRequest(name: name, scopes: scopes, expiresAt: nil))
    }
    func revoke(id: Int64) async throws { _ = try await api.delete("api/access-keys/\(id)") }
}

struct DemoAccessKeysDataSource: AccessKeysDataSource {
    func list() async throws -> [AccessKey] {
        try? await Task.sleep(nanoseconds: 200_000_000)
        return DemoData.accessKeys()
    }
    func create(name: String, scopes: [String]) async throws -> AccessKeyCreated {
        let key = AccessKey(id: Int64.random(in: 100...999), name: name, keyPrefix: "pk_demo1234",
                            scopes: scopes, lastUsedAt: nil, expiresAt: nil, revokedAt: nil,
                            createdAt: "2026-07-05T00:00:00Z")
        return AccessKeyCreated(secret: "sk_live_demo_\(UUID().uuidString.prefix(16))", key: key)
    }
    func revoke(id: Int64) async throws {}
}
