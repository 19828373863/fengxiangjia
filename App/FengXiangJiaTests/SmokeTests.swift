import XCTest
import FXJCore
@testable import FengXiangJia

@MainActor
final class SmokeTests: XCTestCase {
    private func tempRepo() -> HomeRepository {
        HomeRepository(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
    }

    func testThreeTabsInOrder() {
        XCTAssertEqual(AppTab.allCases.map(\.title), ["我家", "试摆", "方案"])
    }

    func testCoreLinked() {
        XCTAssertFalse(FXJCore.version.isEmpty)
    }

    func testEmptyThenSample() {
        let m = AppModel(repository: tempRepo())
        XCTAssertEqual(m.state, .loading)
        m.load()
        XCTAssertEqual(m.state, .empty)
        m.openSample()
        XCTAssertEqual(m.document?.home.rooms.first?.name, "示例客厅")
    }

    func testSamplePersistsAcrossLaunch() {
        let repo = tempRepo()
        AppModel(repository: repo).openSample()
        let m2 = AppModel(repository: repo)
        m2.load()
        XCTAssertNotNil(m2.document)
    }

    func testFanCannotLeaveRoom() {
        let m = AppModel(repository: tempRepo())
        m.openSample()
        let fan = m.document!.home.rooms[0].fans[0]
        XCTAssertTrue(m.moveFan(roomId: SampleData.roomId, fanId: fan.id, to: Vec2(1, 1)))
        XCTAssertFalse(m.moveFan(roomId: SampleData.roomId, fanId: fan.id, to: Vec2(9, 9)))
        XCTAssertEqual(m.document!.home.rooms[0].fans[0].position, Vec2(1, 1))
    }

    func testFitTransformRoundTrip() {
        let t = FitTransform(polygon: [Vec2(0, 0), Vec2(5, 0), Vec2(5, 4), Vec2(0, 4)], size: CGSize(width: 300, height: 300))
        let w = t.toWorld(t.toScreen(Vec2(1.25, 3.5)))
        XCTAssertEqual(w.x, 1.25, accuracy: 1e-9); XCTAssertEqual(w.y, 3.5, accuracy: 1e-9)
    }

    func testCreateBlankAndReplaceRoomBumpsRevision() throws {
        let m = AppModel(repository: tempRepo())
        m.createBlankRoom()
        var editor = RoomEditor(room: m.document!.home.rooms[0])
        try editor.addOpening(.window, near: Vec2(2, 0))
        m.replaceRoom(editor.room)
        XCTAssertEqual(m.document!.geometryRevision, 1)
        XCTAssertEqual(m.document!.home.rooms[0].openings.first?.connects.last, .outdoor)
    }

    func testUpdateLayoutKeepsGeometryRevision() throws {
        let m = AppModel(repository: tempRepo())
        m.openSample()
        var e = RoomEditor(room: m.document!.home.rooms[0])
        try e.setFanPower(e.room.fans[0].id, 1)
        m.updateLayout(e.room)
        XCTAssertEqual(m.document!.geometryRevision, 0)
        XCTAssertEqual(m.document!.home.rooms[0].fans[0].power, 1)
    }

    func testSaveAndCompareScenarios() throws {
        let m = AppModel(repository: tempRepo())
        m.openSample()
        var room = m.document!.home.rooms[0]
        m.saveScenario(name: "A", room: room)
        room.fans[0].yawDegrees = (room.fans[0].yawDegrees + 180).truncatingRemainder(dividingBy: 360)
        m.saveScenario(name: "B", room: room)
        let doc = try XCTUnwrap(m.document)
        XCTAssertEqual(doc.scenarios.map(\.name), ["A", "B"])
        let c = ScenarioComparison.compare(home: doc.home, roomId: room.id, a: doc.scenarios[0], b: doc.scenarios[1],
                                           currentGeometryRevision: doc.geometryRevision, environment: nil)
        XCTAssertNotNil(c)
        XCTAssertLessThanOrEqual(c!.suggestions.count, 3)
        m.deleteScenario(doc.scenarios[0].id)
        XCTAssertEqual(m.document!.scenarios.count, 1)
    }

    func testFeedbackExportAndDelete() throws {
        let repo = tempRepo()
        let m = AppModel(repository: repo)
        m.openSample()
        m.addFeedback(roomId: SampleData.roomId, scenarioHash: nil, rating: .justRight)
        XCTAssertEqual(m.document?.feedback.count, 1)
        let url = try XCTUnwrap(m.exportFileURL())
        let exported = try DocumentCodec.decode(Data(contentsOf: url))
        XCTAssertEqual(exported.feedback.first?.rating, .justRight)
        XCTAssertNil(exported.home.location)
        m.resetToEmpty()
        XCTAssertEqual(m.state, .empty)
        XCTAssertEqual(repo.load(), .empty)
    }

    func testAddAndRemoveAdjacentRoom() {
        let m = AppModel(repository: tempRepo())
        m.openSample()
        XCTAssertNil(m.addAdjacentRoom(to: SampleData.roomId, wallIndex: 1))
        XCTAssertEqual(m.document?.home.rooms.count, 2)
        XCTAssertNotNil(m.addAdjacentRoom(to: SampleData.roomId, wallIndex: 1), "同方向重复添加应被拒绝")
        let newId = m.document!.home.rooms[1].id
        m.removeRoom(newId)
        XCTAssertEqual(m.document?.home.rooms.count, 1)
    }

    func testUpdateHomeInfoCoarsensLocation() {
        let m = AppModel(repository: tempRepo())
        m.openSample()
        m.updateHomeInfo(name: "我的家", location: Location(latitude: 1.352083, longitude: 103.819836), orientation: 370)
        XCTAssertEqual(m.document?.home.name, "我的家")
        XCTAssertEqual(m.document!.home.location!.latitude, 1.35, accuracy: 1e-9)
        XCTAssertEqual(m.document!.home.orientationDegrees, 10, accuracy: 1e-9)
        XCTAssertEqual(m.document!.geometryRevision, 0)
    }

    func testEnvironmentModes() async {
        let m = AppModel(repository: tempRepo(), weatherProvider: MockWeatherProvider())
        m.openSample()
        XCTAssertNil(m.currentEnvironment)
        m.environmentMode = .manual
        m.manualWindFrom = 270; m.manualWindSpeed = 3
        XCTAssertEqual(m.currentEnvironment?.source, .manual)
        XCTAssertEqual(m.environment(now: Date(timeIntervalSince1970: 1000)), m.environment(now: Date(timeIntervalSince1970: 3000)))
        m.environmentMode = .auto
        await m.refreshWeather(force: false)
        XCTAssertEqual(m.weatherOutcome?.error, .noLocation, "未设置住宅位置时不得使用手机位置")
        m.updateHomeInfo(name: "h", location: Location(latitude: 1.35, longitude: 103.82), orientation: 0)
        await m.refreshWeather(force: true)
        XCTAssertNotNil(m.currentEnvironment)
        XCTAssertEqual(m.weatherOutcome?.forecast?.isDemo, true)
    }

    func testWeatherFailureFallsBackToManual() async {
        let m = AppModel(repository: tempRepo(), weatherProvider: MockWeatherProvider(failure: .offline))
        m.openSample()
        m.updateHomeInfo(name: "h", location: Location(latitude: 1.35, longitude: 103.82), orientation: 0)
        m.environmentMode = .auto
        await m.refreshWeather(force: true)
        XCTAssertNil(m.currentEnvironment)
        XCTAssertEqual(m.weatherOutcome?.error, .offline)
    }

    func testObstructionMergedIntoEnvironment() {
        let m = AppModel(repository: tempRepo())
        m.openSample()
        let window = m.document!.home.rooms[0].openings.first { $0.kind == .window }!
        m.setObstruction(openingId: window.id, level: .nearBuilding)
        m.setFloor(3)
        m.environmentMode = .manual
        XCTAssertEqual(m.currentEnvironment?.obstructionByOpening[window.id], .nearBuilding)
        XCTAssertEqual(m.document?.home.floorLevel, 3)
    }
}
