import XCTest
@testable import FXJCore

final class OrientationTests: XCTestCase {
    func testCompassCalibration() {
        // 手机顶部对准户型 +Y，读数 90°（东）→ 户型 +Y 指向东
        XCTAssertEqual(Bearing.orientation(heading: 90, planDirection: Vec2(0, 1)), 90, accuracy: 1e-9)
        // 手机对准户型 +X，读数 0°（北）→ +Y 指向西（270°）
        XCTAssertEqual(Bearing.orientation(heading: 0, planDirection: Vec2(1, 0)), 270, accuracy: 1e-9)
    }

    func testWallFacing() {
        let r = Room(name: "r", polygon: [Vec2(0, 0), Vec2(4, 0), Vec2(4, 3), Vec2(0, 3)])
        // orientation 0：南墙（索引 0，外法线 -Y）朝南
        XCTAssertEqual(Bearing.name(r.walls[0].facingBearing(orientation: 0)), "南")
        XCTAssertEqual(Bearing.name(r.walls[1].facingBearing(orientation: 0)), "东")
        // 整体顺时针转 90°：南墙朝西
        XCTAssertEqual(Bearing.name(r.walls[0].facingBearing(orientation: 90)), "西")
    }

    func testCalibrationConsistentWithWindVector() {
        // 校准后，从窗外吹来的风（来向 = 窗朝向）在户型中应指向窗的内侧
        let r = Room(name: "r", polygon: [Vec2(0, 0), Vec2(4, 0), Vec2(4, 3), Vec2(0, 3)])
        let o = Bearing.orientation(heading: 37, planDirection: Vec2(0, 1))
        let wall = r.walls[1]
        let env = Environment(source: .manual, observedAt: Date(), windFromDegrees: wall.facingBearing(orientation: o))
        let v = env.windToVector(homeOrientationDegrees: o)
        XCTAssertEqual(v.dot(wall.outwardNormal), -1, accuracy: 1e-9)
    }

    func testCircularMean() {
        XCTAssertEqual(Bearing.circularMean([350, 10, 0])!, 0, accuracy: 1e-6)
        XCTAssertEqual(Bearing.circularMean([88, 92])!, 90, accuracy: 1e-6)
        XCTAssertNil(Bearing.circularMean([0, 90, 180, 270]), "读数分散应拒绝")
        XCTAssertNil(Bearing.circularMean([]))
    }

    func testNames() {
        XCTAssertEqual(Bearing.name(359), "北")
        XCTAssertEqual(Bearing.name(135), "东南")
        XCTAssertEqual(Bearing.name(-90), "西")
    }

    func testParseAndCoarsen() throws {
        let a = try Location.parse("1.3521, 103.8198")
        XCTAssertEqual(a.latitude, 1.3521); XCTAssertEqual(a.longitude, 103.8198)
        let b = try Location.parse("北纬31.23 东经121.47")
        XCTAssertEqual(b.latitude, 31.23); XCTAssertEqual(b.longitude, 121.47)
        let c = try Location.parse("33.87S 151.21E")
        XCTAssertEqual(c.latitude, -33.87)
        XCTAssertThrowsError(try Location.parse("abc")) { XCTAssertEqual($0 as? LocationError, .unparsable) }
        XCTAssertThrowsError(try Location.parse("95, 10")) { XCTAssertEqual($0 as? LocationError, .outOfRange) }
        let coarse = a.coarsened()
        XCTAssertEqual(coarse.latitude, 1.35, accuracy: 1e-9)
        XCTAssertEqual(coarse.longitude, 103.82, accuracy: 1e-9)
    }
}
