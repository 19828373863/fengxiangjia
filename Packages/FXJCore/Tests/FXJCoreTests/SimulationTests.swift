import XCTest
@testable import FXJCore

final class SimulationTests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func testSHA256Vectors() {
        XCTAssertEqual(SHA256.hex(Data("abc".utf8)), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertEqual(SHA256.hex(Data()), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        XCTAssertEqual(SHA256.hex(Data(String(repeating: "a", count: 1000).utf8)),
                       "41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3")
    }

    /// 4×3 房间，风扇在 (0.8,1.5) 朝 +X，目标在 (3,1.5)。
    func makeRoom(windows: [Int] = [], fanYaw: Double = 0) -> Home {
        var r = Room(name: "r", polygon: [Vec2(0, 0), Vec2(4, 0), Vec2(4, 3), Vec2(0, 3)])
        for w in windows { r.openings.append(Opening(wallId: r.wallIds[w], kind: .window, offset: (r.walls[w].length - 1.2) / 2, width: 1.2)) }
        r.fans = [Fan(kind: .pedestal, position: Vec2(0.8, 1.5), yawDegrees: fanYaw, power: 0.8)]
        r.targets = [Target(name: "t", shape: .circle(center: Vec2(3, 1.5), radius: 0.5), objective: .wantBreeze)]
        var h = Home(name: "h", rooms: [r])
        Topology.refreshConnections(&h)
        return h
    }

    func input(_ h: Home, wind: Double? = 3, from: Double = 270) -> SimulationInput {
        SimulationInput(home: h, roomId: h.rooms[0].id,
                        environment: wind.map { Environment(source: .manual, observedAt: t0, windFromDegrees: from, windSpeed: $0) })
    }

    func testDeterministic() {
        let i = input(makeRoom(windows: [3, 1]))
        let a = AirflowSolver.solve(i), b = AirflowSolver.solve(i)
        XCTAssertEqual(a, b)
        XCTAssertEqual(a.scenarioHash.count, 64)
        XCTAssertEqual(a.status, .ok)
    }

    func testClosedRoomNoOutdoorExchange() {
        for wind in [nil, 0.0, 10.0] as [Double?] {
            let r = AirflowSolver.solve(input(makeRoom(), wind: wind))
            XCTAssertEqual(r.metrics?.outdoorExchange, Level.none)
            XCTAssertEqual(r.metrics?.hasOutdoorPath, false)
            XCTAssertTrue(r.explanations.contains(.closedRoomInternalOnly))
            XCTAssertGreaterThan(r.metrics!.circulationCoverage, 0, "风扇仍应产生室内循环")
        }
    }

    func testClosedWindowsCountAsClosed() {
        var h = makeRoom(windows: [3, 1])
        for i in h.rooms[0].openings.indices { h.rooms[0].openings[i].openFraction = 0 }
        XCTAssertEqual(AirflowSolver.solve(input(h)).metrics?.outdoorExchange, Level.none)
    }

    func testCrossVentilation() {
        // 西墙(3) 与 东墙(1) 开窗，西风（来自 270°，吹向 +X）
        let r = AirflowSolver.solve(input(makeRoom(windows: [3, 1])))
        XCTAssertEqual(r.metrics?.crossVentilation, true)
        XCTAssertGreaterThanOrEqual(r.metrics!.outdoorExchange, .medium)
        XCTAssertTrue(r.explanations.contains(.crossVentilation))
    }

    func testSingleSided() {
        let r = AirflowSolver.solve(input(makeRoom(windows: [3])))
        XCTAssertEqual(r.metrics?.crossVentilation, false)
        XCTAssertEqual(r.metrics?.outdoorExchange, .low)
        XCTAssertTrue(r.explanations.contains(.singleSidedOpening))
    }

    func testObstructionReducesExchange() {
        var h = makeRoom(windows: [3, 1])
        let open = AirflowSolver.solve(input(h, wind: 2.5)).metrics!.outdoorExchange
        var i = input(h, wind: 2.5)
        i.environment!.obstructionByOpening = [h.rooms[0].openings[0].id: .lightWell]
        h = i.home
        let blocked = AirflowSolver.solve(i).metrics!.outdoorExchange
        XCTAssertLessThanOrEqual(blocked, open)
        XCTAssertLessThan(blocked, .high)
    }

    func testFanTowardTargetCoversIt() {
        let toward = AirflowSolver.solve(input(makeRoom(fanYaw: 0), wind: nil))
        let away = AirflowSolver.solve(input(makeRoom(fanYaw: 180), wind: nil))
        XCTAssertGreaterThan(toward.metrics!.targets[0].meanSpeed, away.metrics!.targets[0].meanSpeed * 3)
        XCTAssertGreaterThan(toward.metrics!.targets[0].breezeCoverage, 0.5)
        XCTAssertTrue(toward.explanations.contains { if case .targetCovered = $0 { return true }; return false })
        XCTAssertTrue(away.explanations.contains { if case .targetMissed = $0 { return true }; return false })
    }

    func testWardrobeBlocksJet() {
        var h = makeRoom()
        h.rooms[0].obstacles = [Obstacle(kind: .wardrobe, center: Vec2(2, 1.5), size: Vec2(0.6, 2.0), heightClass: .tall)]
        let blocked = AirflowSolver.solve(input(h, wind: nil))
        let open = AirflowSolver.solve(input(makeRoom(), wind: nil))
        XCTAssertLessThan(blocked.metrics!.targets[0].meanSpeed, open.metrics!.targets[0].meanSpeed * 0.5)
        // 衣柜格子速度为 0
        let f = blocked.field!
        let (ix, iy) = f.cell(of: Vec2(2, 1.5))!
        XCTAssertFalse(f.mask[f.index(ix, iy)])
        XCTAssertEqual(f.speed(at: f.index(ix, iy)), 0)
    }

    func testFanFacingWallFlagged() {
        var h = makeRoom()
        h.rooms[0].fans[0].position = Vec2(0.3, 1.5)
        h.rooms[0].fans[0].yawDegrees = 180
        let r = AirflowSolver.solve(input(h, wind: nil))
        XCTAssertTrue(r.explanations.contains(.fanBlocked(fanId: h.rooms[0].fans[0].id)))
    }

    func testDraftTarget() {
        var h = makeRoom()
        h.rooms[0].targets[0].objective = .avoidDirectDraft
        h.rooms[0].targets[0].shape = .circle(center: Vec2(1.6, 1.5), radius: 0.3)
        h.rooms[0].fans[0].power = 1
        let r = AirflowSolver.solve(input(h, wind: nil))
        XCTAssertTrue(r.explanations.contains(.targetDraft(targetId: h.rooms[0].targets[0].id)))
    }

    func testHashSensitivityAndInvalidation() {
        let h = makeRoom(windows: [3])
        let base = input(h)
        let result = AirflowSolver.solve(base)
        XCTAssertTrue(result.isCurrent(for: base))

        var moved = h; moved.rooms[0].fans[0].position.x += 0.05
        XCTAssertFalse(result.isCurrent(for: input(moved)))
        var tiny = h; tiny.rooms[0].fans[0].position.x += 0.0001 // < 1 mm 量化
        XCTAssertTrue(result.isCurrent(for: input(tiny)))
        var closed = h; closed.rooms[0].openings[0].openFraction = 0.5
        XCTAssertFalse(result.isCurrent(for: input(closed)))
        XCTAssertFalse(result.isCurrent(for: input(h, wind: 4)))
        XCTAssertFalse(result.isCurrent(for: input(h, from: 0)))
        var rotated = h; rotated.orientationDegrees = 45
        XCTAssertFalse(result.isCurrent(for: input(rotated)))
    }

    func testHashIgnoresDictionaryOrder() {
        let h = makeRoom(windows: [3, 1])
        let ids = h.rooms[0].openings.map(\.id)
        var a = input(h), b = input(h)
        a.environment!.obstructionByOpening = [ids[0]: .open, ids[1]: .partial]
        var d: [EntityID: ObstructionLevel] = [:]; d[ids[1]] = .partial; d[ids[0]] = .open
        b.environment!.obstructionByOpening = d
        XCTAssertEqual(ScenarioHash.compute(a), ScenarioHash.compute(b))
    }

    func testInvalidRoomReportsStatus() {
        var h = makeRoom()
        h.rooms[0].polygon = [Vec2(0, 0), Vec2(2, 2), Vec2(2, 0), Vec2(0, 2)]
        let r = AirflowSolver.solve(input(h))
        guard case .invalidInput = r.status else { return XCTFail() }
        XCTAssertNil(r.field)
    }

    func testIndirectPathViaNeighbor() {
        // A 无外窗，经门通 B，B 有外窗 → A 有对外通路
        var a = Room(name: "A", polygon: [Vec2(0, 0), Vec2(4, 0), Vec2(4, 3), Vec2(0, 3)])
        var b = Room(name: "B", polygon: [Vec2(4, 0), Vec2(7, 0), Vec2(7, 3), Vec2(4, 3)])
        a.openings = [Opening(wallId: a.wallIds[1], kind: .door, offset: 1, width: 0.9)]
        b.openings = [Opening(wallId: b.wallIds[1], kind: .window, offset: 1, width: 1)]
        a.fans = [Fan(position: Vec2(2, 1.5))]
        let h = Home(name: "h", rooms: [a, b])
        let r = AirflowSolver.solve(SimulationInput(home: h, roomId: a.id, environment: nil))
        XCTAssertEqual(r.metrics?.hasOutdoorPath, true)
        XCTAssertTrue(r.explanations.contains(.noEnvironment))
        // 关门后 A 封闭
        var h2 = h; h2.rooms[0].openings[0].openFraction = 0
        XCTAssertEqual(AirflowSolver.solve(SimulationInput(home: h2, roomId: a.id, environment: nil)).metrics?.outdoorExchange, Level.none)
    }

    func testSampleRoomSolvesQuickly() {
        let doc = SampleData.sampleDocument()
        let start = Date()
        let r = AirflowSolver.solve(SimulationInput(home: doc.home, roomId: SampleData.roomId, environment: nil))
        XCTAssertEqual(r.status, .ok)
        XCTAssertLessThan(Date().timeIntervalSince(start), 5)
    }
}
