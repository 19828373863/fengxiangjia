import XCTest
@testable import FXJCore

final class GeometryTopologyTests: XCTestCase {
    let rect = [Vec2(0, 0), Vec2(4, 0), Vec2(4, 3), Vec2(0, 3)]

    func testAreaAndWalls() {
        let r = Room(name: "r", polygon: rect)
        XCTAssertEqual(r.area, 12, accuracy: 1e-9)
        XCTAssertEqual(r.walls.count, 4)
        XCTAssertEqual(r.walls[0].length, 4, accuracy: 1e-9)
        // 逆时针底边外法线指向 -Y
        XCTAssertEqual(r.walls[0].outwardNormal.y, -1, accuracy: 1e-9)
    }

    func testValidRoomHasNoIssues() {
        var r = Room(name: "r", polygon: rect)
        r.openings = [Opening(wallId: r.wallIds[0], kind: .window, offset: 1, width: 1.5)]
        r.fans = [Fan(position: Vec2(2, 1))]
        XCTAssertEqual(RoomValidator.validate(r), [])
    }

    func testSelfIntersectionDetected() {
        let bow = Room(name: "bow", polygon: [Vec2(0, 0), Vec2(2, 2), Vec2(2, 0), Vec2(0, 2)])
        XCTAssertTrue(RoomValidator.validate(bow).contains { if case .selfIntersecting = $0 { return true }; return false })
    }

    func testLShapeIsSimple() {
        let l = [Vec2(0, 0), Vec2(4, 0), Vec2(4, 2), Vec2(2, 2), Vec2(2, 4), Vec2(0, 4)]
        XCTAssertNil(Geometry.selfIntersection(l))
        XCTAssertEqual(Geometry.signedArea(l), 12, accuracy: 1e-9)
        XCTAssertFalse(Geometry.contains(l, Vec2(3, 3)))
        XCTAssertTrue(Geometry.contains(l, Vec2(1, 3)))
    }

    func testClockwiseAndDegenerate() {
        let cw = Room(name: "cw", polygon: rect.reversed())
        XCTAssertTrue(RoomValidator.validate(cw).contains(.clockwiseWinding))
        let deg = Room(name: "d", polygon: [Vec2(0, 0), Vec2(0.01, 0), Vec2(4, 0), Vec2(4, 3)])
        XCTAssertTrue(RoomValidator.validate(deg).contains(.degenerateEdge(index: 0)))
        XCTAssertEqual(RoomValidator.validate(Room(name: "x", polygon: [Vec2(0, 0), Vec2(1, 0)])), [.tooFewVertices])
    }

    func testOpeningMustStayWithinWall() {
        var r = Room(name: "r", polygon: rect)
        let o = Opening(wallId: r.wallIds[1], kind: .door, offset: 2.5, width: 0.9) // 墙长 3
        r.openings = [o]
        XCTAssertTrue(RoomValidator.validate(r).contains(.openingOutsideWall(opening: o.id)))
        let ghost = Opening(wallId: UUID(), kind: .door, offset: 0, width: 0.9)
        r.openings = [ghost]
        XCTAssertEqual(RoomValidator.validate(r), [.openingUnknownWall(opening: ghost.id)])
    }

    func testOverlappingOpeningsAndNarrow() {
        var r = Room(name: "r", polygon: rect)
        let a = Opening(wallId: r.wallIds[0], kind: .window, offset: 0.5, width: 1.5)
        let b = Opening(wallId: r.wallIds[0], kind: .window, offset: 1.5, width: 1.0)
        let c = Opening(wallId: r.wallIds[2], kind: .window, offset: 0.5, width: 0.05)
        r.openings = [a, b, c]
        let issues = RoomValidator.validate(r)
        XCTAssertTrue(issues.contains(.openingsOverlap(a.id, b.id)))
        XCTAssertTrue(issues.contains(.openingTooNarrow(opening: c.id)))
    }

    func testFanOutsideRoomFlagged() {
        var r = Room(name: "r", polygon: rect)
        let f = Fan(position: Vec2(5, 1))
        r.fans = [f]
        XCTAssertEqual(RoomValidator.validate(r), [.itemOutsideRoom(item: f.id)])
    }

    /// 两间相邻房间：A [0,4]x[0,3]，B [4,7]x[0,3]，共享 x=4 墙。
    func twoRooms(doorBetween: Bool) -> (Home, Room, Room) {
        var a = Room(name: "A", polygon: rect)
        var b = Room(name: "B", polygon: [Vec2(4, 0), Vec2(7, 0), Vec2(7, 3), Vec2(4, 3)])
        // A 南墙窗通室外
        a.openings = [Opening(wallId: a.wallIds[0], kind: .window, offset: 1, width: 1)]
        if doorBetween {
            // A 东墙 (4,0)->(4,3) 偏移 1；B 西墙 (4,3)->(4,0)
            a.openings.append(Opening(wallId: a.wallIds[1], kind: .door, offset: 1, width: 0.9))
        }
        b.fans = [Fan(position: Vec2(5, 1))]
        return (Home(name: "h", rooms: [a, b]), a, b)
    }

    func testOpeningConnectsIndoorOutdoor() {
        let (home, a, b) = twoRooms(doorBetween: true)
        XCTAssertEqual(Topology.connections(for: a.openings[0], in: a, home: home), [.room(a.id), .outdoor])
        XCTAssertEqual(Topology.connections(for: a.openings[1], in: a, home: home), [.room(a.id), .room(b.id)])
        XCTAssertTrue(Topology.reachesOutdoor(b.id, in: home))
    }

    func testNoThroughWallWithoutOpening() {
        let (home, _, b) = twoRooms(doorBetween: false)
        XCTAssertFalse(Topology.reachesOutdoor(b.id, in: home), "共享墙但无开口，B 不得连通室外")
    }

    func testClosedOpeningBlocksPath() {
        var (home, _, b) = twoRooms(doorBetween: true)
        home.rooms[0].openings[1].openFraction = 0
        XCTAssertFalse(Topology.reachesOutdoor(b.id, in: home))
    }

    func testRefreshConnections() {
        var (home, _, b) = twoRooms(doorBetween: true)
        Topology.refreshConnections(&home)
        XCTAssertEqual(home.rooms[0].openings[1].connects.last, .room(b.id))
        XCTAssertEqual(home.rooms[0].openings[0].connects.last, .outdoor)
    }

    func testNormalizeWindingKeepsOpeningPosition() {
        var r = Room(name: "cw", polygon: rect.reversed()) // 顺时针
        let wid = r.wallIds[0] // (0,3)->(4,3) 北墙
        r.openings = [Opening(wallId: wid, kind: .window, offset: 0.5, width: 1)]
        let before = r.segment(of: r.openings[0])!
        r.normalizeWinding()
        XCTAssertGreaterThan(Geometry.signedArea(r.polygon), 0)
        let after = r.segment(of: r.openings[0])!
        let s1 = Set([before.0, before.1].map { "\(round($0.x * 1000)),\(round($0.y * 1000))" })
        let s2 = Set([after.0, after.1].map { "\(round($0.x * 1000)),\(round($0.y * 1000))" })
        XCTAssertEqual(s1, s2)
        XCTAssertEqual(RoomValidator.validate(r), [])
    }
}
