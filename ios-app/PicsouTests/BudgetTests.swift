import XCTest
@testable import Picsou

@MainActor
final class BudgetTests: XCTestCase {

    func testCashflowDecoding() throws {
        let json = #"{"period":"CYCLE","from":"2026-07-01","to":"2026-07-31","income":2450,"expense":1684,"net":766,"series":[]}"#
        let cashflow = try JSONDecoder.picsou.decode(CashflowSummary.self, from: Data(json.utf8))
        XCTAssertEqual(cashflow.income, 2450)
        XCTAssertEqual(cashflow.net, 766)
    }

    func testBudgetEnvelopeDecoding_ignoresExtraKeysAndReadsOverBudget() throws {
        let json = ##"[{"id":1,"categoryId":9,"categoryName":"Alimentation","categoryKind":"EXPENSE","categoryColor":"#10B981","categoryIcon":null,"monthlyLimit":500,"spent":543,"remaining":-43,"percent":108.6,"overBudget":true,"rollup":false,"cycleStart":"2026-07-01","cycleEnd":"2026-07-31"}]"##
        let envelopes = try JSONDecoder.picsou.decode([BudgetEnvelope].self, from: Data(json.utf8))
        XCTAssertEqual(envelopes.count, 1)
        XCTAssertTrue(envelopes[0].overBudget)
        XCTAssertEqual(envelopes[0].categoryName, "Alimentation")
    }

    func testDemoBudget_isPopulated() async throws {
        let ds = DemoBudgetDataSource()
        let cashflow = try await ds.cashflow()
        XCTAssertGreaterThan(cashflow.income, 0)
        let budgets = try await ds.budgets()
        XCTAssertFalse(budgets.isEmpty)
        XCTAssertTrue(budgets.contains { $0.overBudget })
    }
}
