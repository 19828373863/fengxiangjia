import XCTest
@testable import FXJCore

final class LayoutSearchTests: XCTestCase {
    func home(yaw: Double, draftTarget: Bool = false) -> Home {
        var r = Room(name: "r", polygon: [Vec2(0, 0), Vec2(4, 0), Vec2(4, 3), Vec2(0, 3)])
        r.fans = [Fan(position: Vec2(1, 1.5), yawDegrees: yaw, power: 0.8)]
        r.targets = [Target(name: "沙发", shape: .circle(center: Vec2(3.2, 1.5), radius: 0.5), objective: .wantBreeze)]
        if draftTarget { r.targets.append(Target(name: "婴儿床", shape: .circle(center: Vec2(2, 1.5), radius: 0.3), objective: .avoidDirectDraft)) }
        return Home(name: "h", rooms: [r])
    }

    func testThreeCandidatesAndStrongerImproves() {
        let h = home(yaw: 180)
        let c = LayoutSearch.search(home: h, roomId: h.rooms[0].id, environment: nil)
        XCTAssertEqual(c.map(\.kind), [.leastEffort, .strongerBreeze, .lessDraft])
        let stronger = c[1]
        XCTAssertGreaterThanOrEqual(stronger.breeze, .medium)
        // 送风更强候选应大致朝向目标（+X）
        let yaw = stronger.fan.yawDegrees * .pi / 180
        let toTarget = (Vec2(3.2, 1.5) - stronger.fan.position).normalized
        XCTAssertGreaterThan(Vec2(cos(yaw), sin(yaw)).dot(toTarget), 0.5)
        // 当前朝背面 → 最省事也应有所改变
        XCTAssertFalse(c[0].isCurrent)
    }

    func testLeastEffortKeepsGoodLayout() {
        let h = home(yaw: 0)
        let c = LayoutSearch.search(home: h, roomId: h.rooms[0].id, environment: nil)
        XCTAssertLessThanOrEqual(LayoutSearch.moveCost(h.rooms[0].fans[0], c[0].fan),
                                 LayoutSearch.moveCost(h.rooms[0].fans[0], c[1].fan) + 1e-9)
    }

    func testLessDraftAvoidsSensitiveTarget() {
        let h = home(yaw: 0, draftTarget: true)
        let c = LayoutSearch.search(home: h, roomId: h.rooms[0].id, environment: nil)
        let draft = { (x: LayoutSearch.Candidate) in x.result.metrics!.targets.first { $0.objective == .avoidDirectDraft }!.draftExposure }
        XCTAssertLessThanOrEqual(draft(c[2]), draft(c[1]) + 1e-9)
        XCTAssertLessThanOrEqual(c[2].draft, .low)
    }

    func testCandidatesRespectPlacementAndShareResult() {
        var h = home(yaw: 90)
        h.rooms[0].obstacles = [Obstacle(kind: .wardrobe, center: Vec2(2, 2.5), size: Vec2(1.5, 0.6), heightClass: .tall)]
        for c in LayoutSearch.search(home: h, roomId: h.rooms[0].id, environment: nil) {
            XCTAssertNil(FanPlacement.check(c.fan.position, in: h.rooms[0]))
            var hh = h; hh.rooms[0] = c.room
            XCTAssertTrue(c.result.isCurrent(for: SimulationInput(home: hh, roomId: hh.rooms[0].id, environment: nil)),
                          "候选结果必须与其布局同源")
        }
    }

    func testDeterministic() {
        let h = home(yaw: 270)
        let a = LayoutSearch.search(home: h, roomId: h.rooms[0].id, environment: nil)
        let b = LayoutSearch.search(home: h, roomId: h.rooms[0].id, environment: nil)
        XCTAssertEqual(a.map(\.fan), b.map(\.fan))
    }

    func testNoFanNoCandidates() {
        var h = home(yaw: 0); h.rooms[0].fans = []
        XCTAssertTrue(LayoutSearch.search(home: h, roomId: h.rooms[0].id, environment: nil).isEmpty)
    }
}
