import Foundation

/// #17 扫描导入（与 RoomPlan 解耦的纯逻辑）。RoomPlan 坐标：y 向上、右手系；户型坐标 = (x, -z)。
public struct ScannedWall: Hashable, Sendable {
    public var start: Vec2
    public var end: Vec2
    public var height: Double?
    public init(start: Vec2, end: Vec2, height: Double? = nil) { self.start = start; self.end = end; self.height = height }

    /// 由 RoomPlan Surface 的中心（x,z）、局部 x 轴方向（x,z）与宽度构造。
    public init(centerX: Double, centerZ: Double, axisX: Double, axisZ: Double, width: Double, height: Double?) {
        let c = Vec2(centerX, -centerZ), d = Vec2(axisX, -axisZ).normalized
        self.init(start: c - d * (width / 2), end: c + d * (width / 2), height: height)
    }
}

public struct ScannedOpening: Hashable, Sendable {
    public enum Kind: Hashable, Sendable { case door(isOpen: Bool), window, opening }
    public var kind: Kind
    public var center: Vec2
    public var width: Double
    public init(kind: Kind, center: Vec2, width: Double) { self.kind = kind; self.center = center; self.width = width }
    public init(kind: Kind, centerX: Double, centerZ: Double, width: Double) { self.init(kind: kind, center: Vec2(centerX, -centerZ), width: width) }
}

public enum ScanWarning: Hashable, Sendable {
    case notClosedApproximated     // 墙体未闭合，已用外接矩形近似
    case tooFewWalls
    case openingUnmatched(Int)     // 若干门窗未能匹配到墙
    case validation([RoomIssue])
}

public struct ScanImportResult: Sendable {
    public var room: Room
    public var warnings: [ScanWarning]
    /// 为对齐坐标轴施加的旋转（度，逆时针）。
    public var rotationDegrees: Double
    public var heightEstimate: Double?
}

public enum ScanImport {
    public static let snapDistance = 0.3

    public static func convert(walls: [ScannedWall], openings: [ScannedOpening], name: String = "扫描的房间",
                               alignToAxes: Bool = true) -> ScanImportResult? {
        let usable = walls.filter { $0.start.distance(to: $0.end) >= 0.2 }
        guard usable.count >= 2 else { return nil }
        var warnings: [ScanWarning] = []

        // 1. 对齐：最长墙转到 x 轴（便于编辑），记录旋转
        let longest = usable.max { $0.start.distance(to: $0.end) < $1.start.distance(to: $1.end) }!
        let d = longest.end - longest.start
        var angle = alignToAxes ? -atan2(d.y, d.x) : 0
        // 只取 90° 的模，避免把房间整体翻转
        let quarter = Double.pi / 2
        angle = angle - (angle / quarter).rounded() * quarter + 0
        func rot(_ p: Vec2) -> Vec2 { Vec2(p.x * cos(angle) - p.y * sin(angle), p.x * sin(angle) + p.y * cos(angle)) }
        var segs = usable.map { (rot($0.start), rot($0.end)) }
        let ops = openings.map { ScannedOpening(kind: $0.kind, center: rot($0.center), width: $0.width) }

        // 2. 端点聚类吸附
        var points: [Vec2] = []
        func cluster(_ p: Vec2) -> Int {
            if let i = points.firstIndex(where: { $0.distance(to: p) <= snapDistance }) { return i }
            points.append(p); return points.count - 1
        }
        var edges = segs.map { (cluster($0.0), cluster($0.1)) }.filter { $0.0 != $0.1 }
        // 用聚类均值替代端点
        var sums = Array(repeating: Vec2.zero, count: points.count), counts = Array(repeating: 0, count: points.count)
        for (s, (a, b)) in zip(segs, segs.map { (cluster($0.0), cluster($0.1)) }) {
            sums[a] = sums[a] + s.0; counts[a] += 1; sums[b] = sums[b] + s.1; counts[b] += 1
        }
        let centers = points.indices.map { counts[$0] > 0 ? sums[$0] * (1 / Double(counts[$0])) : points[$0] }
        segs = []

        // 3. 沿边链接成闭合环
        var polygonIdx: [Int]? = nil
        if edges.count >= 3, let first = edges.first {
            var loop = [first.0, first.1]
            edges.removeFirst()
            while let last = loop.last, last != loop[0] {
                guard let k = edges.firstIndex(where: { $0.0 == last || $0.1 == last }) else { break }
                let e = edges.remove(at: k)
                loop.append(e.0 == last ? e.1 : e.0)
            }
            if loop.count >= 4, loop.last == loop[0] { polygonIdx = Array(loop.dropLast()) }
        }
        var polygon: [Vec2]
        if let idx = polygonIdx {
            polygon = idx.map { centers[$0] }
        } else {
            warnings.append(usable.count < 3 ? .tooFewWalls : .notClosedApproximated)
            let xs = centers.map(\.x), ys = centers.map(\.y)
            polygon = [Vec2(xs.min()!, ys.min()!), Vec2(xs.max()!, ys.min()!), Vec2(xs.max()!, ys.max()!), Vec2(xs.min()!, ys.max()!)]
        }
        // 4. 平移到原点、四舍五入到 1 cm、逆时针
        let minX = polygon.map(\.x).min()!, minY = polygon.map(\.y).min()!
        func place(_ p: Vec2) -> Vec2 { Vec2(((p.x - minX) * 100).rounded() / 100, ((p.y - minY) * 100).rounded() / 100) }
        polygon = polygon.map(place)
        if Geometry.signedArea(polygon) < 0 { polygon.reverse() }
        let heights = usable.compactMap(\.height)
        var room = Room(name: name, polygon: polygon,
                        height: heights.isEmpty ? 2.7 : heights.reduce(0, +) / Double(heights.count))

        // 5. 门窗匹配到最近墙段
        var unmatched = 0
        for o in ops {
            let c = place(o.center)
            let best = room.walls.map { w -> (Wall, Double, Double) in
                let cp = Geometry.closestPointOnSegment(c, w.start, w.end)
                return (w, cp.point.distance(to: c), cp.along)
            }.min { $0.1 < $1.1 }
            guard let (w, dist, along) = best, dist <= snapDistance, w.length >= 0.2 else { unmatched += 1; continue }
            let width = min(max(o.width, RoomValidator.minOpeningWidth), w.length)
            let offset = min(max(along - width / 2, 0), w.length - width)
            let kind: OpeningKind, frac: Double
            switch o.kind {
            case .door(let isOpen): kind = .door; frac = isOpen ? 1 : 0
            case .window: kind = .window; frac = 0
            case .opening: kind = .passage; frac = 1
            }
            let clash = room.openings.contains { $0.wallId == w.id && offset < $0.offset + $0.width && $0.offset < offset + width }
            if clash { unmatched += 1; continue }
            room.openings.append(Opening(wallId: w.id, kind: kind, offset: (offset * 100).rounded() / 100, width: width, openFraction: frac))
        }
        if unmatched > 0 { warnings.append(.openingUnmatched(unmatched)) }
        let issues = RoomValidator.validate(room)
        if !issues.isEmpty { warnings.append(.validation(issues)) }
        return ScanImportResult(room: room, warnings: warnings, rotationDegrees: angle * 180 / .pi,
                                heightEstimate: heights.isEmpty ? nil : room.height)
    }
}
