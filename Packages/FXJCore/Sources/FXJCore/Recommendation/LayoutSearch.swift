import Foundation

/// #13 约束下的有限布局搜索：枚举风扇位置（网格）× 朝向（8 方向 + 当前），粗网格快速评估，
/// 选出“最省事 / 送风更强 / 少直吹”三种候选，再以标准精度重算，保证展示的风线与建议同源。
public enum LayoutSearch {
    public enum Kind: String, Sendable, CaseIterable { case leastEffort, strongerBreeze, lessDraft }

    public struct Candidate: Sendable {
        public var kind: Kind
        public var fan: Fan
        public var room: Room
        /// 标准精度结果（与界面绘制共用）。
        public var result: SimulationResult
        public var cost: Level
        public var breeze: Level
        public var draft: Level
        public var isCurrent: Bool
    }

    public struct Options: Sendable {
        public var positionSpacing = 0.5
        public var yawStepDegrees = 45.0
        public var maxCandidates = 320
        public var coarse: SimulationOptions = { var o = SimulationOptions(); o.cellSize = 0.2; o.maxCellsPerAxis = 40; o.smoothingIterations = 6; return o }()
        public var final = SimulationOptions()
        public init() {}
    }

    struct Score { var breeze: Double; var draft: Double; var cost: Double; var fan: Fan }

    public static func search(home: Home, roomId: EntityID, fanId: EntityID? = nil, environment: Environment?,
                              options: Options = .init()) -> [Candidate] {
        guard let room = home.rooms.first(where: { $0.id == roomId }),
              let fi = fanId.flatMap({ id in room.fans.firstIndex { $0.id == id } }) ?? (room.fans.isEmpty ? nil : 0)
        else { return [] }
        let current = room.fans[fi]

        func evaluate(_ fan: Fan, _ opt: SimulationOptions) -> (SimulationResult, Room) {
            var r = room; r.fans[fi] = fan
            var h = home
            if let i = h.rooms.firstIndex(where: { $0.id == roomId }) { h.rooms[i] = r }
            return (AirflowSolver.solve(SimulationInput(home: h, roomId: roomId, environment: environment, options: opt)), r)
        }
        func score(_ res: SimulationResult, _ fan: Fan) -> Score? {
            guard let m = res.metrics else { return nil }
            let want = m.targets.filter { $0.objective == .wantBreeze }
            let avoid = m.targets.filter { $0.objective == .avoidDirectDraft }
            let breeze = want.isEmpty ? m.circulationCoverage : want.map { $0.breezeCoverage + 0.1 * min($0.meanSpeed, 1) }.reduce(0, +) / Double(want.count)
            let draft = avoid.map(\.draftExposure).max() ?? 0
            return Score(breeze: breeze, draft: draft, cost: moveCost(current, fan), fan: fan)
        }

        // 候选枚举（确定性顺序）
        var fans: [Fan] = [current]
        let xs = room.polygon.map(\.x), ys = room.polygon.map(\.y)
        var y = ys.min()! + options.positionSpacing / 2
        var positions: [Vec2] = []
        while y < ys.max()! {
            var x = xs.min()! + options.positionSpacing / 2
            while x < xs.max()! {
                let p = Vec2((x * 100).rounded() / 100, (y * 100).rounded() / 100)
                if FanPlacement.check(p, in: room) == nil { positions.append(p) }
                x += options.positionSpacing
            }
            y += options.positionSpacing
        }
        let yaws = stride(from: 0.0, to: 360.0, by: options.yawStepDegrees).map { $0 }
        for p in positions { for yaw in yaws {
            var f = current; f.position = p; f.yawDegrees = yaw; fans.append(f)
        } }
        // 原位转向
        for yaw in yaws { var f = current; f.yawDegrees = yaw; fans.append(f) }
        if fans.count > options.maxCandidates {
            let stride = Double(fans.count - 1) / Double(options.maxCandidates - 1)
            fans = (0..<options.maxCandidates).map { fans[Int((Double($0) * stride).rounded())] }
        }

        let scores = fans.compactMap { f in score(evaluate(f, options.coarse).0, f) }
        guard let base = scores.first else { return [] }

        let stronger = scores.max { a, b in
            a.breeze != b.breeze ? a.breeze < b.breeze : (a.draft != b.draft ? a.draft > b.draft : a.cost > b.cost)
        }!
        let lessDraft = scores.filter { $0.breeze >= min(base.breeze, stronger.breeze * 0.5) }.min { a, b in
            a.draft != b.draft ? a.draft < b.draft : (a.breeze != b.breeze ? a.breeze > b.breeze : a.cost < b.cost)
        } ?? base
        // 最省事：在不更差（送风不降、直吹不增）的前提下，取能明显改善且调整最少的；无改善则保持现状
        let improving = scores.filter { $0.breeze >= base.breeze + 0.1 && $0.draft <= base.draft + 1e-9 }
        let least = improving.min { a, b in a.cost != b.cost ? a.cost < b.cost : a.breeze > b.breeze } ?? base

        return [(Kind.leastEffort, least), (.strongerBreeze, stronger), (.lessDraft, lessDraft)].map { kind, s in
            let (res, r) = evaluate(s.fan, options.final)
            let m = res.metrics
            let want = m?.targets.filter { $0.objective == .wantBreeze } ?? []
            let avoid = m?.targets.filter { $0.objective == .avoidDirectDraft } ?? []
            func lvl(_ x: Double) -> Level { x >= 0.6 ? .high : x >= 0.3 ? .medium : x > 0.05 ? .low : .none }
            let breeze = want.isEmpty ? (m?.circulationCoverage ?? 0) : want.map(\.breezeCoverage).reduce(0, +) / Double(want.count)
            return Candidate(kind: kind, fan: s.fan, room: r, result: res,
                             cost: s.cost < 0.05 ? .none : s.cost < 1 ? .low : s.cost < 3 ? .medium : .high,
                             breeze: lvl(breeze), draft: lvl(avoid.map(\.draftExposure).max() ?? 0),
                             isCurrent: s.cost < 0.05)
        }
    }

    /// 调整成本（米 + 转向折算）。
    static func moveCost(_ a: Fan, _ b: Fan) -> Double {
        var dy = abs(a.yawDegrees - b.yawDegrees).truncatingRemainder(dividingBy: 360); if dy > 180 { dy = 360 - dy }
        return a.position.distance(to: b.position) + (dy > 1 ? 0.3 + dy / 360 : 0)
    }
}
