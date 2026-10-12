import XCTest
@testable import FXJCore

final class EditorTests: XCTestCase {
    func testTemplatesValid() {
        for t in [RoomTemplate.defaultRectangle, .defaultLShape] {
            XCTAssertEqual(RoomValidator.validate(Room(name: "t", polygon: t.polygon)), [], "\(t)")
        }
        XCTAssertEqual(RoomTemplate.defaultLShape.polygon.count, 6)
        XCTAssertEqual(Geometry.signedArea(RoomTemplate.defaultLShape.polygon), 20 - 3, accuracy: 1e-9)
    }

    func testUndoRedo() throws {
        var e = RoomEditor(template: .defaultRectangle)
        let original = e.room
        XCTAssertFalse(e.canUndo)
        let id = try e.addFurniture(.bed, at: Vec2(1, 1))
        try e.moveFurniture(id, to: Vec2(2, 2))
        XCTAssertEqual(e.room.obstacles[0].center, Vec2(2, 2))
        e.undo()
        XCTAssertEqual(e.room.obstacles[0].center, Vec2(1, 1))
        e.undo()
        XCTAssertEqual(e.room, original)
        XCTAssertFalse(e.canUndo)
        e.redo(); e.redo()
        XCTAssertEqual(e.room.obstacles[0].center, Vec2(2, 2))
        XCTAssertFalse(e.canRedo)
    }

    func testNewEditClearsRedo() throws {
        var e = RoomEditor(template: .defaultRectangle)
        _ = try e.addFan(at: Vec2(1, 1))
        e.undo()
        XCTAssertTrue(e.canRedo)
        _ = try e.addFurniture(.sofa, at: Vec2(2, 1))
        XCTAssertFalse(e.canRedo)
    }

    func testFailedEditDoesNotChangeOrRecord() {
        var e = RoomEditor(template: .defaultRectangle)
        XCTAssertThrowsError(try e.addFurniture(.bed, at: Vec2(10, 10))) { XCTAssertEqual($0 as? EditError, .outsideRoom) }
        XCTAssertFalse(e.canUndo)
        // 把 (4,3) 拖到 (−1,−1) 会产生自交
        XCTAssertThrowsError(try e.moveVertex(2, to: Vec2(-1, 1.5)))
        XCTAssertEqual(e.room.polygon, RoomTemplate.defaultRectangle.polygon)
    }

    func testMoveVertexSnapsToGrid() throws {
        var e = RoomEditor(template: .defaultRectangle)
        try e.moveVertex(2, to: Vec2(4.512, 3.488))
        XCTAssertEqual(e.room.polygon[2].x, 4.5, accuracy: 1e-9)
        XCTAssertEqual(e.room.polygon[2].y, 3.5, accuracy: 1e-9)
    }

    func testMoveVertexRejectedWhenOpeningWouldLeaveWall() throws {
        var e = RoomEditor(template: .defaultRectangle)
        try e.addOpening(.window, near: Vec2(3.2, 0.1), width: 1.2) // 南墙 0→4，偏移约 2.6
        XCTAssertThrowsError(try e.moveVertex(1, to: Vec2(2.5, 0))) // 南墙缩短为 2.5
    }

    func testOpeningSnapsToNearestWall() throws {
        var e = RoomEditor(template: .defaultRectangle)
        let id = try e.addOpening(.window, near: Vec2(2, 0.3))
        let o = e.room.openings.first { $0.id == id }!
        XCTAssertEqual(o.wallId, e.room.wallIds[0])
        XCTAssertEqual(o.offset, 1.4, accuracy: 1e-9) // 以 x=2 为中心，宽 1.2
        XCTAssertEqual(RoomValidator.validate(e.room), [])
    }

    func testOpeningClampedAtCorner() throws {
        var e = RoomEditor(template: .defaultRectangle)
        let id = try e.addOpening(.door, near: Vec2(3.95, 0.05))
        let o = e.room.openings.first { $0.id == id }!
        XCTAssertLessThanOrEqual(o.offset + o.width, 4 + 1e-9)
        XCTAssertEqual(RoomValidator.validate(e.room), [])
    }

    func testOpeningFarFromWallRejected() {
        var e = RoomEditor(template: .defaultRectangle)
        XCTAssertThrowsError(try e.addOpening(.window, near: Vec2(2, 1.5))) { XCTAssertEqual($0 as? EditError, .noWallNearby) }
    }

    func testOpeningOverlapRejected() throws {
        var e = RoomEditor(template: .defaultRectangle)
        try e.addOpening(.window, near: Vec2(2, 0))
        XCTAssertThrowsError(try e.addOpening(.door, near: Vec2(2.3, 0))) { XCTAssertEqual($0 as? EditError, .overlapsOpening) }
    }

    func testMoveOpeningToOtherWall() throws {
        var e = RoomEditor(template: .defaultRectangle)
        let id = try e.addOpening(.window, near: Vec2(2, 0))
        try e.moveOpening(id, near: Vec2(3.9, 1.5)) // 东墙
        XCTAssertEqual(e.room.openings[0].wallId, e.room.wallIds[1])
        e.undo()
        XCTAssertEqual(e.room.openings[0].wallId, e.room.wallIds[0])
    }

    func testApplyTemplateDropsOpeningsKeepsInsideItems() throws {
        var e = RoomEditor(template: .defaultRectangle)
        try e.addOpening(.window, near: Vec2(2, 0))
        try e.addFurniture(.bed, at: Vec2(1, 1))
        try e.addFan(at: Vec2(3.8, 2.8))
        e.apply(.lShape(width: 4, depth: 3, cutWidth: 1, cutDepth: 1)) // (3.8,2.8) 位于切除区
        XCTAssertTrue(e.room.openings.isEmpty)
        XCTAssertEqual(e.room.obstacles.count, 1)
        XCTAssertTrue(e.room.fans.isEmpty)
        XCTAssertEqual(e.room.wallIds.count, 6)
        e.undo()
        XCTAssertEqual(e.room.openings.count, 1)
    }

    func testRemoveItem() throws {
        var e = RoomEditor(template: .defaultRectangle)
        let id = try e.addFurniture(.desk, at: Vec2(1, 1))
        try e.removeItem(id)
        XCTAssertTrue(e.room.obstacles.isEmpty)
        XCTAssertThrowsError(try e.removeItem(UUID()))
    }

    func testHistoryLimit() throws {
        var e = RoomEditor(template: .defaultRectangle)
        for i in 0..<(RoomEditor.historyLimit + 20) { try e.moveVertex(2, to: Vec2(4 + Double(i % 2) * 0.5, 3)) }
        var n = 0
        while e.canUndo { e.undo(); n += 1 }
        XCTAssertEqual(n, RoomEditor.historyLimit)
    }

    func testViewportPanZoom() {
        var v = Viewport(scale: 50, offset: Vec2(10, 300))
        let w = Vec2(2, 3)
        let s = v.toScreen(w)
        XCTAssertEqual(v.toWorld(s), w)
        let anchor = Vec2(120, 80)
        let before = v.toWorld(anchor)
        v.zoom(by: 2, anchor: anchor)
        XCTAssertEqual(v.scale, 100)
        let after = v.toWorld(anchor)
        XCTAssertEqual(after.x, before.x, accuracy: 1e-9); XCTAssertEqual(after.y, before.y, accuracy: 1e-9)
        v.zoom(by: 1000, anchor: anchor)
        XCTAssertEqual(v.scale, Viewport.maxScale)
        v.pan(by: Vec2(5, -5))
        XCTAssertEqual(v.toWorld(anchor + Vec2(5, -5)).x, after.x, accuracy: 1e-9)
    }

    func testViewportFitting() {
        let v = Viewport.fitting(RoomTemplate.defaultRectangle.polygon, width: 400, height: 400)
        let c = v.toScreen(Vec2(2, 1.5))
        XCTAssertEqual(c.x, 200, accuracy: 1e-6); XCTAssertEqual(c.y, 200, accuracy: 1e-6)
    }
}
