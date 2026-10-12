import XCTest
@testable import FXJCore

final class FXJCoreTests: XCTestCase {
    func testVersionPresent() {
        XCTAssertEqual(FXJCore.version, "0.0.1")
    }
}
