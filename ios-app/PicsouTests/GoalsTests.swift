import XCTest
@testable import Picsou

@MainActor
final class GoalsTests: XCTestCase {

    func testGoalRequestEncodesFields() throws {
        let request = GoalRequest(name: "Vacances", targetAmount: 5000, deadline: "2027-01-01", accountIds: [1, 3])
        let data = try JSONEncoder.picsou.encode(request)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["name"] as? String, "Vacances")
        XCTAssertEqual(json["deadline"] as? String, "2027-01-01")
        XCTAssertEqual((json["accountIds"] as? [Any])?.count, 2)
    }

    func testDemoGoalsCreate_returnsGoalFromRequest() async throws {
        let goal = try await DemoGoalsDataSource().create(
            GoalRequest(name: "Fonds", targetAmount: 8000, deadline: "2027-06-01", accountIds: [1]))
        XCTAssertEqual(goal.name, "Fonds")
        XCTAssertEqual(goal.targetAmount, 8000)
        XCTAssertEqual(goal.deadline, "2027-06-01")
    }

    func testDemoGoalsAccounts_isPopulated() async throws {
        let accounts = try await DemoGoalsDataSource().accounts()
        XCTAssertFalse(accounts.isEmpty)
    }
}
