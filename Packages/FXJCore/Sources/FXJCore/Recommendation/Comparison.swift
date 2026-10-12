import Foundation

public extension Scenario {
    /// 从当前房间布局捕获方案。
    static func capture(name: String, room: Room, geometryRevision: Int, environment: Environment?, createdAt: Date = Date()) -> Scenario {
        Scenario(name: name, roomId: room.id, geometryRevision: geometryRevision, environmentSnapshot: environment,
                 fans: room.fans, openingStates: Dictionary(uniqueKeysWithValues: room.openings.map { ($0.id, $0.openFraction) }),
                 targets: room.targets, createdAt: createdAt)
    }

    /// 把方案布局套到房间几何上（几何以当前房间为准）。
    func applied(to room: Room) -> Room {
        var r = room
        r.fans = fans
        r.targets = targets
        for i in r.openings.indices { if let f = openingStates[r.openings[i].id] { r.openings[i].openFraction = f } }
        return r
    }
}

public enum Preference: String, Codable, Sendable { case a, b, similar }

public struct ComparisonDimension: Hashable, Sendable {
    public enum Kind: String, Sendable, CaseIterable { case targetBreeze, directDraft, outdoorExchange, circulation, adjustmentCost }
    public var kind: Kind
    public var levelA: Level
    public var levelB: Level
    /// 对用户更有利的一方（直吹与调整成本：越低越好）。
    public var preferred: Preference
}

public enum ComparisonNote: Hashable, Sendable {
    case environmentUnified          // 两方案原快照不同，已统一使用同一环境
    case geometryChanged(Preference) // 方案保存后户型已修改，结果按当前户型重算
    case closedRoomNoExchange        // 封闭房间：对外换气不因风扇改变
    case invalid(Preference)
}

public enum Suggestion: Hashable, Sendable {
    case preferForBreeze(Preference)
    case reduceDraft(Preference)
    case openOppositeWindow
    case similarChooseCheaper(Preference)
    case aimFanAtTarget
}

public struct ScenarioComparison: Sendable {
    public var environment: Environment?
    public var resultA: SimulationResult
    public var resultB: SimulationResult
    public var dimensions: [ComparisonDimension]
    public var notes: [ComparisonNote]
    /// 至多 3 条。
    public var suggestions: [Suggestion]
    public var legendRange: ClosedRange<Double> { FlowVisualization.legendRange }

    /// 用同一环境快照、同一网格参数比较两方案。
    public static func compare(home: Home, roomId: EntityID, a: Scenario, b: Scenario, currentGeometryRevision: Int,
                               environment: Environment?, options: SimulationOptions = .init()) -> ScenarioComparison? {
        guard let base = home.rooms.first(where: { $0.id == roomId }) else { return nil }
        var notes: [ComparisonNote] = []
        if a.environmentSnapshot != b.environmentSnapshot { notes.append(.environmentUnified) }
        if a.geometryRevision != currentGeometryRevision { notes.append(.geometryChanged(.a)) }
        if b.geometryRevision != currentGeometryRevision { notes.append(.geometryChanged(.b)) }

        func solve(_ s: Scenario) -> (SimulationResult, Room) {
            var h = home
            let r = s.applied(to: base)
            if let i = h.rooms.firstIndex(where: { $0.id == roomId }) { h.rooms[i] = r }
            return (AirflowSolver.solve(SimulationInput(home: h, roomId: roomId, environment: environment, options: options)), r)
        }
        let (ra, roomA) = solve(a), (rb, roomB) = solve(b)
        guard let ma = ra.metrics, let mb = rb.metrics else {
            if ra.metrics == nil { notes.append(.invalid(.a)) }
            if rb.metrics == nil { notes.append(.invalid(.b)) }
            return ScenarioComparison(environment: environment, resultA: ra, resultB: rb, dimensions: [], notes: notes, suggestions: [])
        }

        var dims: [ComparisonDimension] = []
        func fractionLevel(_ x: Double) -> Level { x >= 0.6 ? .high : x >= 0.3 ? .medium : x > 0.05 ? .low : .none }
        func add(_ k: ComparisonDimension.Kind, _ la: Level, _ lb: Level, higherIsBetter: Bool) {
            let pref: Preference = la == lb ? .similar : ((la > lb) == higherIsBetter ? .a : .b)
            dims.append(ComparisonDimension(kind: k, levelA: la, levelB: lb, preferred: pref))
        }
        func meanBreeze(_ m: SimulationMetrics) -> Double? {
            let t = m.targets.filter { $0.objective == .wantBreeze }
            return t.isEmpty ? nil : t.map(\.breezeCoverage).reduce(0, +) / Double(t.count)
        }
        func maxDraft(_ m: SimulationMetrics) -> Double? {
            let t = m.targets.filter { $0.objective == .avoidDirectDraft }
            return t.isEmpty ? nil : t.map(\.draftExposure).max()
        }
        if let x = meanBreeze(ma), let y = meanBreeze(mb) { add(.targetBreeze, fractionLevel(x), fractionLevel(y), higherIsBetter: true) }
        if let x = maxDraft(ma), let y = maxDraft(mb) { add(.directDraft, fractionLevel(x), fractionLevel(y), higherIsBetter: false) }
        add(.outdoorExchange, ma.outdoorExchange, mb.outdoorExchange, higherIsBetter: true)
        add(.circulation, fractionLevel(ma.circulationCoverage), fractionLevel(mb.circulationCoverage), higherIsBetter: true)
        add(.adjustmentCost, .none, adjustmentCost(from: roomA, to: roomB), higherIsBetter: false)

        if !ma.hasOutdoorPath && !mb.hasOutdoorPath { notes.append(.closedRoomNoExchange) }

        var sug: [Suggestion] = []
        let benefit = dims.filter { $0.kind != .adjustmentCost }
        if let d = dims.first(where: { $0.kind == .targetBreeze }), d.preferred != .similar { sug.append(.preferForBreeze(d.preferred)) }
        if let d = dims.first(where: { $0.kind == .directDraft }), d.preferred != .similar { sug.append(.reduceDraft(d.preferred)) }
        if benefit.allSatisfy({ $0.preferred == .similar }) { sug.append(.similarChooseCheaper(.a)) }
        if !ma.crossVentilation && !mb.crossVentilation && (ma.hasOutdoorPath || mb.hasOutdoorPath) && environment != nil {
            sug.append(.openOppositeWindow)
        }
        if let d = dims.first(where: { $0.kind == .targetBreeze }), d.levelA < .medium && d.levelB < .medium { sug.append(.aimFanAtTarget) }
        return ScenarioComparison(environment: environment, resultA: ra, resultB: rb, dimensions: dims, notes: notes,
                                  suggestions: Array(sug.prefix(3)))
    }

    /// 调整成本：A→B 需要移动/转动/调档的风扇与改动开口数量，归为定性等级。
    public static func adjustmentCost(from a: Room, to b: Room) -> Level {
        var cost = 0.0
        for fb in b.fans {
            guard let fa = a.fans.first(where: { $0.id == fb.id }) else { cost += 2; continue }
            cost += min(fa.position.distance(to: fb.position), 3)
            var dy = abs(fa.yawDegrees - fb.yawDegrees).truncatingRemainder(dividingBy: 360); if dy > 180 { dy = 360 - dy }
            if dy > 10 { cost += 0.5 }
            if abs(fa.power - fb.power) > 0.05 { cost += 0.25 }
        }
        cost += Double(a.fans.filter { fa in !b.fans.contains { $0.id == fa.id } }.count)
        for ob in b.openings where a.openings.first(where: { $0.id == ob.id }).map({ abs($0.openFraction - ob.openFraction) > 0.05 }) ?? false {
            cost += 0.5
        }
        return cost < 0.05 ? .none : cost < 1 ? .low : cost < 3 ? .medium : .high
    }
}
