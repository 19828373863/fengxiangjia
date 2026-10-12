import XCTest
@testable import FXJCore

final class DomainModelTests: XCTestCase {
    func makeDoc() -> HomeDocument {
        let room = Room(name: "客厅", polygon: [Vec2(0, 0), Vec2(4, 0), Vec2(4, 3), Vec2(0, 3)])
        var r = room
        r.openings = [Opening(wallId: room.wallIds[0], kind: .window, offset: 1, width: 1.2, connects: [.room(room.id), .outdoor])]
        r.fans = [Fan(position: Vec2(1, 1), yawDegrees: 45, power: 0.5)]
        r.obstacles = [Obstacle(kind: .sofa, center: Vec2(2, 2.5), size: Vec2(2, 0.8))]
        r.targets = [Target(name: "沙发", shape: .circle(center: Vec2(2, 2.5), radius: 0.6), objective: .wantBreeze)]
        let env = Environment(source: .manual, observedAt: Date(timeIntervalSince1970: 1_800_000_000),
                              windFromDegrees: 90, windSpeed: 2.5, obstructionByOpening: [r.openings[0].id: .partial])
        let home = Home(name: "我家", location: Location(latitude: 1.35, longitude: 103.82), orientationDegrees: 30, rooms: [r])
        let sc = Scenario(name: "A", roomId: r.id, geometryRevision: 0, environmentSnapshot: env, fans: r.fans,
                          createdAt: Date(timeIntervalSince1970: 1_800_000_000))
        return HomeDocument(home: home, scenarios: [sc])
    }

    func testRoundTrip() throws {
        let doc = makeDoc()
        let data = try DocumentCodec.encode(doc)
        XCTAssertEqual(try DocumentCodec.decode(data), doc)
    }

    func testEncodedContainsSchemaVersion() throws {
        let json = String(decoding: try DocumentCodec.encode(makeDoc()), as: UTF8.self)
        XCTAssertTrue(json.contains("\"schemaVersion\" : 2"))
    }

    func testWallIdsMatchVertices() {
        let r = Room(name: "x", polygon: [Vec2(0, 0), Vec2(1, 0), Vec2(0, 1)])
        XCTAssertEqual(r.wallIds.count, 3)
        XCTAssertEqual(Set(r.wallIds).count, 3)
    }

    func testFanPowerClamped() {
        XCTAssertEqual(Fan(position: .zero, power: 3).power, 1)
        XCTAssertEqual(Fan(position: .zero, power: -1).power, 0)
    }

    func testRejectsFutureVersion() {
        let data = Data(#"{"schemaVersion": 99, "home": {}}"#.utf8)
        XCTAssertThrowsError(try DocumentCodec.decode(data)) {
            XCTAssertEqual($0 as? PersistenceError, .unsupportedFutureVersion(99))
        }
    }

    func testRejectsMissingVersion() {
        XCTAssertThrowsError(try DocumentCodec.decode(Data(#"{"home": {}}"#.utf8))) {
            XCTAssertEqual($0 as? PersistenceError, .missingSchemaVersion)
        }
    }

    func testCorruptDataThrows() {
        XCTAssertThrowsError(try DocumentCodec.decode(Data("not json".utf8)))
        XCTAssertThrowsError(try DocumentCodec.decode(Data(#"{"schemaVersion": 1, "home": 3}"#.utf8))) {
            guard case .corrupt = $0 as? PersistenceError else { return XCTFail("expected corrupt, got \($0)") }
        }
    }

    func testMigrateV0() throws {
        let id = UUID().uuidString
        let v0 = """
        {"schemaVersion": 0, "home": {"id": "\(UUID().uuidString)", "name": "旧家",
          "rooms": [{"id": "\(id)", "name": "卧室", "points": [{"x":0,"y":0},{"x":3,"y":0},{"x":3,"y":3},{"x":0,"y":3}]}]}}
        """
        let doc = try DocumentCodec.decode(Data(v0.utf8))
        XCTAssertEqual(doc.schemaVersion, HomeDocument.currentSchemaVersion)
        XCTAssertEqual(doc.home.rooms.count, 1)
        XCTAssertEqual(doc.home.rooms[0].polygon.count, 4)
        XCTAssertEqual(doc.home.rooms[0].wallIds.count, 4)
        XCTAssertEqual(doc.home.rooms[0].purpose, .other)
        XCTAssertEqual(doc.home.rooms[0].id.uuidString, id)
        // 迁移后可再次编码并读回
        XCTAssertEqual(try DocumentCodec.decode(DocumentCodec.encode(doc)), doc)
    }

    func testWindVectorConvention() {
        let env = Environment(source: .assumed, observedAt: Date(), windFromDegrees: 0) // 北风：吹向南
        let v = env.windToVector(homeOrientationDegrees: 0)
        XCTAssertEqual(v.x, 0, accuracy: 1e-9); XCTAssertEqual(v.y, -1, accuracy: 1e-9)
        // 住宅 +Y 指向正东时，西风（来自 270°，吹向东）应沿 +Y
        let w = Environment(source: .assumed, observedAt: Date(), windFromDegrees: 270)
            .windToVector(homeOrientationDegrees: 90)
        XCTAssertEqual(w.x, 0, accuracy: 1e-9); XCTAssertEqual(w.y, 1, accuracy: 1e-9)
    }
}
