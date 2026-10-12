import Foundation

/// 室内外连接拓扑。原则：区域之间只能经由开口连通；共享墙体但无开口的房间互不连通（不得穿墙）。
public enum Topology {
    /// 墙体视为零厚度中心线；邻室墙段距离容差（米）。
    public static let sharedWallTolerance = 0.05

    /// 推导开口两侧连接：宿主房间 + （共享墙的邻室 或 室外）。
    public static func connections(for opening: Opening, in room: Room, home: Home) -> [ConnectionTarget] {
        guard let (a, b) = room.segment(of: opening), let host = room.wall(id: opening.wallId) else {
            return [.room(room.id), .unknown]
        }
        let mid = (a + b) * 0.5
        for other in home.rooms where other.id != room.id {
            for w in other.walls {
                // 反向共线：法线相对，且开口两端都落在该墙上
                guard host.direction.dot(w.direction) < -0.99 else { continue }
                if Geometry.distanceToSegment(a, w.start, w.end) <= sharedWallTolerance,
                   Geometry.distanceToSegment(b, w.start, w.end) <= sharedWallTolerance,
                   Geometry.distanceToSegment(mid, w.start, w.end) <= sharedWallTolerance {
                    return [.room(room.id), .room(other.id)]
                }
            }
        }
        return [.room(room.id), .outdoor]
    }

    /// 重新计算全部开口的 `connects` 缓存。
    public static func refreshConnections(_ home: inout Home) {
        let snapshot = home
        for r in home.rooms.indices {
            for o in home.rooms[r].openings.indices {
                home.rooms[r].openings[o].connects = connections(for: home.rooms[r].openings[o], in: snapshot.rooms[r], home: snapshot)
            }
        }
    }

    public struct Edge: Hashable, Sendable {
        public var from: ConnectionTarget
        public var to: ConnectionTarget
        public var openingId: EntityID
    }

    /// 区域连接图（仅开口边）。
    public static func graph(_ home: Home) -> [Edge] {
        home.rooms.flatMap { r in
            r.openings.map { o in
                let c = connections(for: o, in: r, home: home)
                return Edge(from: c[0], to: c[1], openingId: o.id)
            }
        }
    }

    /// 房间是否有任一开口通向室外（含经其他房间间接连通）。只计算 openFraction > 0 的开口。
    public static func reachesOutdoor(_ roomId: EntityID, in home: Home) -> Bool {
        let openIds = Set(home.rooms.flatMap { $0.openings.filter { $0.openFraction > 0 }.map(\.id) })
        let edges = graph(home).filter { openIds.contains($0.openingId) }
        var seen: Set<ConnectionTarget> = [.room(roomId)]
        var frontier: [ConnectionTarget] = [.room(roomId)]
        while let n = frontier.popLast() {
            for e in edges {
                let next: ConnectionTarget? = e.from == n ? e.to : (e.to == n ? e.from : nil)
                guard let nx = next, !seen.contains(nx) else { continue }
                if nx == .outdoor { return true }
                seen.insert(nx); frontier.append(nx)
            }
        }
        return false
    }
}
