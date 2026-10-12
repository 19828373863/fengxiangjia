import XCTest
@testable import FXJCore

final class PersistenceV2Tests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func doc() -> HomeDocument {
        var d = SampleData.sampleDocument()
        d.home.location = Location(latitude: 1.3521, longitude: 103.8198, label: "家")
        let room = d.home.rooms[0]
        d.scenarios = [Scenario.capture(name: "A", room: room, geometryRevision: 0,
                                        environment: Environment(source: .manual, observedAt: t0, windFromDegrees: 200, windSpeed: 2,
                                                                 obstructionByOpening: [room.openings[0].id: .partial]),
                                        createdAt: t0)]
        d.feedback = [ComfortFeedback(roomId: room.id, scenarioHash: "abc", rating: .justRight, note: "很舒服", createdAt: t0)]
        return d
    }

    func testScenarioAndFeedbackRoundTrip() throws {
        let d = doc()
        XCTAssertEqual(try DocumentCodec.decode(DocumentCodec.encode(d)), d)
    }

    func testMigrateV1AddsFeedback() throws {
        // 取 v2 编码后去掉 feedback 并标为 v1，模拟旧版本文件
        let original = doc()
        var obj = try JSONSerialization.jsonObject(with: DocumentCodec.encode(original)) as! [String: Any]
        obj.removeValue(forKey: "feedback"); obj["schemaVersion"] = 1
        let migrated = try DocumentCodec.decode(JSONSerialization.data(withJSONObject: obj))
        XCTAssertEqual(migrated.schemaVersion, 2)
        XCTAssertEqual(migrated.feedback, [])
        XCTAssertEqual(migrated.scenarios, original.scenarios)
    }

    func testMigrateV0ToV2() throws {
        let v0 = #"{"schemaVersion":0,"home":{"id":"\#(UUID().uuidString)","name":"旧","rooms":[{"id":"\#(UUID().uuidString)","name":"r","points":[{"x":0,"y":0},{"x":3,"y":0},{"x":3,"y":3}]}]}}"#
        let d = try DocumentCodec.decode(Data(v0.utf8))
        XCTAssertEqual(d.schemaVersion, 2)
        XCTAssertEqual(d.feedback, [])
    }

    func testExportRedactsByDefault() throws {
        let data = try DocumentExport.json(doc())
        let back = try DocumentCodec.decode(data)
        XCTAssertNil(back.home.location)
        XCTAssertNil(back.feedback[0].note)
        XCTAssertEqual(back.feedback[0].rating, .justRight)
        XCTAssertFalse(String(decoding: data, as: UTF8.self).contains("103.8"))
        let full = try DocumentCodec.decode(DocumentExport.json(doc(), options: .init(includeLocation: true, includeFeedbackNotes: true)))
        XCTAssertNotNil(full.home.location)
        XCTAssertEqual(full.feedback[0].note, "很舒服")
    }

    func testExportFileName() {
        XCTAssertEqual(DocumentExport.fileName(for: doc(), date: t0), "fengxiangjia-20270115-0800.json")
    }

    func testRepositoryDeleteRemovesEverything() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let repo = HomeRepository(directory: dir)
        try repo.save(doc())
        try repo.delete()
        XCTAssertFalse(FileManager.default.fileExists(atPath: repo.fileURL.path))
        XCTAssertEqual(repo.load(), .empty)
    }
}
