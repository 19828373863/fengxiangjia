import XCTest
@testable import FXJCore

final class ObstructionTests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func home(_ west: ObstructionLevel?, floor: Int? = 8) -> Home {
        var r = Room(name: "r", polygon: [Vec2(0, 0), Vec2(4, 0), Vec2(4, 3), Vec2(0, 3)])
        r.openings = [Opening(wallId: r.wallIds[3], kind: .window, offset: 0.9, width: 1.2, exteriorObstruction: west),
                      Opening(wallId: r.wallIds[1], kind: .window, offset: 0.9, width: 1.2, exteriorObstruction: .open)]
        var h = Home(name: "h", floorLevel: floor, rooms: [r]); Topology.refreshConnections(&h)
        return h
    }

    func solve(_ h: Home) -> SimulationResult {
        let env = ObstructionModel.apply(Environment(source: .manual, observedAt: t0, windFromDegrees: 270, windSpeed: 3), home: h)
        return AirflowSolver.solve(SimulationInput(home: h, roomId: h.rooms[0].id, environment: env))
    }

    func testFactorsOrdered() {
        let levels: [ObstructionLevel] = [.open, .partial, .nearBuilding, .lightWell]
        let f = levels.map { ObstructionModel.windFactor($0, floor: 10) }
        XCTAssertEqual(f, f.sorted(by: >))
        XCTAssertLessThan(ObstructionModel.windFactor(.open, floor: 1), ObstructionModel.windFactor(.open, floor: 10))
        XCTAssertLessThanOrEqual(ObstructionModel.windFactor(.open, floor: 30), 1)
    }

    func testMapOnlyExteriorAndDefaultsUnknown() {
        var h = home(nil)
        // 增加一间邻室及室内门：室内门不应进入遮挡表
        _ = try? h.addAdjacentRoom(to: h.rooms[0].id, wallIndex: 0, depth: 2, name: "b")
        h.rooms[0].openings.append(Opening(wallId: h.rooms[0].wallIds[0], kind: .door, offset: 1, width: 0.9))
        let m = ObstructionModel.obstructionMap(h)
        XCTAssertEqual(m.count, 2)
        XCTAssertEqual(m[h.rooms[0].openings[0].id], .unknown)
        XCTAssertNil(m[h.rooms[0].openings[2].id])
        XCTAssertEqual(ObstructionModel.exteriorOpenings(h).count, 2)
    }

    func testObstructionLowersExchangeAndExplains() {
        let open = solve(home(.open)), well = solve(home(.lightWell))
        XCTAssertGreaterThan(open.metrics!.outdoorExchange, well.metrics!.outdoorExchange)
        let wid = home(.lightWell).rooms[0].openings[0].id
        XCTAssertTrue(well.explanations.contains { if case .obstructionReduced(_, .lightWell) = $0 { return true }; return false })
        XCTAssertNotEqual(wid, UUID())
    }

    func testFloorAndObstructionChangeHash() {
        let h = home(.open)
        let base = ScenarioHash.compute(SimulationInput(home: h, roomId: h.rooms[0].id,
                                                        environment: ObstructionModel.apply(.manual(windFromDegrees: 270, windSpeed: 3, at: t0), home: h)))
        var f = h; f.floorLevel = 1
        let hf = ScenarioHash.compute(SimulationInput(home: f, roomId: f.rooms[0].id,
                                                      environment: ObstructionModel.apply(.manual(windFromDegrees: 270, windSpeed: 3, at: t0), home: f)))
        var o = h; o.rooms[0].openings[0].exteriorObstruction = .nearBuilding
        let ho = ScenarioHash.compute(SimulationInput(home: o, roomId: o.rooms[0].id,
                                                      environment: ObstructionModel.apply(.manual(windFromDegrees: 270, windSpeed: 3, at: t0), home: o)))
        XCTAssertNotEqual(base, hf); XCTAssertNotEqual(base, ho)
    }

    func testLegacyOpeningDecodesWithoutObstruction() throws {
        let json = #"{"id":"\#(UUID().uuidString)","wallId":"\#(UUID().uuidString)","kind":"window","offset":1,"width":1,"openFraction":1,"locked":false,"connects":[]}"#
        let o = try JSONDecoder().decode(Opening.self, from: Data(json.utf8))
        XCTAssertNil(o.exteriorObstruction)
    }

    func testApplyNilEnvironment() {
        XCTAssertNil(ObstructionModel.apply(nil, home: home(.open)))
    }
}
