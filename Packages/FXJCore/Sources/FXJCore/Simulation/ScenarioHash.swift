import Foundation

/// scenarioHash = SHA-256(规范化 JSON(模型版本 + 房间几何/布局/开口状态 + 连接 + 环境 + 选项))。
/// 规范化：键排序；长度量化到 1 mm、角度 0.1°、比例 0.001；字典转为按 id 排序的数组。
public enum ScenarioHash {
    struct Canonical: Encodable {
        var model: String
        var orientation: Int
        var polygon: [[Int]]
        var wallIds: [String]
        var openings: [[String]]
        var obstacles: [[String]]
        var fans: [[String]]
        var targets: [[String]]
        var environment: [String]?
        var obstruction: [[String]]
        var options: [String]
    }

    static func q(_ x: Double, _ step: Double) -> Int { Int((x / step).rounded()) }
    static func s(_ x: Double, _ step: Double) -> String { String(q(x, step)) }

    public static func compute(_ input: SimulationInput) -> String {
        guard let room = input.room else { return "no-room" }
        let mm = 0.001
        func conn(_ c: ConnectionTarget) -> String {
            switch c { case .room(let id): return "r:" + id.uuidString; case .outdoor: return "out"; case .unknown: return "?" }
        }
        let openings = room.openings.sorted { $0.id.uuidString < $1.id.uuidString }.map { o in
            [o.id.uuidString, o.wallId.uuidString, o.kind.rawValue, s(o.offset, mm), s(o.width, mm), s(o.openFraction, 0.001),
             Topology.connections(for: o, in: room, home: input.home).map(conn).joined(separator: "|")]
        }
        let obstacles = room.obstacles.sorted { $0.id.uuidString < $1.id.uuidString }.map { b in
            [b.id.uuidString, b.kind.rawValue, s(b.center.x, mm), s(b.center.y, mm), s(b.size.x, mm), s(b.size.y, mm),
             s(b.rotationDegrees, 0.1), b.heightClass.rawValue]
        }
        let fans = room.fans.sorted { $0.id.uuidString < $1.id.uuidString }.map { f in
            [f.id.uuidString, f.kind.rawValue, s(f.position.x, mm), s(f.position.y, mm), s(f.yawDegrees, 0.1), s(f.power, 0.001)]
        }
        let targets = room.targets.sorted { $0.id.uuidString < $1.id.uuidString }.map { t -> [String] in
            var row = [t.id.uuidString, t.objective.rawValue]
            switch t.shape {
            case .circle(let c, let r): row += ["c", s(c.x, mm), s(c.y, mm), s(r, mm)]
            case .polygon(let p): row += ["p"] + p.flatMap { [s($0.x, mm), s($0.y, mm)] }
            }
            return row
        }
        let env = input.environment.map { e in
            [e.source.rawValue, String(Int(e.observedAt.timeIntervalSince1970)), s(e.windFromDegrees, 0.1),
             e.windSpeed.map { s($0, 0.01) } ?? "-", e.temperature.map { s($0, 0.1) } ?? "-", e.rain.map { s($0, 0.1) } ?? "-"]
        }
        let obstruction = (input.environment?.obstructionByOpening ?? [:])
            .map { [$0.key.uuidString, $0.value.rawValue] }.sorted { $0[0] < $1[0] }
        let o = input.options
        let canonical = Canonical(
            model: SimulationResult.modelVersion,
            orientation: q(input.home.orientationDegrees, 0.1),
            polygon: room.polygon.map { [q($0.x, mm), q($0.y, mm)] },
            wallIds: room.wallIds.map(\.uuidString),
            openings: openings, obstacles: obstacles, fans: fans, targets: targets,
            environment: env, obstruction: obstruction,
            options: [s(o.cellSize, mm), String(o.maxCellsPerAxis), String(o.smoothingIterations)])
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys]
        let data = (try? enc.encode(canonical)) ?? Data()
        return SHA256.hex(data)
    }
}
