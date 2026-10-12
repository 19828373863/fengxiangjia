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

fileprivate extension EditorTests {
    @available(*, deprecated, message: "Not actually deprecated. Marked as deprecated to allow inclusion of deprecated tests (which test deprecated functionality) without warnings")
    static nonisolated(unsafe) let __allTests__EditorTests = [
        ("testApplyTemplateDropsOpeningsKeepsInsideItems", testApplyTemplateDropsOpeningsKeepsInsideItems),
        ("testFailedEditDoesNotChangeOrRecord", testFailedEditDoesNotChangeOrRecord),
        ("testHistoryLimit", testHistoryLimit),
        ("testMoveOpeningToOtherWall", testMoveOpeningToOtherWall),
        ("testMoveVertexRejectedWhenOpeningWouldLeaveWall", testMoveVertexRejectedWhenOpeningWouldLeaveWall),
        ("testMoveVertexSnapsToGrid", testMoveVertexSnapsToGrid),
        ("testNewEditClearsRedo", testNewEditClearsRedo),
        ("testOpeningClampedAtCorner", testOpeningClampedAtCorner),
        ("testOpeningFarFromWallRejected", testOpeningFarFromWallRejected),
        ("testOpeningOverlapRejected", testOpeningOverlapRejected),
        ("testOpeningSnapsToNearestWall", testOpeningSnapsToNearestWall),
        ("testRemoveItem", testRemoveItem),
        ("testTemplatesValid", testTemplatesValid),
        ("testUndoRedo", testUndoRedo),
        ("testViewportFitting", testViewportFitting),
        ("testViewportPanZoom", testViewportPanZoom)
    ]
}

fileprivate extension FanTargetTests {
    @available(*, deprecated, message: "Not actually deprecated. Marked as deprecated to allow inclusion of deprecated tests (which test deprecated functionality) without warnings")
    static nonisolated(unsafe) let __allTests__FanTargetTests = [
        ("testLowFurnitureAllowsFan", testLowFurnitureAllowsFan),
        ("testPlaceMoveRejectsAndUndo", testPlaceMoveRejectsAndUndo),
        ("testPlacementRules", testPlacementRules),
        ("testRotatedObstacleContains", testRotatedObstacleContains),
        ("testTargets", testTargets),
        ("testYawPowerNormalization", testYawPowerNormalization)
    ]
}

fileprivate extension GeometryTopologyTests {
    @available(*, deprecated, message: "Not actually deprecated. Marked as deprecated to allow inclusion of deprecated tests (which test deprecated functionality) without warnings")
    static nonisolated(unsafe) let __allTests__GeometryTopologyTests = [
        ("testAreaAndWalls", testAreaAndWalls),
        ("testClockwiseAndDegenerate", testClockwiseAndDegenerate),
        ("testClosedOpeningBlocksPath", testClosedOpeningBlocksPath),
        ("testFanOutsideRoomFlagged", testFanOutsideRoomFlagged),
        ("testLShapeIsSimple", testLShapeIsSimple),
        ("testNoThroughWallWithoutOpening", testNoThroughWallWithoutOpening),
        ("testNormalizeWindingKeepsOpeningPosition", testNormalizeWindingKeepsOpeningPosition),
        ("testOpeningConnectsIndoorOutdoor", testOpeningConnectsIndoorOutdoor),
        ("testOpeningMustStayWithinWall", testOpeningMustStayWithinWall),
        ("testOverlappingOpeningsAndNarrow", testOverlappingOpeningsAndNarrow),
        ("testRefreshConnections", testRefreshConnections),
        ("testSelfIntersectionDetected", testSelfIntersectionDetected),
        ("testValidRoomHasNoIssues", testValidRoomHasNoIssues)
    ]
}

fileprivate extension LibraryTests {
    @available(*, deprecated, message: "Not actually deprecated. Marked as deprecated to allow inclusion of deprecated tests (which test deprecated functionality) without warnings")
    static nonisolated(unsafe) let __allTests__LibraryTests = [
        ("testCorruptFileGivesError", testCorruptFileGivesError),
        ("testEmptyThenSaveThenLoad", testEmptyThenSaveThenLoad),
        ("testFutureVersionGivesError", testFutureVersionGivesError),
        ("testSampleIsDeterministic", testSampleIsDeterministic),
        ("testSampleIsValid", testSampleIsValid)
    ]
}
@available(*, deprecated, message: "Not actually deprecated. Marked as deprecated to allow inclusion of deprecated tests (which test deprecated functionality) without warnings")
func __FXJCoreTests__allTests() -> [XCTestCaseEntry] {
    return [
        testCase(DomainModelTests.__allTests__DomainModelTests),
        testCase(EditorTests.__allTests__EditorTests),
        testCase(FanTargetTests.__allTests__FanTargetTests),
        testCase(GeometryTopologyTests.__allTests__GeometryTopologyTests),
        testCase(LibraryTests.__allTests__LibraryTests)
    ]
}