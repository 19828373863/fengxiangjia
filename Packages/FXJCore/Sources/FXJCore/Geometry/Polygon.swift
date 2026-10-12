import Foundation

public enum Geometry {
    public static let epsilon = 1e-6

    /// 鞋带公式有向面积（逆时针为正），平方米。
    public static func signedArea(_ p: [Vec2]) -> Double {
        guard p.count >= 3 else { return 0 }
        var s = 0.0
        for i in p.indices { s += p[i].cross(p[(i + 1) % p.count]) }
        return s / 2
    }

    /// 两闭线段是否相交（含端点接触与共线重叠）。
    public static func segmentsIntersect(_ a: Vec2, _ b: Vec2, _ c: Vec2, _ d: Vec2, eps: Double = epsilon) -> Bool {
        func orient(_ p: Vec2, _ q: Vec2, _ r: Vec2) -> Int {
            let v = (q - p).cross(r - p)
            return abs(v) < eps ? 0 : (v > 0 ? 1 : -1)
        }
        func onSeg(_ p: Vec2, _ q: Vec2, _ r: Vec2) -> Bool {
            min(p.x, r.x) - eps <= q.x && q.x <= max(p.x, r.x) + eps &&
            min(p.y, r.y) - eps <= q.y && q.y <= max(p.y, r.y) + eps
        }
        let o1 = orient(a, b, c), o2 = orient(a, b, d), o3 = orient(c, d, a), o4 = orient(c, d, b)
        if o1 != o2 && o3 != o4 { return true }
        if o1 == 0 && onSeg(a, c, b) { return true }
        if o2 == 0 && onSeg(a, d, b) { return true }
        if o3 == 0 && onSeg(c, a, d) { return true }
        if o4 == 0 && onSeg(c, b, d) { return true }
        return false
    }

    /// 返回第一对相交的非相邻边索引；简单多边形返回 nil。
    public static func selfIntersection(_ p: [Vec2]) -> (Int, Int)? {
        let n = p.count
        guard n >= 4 else { return nil }
        for i in 0..<n {
            for j in (i + 1)..<n {
                if j == i + 1 || (i == 0 && j == n - 1) { continue } // 相邻边
                if segmentsIntersect(p[i], p[(i + 1) % n], p[j], p[(j + 1) % n]) { return (i, j) }
            }
        }
        return nil
    }

    /// 射线法判断点是否在多边形内（边界上视为在内）。
    public static func contains(_ p: [Vec2], _ q: Vec2) -> Bool {
        let n = p.count
        guard n >= 3 else { return false }
        for i in 0..<n where distanceToSegment(q, p[i], p[(i + 1) % n]) < 1e-9 { return true }
        var inside = false
        var j = n - 1
        for i in 0..<n {
            let a = p[i], b = p[j]
            if (a.y > q.y) != (b.y > q.y) && q.x < (b.x - a.x) * (q.y - a.y) / (b.y - a.y) + a.x { inside.toggle() }
            j = i
        }
        return inside
    }

    public static func distanceToSegment(_ q: Vec2, _ a: Vec2, _ b: Vec2) -> Double {
        q.distance(to: closestPointOnSegment(q, a, b).point)
    }

    /// 线段上最近点及其沿线段距离起点的长度（米）。
    public static func closestPointOnSegment(_ q: Vec2, _ a: Vec2, _ b: Vec2) -> (point: Vec2, along: Double) {
        let ab = b - a
        let len2 = ab.dot(ab)
        guard len2 > 0 else { return (a, 0) }
        let t = min(max((q - a).dot(ab) / len2, 0), 1)
        return (a + ab * t, t * len2.squareRoot())
    }
}

public extension Room {
    /// 由多边形边派生墙段。
    var walls: [Wall] {
        let n = polygon.count
        guard n >= 2, wallIds.count == n else { return [] }
        return (0..<n).map { Wall(id: wallIds[$0], roomId: id, index: $0, start: polygon[$0], end: polygon[($0 + 1) % n]) }
    }

    func wall(id wid: EntityID) -> Wall? { walls.first { $0.id == wid } }

    var area: Double { abs(Geometry.signedArea(polygon)) }

    func contains(_ q: Vec2) -> Bool { Geometry.contains(polygon, q) }

    /// 开口在户型坐标中的两端点。
    func segment(of opening: Opening) -> (Vec2, Vec2)? {
        guard let w = wall(id: opening.wallId) else { return nil }
        return (w.point(atOffset: opening.offset), w.point(atOffset: opening.offset + opening.width))
    }

    /// 若为顺时针则翻转为逆时针，同时保持 wallId 与墙段对应、开口位置不变。
    mutating func normalizeWinding() {
        guard Geometry.signedArea(polygon) < 0 else { return }
        let n = polygon.count
        let oldWalls = walls
        polygon.reverse()
        // 新边 j: 新 v_j → v_{j+1} = 旧 v_{n-1-j} → v_{n-2-j} = 旧边 (n-2-j) 的反向
        wallIds = (0..<n).map { j in wallIds[((n - 2 - j) % n + n) % n] }
        for k in openings.indices {
            if let w = oldWalls.first(where: { $0.id == openings[k].wallId }) {
                openings[k].offset = w.length - openings[k].offset - openings[k].width
            }
        }
    }
}
