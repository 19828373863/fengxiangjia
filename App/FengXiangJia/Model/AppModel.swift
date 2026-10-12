import Foundation
import Observation
import FXJCore

/// App 状态：加载本地住宅文档，或在用户选择后载入示例房间。无需登录。
@MainActor
@Observable
final class AppModel {
    private(set) var state: HomeLoadState = .loading
    var saveError: String?
    private let repository: HomeRepository

    init(repository: HomeRepository = AppModel.defaultRepository()) {
        self.repository = repository
    }

    nonisolated static func defaultRepository() -> HomeRepository {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return HomeRepository(directory: dir)
    }

    var document: HomeDocument? {
        if case .loaded(let d) = state { return d }
        return nil
    }

    func load() {
        state = .loading
        state = repository.load()
        // UI 截图/演示：-FXJSampleOnLaunch 时空状态自动载入示例
        if state == .empty, ProcessInfo.processInfo.arguments.contains("-FXJSampleOnLaunch") { openSample() }
    }

    func openSample() {
        let doc = SampleData.sampleDocument()
        state = .loaded(doc)
        persist(doc)
    }

    func resetToEmpty() {
        try? repository.delete()
        state = .empty
    }

    /// 移动风扇；新位置不在房间内则拒绝（不得穿墙放置）。
    @discardableResult
    func moveFan(roomId: UUID, fanId: UUID, to p: Vec2) -> Bool {
        guard var doc = document,
              let r = doc.home.rooms.firstIndex(where: { $0.id == roomId }),
              let f = doc.home.rooms[r].fans.firstIndex(where: { $0.id == fanId }),
              doc.home.rooms[r].contains(p) else { return false }
        doc.home.rooms[r].fans[f].position = p
        state = .loaded(doc)
        return true
    }

    func update(_ doc: HomeDocument) {
        state = .loaded(doc)
        persist(doc)
    }

    /// 新建手绘房间（默认 4×3 m 矩形），随后可在编辑器中修改。
    func createBlankRoom() {
        let room = Room(name: "我的房间", polygon: RoomTemplate.defaultRectangle.polygon)
        update(HomeDocument(home: Home(name: "我家", rooms: [room])))
    }

    /// 编辑器保存：替换房间、刷新开口连接、几何版本 +1（旧模拟结果因此失效）。
    func replaceRoom(_ room: Room) {
        guard var doc = document, let i = doc.home.rooms.firstIndex(where: { $0.id == room.id }) else { return }
        doc.home.rooms[i] = room
        Topology.refreshConnections(&doc.home)
        doc.geometryRevision += 1
        update(doc)
    }

    /// 布局（风扇/目标）变化：不改变几何版本。
    func updateLayout(_ room: Room) {
        guard var doc = document, let i = doc.home.rooms.firstIndex(where: { $0.id == room.id }) else { return }
        doc.home.rooms[i] = room
        update(doc)
    }

    /// 把当前布局保存为方案（#10/#11）。
    @discardableResult
    func saveScenario(name: String, room: Room, environment: OutdoorConditions? = nil) -> Scenario? {
        guard var doc = document else { return nil }
        let s = Scenario.capture(name: name, room: room, geometryRevision: doc.geometryRevision, environment: environment)
        doc.scenarios.append(s)
        update(doc)
        return s
    }

    func deleteScenario(_ id: UUID) {
        guard var doc = document else { return }
        doc.scenarios.removeAll { $0.id == id }
        update(doc)
    }

    /// 体感反馈（仅本地）。
    func addFeedback(roomId: UUID, scenarioHash: String?, rating: ComfortRating) {
        guard var doc = document else { return }
        doc.feedback.append(ComfortFeedback(roomId: roomId, scenarioHash: scenarioHash, rating: rating))
        update(doc)
    }

    /// 写出脱敏 JSON 到临时目录，供分享面板导出。
    func exportFileURL(includeLocation: Bool = false) -> URL? {
        guard let doc = document, let data = try? DocumentExport.json(doc, options: .init(includeLocation: includeLocation)) else { return nil }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(DocumentExport.fileName(for: doc))
        do { try data.write(to: url, options: .atomic); return url } catch { return nil }
    }

    /// 在某房间某面墙外侧添加相邻房间（#12）。失败返回错误描述。
    func addAdjacentRoom(to roomId: UUID, wallIndex: Int, depth: Double = 3) -> String? {
        guard var doc = document else { return "没有住宅" }
        do {
            try doc.home.addAdjacentRoom(to: roomId, wallIndex: wallIndex, depth: depth, name: "房间 \(doc.home.rooms.count + 1)")
            doc.geometryRevision += 1
            update(doc)
            return nil
        } catch { return "该方向已有房间或空间不足" }
    }

    func removeRoom(_ id: UUID) {
        guard var doc = document, doc.home.rooms.count > 1 else { return }
        doc.home.removeRoom(id)
        doc.scenarios.removeAll { $0.roomId == id }
        doc.geometryRevision += 1
        update(doc)
    }

    /// 住宅名称、位置（已粗化）与朝向（#14）。朝向影响模拟输入 hash，但不改变几何版本。
    func updateHomeInfo(name: String, location: Location?, orientation: Double) {
        guard var doc = document else { return }
        doc.home.name = name.isEmpty ? doc.home.name : name
        doc.home.location = location?.coarsened()
        doc.home.orientationDegrees = Bearing.normalize(orientation)
        update(doc)
    }

    func persistCurrent() { if let d = document { persist(d) } }

    private func persist(_ doc: HomeDocument) {
        do { try repository.save(doc); saveError = nil }
        catch { saveError = "保存失败：\(error.localizedDescription)" }
    }
}
