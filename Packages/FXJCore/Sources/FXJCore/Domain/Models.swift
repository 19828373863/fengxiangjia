import Foundation

// 暂定契约（provisional，待 #26 确认）：长度单位米；角度单位度；ID 为 UUID。

public typealias EntityID = UUID

/// 住宅。`orientationDegrees`：户型 +Y 轴相对真北的方位角，顺时针为正（0 = +Y 指向正北，90 = +Y 指向正东）。
public struct Home: Codable, Hashable, Identifiable, Sendable {
    public var id: EntityID
    public var name: String
    public var location: Location?
    public var orientationDegrees: Double
    public var floorLevel: Int?
    public var rooms: [Room]
    public var constraints: [String]

    public init(id: EntityID = UUID(), name: String, location: Location? = nil, orientationDegrees: Double = 0,
                floorLevel: Int? = nil, rooms: [Room] = [], constraints: [String] = []) {
        self.id = id; self.name = name; self.location = location; self.orientationDegrees = orientationDegrees
        self.floorLevel = floorLevel; self.rooms = rooms; self.constraints = constraints
    }
}

/// 大致位置（隐私：仅本地保存，建议保留约 0.01° 精度）。
public struct Location: Codable, Hashable, Sendable {
    public var latitude: Double
    public var longitude: Double
    public var label: String?
    public init(latitude: Double, longitude: Double, label: String? = nil) {
        self.latitude = latitude; self.longitude = longitude; self.label = label
    }
}

public enum RoomPurpose: String, Codable, Sendable, CaseIterable {
    case living, bedroom, study, kitchen, bathroom, balcony, other
}

/// 房间。`polygon` 顶点按逆时针存储；第 i 面墙为顶点 i → i+1（取模）。`wallIds.count == polygon.count`。
public struct Room: Codable, Hashable, Identifiable, Sendable {
    public var id: EntityID
    public var name: String
    public var polygon: [Vec2]
    public var wallIds: [EntityID]
    public var height: Double?
    public var purpose: RoomPurpose
    public var openings: [Opening]
    public var obstacles: [Obstacle]
    public var fans: [Fan]
    public var targets: [Target]

    public init(id: EntityID = UUID(), name: String, polygon: [Vec2], wallIds: [EntityID]? = nil, height: Double? = 2.7,
                purpose: RoomPurpose = .living, openings: [Opening] = [], obstacles: [Obstacle] = [],
                fans: [Fan] = [], targets: [Target] = []) {
        self.id = id; self.name = name; self.polygon = polygon
        self.wallIds = wallIds ?? polygon.map { _ in UUID() }
        self.height = height; self.purpose = purpose; self.openings = openings
        self.obstacles = obstacles; self.fans = fans; self.targets = targets
    }
}

/// 由房间边派生的墙段（不单独持久化，只持久化 `Room.wallIds`）。
public struct Wall: Hashable, Identifiable, Sendable {
    public var id: EntityID
    public var roomId: EntityID
    public var index: Int
    public var start: Vec2
    public var end: Vec2
    public var length: Double { start.distance(to: end) }
    public var direction: Vec2 { (end - start).normalized }
    /// 逆时针多边形的外法线（指向房间外侧）。
    public var outwardNormal: Vec2 { let d = direction; return Vec2(d.y, -d.x) }
    public func point(atOffset offset: Double) -> Vec2 { start + direction * offset }
}

public enum OpeningKind: String, Codable, Sendable, CaseIterable { case door, window, passage }

/// 开口一侧连接的区域。
public enum ConnectionTarget: Codable, Hashable, Sendable {
    case room(EntityID)
    case outdoor
    case unknown
}

/// 门窗：附着于宿主墙，`offset` 为沿墙（起点→终点）到开口起点的距离（米），`width` 为开口宽度（米）。
/// `connects` 由几何拓扑推导（见 Topology），保存时作为缓存。
public struct Opening: Codable, Hashable, Identifiable, Sendable {
    public var id: EntityID
    public var wallId: EntityID
    public var kind: OpeningKind
    public var offset: Double
    public var width: Double
    public var openFraction: Double
    public var locked: Bool
    public var connects: [ConnectionTarget]

    public init(id: EntityID = UUID(), wallId: EntityID, kind: OpeningKind, offset: Double, width: Double,
                openFraction: Double = 1, locked: Bool = false, connects: [ConnectionTarget] = []) {
        self.id = id; self.wallId = wallId; self.kind = kind; self.offset = offset; self.width = width
        self.openFraction = openFraction; self.locked = locked; self.connects = connects
    }
}

public enum HeightClass: String, Codable, Sendable, CaseIterable { case low, medium, tall }
public enum ObstacleKind: String, Codable, Sendable, CaseIterable { case bed, sofa, table, wardrobe, desk, other }

/// 家具/障碍物：轴对齐矩形，`center` 与 `size`（米），`rotationDegrees` 逆时针。
public struct Obstacle: Codable, Hashable, Identifiable, Sendable {
    public var id: EntityID
    public var kind: ObstacleKind
    public var center: Vec2
    public var size: Vec2
    public var rotationDegrees: Double
    public var heightClass: HeightClass
    public init(id: EntityID = UUID(), kind: ObstacleKind, center: Vec2, size: Vec2,
                rotationDegrees: Double = 0, heightClass: HeightClass = .medium) {
        self.id = id; self.kind = kind; self.center = center; self.size = size
        self.rotationDegrees = rotationDegrees; self.heightClass = heightClass
    }
}

public enum FanKind: String, Codable, Sendable, CaseIterable { case pedestal, circulator, desk, tower, ceiling }

/// 风扇。`yawDegrees`：出风方向，户型坐标系中自 +X 逆时针。`power`：无量纲档位 0…1。`referenceAirflow`：可选 m³/h。
public struct Fan: Codable, Hashable, Identifiable, Sendable {
    public var id: EntityID
    public var kind: FanKind
    public var position: Vec2
    public var yawDegrees: Double
    public var power: Double
    public var referenceAirflow: Double?
    public init(id: EntityID = UUID(), kind: FanKind = .pedestal, position: Vec2, yawDegrees: Double = 0,
                power: Double = 0.6, referenceAirflow: Double? = nil) {
        self.id = id; self.kind = kind; self.position = position; self.yawDegrees = yawDegrees
        self.power = min(max(power, 0), 1); self.referenceAirflow = referenceAirflow
    }
}

public enum TargetShape: Codable, Hashable, Sendable {
    case circle(center: Vec2, radius: Double)
    case polygon([Vec2])
}

public enum TargetObjective: String, Codable, Sendable, CaseIterable { case wantBreeze, avoidDirectDraft }

public struct Target: Codable, Hashable, Identifiable, Sendable {
    public var id: EntityID
    public var name: String
    public var shape: TargetShape
    public var objective: TargetObjective
    public init(id: EntityID = UUID(), name: String, shape: TargetShape, objective: TargetObjective) {
        self.id = id; self.name = name; self.shape = shape; self.objective = objective
    }
}

public enum EnvironmentSource: String, Codable, Sendable { case weatherKit, manual, assumed }
public enum ObstructionLevel: String, Codable, Sendable, CaseIterable { case open, partial, nearBuilding, lightWell, unknown }

/// 外部环境快照。`windFromDegrees`：气象惯例“来向”，相对真北顺时针（0 = 北风，从北吹来）。
/// `windSpeed` m/s，`temperature` ℃，`rain` mm/h。
public struct Environment: Codable, Hashable, Sendable {
    public var source: EnvironmentSource
    public var observedAt: Date
    public var windFromDegrees: Double
    public var windSpeed: Double?
    public var temperature: Double?
    public var rain: Double?
    public var obstructionByOpening: [EntityID: ObstructionLevel]
    public init(source: EnvironmentSource, observedAt: Date, windFromDegrees: Double, windSpeed: Double? = nil,
                temperature: Double? = nil, rain: Double? = nil, obstructionByOpening: [EntityID: ObstructionLevel] = [:]) {
        self.source = source; self.observedAt = observedAt; self.windFromDegrees = windFromDegrees
        self.windSpeed = windSpeed; self.temperature = temperature; self.rain = rain
        self.obstructionByOpening = obstructionByOpening
    }

    /// 风“去向”在户型坐标中的单位向量（考虑住宅朝向）。
    public func windToVector(homeOrientationDegrees: Double) -> Vec2 {
        // 去向方位角（真北顺时针）
        let toBearing = (windFromDegrees + 180).truncatingRemainder(dividingBy: 360)
        // 相对户型 +Y 的顺时针角
        let rel = (toBearing - homeOrientationDegrees) * .pi / 180
        return Vec2(sin(rel), cos(rel))
    }
}

/// 方案：几何版本 + 环境快照 + 布局快照。
public struct Scenario: Codable, Hashable, Identifiable, Sendable {
    public var id: EntityID
    public var name: String
    public var roomId: EntityID
    public var geometryRevision: Int
    public var environmentSnapshot: Environment?
    public var fans: [Fan]
    public var openingStates: [EntityID: Double]
    public var targets: [Target]
    public var createdAt: Date
    public init(id: EntityID = UUID(), name: String, roomId: EntityID, geometryRevision: Int,
                environmentSnapshot: Environment?, fans: [Fan], openingStates: [EntityID: Double] = [:],
                targets: [Target] = [], createdAt: Date = Date()) {
        self.id = id; self.name = name; self.roomId = roomId; self.geometryRevision = geometryRevision
        self.environmentSnapshot = environmentSnapshot; self.fans = fans; self.openingStates = openingStates
        self.targets = targets; self.createdAt = createdAt
    }
}

/// App 层别名：避免与 SwiftUI `Environment` 冲突（模块与同名 enum `FXJCore` 使 `FXJCore.Environment` 无法限定）。
/// 是否将类型正式改名见 #26。
public typealias OutdoorConditions = Environment
