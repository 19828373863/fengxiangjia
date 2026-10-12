import XCTest
@testable import FXJCore

/// #18 环境输入与模型联动回归：任何输入变化使旧结果过期，并能说出原因；比较锁定同一环境。
final class EnvironmentRegressionTests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func base() -> SimulationInput {
        var r = Room(name: "r", polygon: [Vec2(0, 0), Vec2(4, 0), Vec2(4, 3), Vec2(0, 3)])
        r.openings = [Opening(wallId: r.wallIds[3], kind: .window, offset: 0.9, width: 1.2, openFraction: 1, exteriorObstruction: .open),
                      Opening(wallId: r.wallIds[1], kind: .window, offset: 0.9, width: 1.2, openFraction: 1, exteriorObstruction: .open)]
        r.fans = [Fan(kind: .pedestal, position: Vec2(2, 1.5), yawDegrees: 0, power: 0.6)]
        var h = Home(name: "h", orientationDegrees: 0, floorLevel: 5, rooms: [r]); Topology.refreshConnections(&h)
        let env = ObstructionModel.apply(Environment(source: .manual, observedAt: t0, windFromDegrees: 270, windSpeed: 3), home: h)
        return SimulationInput(home: h, roomId: r.id, environment: env)
    }

    func check(_ mutate: (inout SimulationInput) -> Void, expect: StaleReason, file: StaticString = #filePath, line: UInt = #line) {
        let a = base()
        let res = AirflowSolver.solve(a)
        XCTAssertTrue(res.isCurrent(for: a), file: file, line: line)
        var b = a; mutate(&b)
        XCTAssertFalse(res.isCurrent(for: b), "\(expect) 变化应使结果过期", file: file, line: line)
        XCTAssertTrue(Staleness.reasons(result: res, oldInput: a, newInput: b).contains(expect), file: file, line: line)
    }

    func testUnchangedIsValid() {
        let a = base(); let res = AirflowSolver.solve(a)
        let same = a  // 同一输入（同 id）重算
        XCTAssertTrue(res.isCurrent(for: same))
        XCTAssertTrue(Staleness.reasons(result: res, oldInput: a, newInput: same).isEmpty)
    }

    func testWeatherChangesInvalidate() {
        check({ $0.environment?.windFromDegrees = 90 }, expect: .weather)
        check({ $0.environment?.windSpeed = 6 }, expect: .weather)
        check({ $0.environment?.observedAt = self.t0.addingTimeInterval(3600) }, expect: .weather)
        check({ $0.environment = nil }, expect: .weather)
    }

    func testOrientationFloorObstructionInvalidate() {
        check({ $0.home.orientationDegrees = 45 }, expect: .orientation)
        check({ $0.home.floorLevel = 1 }, expect: .floor)
        check({ i in let id = i.room!.openings[0].id; i.environment?.obstructionByOpening[id] = .lightWell }, expect: .obstruction)
    }

    func testOpeningsGeometryLayoutInvalidate() {
        check({ $0.home.rooms[0].openings[0].openFraction = 0.2 }, expect: .openings)
        check({ $0.home.rooms[0].polygon[2] = Vec2(4.5, 3) }, expect: .geometry)
        check({ $0.home.rooms[0].fans[0].power = 1 }, expect: .layout)
    }

    func testEnvironmentChangesResultDeterministically() {
        let a = base(); var b = a; b.environment?.windFromDegrees = 90
        let ra = AirflowSolver.solve(a), ra2 = AirflowSolver.solve(a), rb = AirflowSolver.solve(b)
        XCTAssertEqual(ra.scenarioHash, ra2.scenarioHash)
        XCTAssertEqual(ra.field?.u, ra2.field?.u)
        XCTAssertNotEqual(ra.field?.u, rb.field?.u, "风向变化应改变流场")
    }

    func testComparisonLocksSingleEnvironment() {
        let a = base(), room = a.room!
        var s1 = Scenario(name: "A", roomId: room.id, geometryRevision: 0, environmentSnapshot: a.environment, fans: room.fans)
        var s2 = s1; s2.id = UUID(); s2.name = "B"
        s2.environmentSnapshot?.windFromDegrees = 90   // 保存时天气不同
        s1.fans[0].yawDegrees = 90
        let c = ScenarioComparison.compare(home: a.home, roomId: room.id, a: s1, b: s2, currentGeometryRevision: 0, environment: a.environment)!
        XCTAssertTrue(c.notes.contains(.environmentUnified))
        // 两结果均以同一环境求解：用该环境重建输入应同时有效
        func input(_ s: Scenario) -> SimulationInput {
            var h = a.home; h.rooms[0] = s.applied(to: room)
            return SimulationInput(home: h, roomId: room.id, environment: a.environment)
        }
        XCTAssertTrue(c.resultA.isCurrent(for: input(s1)))
        XCTAssertTrue(c.resultB.isCurrent(for: input(s2)))
    }
}
