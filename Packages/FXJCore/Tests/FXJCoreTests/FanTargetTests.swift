import XCTest
@testable import FXJCore

final class FanTargetTests: XCTestCase {
    func editor() -> RoomEditor {
        var e = RoomEditor(template: .defaultRectangle) // 4×3
        _ = try! e.addFurniture(.wardrobe, at: Vec2(3, 2), size: Vec2(1, 0.6))
        return e
    }

    func testPlacementRules() {
        let e = editor()
        XCTAssertNil(FanPlacement.check(Vec2(1, 1), in: e.room))
        XCTAssertEqual(FanPlacement.check(Vec2(5, 1), in: e.room), .outsideRoom)
        XCTAssertEqual(FanPlacement.check(Vec2(0.1, 1), in: e.room), .tooCloseToWall)
        XCTAssertEqual(FanPlacement.check(Vec2(3, 2), in: e.room), .onFurniture)
    }

    func testLowFurnitureAllowsFan() throws {
        var e = RoomEditor(template: .defaultRectangle)
        _ = try e.addFurniture(.table, at: Vec2(2, 1.5))
        var r = e.room; r.obstacles[0].heightClass = .low
        e = RoomEditor(room: r)
        XCTAssertNil(FanPlacement.check(Vec2(2, 1.5), in: e.room))
    }

    func testRotatedObstacleContains() {
        let ob = Obstacle(kind: .bed, center: Vec2(0, 0), size: Vec2(2, 0.4), rotationDegrees: 90)
        XCTAssertTrue(ob.contains(Vec2(0, 0.9)))
        XCTAssertFalse(ob.contains(Vec2(0.9, 0)))
    }

    func testPlaceMoveRejectsAndUndo() throws {
        var e = editor()
        let id = try e.placeFan(at: Vec2(1, 1))
        XCTAssertThrowsError(try e.moveFan(id, to: Vec2(3, 2))) { XCTAssertEqual($0 as? EditError, .onFurniture) }
        try e.moveFan(id, to: Vec2(1.5, 1.5))
        XCTAssertEqual(e.room.fans[0].position, Vec2(1.5, 1.5))
        e.undo()
        XCTAssertEqual(e.room.fans[0].position, Vec2(1, 1))
    }

    func testYawPowerNormalization() throws {
        var e = editor()
        let id = try e.placeFan(at: Vec2(1, 1))
        try e.setFanYaw(id, degrees: -92)
        XCTAssertEqual(e.room.fans[0].yawDegrees, 270)
        try e.setFanYaw(id, degrees: 723)
        XCTAssertEqual(e.room.fans[0].yawDegrees, 5)
        try e.aimFan(id, at: Vec2(1, 2.5))
        XCTAssertEqual(e.room.fans[0].yawDegrees, 90)
        try e.setFanPower(id, 0.87)
        XCTAssertEqual(e.room.fans[0].power, 0.9, accuracy: 1e-12)
        try e.setFanPower(id, 5)
        XCTAssertEqual(e.room.fans[0].power, 1)
    }

    func testTargets() throws {
        var e = editor()
        let id = try e.addTarget(name: "床", at: Vec2(1, 2), radius: 10, objective: .avoidDirectDraft)
        guard case .circle(_, let r) = e.room.targets[0].shape else { return XCTFail() }
        XCTAssertEqual(r, 3)
        try e.moveTarget(id, to: Vec2(2, 1))
        XCTAssertEqual(e.room.targets[0].center, Vec2(2, 1))
        XCTAssertTrue(e.room.targets[0].contains(Vec2(2.5, 1)))
        try e.setTargetObjective(id, .wantBreeze)
        XCTAssertEqual(e.room.targets[0].objective, .wantBreeze)
        try e.setTargetRadius(id, 0.05)
        guard case .circle(_, let r2) = e.room.targets[0].shape else { return XCTFail() }
        XCTAssertEqual(r2, 0.2)
        XCTAssertThrowsError(try e.addTarget(name: "x", at: Vec2(9, 9), objective: .wantBreeze))
        e.undo(); e.undo(); e.undo()
        XCTAssertEqual(e.room.targets[0].center, Vec2(1, 2))
    }
}
