import XCTest
@testable import FXJCore

final class ScanImportTests: XCTestCase {
    /// 4×3 房间四面墙（带 5 cm 噪声），已旋转 30°、平移。
    func walls(rotation: Double = 30, noise: Double = 0.05, closed: Bool = true) -> [ScannedWall] {
        let pts = [Vec2(0, 0), Vec2(4, 0), Vec2(4, 3), Vec2(0, 3)]
        let a = rotation * .pi / 180
        func t(_ p: Vec2) -> Vec2 { Vec2(p.x * cos(a) - p.y * sin(a) + 10, p.x * sin(a) + p.y * cos(a) - 5) }
        var out: [ScannedWall] = []
        for i in 0..<(closed ? 4 : 3) {
            let j = Double(i % 2 == 0 ? 1 : -1) * noise
            out.append(ScannedWall(start: t(pts[i]) + Vec2(j, 0), end: t(pts[(i + 1) % 4]) + Vec2(0, j), height: 2.6))
        }
        return out
    }

    func rotated(_ p: Vec2, _ deg: Double = 30) -> Vec2 {
        let a = deg * .pi / 180
        return Vec2(p.x * cos(a) - p.y * sin(a) + 10, p.x * sin(a) + p.y * cos(a) - 5)
    }

    func testClosedRoomRecovered() throws {
        let r = try XCTUnwrap(ScanImport.convert(walls: walls(), openings: []))
        XCTAssertEqual(r.warnings, [])
        XCTAssertEqual(r.room.polygon.count, 4)
        XCTAssertEqual(r.room.area, 12, accuracy: 0.5)
        XCTAssertGreaterThan(Geometry.signedArea(r.room.polygon), 0)
        XCTAssertEqual(r.room.height ?? 0, 2.6, accuracy: 1e-9)
        // 已对齐坐标轴：每条边接近水平或竖直
        for w in r.room.walls { XCTAssertLessThan(min(abs(w.direction.x), abs(w.direction.y)), 0.05) }
    }

    func testOpeningsMatchedToWalls() throws {
        let ops = [ScannedOpening(kind: .window, center: rotated(Vec2(2, 0.05)), width: 1.2),
                   ScannedOpening(kind: .door(isOpen: true), center: rotated(Vec2(4, 1.5)), width: 0.9),
                   ScannedOpening(kind: .window, center: rotated(Vec2(2, 1.5)), width: 1)] // 房间中央：无法匹配
        let r = try XCTUnwrap(ScanImport.convert(walls: walls(), openings: ops))
        XCTAssertEqual(r.room.openings.count, 2)
        XCTAssertTrue(r.warnings.contains(.openingUnmatched(1)))
        XCTAssertEqual(r.room.openings.first { $0.kind == .door }?.openFraction, 1)
        XCTAssertEqual(r.room.openings.first { $0.kind == .window }?.openFraction, 0, "扫描无法判断窗开关，默认关闭")
        XCTAssertFalse(r.warnings.contains { if case .validation = $0 { return true }; return false })
    }

    func testOpenChainApproximated() throws {
        let r = try XCTUnwrap(ScanImport.convert(walls: walls(closed: false), openings: []))
        XCTAssertTrue(r.warnings.contains(.notClosedApproximated))
        XCTAssertEqual(r.room.polygon.count, 4)
        XCTAssertEqual(r.room.area, 12, accuracy: 1.0)
    }

    func testTooFewWalls() {
        XCTAssertNil(ScanImport.convert(walls: Array(walls().prefix(1)), openings: []))
    }

    func testRoomPlanCoordinateConvention() {
        // RoomPlan：中心 (x=1, z=-2)，局部 x 轴 (1,0,0)，宽 4 → 户型 (−1..3, 2)
        let w = ScannedWall(centerX: 1, centerZ: -2, axisX: 1, axisZ: 0, width: 4, height: 2.5)
        XCTAssertEqual(w.start, Vec2(-1, 2)); XCTAssertEqual(w.end, Vec2(3, 2))
        XCTAssertEqual(ScannedOpening(kind: .window, centerX: 0, centerZ: 1, width: 1).center, Vec2(0, -1))
    }

    func testImportedRoomIsEditable() throws {
        let r = try XCTUnwrap(ScanImport.convert(walls: walls(), openings: []))
        var e = RoomEditor(room: r.room)
        XCTAssertNoThrow(try e.addOpening(.window, near: e.room.walls[0].point(atOffset: 2)))
        XCTAssertNoThrow(try e.moveVertex(2, to: e.room.polygon[2] + Vec2(0.5, 0)))
    }
}
