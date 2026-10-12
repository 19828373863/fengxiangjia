import XCTest
@testable import FXJCore

final class ComparisonTests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func base(windows: [Int] = []) -> Home {
        var r = Room(name: "r", polygon: [Vec2(0, 0), Vec2(4, 0), Vec2(4, 3), Vec2(0, 3)])
        for w in windows { r.openings.append(Opening(wallId: r.wallIds[w], kind: .window, offset: (r.walls[w].length - 1.2) / 2, width: 1.2)) }
        r.fans = [Fan(position: Vec2(0.8, 1.5), yawDegrees: 0, power: 0.8)]
        r.targets = [Target(name: "沙发", shape: .circle(center: Vec2(3, 1.5), radius: 0.5), objective: .wantBreeze)]
        var h = Home(name: "h", rooms: [r]); Topology.refreshConnections(&h)
        return h
    }

    func scenario(_ h: Home, name: String, yaw: Double, env: Environment? = nil, rev: Int = 0) -> Scenario {
        var r = h.rooms[0]; r.fans[0].yawDegrees = yaw
        return .capture(name: name, room: r, geometryRevision: rev, environment: env, createdAt: t0)
    }

    func testFanTowardTargetPreferred() throws {
        let h = base()
        let a = scenario(h, name: "A", yaw: 0), b = scenario(h, name: "B", yaw: 180)
        let c = try XCTUnwrap(ScenarioComparison.compare(home: h, roomId: h.rooms[0].id, a: a, b: b, currentGeometryRevision: 0, environment: nil))
        XCTAssertEqual(c.dimensions.first { $0.kind == .targetBreeze }?.preferred, .a)
        XCTAssertTrue(c.suggestions.contains(.preferForBreeze(.a)))
        XCTAssertLessThanOrEqual(c.suggestions.count, 3)
        XCTAssertTrue(c.notes.contains(.closedRoomNoExchange))
        XCTAssertEqual(c.dimensions.first { $0.kind == .outdoorExchange }?.preferred, .similar)
    }

    func testSameEnvironmentEnforced() throws {
        let h = base(windows: [3, 1])
        let e1 = Environment(source: .manual, observedAt: t0, windFromDegrees: 270, windSpeed: 3)
        let e2 = Environment(source: .manual, observedAt: t0, windFromDegrees: 90, windSpeed: 1)
        let a = scenario(h, name: "A", yaw: 0, env: e1), b = scenario(h, name: "B", yaw: 0, env: e2)
        let c = try XCTUnwrap(ScenarioComparison.compare(home: h, roomId: h.rooms[0].id, a: a, b: b, currentGeometryRevision: 0, environment: e1))
        XCTAssertTrue(c.notes.contains(.environmentUnified))
        // 相同布局 + 同一环境 → 结果完全一致
        XCTAssertEqual(c.resultA.metrics, c.resultB.metrics)
        XCTAssertTrue(c.dimensions.filter { $0.kind != .adjustmentCost }.allSatisfy { $0.preferred == .similar })
        XCTAssertEqual(c.dimensions.first { $0.kind == .adjustmentCost }?.levelB, Level.none)
    }

    func testIdenticalScenariosSuggestSimilar() throws {
        let h = base()
        let a = scenario(h, name: "A", yaw: 0)
        let c = try XCTUnwrap(ScenarioComparison.compare(home: h, roomId: h.rooms[0].id, a: a, b: a, currentGeometryRevision: 0, environment: nil))
        XCTAssertTrue(c.suggestions.contains(.similarChooseCheaper(.a)))
    }

    func testGeometryChangedNoted() throws {
        let h = base()
        let a = scenario(h, name: "A", yaw: 0, rev: 0), b = scenario(h, name: "B", yaw: 90, rev: 2)
        let c = try XCTUnwrap(ScenarioComparison.compare(home: h, roomId: h.rooms[0].id, a: a, b: b, currentGeometryRevision: 2, environment: nil))
        XCTAssertTrue(c.notes.contains(.geometryChanged(.a)))
        XCTAssertFalse(c.notes.contains(.geometryChanged(.b)))
    }

    func testOpeningStateApplied() throws {
        let h = base(windows: [3, 1])
        let env = Environment(source: .manual, observedAt: t0, windFromDegrees: 270, windSpeed: 3)
        var closed = h.rooms[0]; for i in closed.openings.indices { closed.openings[i].openFraction = 0 }
        let a = Scenario.capture(name: "开窗", room: h.rooms[0], geometryRevision: 0, environment: env)
        let b = Scenario.capture(name: "关窗", room: closed, geometryRevision: 0, environment: env)
        let c = try XCTUnwrap(ScenarioComparison.compare(home: h, roomId: h.rooms[0].id, a: a, b: b, currentGeometryRevision: 0, environment: env))
        let d = try XCTUnwrap(c.dimensions.first { $0.kind == .outdoorExchange })
        XCTAssertEqual(d.levelB, Level.none)
        XCTAssertEqual(d.preferred, .a)
        XCTAssertGreaterThan(c.dimensions.first { $0.kind == .adjustmentCost }!.levelB, Level.none)
    }

    func testAdjustmentCostLevels() {
        let h = base()
        var moved = h.rooms[0]; moved.fans[0].position = Vec2(3, 2.5)
        XCTAssertEqual(ScenarioComparison.adjustmentCost(from: h.rooms[0], to: h.rooms[0]), Level.none)
        XCTAssertGreaterThanOrEqual(ScenarioComparison.adjustmentCost(from: h.rooms[0], to: moved), .medium)
        var rotated = h.rooms[0]; rotated.fans[0].yawDegrees = 90
        XCTAssertEqual(ScenarioComparison.adjustmentCost(from: h.rooms[0], to: rotated), .low)
    }

    func testCaptureApplyRoundTrip() {
        let h = base(windows: [3])
        let s = Scenario.capture(name: "A", room: h.rooms[0], geometryRevision: 0, environment: nil)
        XCTAssertEqual(s.applied(to: h.rooms[0]), h.rooms[0])
    }
}
