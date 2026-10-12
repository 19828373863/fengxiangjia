import Foundation

/// 模拟输入：单房间 + 所属住宅（用于拓扑）+ 外部环境快照。
public struct SimulationInput: Sendable {
    public var home: Home
    public var roomId: EntityID
    public var environment: Environment?
    public var options: SimulationOptions

    public init(home: Home, roomId: EntityID, environment: Environment?, options: SimulationOptions = .init()) {
        self.home = home; self.roomId = roomId; self.environment = environment; self.options = options
    }

    public var room: Room? { home.rooms.first { $0.id == roomId } }
}

public struct SimulationOptions: Codable, Hashable, Sendable {
    /// 网格边长（米）；实际会放大以限制单轴最多 `maxCellsPerAxis`。
    public var cellSize: Double = 0.1
    public var maxCellsPerAxis: Int = 80
    public var smoothingIterations: Int = 12
    public init() {}
}

/// 网格向量场（m/s，户型坐标）。`mask[i] == false` 为墙外或障碍。索引 = iy * nx + ix。
public struct FlowField: Codable, Hashable, Sendable {
    public var origin: Vec2
    public var cellSize: Double
    public var nx: Int
    public var ny: Int
    public var u: [Double]
    public var v: [Double]
    public var mask: [Bool]

    public func index(_ ix: Int, _ iy: Int) -> Int { iy * nx + ix }
    public func center(_ ix: Int, _ iy: Int) -> Vec2 {
        Vec2(origin.x + (Double(ix) + 0.5) * cellSize, origin.y + (Double(iy) + 0.5) * cellSize)
    }
    public func cell(of p: Vec2) -> (Int, Int)? {
        let ix = Int(((p.x - origin.x) / cellSize).rounded(.down)), iy = Int(((p.y - origin.y) / cellSize).rounded(.down))
        guard ix >= 0, iy >= 0, ix < nx, iy < ny else { return nil }
        return (ix, iy)
    }
    public func speed(at i: Int) -> Double { (u[i] * u[i] + v[i] * v[i]).squareRoot() }

    /// 双线性插值采样；墙外/障碍返回 nil。
    public func sample(_ p: Vec2) -> Vec2? {
        guard let (cx, cy) = cell(of: p), mask[index(cx, cy)] else { return nil }
        let fx = (p.x - origin.x) / cellSize - 0.5, fy = (p.y - origin.y) / cellSize - 0.5
        let x0 = max(min(Int(fx.rounded(.down)), nx - 1), 0), y0 = max(min(Int(fy.rounded(.down)), ny - 1), 0)
        let x1 = min(x0 + 1, nx - 1), y1 = min(y0 + 1, ny - 1)
        let tx = min(max(fx - Double(x0), 0), 1), ty = min(max(fy - Double(y0), 0), 1)
        var acc = Vec2.zero, wsum = 0.0
        for (ix, iy, w) in [(x0, y0, (1 - tx) * (1 - ty)), (x1, y0, tx * (1 - ty)), (x0, y1, (1 - tx) * ty), (x1, y1, tx * ty)] {
            let i = index(ix, iy)
            guard mask[i], w > 0 else { continue }
            acc = acc + Vec2(u[i], v[i]) * w; wsum += w
        }
        return wsum > 0 ? acc * (1 / wsum) : Vec2(u[index(cx, cy)], v[index(cx, cy)])
    }
}

/// 定性等级：避免伪精确分数。
public enum Level: Int, Codable, Comparable, Sendable, CaseIterable {
    case none = 0, low, medium, high
    public static func < (a: Level, b: Level) -> Bool { a.rawValue < b.rawValue }
}

public struct TargetMetrics: Codable, Hashable, Sendable {
    public var targetId: EntityID
    public var objective: TargetObjective
    /// 目标区域内风速 ≥ breezeThreshold 的格子比例（0…1）。
    public var breezeCoverage: Double
    /// 目标区域内风速 ≥ draftThreshold 的格子比例（0…1）。
    public var draftExposure: Double
    public var meanSpeed: Double
}

public struct SimulationMetrics: Codable, Hashable, Sendable {
    public static let breezeThreshold = 0.3 // m/s，可感微风
    public static let draftThreshold = 0.8  // m/s，明显直吹
    public static let circulationThreshold = 0.15

    /// 室内有效循环覆盖率（0…1）。
    public var circulationCoverage: Double
    public var meanSpeed: Double
    /// 对外换气等级；全封闭房间恒为 .none。
    public var outdoorExchange: Level
    /// 是否存在对外通路（至少一个开启的开口可达室外）。
    public var hasOutdoorPath: Bool
    /// 是否形成穿堂风（迎风 + 背风开口）。
    public var crossVentilation: Bool
    public var targets: [TargetMetrics]
}

public enum Explanation: Hashable, Codable, Sendable {
    case closedRoomInternalOnly
    case singleSidedOpening
    case crossVentilation
    case noEnvironment
    case staleEnvironment
    case fanBlocked(fanId: EntityID)
    case targetCovered(targetId: EntityID)
    case targetMissed(targetId: EntityID)
    case targetDraft(targetId: EntityID)
    case fanAidsExhaust(fanId: EntityID)
    case obstructionReduced(openingId: EntityID, level: ObstructionLevel)
}

public enum SimulationStatus: Hashable, Codable, Sendable {
    case ok
    case invalidInput([String])
}

public struct SimulationResult: Codable, Hashable, Sendable {
    public static let modelVersion = "fxj-2d-0.1"
    public var scenarioHash: String
    public var modelVersion: String
    public var status: SimulationStatus
    public var field: FlowField?
    public var metrics: SimulationMetrics?
    public var explanations: [Explanation]

    /// 结果是否仍对应当前输入（输入任何变化 → 失效，界面需标“待更新”）。
    public func isCurrent(for input: SimulationInput) -> Bool {
        modelVersion == Self.modelVersion && scenarioHash == ScenarioHash.compute(input)
    }
}
