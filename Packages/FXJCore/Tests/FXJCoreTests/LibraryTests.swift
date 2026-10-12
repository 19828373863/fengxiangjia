import XCTest
@testable import FXJCore

final class LibraryTests: XCTestCase {
    var dir: URL!
    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("fxj-\(UUID().uuidString)")
    }
    override func tearDown() { try? FileManager.default.removeItem(at: dir) }

    func testSampleIsValid() {
        let doc = SampleData.sampleDocument()
        XCTAssertEqual(doc.home.rooms.count, 1)
        XCTAssertEqual(RoomValidator.validate(doc.home.rooms[0]), [])
        XCTAssertTrue(Topology.reachesOutdoor(SampleData.roomId, in: doc.home))
        XCTAssertEqual(doc.home.rooms[0].openings[0].connects, [.room(SampleData.roomId), .outdoor])
    }

    func testSampleIsDeterministic() {
        XCTAssertEqual(SampleData.sampleDocument(), SampleData.sampleDocument())
    }

    func testEmptyThenSaveThenLoad() throws {
        let repo = HomeRepository(directory: dir)
        XCTAssertEqual(repo.load(), .empty)
        let doc = SampleData.sampleDocument()
        try repo.save(doc)
        XCTAssertEqual(repo.load(), .loaded(doc))
        try repo.delete()
        XCTAssertEqual(repo.load(), .empty)
    }

    func testCorruptFileGivesError() throws {
        let repo = HomeRepository(directory: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data("garbage".utf8).write(to: repo.fileURL)
        guard case .failed = repo.load() else { return XCTFail("expected failed") }
    }

    func testFutureVersionGivesError() throws {
        let repo = HomeRepository(directory: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data(#"{"schemaVersion": 7}"#.utf8).write(to: repo.fileURL)
        XCTAssertEqual(repo.load(), .failed("文件来自较新版本（schema v7），请更新 App"))
    }
}
