import XCTest
@testable import FXJCoreTests

fileprivate extension DomainModelTests {
    @available(*, deprecated, message: "Not actually deprecated. Marked as deprecated to allow inclusion of deprecated tests (which test deprecated functionality) without warnings")
    static nonisolated(unsafe) let __allTests__DomainModelTests = [
        ("testCorruptDataThrows", testCorruptDataThrows),
        ("testEncodedContainsSchemaVersion", testEncodedContainsSchemaVersion),
        ("testFanPowerClamped", testFanPowerClamped),
        ("testMigrateV0", testMigrateV0),
        ("testRejectsFutureVersion", testRejectsFutureVersion),
        ("testRejectsMissingVersion", testRejectsMissingVersion),
        ("testRoundTrip", testRoundTrip),
        ("testWallIdsMatchVertices", testWallIdsMatchVertices),
        ("testWindVectorConvention", testWindVectorConvention)
    ]
}
@available(*, deprecated, message: "Not actually deprecated. Marked as deprecated to allow inclusion of deprecated tests (which test deprecated functionality) without warnings")
func __FXJCoreTests__allTests() -> [XCTestCaseEntry] {
    return [
        testCase(DomainModelTests.__allTests__DomainModelTests)
    ]
}