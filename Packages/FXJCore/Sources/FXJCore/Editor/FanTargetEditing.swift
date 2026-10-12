import Foundation

public extension Obstacle {
    /// 点是否落在（可旋转的）家具矩形内。
    func contains(_ p: Vec2, margin: Double = 0) -> Bool {
        let a = -rotationDegrees * .pi / 180
        let d = p - center
        let local = Vec2(d.x * cos(a) - d.y * sin(a), d.x * sin(a) + d.y * cos(a))
        return abs(local.x) <= size.x / 2 + margin && abs(local.y) <= size.y / 2 + margin
    }
}

public enum FanPlacement {
    /// 风扇中心离墙最小距离（米）。
    public static let minWallClearance = 0.2

    public enum Problem: Equatable, Sendable { case outsideRoom, tooCloseToWall, onFurniture }

    public static func check(_ p: Vec2, in room: Room) -> Problem? {
        guard room.contains(p) else { return .outsideRoom }
        if room.walls.contains(where: { Geometry.distanceToSegment(p, $0.start, $0.end) < minWallClearance }) {
            return .tooCloseToWall
        }
        if room.obstacles.contains(where: { $0.heightClass != .low && $0.contains(p) }) { return .onFurniture }
        return nil
    }
}

public extension EditError {
    static func placement(_ p: FanPlacement.Problem) -> EditError {
        switch p {
        case .outsideRoom: return .outsideRoom
        case .tooCloseToWall: return .tooCloseToWall
        case .onFurniture: return .onFurniture
        }
    }
}

/// #7 风扇与目标编辑（可撤销）。
public extension RoomEditor {
    @discardableResult
    mutating func placeFan(at p: Vec2, kind: FanKind = .pedestal, yawDegrees: Double = 0, power: Double = 0.6) throws -> EntityID {
        let s = snap(p)
        if let problem = FanPlacement.check(s, in: room) { throw EditError.placement(problem) }
        let f = Fan(kind: kind, position: s, yawDegrees: yawDegrees, power: power)
        var r = room; r.fans.append(f); commit(r)
        return f.id
    }

    mutating func moveFan(_ id: EntityID, to p: Vec2) throws {
        guard let i = room.fans.firstIndex(where: { $0.id == id }) else { throw EditError.notFound }
        let s = snap(p)
        if let problem = FanPlacement.check(s, in: room) { throw EditError.placement(problem) }
        var r = room; r.fans[i].position = s; commit(r)
    }

    /// 设置朝向（度，户型 +X 逆时针），归一到 [0, 360)，吸附 5°。
    mutating func setFanYaw(_ id: EntityID, degrees: Double) throws {
        guard let i = room.fans.firstIndex(where: { $0.id == id }) else { throw EditError.notFound }
        var d = (degrees / 5).rounded() * 5
        d = d.truncatingRemainder(dividingBy: 360); if d < 0 { d += 360 }
        var r = room; r.fans[i].yawDegrees = d; commit(r)
    }

    /// 让风扇朝向某点。
    mutating func aimFan(_ id: EntityID, at p: Vec2) throws {
        guard let f = room.fans.first(where: { $0.id == id }) else { throw EditError.notFound }
        let d = p - f.position
        guard d.length > 1e-6 else { return }
        try setFanYaw(id, degrees: atan2(d.y, d.x) * 180 / .pi)
    }

    /// 档位：0…1，吸附到 0.1。
    mutating func setFanPower(_ id: EntityID, _ power: Double) throws {
        guard let i = room.fans.firstIndex(where: { $0.id == id }) else { throw EditError.notFound }
        var r = room; r.fans[i].power = min(max((power * 10).rounded() / 10, 0), 1); commit(r)
    }

    mutating func setFanKind(_ id: EntityID, _ kind: FanKind) throws {
        guard let i = room.fans.firstIndex(where: { $0.id == id }) else { throw EditError.notFound }
        var r = room; r.fans[i].kind = kind; commit(r)
    }

    @discardableResult
    mutating func addTarget(name: String, at p: Vec2, radius: Double = 0.6, objective: TargetObjective) throws -> EntityID {
        let c = snap(p)
        guard room.contains(c) else { throw EditError.outsideRoom }
        let t = Target(name: name, shape: .circle(center: c, radius: min(max(radius, 0.2), 3)), objective: objective)
        var r = room; r.targets.append(t); commit(r)
        return t.id
    }

    mutating func moveTarget(_ id: EntityID, to p: Vec2) throws {
        guard let i = room.targets.firstIndex(where: { $0.id == id }) else { throw EditError.notFound }
        let c = snap(p)
        guard room.contains(c) else { throw EditError.outsideRoom }
        var r = room
        switch r.targets[i].shape {
        case .circle(_, let radius): r.targets[i].shape = .circle(center: c, radius: radius)
        case .polygon(let pts):
            let centroid = pts.reduce(Vec2.zero, +) * (1 / Double(max(pts.count, 1)))
            r.targets[i].shape = .polygon(pts.map { $0 - centroid + c })
        }
        commit(r)
    }

    mutating func setTargetRadius(_ id: EntityID, _ radius: Double) throws {
        guard let i = room.targets.firstIndex(where: { $0.id == id }),
              case .circle(let c, _) = room.targets[i].shape else { throw EditError.notFound }
        var r = room; r.targets[i].shape = .circle(center: c, radius: min(max(radius, 0.2), 3)); commit(r)
    }

    mutating func setTargetObjective(_ id: EntityID, _ objective: TargetObjective) throws {
        guard let i = room.targets.firstIndex(where: { $0.id == id }) else { throw EditError.notFound }
        var r = room; r.targets[i].objective = objective; commit(r)
    }
}

public extension Target {
    var center: Vec2 {
        switch shape {
        case .circle(let c, _): return c
        case .polygon(let p): return p.reduce(Vec2.zero, +) * (1 / Double(max(p.count, 1)))
        }
    }

    func contains(_ p: Vec2) -> Bool {
        switch shape {
        case .circle(let c, let r): return c.distance(to: p) <= r
        case .polygon(let pts): return Geometry.contains(pts, p)
        }
    }
}
