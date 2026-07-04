import Foundation

/// Mirrors backend `SessionResponse` (GET /api/auth/sessions).
struct SessionInfo: Decodable, Identifiable, Equatable {
    let id: Int64
    let userAgent: String?
    let ipPrefix: String?
    let createdAt: String?
    let lastUsedAt: String?
    let expiresAt: String?
    let trustedFor2fa: Bool
    let current: Bool
}

struct ChangeUsernameRequest: Encodable { let newUsername: String }
struct ChangePasswordRequest: Encodable { let currentPassword: String; let newPassword: String }

/// Profile + session settings (auth endpoints).
protocol SettingsDataSource: Sendable {
    func sessions() async throws -> [SessionInfo]
    func revokeSession(id: Int64) async throws
    func revokeOtherSessions() async throws
    func changeUsername(_ newUsername: String) async throws -> String
    func changePassword(current: String, new: String) async throws
}

struct LiveSettingsDataSource: SettingsDataSource {
    let api: APIClient

    func sessions() async throws -> [SessionInfo] { try await api.get("api/auth/sessions") }
    func revokeSession(id: Int64) async throws { _ = try await api.delete("api/auth/sessions/\(id)") }
    func revokeOtherSessions() async throws { _ = try await api.delete("api/auth/sessions") }

    func changeUsername(_ newUsername: String) async throws -> String {
        let response: [String: String] = try await api.patch("api/auth/username", body: ChangeUsernameRequest(newUsername: newUsername))
        return response["username"] ?? newUsername
    }
    func changePassword(current: String, new: String) async throws {
        let _: [String: String] = try await api.post("api/auth/change-password",
                                                      body: ChangePasswordRequest(currentPassword: current, newPassword: new))
    }
}

struct DemoSettingsDataSource: SettingsDataSource {
    func sessions() async throws -> [SessionInfo] {
        try? await Task.sleep(nanoseconds: 200_000_000)
        return [
            SessionInfo(id: 1, userAgent: "Picsou iOS · iPhone", ipPrefix: "192.168.1.x",
                        createdAt: "2026-07-01T09:00:00Z", lastUsedAt: "2026-07-04T08:00:00Z",
                        expiresAt: "2026-08-01T09:00:00Z", trustedFor2fa: true, current: true),
            SessionInfo(id: 2, userAgent: "Safari · macOS", ipPrefix: "192.168.1.x",
                        createdAt: "2026-06-20T10:00:00Z", lastUsedAt: "2026-07-02T19:30:00Z",
                        expiresAt: "2026-07-20T10:00:00Z", trustedFor2fa: false, current: false),
            SessionInfo(id: 3, userAgent: "Chrome · Windows", ipPrefix: "10.0.0.x",
                        createdAt: "2026-06-10T14:00:00Z", lastUsedAt: "2026-06-28T11:00:00Z",
                        expiresAt: "2026-07-10T14:00:00Z", trustedFor2fa: false, current: false),
        ]
    }
    func revokeSession(id: Int64) async throws {}
    func revokeOtherSessions() async throws {}
    func changeUsername(_ newUsername: String) async throws -> String { newUsername }
    func changePassword(current: String, new: String) async throws {}
}
