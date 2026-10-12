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

    func persistCurrent() { if let d = document { persist(d) } }

    private func persist(_ doc: HomeDocument) {
        do { try repository.save(doc); saveError = nil }
        catch { saveError = "保存失败：\(error.localizedDescription)" }
    }
}
