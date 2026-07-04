import XCTest
@testable import Picsou

@MainActor
final class SettingsTests: XCTestCase {

    func testSessionDecoding() throws {
        let json = #"[{"id":1,"userAgent":"Picsou iOS","ipPrefix":"192.168.1.x","createdAt":"2026-07-01T09:00:00Z","lastUsedAt":"2026-07-04T08:00:00Z","expiresAt":"2026-08-01T09:00:00Z","trustedFor2fa":true,"current":true}]"#
        let sessions = try JSONDecoder.picsou.decode([SessionInfo].self, from: Data(json.utf8))
        XCTAssertEqual(sessions.count, 1)
        XCTAssertTrue(sessions[0].current)
        XCTAssertEqual(sessions[0].userAgent, "Picsou iOS")
    }

    func testChangePasswordRequestEncodes() throws {
        let data = try JSONEncoder.picsou.encode(ChangePasswordRequest(currentPassword: "old", newPassword: "newpass12"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
        XCTAssertEqual(json["currentPassword"], "old")
        XCTAssertEqual(json["newPassword"], "newpass12")
    }

    func testDemoSettingsSessions_populatedWithCurrent() async throws {
        let sessions = try await DemoSettingsDataSource().sessions()
        XCTAssertFalse(sessions.isEmpty)
        XCTAssertTrue(sessions.contains { $0.current })
    }

    func testJWTPayloadDecodesClaims() {
        let payload = Data(#"{"sub":"chloe","role":"ADMIN"}"#.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        let claims = JWT.payload(of: "aaa.\(payload).bbb")
        XCTAssertEqual(claims?["sub"] as? String, "chloe")
        XCTAssertEqual(claims?["role"] as? String, "ADMIN")
    }

    func testBankConnectionDecoding_ignoresAuditKeys() throws {
        let json = #"[{"id":1,"institutionName":"Crédit Agricole","institutionId":"ca_fr","status":"LINKED","authLink":null,"lastSyncedAt":"2026-07-04T07:30:00Z","createdAt":"2026-06-01T00:00:00Z"}]"#
        let connections = try JSONDecoder.picsou.decode([BankConnection].self, from: Data(json.utf8))
        XCTAssertEqual(connections.count, 1)
        XCTAssertEqual(connections[0].status, "LINKED")
        XCTAssertEqual(connections[0].institutionName, "Crédit Agricole")
    }

    func testDemoSyncConnections_hasLinkedAndFailed() async throws {
        let connections = try await DemoSyncDataSource().connections()
        XCTAssertTrue(connections.contains { $0.status == "LINKED" })
        XCTAssertTrue(connections.contains { $0.status == "FAILED" })
    }
}
