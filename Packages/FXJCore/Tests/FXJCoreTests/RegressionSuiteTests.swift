import XCTest
@testable import FXJCore

/// #21 综合回归：确定性基准、存储迁移夹具、天气故障矩阵、性能预算。
final class RegressionSuiteTests: XCTestCase {
    func testGoldenScenariosDeterministic() {
        for c in GoldenScenarios.cases {
            let r = AirflowSolver.solve(GoldenScenarios.input(c))
            XCTAssertEqual(r.scenarioHash, c.scenarioHash, c.name)
            let m = try! XCTUnwrap(r.metrics)
            XCTAssertEqual(m.outdoorExchange, c.outdoorExchange, c.name)
            XCTAssertEqual(m.crossVentilation, c.crossVentilation, c.name)
            XCTAssertEqual(m.circulationCoverage, c.circulationCoverage, accuracy: 2e-3, c.name)
            XCTAssertEqual(m.meanSpeed, c.meanSpeed, accuracy: 2e-3, c.name)
            XCTAssertEqual(m.targets[0].breezeCoverage, c.targetBreeze, accuracy: 2e-3, c.name)
        }
    }

    func testRepeatedSolveBitIdentical() {
        let i = GoldenScenarios.input(GoldenScenarios.cases[1])
        let a = AirflowSolver.solve(i), b = AirflowSolver.solve(i)
        XCTAssertEqual(a, b)
    }

    /// 冻结的 v1 文件夹具（不依赖当前编码器生成）。
    func testFrozenV1FixtureMigrates() throws {
        let v1 = """
        {"schemaVersion":1,"geometryRevision":3,"scenarios":[],
         "home":{"id":"00000000-0000-0000-0000-00000000F001","name":"旧家","orientationDegrees":90,"constraints":[],
          "rooms":[{"id":"00000000-0000-0000-0000-00000000F002","name":"卧室","height":2.7,"purpose":"bedroom",
            "polygon":[{"x":0,"y":0},{"x":3,"y":0},{"x":3,"y":3},{"x":0,"y":3}],
            "wallIds":["00000000-0000-0000-0000-0000000A0001","00000000-0000-0000-0000-0000000A0002","00000000-0000-0000-0000-0000000A0003","00000000-0000-0000-0000-0000000A0004"],
            "openings":[],"obstacles":[],"fans":[],"targets":[]}]}}
        """
        do {
            let d = try DocumentCodec.decode(Data(v1.utf8))
            XCTAssertEqual(d.schemaVersion, 2)
            XCTAssertEqual(d.home.rooms.first?.name, "卧室")
            XCTAssertEqual(d.home.orientationDegrees, 90)
            XCTAssertEqual(d.geometryRevision, 3)
            XCTAssertEqual(d.feedback, [])
        } catch {
            XCTFail("v1 夹具应能迁移：\(error)")
        }
    }

    func testFullDocumentRoundTripPreservesEverything() throws {
        var d = SampleData.sampleDocument()
        d.home.location = Location(latitude: 1.35, longitude: 103.82).coarsened()
        d.home.floorLevel = 12
        d.home.rooms[0].openings[0].exteriorObstruction = .nearBuilding
        d.scenarios = [Scenario.capture(name: "A", room: d.home.rooms[0], geometryRevision: 0,
                                        environment: GoldenScenarios.cases[1].environment,
                                        createdAt: GoldenScenarios.observedAt)]  // 存储时间精度为秒
        d.feedback = [ComfortFeedback(roomId: SampleData.roomId, scenarioHash: "x", rating: .justRight, createdAt: GoldenScenarios.observedAt)]
        let back = try DocumentCodec.decode(DocumentCodec.encode(d))
        XCTAssertEqual(back, d)
    }

    func testWeatherFailureMatrix() async {
        let loc = Location(latitude: 1.35, longitude: 103.82)
        let t0 = GoldenScenarios.observedAt
        for e in [WeatherError.offline, .notAuthorized, .unavailable("x")] {
            let s = WeatherService(provider: MockWeatherProvider(failure: e))
            let o = await s.environment(for: loc, now: t0)
            XCTAssertNil(o.environment, "\(e) 无缓存时不应产生环境")
        }
        let s = WeatherService(provider: MockWeatherProvider())
        let none = await s.environment(for: nil, now: t0)
        XCTAssertNil(none.environment)
    }

    // MARK: 性能预算（Debug 构建、CI 共享机，预算宽松；Release 实测见 docs/TEST_REPORT.md）
    func testSolveWithinBudget() {
        let i = GoldenScenarios.input(GoldenScenarios.cases[1])
        let t = Date()
        for _ in 0..<5 { _ = AirflowSolver.solve(i) }
        XCTAssertLessThan(Date().timeIntervalSince(t) / 5, 0.5, "单次求解应 < 0.5 s（Debug）")
    }

    func testLargeRoomGridCapped() {
        var d = SampleData.sampleDocument()
        d.home.rooms[0] = Room(id: SampleData.roomId, name: "大厅", polygon: [Vec2(0, 0), Vec2(30, 0), Vec2(30, 20), Vec2(0, 20)])
        d.home.rooms[0].fans = [Fan(position: Vec2(15, 10))]
        let i = SimulationInput(home: d.home, roomId: SampleData.roomId, environment: nil)
        let t = Date(); let r = AirflowSolver.solve(i)
        XCTAssertLessThanOrEqual(max(r.field!.nx, r.field!.ny), i.options.maxCellsPerAxis)
        XCTAssertLessThan(Date().timeIntervalSince(t), 1.0)
    }

    func testLayoutSearchWithinBudget() {
        let d = SampleData.sampleDocument()
        let t = Date()
        let c = LayoutSearch.search(home: d.home, roomId: SampleData.roomId, environment: GoldenScenarios.cases[1].environment)
        XCTAssertEqual(c.count, 3)
        XCTAssertLessThan(Date().timeIntervalSince(t), 8, "自动搜索应 < 8 s（Debug）")
    }
}
