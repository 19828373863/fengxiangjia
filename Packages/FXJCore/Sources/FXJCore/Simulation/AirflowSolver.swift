import Foundation

/// 轻量二维气流示意模型 fxj-2d-0.1（生活用途，非工程 CFD）。
/// 1) 网格化房间，墙外与非低矮家具为固体；2) 风扇按自由射流衰减叠加，视线被固体遮挡；
/// 3) 若形成穿堂风，在迎风开口叠加入流射流；4) 邻域平滑模拟扩散与绕流；5) 汇总定性指标。
/// 确定性：相同输入（同平台）得到逐位相同结果。
public enum AirflowSolver {
    struct FanSpec { var u0: Double; var decay: Double }

    static func spec(_ f: Fan) -> FanSpec {
        let p = f.power
        switch f.kind {
        case .pedestal: return FanSpec(u0: 3.0 * p, decay: 1.5 + 2.5 * p)
        case .circulator: return FanSpec(u0: 3.5 * p, decay: 2.5 + 3.5 * p)
        case .desk: return FanSpec(u0: 2.2 * p, decay: 1.0 + 1.5 * p)
        case .tower: return FanSpec(u0: 2.0 * p, decay: 1.2 + 1.5 * p)
        case .ceiling: return FanSpec(u0: 1.2 * p, decay: 1.5)
        }
    }

    public static func solve(_ input: SimulationInput) -> SimulationResult {
        let hash = ScenarioHash.compute(input)
        guard let room = input.room else {
            return SimulationResult(scenarioHash: hash, modelVersion: SimulationResult.modelVersion,
                                    status: .invalidInput(["roomNotFound"]), field: nil, metrics: nil, explanations: [])
        }
        let blocking = RoomValidator.validate(room).filter { if case .itemOutsideRoom = $0 { return false }; return true }
        guard blocking.isEmpty else {
            return SimulationResult(scenarioHash: hash, modelVersion: SimulationResult.modelVersion,
                                    status: .invalidInput(blocking.map { String(describing: $0) }), field: nil, metrics: nil, explanations: [])
        }

        var field = makeGrid(room, input.options)
        var explanations: [Explanation] = []

        // 风扇射流
        for fan in room.fans.sorted(by: { $0.id.uuidString < $1.id.uuidString }) {
            let sp = spec(fan)
            if fan.kind == .ceiling {
                addRadial(&field, from: fan.position, u0: sp.u0, decay: sp.decay)
            } else {
                let yaw = fan.yawDegrees * .pi / 180
                let dir = Vec2(cos(yaw), sin(yaw))
                if isBlocked(field, from: fan.position, dir: dir, distance: 0.4) { explanations.append(.fanBlocked(fanId: fan.id)) }
                addJet(&field, from: fan.position, dir: dir, u0: sp.u0, decay: sp.decay, spread0: 0.15)
            }
        }

        // 对外通路与换气
        let paths = outdoorPathOpenings(room, home: input.home)
        let windTo = input.environment?.windToVector(homeOrientationDegrees: input.home.orientationDegrees)
        let windSpeed = input.environment?.windSpeed ?? 0
        var windward: [(Opening, Wall, Double)] = [], leeward = 0, indirect = 0
        for (o, direct) in paths {
            guard direct else { indirect += 1; continue }
            guard let w = room.wall(id: o.wallId), let wt = windTo else { continue }
            let d = w.outwardNormal.dot(wt)
            let level = input.environment?.obstructionByOpening[o.id]
            let factor = ObstructionModel.windFactor(level, floor: input.home.floorLevel)
            if let level, level != .open { explanations.append(.obstructionReduced(openingId: o.id, level: level)) }
            if d < -0.3 { windward.append((o, w, windSpeed * factor * o.openFraction)) } else if d > 0.3 { leeward += 1 }
        }
        let cross = !windward.isEmpty && (leeward > 0 || indirect > 0) && windSpeed > 0.1
        var exchange: Level = .none
        if !paths.isEmpty {
            if input.environment == nil { explanations.append(.noEnvironment) }
            let eff = windward.reduce(0.0) { $0 + $1.2 * $1.0.width }
            if cross {
                explanations.append(.crossVentilation)
                for (o, w, u) in windward {
                    let c = w.point(atOffset: o.offset + o.width / 2)
                    addJet(&field, from: c - w.outwardNormal * 0.05, dir: w.outwardNormal * -1, u0: u * 0.35,
                           decay: 4, spread0: o.width / 2)
                }
                exchange = eff >= 3 ? .high : (eff >= 1 ? .medium : .low)
            } else {
                if paths.count == 1 { explanations.append(.singleSidedOpening) }
                exchange = .low
            }
            // 风扇靠近对外开口并朝向开口 → 助排风，提升一级
            for fan in room.fans where fan.kind != .ceiling {
                let yaw = fan.yawDegrees * .pi / 180
                let dir = Vec2(cos(yaw), sin(yaw))
                let aids = paths.contains { (o, direct) in
                    guard direct, let w = room.wall(id: o.wallId) else { return false }
                    let c = w.point(atOffset: o.offset + o.width / 2)
                    return c.distance(to: fan.position) <= 1.5 && (c - fan.position).normalized.dot(dir) > 0.7
                }
                if aids && paths.count >= 2 {
                    explanations.append(.fanAidsExhaust(fanId: fan.id))
                    exchange = Level(rawValue: min(exchange.rawValue + 1, Level.high.rawValue)) ?? exchange
                    break
                }
            }
        } else if !room.fans.isEmpty {
            explanations.append(.closedRoomInternalOnly)
        }

        smooth(&field, iterations: input.options.smoothingIterations)

        // 指标
        var inside = 0, circ = 0, sum = 0.0
        for i in field.mask.indices where field.mask[i] {
            inside += 1
            let s = field.speed(at: i)
            sum += s
            if s >= SimulationMetrics.circulationThreshold { circ += 1 }
        }
        var targets: [TargetMetrics] = []
        for t in room.targets {
            var n = 0, breeze = 0, draft = 0, ts = 0.0
            for iy in 0..<field.ny { for ix in 0..<field.nx {
                let i = field.index(ix, iy)
                guard field.mask[i], t.contains(field.center(ix, iy)) else { continue }
                n += 1
                let s = field.speed(at: i)
                ts += s
                if s >= SimulationMetrics.breezeThreshold { breeze += 1 }
                if s >= SimulationMetrics.draftThreshold { draft += 1 }
            } }
            let m = TargetMetrics(targetId: t.id, objective: t.objective,
                                  breezeCoverage: n > 0 ? Double(breeze) / Double(n) : 0,
                                  draftExposure: n > 0 ? Double(draft) / Double(n) : 0,
                                  meanSpeed: n > 0 ? ts / Double(n) : 0)
            targets.append(m)
            switch t.objective {
            case .wantBreeze: explanations.append(m.breezeCoverage >= 0.5 ? .targetCovered(targetId: t.id) : .targetMissed(targetId: t.id))
            case .avoidDirectDraft: if m.draftExposure > 0.2 { explanations.append(.targetDraft(targetId: t.id)) }
            }
        }
        let metrics = SimulationMetrics(
            circulationCoverage: inside > 0 ? Double(circ) / Double(inside) : 0,
            meanSpeed: inside > 0 ? sum / Double(inside) : 0,
            outdoorExchange: exchange, hasOutdoorPath: !paths.isEmpty, crossVentilation: cross, targets: targets)
        return SimulationResult(scenarioHash: hash, modelVersion: SimulationResult.modelVersion, status: .ok,
                                field: field, metrics: metrics, explanations: explanations)
    }

    /// 本房间开启的、可通向室外的开口；direct = 直接通室外。
    static func outdoorPathOpenings(_ room: Room, home: Home) -> [(Opening, Bool)] {
        let affecting = home.openingsAffecting(room.id).filter { $0.opening.openFraction > 0 }
        // 隔离：去掉所有与本房间相关的开口，判断邻室是否能“不经本房间”通向室外
        let related = Set(home.openingsAffecting(room.id).map(\.opening.id))
        var isolated = home
        for i in isolated.rooms.indices { isolated.rooms[i].openings.removeAll { related.contains($0.id) } }
        return affecting.compactMap { item in
            let (o, host) = item
            let c = Topology.connections(for: o, in: host, home: home)
            let other: ConnectionTarget? = host.id == room.id ? c.last : .room(host.id)
            switch other {
            case .outdoor?: return (o, true)
            case .room(let rid)?: return Topology.reachesOutdoor(rid, in: isolated) ? (o, false) : nil
            default: return nil
            }
        }
    }

    static func makeGrid(_ room: Room, _ opt: SimulationOptions) -> FlowField {
        let xs = room.polygon.map(\.x), ys = room.polygon.map(\.y)
        let minX = xs.min()!, maxX = xs.max()!, minY = ys.min()!, maxY = ys.max()!
        let cell = max(opt.cellSize, max(maxX - minX, maxY - minY) / Double(max(opt.maxCellsPerAxis, 4)))
        let nx = max(Int(((maxX - minX) / cell).rounded(.up)), 1), ny = max(Int(((maxY - minY) / cell).rounded(.up)), 1)
        var f = FlowField(origin: Vec2(minX, minY), cellSize: cell, nx: nx, ny: ny,
                          u: Array(repeating: 0, count: nx * ny), v: Array(repeating: 0, count: nx * ny),
                          mask: Array(repeating: false, count: nx * ny))
        let solids = room.obstacles.filter { $0.heightClass != .low }
        for iy in 0..<ny { for ix in 0..<nx {
            let c = f.center(ix, iy)
            f.mask[f.index(ix, iy)] = room.contains(c) && !solids.contains { $0.contains(c) }
        } }
        return f
    }

    static func isBlocked(_ f: FlowField, from a: Vec2, to b: Vec2) -> Bool {
        let d = b - a, len = d.length
        guard len > 0 else { return false }
        let step = f.cellSize * 0.5
        var t = step
        while t < len - step {
            let p = a + d * (t / len)
            guard let (ix, iy) = f.cell(of: p), f.mask[f.index(ix, iy)] else { return true }
            t += step
        }
        return false
    }

    static func isBlocked(_ f: FlowField, from a: Vec2, dir: Vec2, distance: Double) -> Bool {
        isBlocked(f, from: a, to: a + dir * distance) || f.cell(of: a + dir * distance).map { !f.mask[f.index($0.0, $0.1)] } ?? true
    }

    static func addJet(_ f: inout FlowField, from p: Vec2, dir: Vec2, u0: Double, decay: Double, spread0: Double) {
        guard u0 > 0 else { return }
        for iy in 0..<f.ny { for ix in 0..<f.nx {
            let i = f.index(ix, iy)
            guard f.mask[i] else { continue }
            let c = f.center(ix, iy), r = c - p
            let along = r.dot(dir)
            guard along > 0 else { continue }
            let lateral = abs(r.cross(dir)), width = spread0 + 0.22 * along
            let amp = u0 * exp(-along / decay) * exp(-(lateral / width) * (lateral / width))
            guard amp > 0.01, !isBlocked(f, from: p, to: c) else { continue }
            f.u[i] += dir.x * amp; f.v[i] += dir.y * amp
        } }
    }

    static func addRadial(_ f: inout FlowField, from p: Vec2, u0: Double, decay: Double) {
        for iy in 0..<f.ny { for ix in 0..<f.nx {
            let i = f.index(ix, iy)
            guard f.mask[i] else { continue }
            let r = f.center(ix, iy) - p, d = r.length
            guard d > 0.05, !isBlocked(f, from: p, to: f.center(ix, iy)) else { continue }
            let amp = u0 * exp(-d / decay)
            let n = r.normalized
            f.u[i] += n.x * amp; f.v[i] += n.y * amp
        } }
    }

    /// 邻域平滑（固体处速度为 0，近似无滑移边界）。
    static func smooth(_ f: inout FlowField, iterations: Int) {
        guard iterations > 0 else { return }
        for _ in 0..<iterations {
            var nu = f.u, nv = f.v
            for iy in 0..<f.ny { for ix in 0..<f.nx {
                let i = f.index(ix, iy)
                guard f.mask[i] else { continue }
                var su = 0.0, sv = 0.0
                for (dx, dy) in [(1, 0), (-1, 0), (0, 1), (0, -1)] {
                    let x = ix + dx, y = iy + dy
                    guard x >= 0, y >= 0, x < f.nx, y < f.ny else { continue }
                    let j = f.index(x, y)
                    if f.mask[j] { su += f.u[j]; sv += f.v[j] }
                }
                nu[i] = 0.6 * f.u[i] + 0.1 * su
                nv[i] = 0.6 * f.v[i] + 0.1 * sv
            } }
            f.u = nu; f.v = nv
        }
    }
}
