import Foundation

public enum EditError: Error, Equatable, Sendable {
    case invalidGeometry([RoomIssue])
    case outsideRoom
    case noWallNearby
    case wallTooShort
    case overlapsOpening
    case notFound
    case tooCloseToWall
    case onFurniture
}

/// 二维户型编辑器（纯逻辑）。每个成功的编辑都可撤销/重做；失败的编辑不改变状态、不入栈。
public struct RoomEditor: Sendable {
    public private(set) var room: Room
    private var undoStack: [Room] = []
    private var redoStack: [Room] = []
    public static let historyLimit = 100
    public var gridSize: Double = 0.05
    public var wallSnapDistance: Double = 0.5

    public init(room: Room) { self.room = room }
    public init(template: RoomTemplate, name: String = "我的房间") {
        room = Room(name: name, polygon: template.polygon)
    }

    public var canUndo: Bool { !undoStack.isEmpty }
    public var canRedo: Bool { !redoStack.isEmpty }

    public mutating func undo() {
        guard let prev = undoStack.popLast() else { return }
        redoStack.append(room); room = prev
    }

    public mutating func redo() {
        guard let next = redoStack.popLast() else { return }
        undoStack.append(room); room = next
    }

    mutating func commit(_ new: Room) {
        undoStack.append(room)
        if undoStack.count > Self.historyLimit { undoStack.removeFirst() }
        redoStack.removeAll()
        room = new
    }

    public func snap(_ p: Vec2) -> Vec2 {
        guard gridSize > 0 else { return p }
        return Vec2((p.x / gridSize).rounded() * gridSize, (p.y / gridSize).rounded() * gridSize)
    }

    // MARK: 几何

    /// 套用模板：替换轮廓，重建墙 ID，清空依附于旧墙的开口；保留仍在新房间内的家具与风扇。
    public mutating func apply(_ template: RoomTemplate) {
        var r = room
        r.polygon = template.polygon
        r.wallIds = r.polygon.map { _ in UUID() }
        r.openings = []
        r.obstacles = r.obstacles.filter { r.contains($0.center) }
        r.fans = r.fans.filter { r.contains($0.position) }
        commit(r)
    }

    /// 移动顶点（吸附网格）。导致自交、退化或开口越出墙段时拒绝。
    public mutating func moveVertex(_ index: Int, to p: Vec2) throws {
        guard room.polygon.indices.contains(index) else { throw EditError.notFound }
        var r = room
        r.polygon[index] = snap(p)
        let issues = RoomValidator.validate(r).filter {
            switch $0 {
            case .itemOutsideRoom: return false
            default: return true
            }
        }
        guard issues.isEmpty else { throw EditError.invalidGeometry(issues) }
        commit(r)
    }

    // MARK: 家具

    @discardableResult
    public mutating func addFurniture(_ kind: ObstacleKind, at p: Vec2, size: Vec2 = Vec2(1.0, 0.6)) throws -> EntityID {
        let c = snap(p)
        guard room.contains(c) else { throw EditError.outsideRoom }
        let ob = Obstacle(kind: kind, center: c, size: size)
        var r = room; r.obstacles.append(ob); commit(r)
        return ob.id
    }

    public mutating func moveFurniture(_ id: EntityID, to p: Vec2) throws {
        guard let i = room.obstacles.firstIndex(where: { $0.id == id }) else { throw EditError.notFound }
        let c = snap(p)
        guard room.contains(c) else { throw EditError.outsideRoom }
        var r = room; r.obstacles[i].center = c; commit(r)
    }

    @discardableResult
    public mutating func addFan(at p: Vec2) throws -> EntityID {
        guard room.contains(p) else { throw EditError.outsideRoom }
        let f = Fan(position: snap(p))
        var r = room; r.fans.append(f); commit(r)
        return f.id
    }

    public mutating func removeItem(_ id: EntityID) throws {
        var r = room
        let before = r.obstacles.count + r.fans.count + r.openings.count + r.targets.count
        r.obstacles.removeAll { $0.id == id }; r.fans.removeAll { $0.id == id }
        r.openings.removeAll { $0.id == id }; r.targets.removeAll { $0.id == id }
        guard r.obstacles.count + r.fans.count + r.openings.count + r.targets.count < before else { throw EditError.notFound }
        commit(r)
    }

    // MARK: 门窗吸附

    /// 计算点击点对应的墙体吸附位置：最近墙段（距离 ≤ wallSnapDistance），开口以投影点为中心并夹在墙内。
    public func snapOpening(near p: Vec2, width: Double, excluding: EntityID? = nil) throws -> (wallId: EntityID, offset: Double) {
        let candidates = room.walls.map { w -> (Wall, Double, Double) in
            let c = Geometry.closestPointOnSegment(p, w.start, w.end)
            return (w, c.point.distance(to: p), c.along)
        }.filter { $0.1 <= wallSnapDistance }.sorted { $0.1 < $1.1 }
        guard let (wall, _, along) = candidates.first else { throw EditError.noWallNearby }
        guard wall.length >= width else { throw EditError.wallTooShort }
        var offset = min(max(along - width / 2, 0), wall.length - width)
        offset = (offset / gridSize).rounded() * gridSize
        offset = min(max(offset, 0), wall.length - width)
        let clash = room.openings.contains { o in
            o.id != excluding && o.wallId == wall.id && offset < o.offset + o.width - Geometry.epsilon && o.offset < offset + width - Geometry.epsilon
        }
        guard !clash else { throw EditError.overlapsOpening }
        return (wall.id, offset)
    }

    @discardableResult
    public mutating func addOpening(_ kind: OpeningKind, near p: Vec2, width: Double? = nil) throws -> EntityID {
        let w = width ?? (kind == .window ? 1.2 : 0.9)
        let s = try snapOpening(near: p, width: w)
        let o = Opening(wallId: s.wallId, kind: kind, offset: s.offset, width: w)
        var r = room; r.openings.append(o); commit(r)
        return o.id
    }

    public mutating func moveOpening(_ id: EntityID, near p: Vec2) throws {
        guard let i = room.openings.firstIndex(where: { $0.id == id }) else { throw EditError.notFound }
        let s = try snapOpening(near: p, width: room.openings[i].width, excluding: id)
        var r = room; r.openings[i].wallId = s.wallId; r.openings[i].offset = s.offset; commit(r)
    }
}
