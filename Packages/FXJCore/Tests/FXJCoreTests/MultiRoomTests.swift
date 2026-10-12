import XCTest
@testable import FXJCore

final class MultiRoomTests: XCTestCase {
    /// 客厅 4×3；南侧加卧室（共享南墙），东侧加厨房。
    func makeHome() throws -> (Home, EntityID, EntityID, EntityID) {
        let living = Room(name: "客厅", polygon: [Vec2(0, 0), Vec2(4, 0), Vec2(4, 3), Vec2(0, 3)])
        var h = Home(name: "h", rooms: [living])
        let bed = try h.addAdjacentRoom(to: living.id, wallIndex: 0, depth: 3, name: "卧室", purpose: .bedroom)
        let kitchen = try h.addAdjacentRoom(to: living.id, wallIndex: 1, depth: 2, name: "厨房", purpose: .kitchen)
        return (h, living.id, bed, kitchen)
    }

    func testAdjacentRoomsValidAndShareWalls() throws {
        let (h, _, bed, kitchen) = try makeHome()
        XCTAssertEqual(HomeValidator.validate(h), [])
        let b = h.rooms.first { $0.id == bed }!
        XCTAssertGreaterThan(Geometry.signedArea(b.polygon), 0)
        XCTAssertEqual(b.area, 12, accuracy: 1e-9)
        XCTAssertEqual(h.rooms.first { $0.id == kitchen }!.area, 6, accuracy: 1e-9)
    }

    func testOverlapRejected() throws {
        var (h, living, _, _) = try makeHome()
        // 客厅南墙外已有卧室，再加一间同位置房间应拒绝
        XCTAssertThrowsError(try h.addAdjacentRoom(to: living, wallIndex: 0, depth: 1, name: "重叠"))
        XCTAssertEqual(h.rooms.count, 3)
        let a = Room(name: "a", polygon: [Vec2(0, 0), Vec2(2, 0), Vec2(2, 2), Vec2(0, 2)])
        let b = Room(name: "b", polygon: [Vec2(1, 1), Vec2(3, 1), Vec2(3, 3), Vec2(1, 3)])
        XCTAssertTrue(HomeValidator.validate(Home(name: "x", rooms: [a, b])).contains(.roomsOverlap(a.id, b.id)))
    }

    func testInteriorDoorConnectsRoomsAndExteriorWindow() throws {
        var (h, living, bed, _) = try makeHome()
        let li = h.rooms.firstIndex { $0.id == living }!
        let bi = h.rooms.firstIndex { $0.id == bed }!
        let door = Opening(wallId: h.rooms[li].wallIds[0], kind: .door, offset: 1, width: 0.9)
        h.rooms[li].openings = [door]
        // 卧室南墙（索引 2：(0,-3)->(4,-3)）开外窗
        h.rooms[bi].openings = [Opening(wallId: h.rooms[bi].wallIds[2], kind: .window, offset: 1, width: 1.2)]
        Topology.refreshConnections(&h)
        XCTAssertEqual(h.rooms[li].openings[0].connects, [.room(living), .room(bed)])
        XCTAssertEqual(h.rooms[bi].openings[0].connects.last, .outdoor)
        XCTAssertTrue(Topology.reachesOutdoor(living, in: h))
        // 卧室视角能看到客厅持有的门
        XCTAssertEqual(Set(h.openingsAffecting(bed).map(\.opening.id)), [door.id, h.rooms[bi].openings[0].id])
    }

    func testSolverSeesForeignDoor() throws {
        var (h, living, bed, _) = try makeHome()
        let li = h.rooms.firstIndex { $0.id == living }!
        let bi = h.rooms.firstIndex { $0.id == bed }!
        // 门属于卧室（卧室北墙 = 索引 0：(4,0)->(0,0)），客厅西墙开外窗
        h.rooms[bi].openings = [Opening(wallId: h.rooms[bi].wallIds[0], kind: .door, offset: 1, width: 0.9)]
        h.rooms[li].openings = [Opening(wallId: h.rooms[li].wallIds[3], kind: .window, offset: 1, width: 1.2)]
        h.rooms[bi].fans = [Fan(position: Vec2(2, -1.5))]
        Topology.refreshConnections(&h)
        let r = AirflowSolver.solve(SimulationInput(home: h, roomId: bed, environment: nil))
        XCTAssertEqual(r.metrics?.hasOutdoorPath, true, "卧室经自己的门→客厅→外窗通室外")
        let r2 = AirflowSolver.solve(SimulationInput(home: h, roomId: living, environment: nil))
        XCTAssertEqual(r2.metrics?.hasOutdoorPath, true)
        // 关门：卧室封闭
        h.rooms[bi].openings[0].openFraction = 0
        XCTAssertEqual(AirflowSolver.solve(SimulationInput(home: h, roomId: bed, environment: nil)).metrics?.outdoorExchange, Level.none)
    }

    func testChainWithoutDoorsStaysClosed() throws {
        var (h, living, bed, kitchen) = try makeHome()
        let ki = h.rooms.firstIndex { $0.id == kitchen }!
        h.rooms[ki].openings = [Opening(wallId: h.rooms[ki].wallIds[2], kind: .window, offset: 0.5, width: 1)]
        Topology.refreshConnections(&h)
        XCTAssertTrue(Topology.reachesOutdoor(kitchen, in: h))
        XCTAssertFalse(Topology.reachesOutdoor(living, in: h), "无室内门不得穿墙连通")
        XCTAssertFalse(Topology.reachesOutdoor(bed, in: h))
    }

    func testRemoveRoomRefreshesConnections() throws {
        var (h, living, bed, _) = try makeHome()
        let li = h.rooms.firstIndex { $0.id == living }!
        h.rooms[li].openings = [Opening(wallId: h.rooms[li].wallIds[0], kind: .door, offset: 1, width: 0.9)]
        Topology.refreshConnections(&h)
        XCTAssertEqual(h.rooms[li].openings[0].connects.last, .room(bed))
        h.removeRoom(bed)
        XCTAssertEqual(h.rooms.first { $0.id == living }!.openings[0].connects.last, .outdoor)
    }
}
