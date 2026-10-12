import XCTest
import FXJCore
@testable import FengXiangJia

final class SmokeTests: XCTestCase {
    func testThreeTabsInOrder() {
        XCTAssertEqual(AppTab.allCases.map(\.title), ["我家", "试摆", "方案"])
    }

    func testRootViewBuilds() {
        _ = RootTabView().body
    }

    func testCoreLinked() {
        XCTAssertFalse(FXJCore.version.isEmpty)
    }
}
