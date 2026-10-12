import Foundation

public enum HomeIssue: Hashable, Sendable {
    case roomsOverlap(EntityID, EntityID)
    case room(EntityID, RoomIssue)
}

public enum HomeValidator {
    /// 校验整屋：各房间自身合法，且房间之间不重叠（共享墙允许）。
    public static func validate(_ home: Home) -> [HomeIssue] {
        var out: [HomeIssue] = []
        for r in home.rooms { out += RoomValidator.validate(r).map { .room(r.id, $0) } }
        for i in home.rooms.indices { for j in home.rooms.indices where j > i {
            if overlaps(home.rooms[i].polygon, home.rooms[j].polygon) { out.append(.roomsOverlap(home.rooms[i].id, home.rooms[j].id)) }
        } }
        return out
    }

    /// 内部重叠：一方顶点/边中点严格在另一方内部（距边界 > 1 cm），或边真相交（非共线接触）。
    static func overlaps(_ a: [Vec2], _ b: [Vec2]) -> Bool {
        func strictlyInside(_ p: Vec2, _ poly: [Vec2]) -> Bool {
            guard Geometry.contains(poly, p) else { return false }
            for k in poly.indices where Geometry.distanceToSegment(p, poly[k], poly[(k + 1) % poly.count]) < 0.01 { return false }
            return true
        }
        func samples(_ p: [Vec2]) -> [Vec2] {
            p.indices.flatMap { [p[$0], (p[$0] + p[($0 + 1) % p.count]) * 0.5] }
        }
        if samples(a).contains(where: { strictlyInside($0, b) }) || samples(b).contains(where: { strictlyInside($0, a) }) { return true }
        for i in a.indices { for j in b.indices {
            let p1 = a[i], p2 = a[(i + 1) % a.count], q1 = b[j], q2 = b[(j + 1) % b.count]
            let d1 = (p2 - p1).cross(q1 - p1), d2 = (p2 - p1).cross(q2 - p1)
            let d3 = (q2 - q1).cross(p1 - q1), d4 = (q2 - q1).cross(p2 - q1)
            if ((d1 > 1e-6 && d2 < -1e-6) || (d1 < -1e-6 && d2 > 1e-6)) && ((d3 > 1e-6 && d4 < -1e-6) || (d3 < -1e-6 && d4 > 1e-6)) { return true }
        } }
        // 完全相同的多边形
        return a.count == b.count && Set(a.map { "\($0.x),\($0.y)" }) == Set(b.map { "\($0.x),\($0.y)" })
    }
}

public extension Home {
    /// 在某房间某面墙外侧新建贴墙矩形房间（共享该墙段）。
    @discardableResult
    mutating func addAdjacentRoom(to roomId: EntityID, wallIndex: Int, depth: Double, name: String,
                                  purpose: RoomPurpose = .other) throws -> EntityID {
        guard let host = rooms.first(where: { $0.id == roomId }), host.walls.indices.contains(wallIndex) else { throw EditError.notFound }
        let w = host.walls[wallIndex]
        let n = w.outwardNormal * max(depth, 0.5)
        let room = Room(name: name, polygon: [w.end, w.start, w.start + n, w.end + n], purpose: purpose)
        var candidate = self
        candidate.rooms.append(room)
        let overlaps = HomeValidator.validate(candidate).contains { if case .roomsOverlap = $0 { return true }; return false }
        guard !overlaps else { throw EditError.invalidGeometry([]) }
        self = candidate
        Topology.refreshConnections(&self)
        return room.id
    }

    mutating func removeRoom(_ id: EntityID) {
        rooms.removeAll { $0.id == id }
        Topology.refreshConnections(&self)
    }

    /// 影响某房间的全部开口：本房间自有开口 + 其他房间上通向本房间的开口（如邻室持有的室内门）。
    func openingsAffecting(_ roomId: EntityID) -> [(opening: Opening, host: Room)] {
        var out: [(Opening, Room)] = []
        for r in rooms {
            for o in r.openings {
                if r.id == roomId { out.append((o, r)); continue }
                if Topology.connections(for: o, in: r, home: self).contains(.room(roomId)) { out.append((o, r)) }
            }
        }
        return out
    }
}
