import Foundation

/// #16 窗外遮挡与楼层的简化外部风况模型。系数为生活经验估计（未校准），用于可解释的相对比较。
public enum ObstructionModel {
    /// 遮挡系数：窗口处有效风速 ≈ 区域风速 × 系数。
    public static func levelFactor(_ l: ObstructionLevel?) -> Double {
        switch l ?? .unknown {
        case .open: return 1.0
        case .partial: return 0.6
        case .unknown: return 0.6
        case .nearBuilding: return 0.35
        case .lightWell: return 0.25
        }
    }

    /// 楼层系数：低层受地面粗糙度影响风速更低。nil = 未填写，按中间值。
    public static func floorFactor(_ floor: Int?) -> Double {
        guard let f = floor else { return 0.85 }
        switch f {
        case ..<2: return 0.7
        case 2...5: return 0.85
        default: return 1.0
        }
    }

    public static func windFactor(_ l: ObstructionLevel?, floor: Int?) -> Double {
        min(levelFactor(l) * floorFactor(floor), 1)
    }

    /// 由各开口的用户设置生成遮挡表（只含通向室外的开口）。
    public static func obstructionMap(_ home: Home) -> [EntityID: ObstructionLevel] {
        var out: [EntityID: ObstructionLevel] = [:]
        for r in home.rooms {
            for o in r.openings where Topology.connections(for: o, in: r, home: home).last == .outdoor {
                out[o.id] = o.exteriorObstruction ?? .unknown
            }
        }
        return out
    }

    /// 将住宅遮挡设置写入环境快照（快照中已有的值以住宅设置为准）。
    public static func apply(_ env: Environment?, home: Home) -> Environment? {
        guard var e = env else { return nil }
        for (k, v) in obstructionMap(home) { e.obstructionByOpening[k] = v }
        return e
    }

    /// 室外开口列表（UI 用）：房间、开口、朝向。
    public struct ExteriorOpening: Identifiable, Sendable {
        public var room: Room
        public var opening: Opening
        public var bearing: Double?
        public var id: EntityID { opening.id }
    }

    public static func exteriorOpenings(_ home: Home) -> [ExteriorOpening] {
        home.rooms.flatMap { r in
            r.openings.compactMap { o in
                guard Topology.connections(for: o, in: r, home: home).last == .outdoor else { return nil }
                return ExteriorOpening(room: r, opening: o, bearing: r.wall(id: o.wallId)?.facingBearing(orientation: home.orientationDegrees))
            }
        }
    }
}
