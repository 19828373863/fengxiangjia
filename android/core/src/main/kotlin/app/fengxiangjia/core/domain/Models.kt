package app.fengxiangjia.core.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin

// 暂定契约（provisional，待 #26 确认），与 iOS FXJCore `Domain/Models.swift` 逐字段对应。

/** 住宅。`orientationDegrees`：户型 +Y 轴相对真北的方位角，顺时针为正。 */
@Serializable
data class Home(
    @Serializable(EntityIdSerializer::class) val id: EntityID = newId(),
    val name: String,
    val location: Location? = null,
    val orientationDegrees: Double = 0.0,
    val floorLevel: Int? = null,
    val rooms: List<Room> = emptyList(),
    val constraints: List<String> = emptyList(),
)

/** 大致位置（隐私：仅本地保存）。 */
@Serializable
data class Location(val latitude: Double, val longitude: Double, val label: String? = null)

@Serializable
enum class RoomPurpose { living, bedroom, study, kitchen, bathroom, balcony, other }

/** 房间。`polygon` 逆时针；第 i 面墙为顶点 i → i+1（取模）。`wallIds.size == polygon.size`。 */
@Serializable
data class Room(
    @Serializable(EntityIdSerializer::class) val id: EntityID = newId(),
    val name: String,
    val polygon: List<Vec2>,
    val wallIds: List<@Serializable(EntityIdSerializer::class) EntityID> = polygon.map { newId() },
    val height: Double? = 2.7,
    val purpose: RoomPurpose = RoomPurpose.living,
    val openings: List<Opening> = emptyList(),
    val obstacles: List<Obstacle> = emptyList(),
    val fans: List<Fan> = emptyList(),
    val targets: List<Target> = emptyList(),
)

/** 由房间边派生的墙段（不持久化）。 */
data class Wall(val id: EntityID, val roomId: EntityID, val index: Int, val start: Vec2, val end: Vec2) {
    val length: Double get() = start.distanceTo(end)
    val direction: Vec2 get() = (end - start).normalized
    /** 逆时针多边形的外法线。 */
    val outwardNormal: Vec2 get() { val d = direction; return Vec2(d.y, -d.x) }
    fun pointAtOffset(offset: Double) = start + direction * offset
}

@Serializable
enum class OpeningKind { door, window, passage }

/** 开口一侧连接的区域。JSON：`{"room":{"_0":id}}` / `{"outdoor":{}}` / `{"unknown":{}}`。 */
@Serializable(ConnectionTargetSerializer::class)
sealed interface ConnectionTarget {
    data class RoomRef(val id: EntityID) : ConnectionTarget
    data object Outdoor : ConnectionTarget
    data object Unknown : ConnectionTarget
}

object ConnectionTargetSerializer : KSerializer<ConnectionTarget> {
    override val descriptor = SwiftEnum.emptyDescriptor
    override fun serialize(encoder: Encoder, value: ConnectionTarget) {
        val el = when (value) {
            is ConnectionTarget.RoomRef -> buildJsonObject { put("room", buildJsonObject { put("_0", value.id.uppercase()) }) }
            ConnectionTarget.Outdoor -> buildJsonObject { put("outdoor", JsonObject(emptyMap())) }
            ConnectionTarget.Unknown -> buildJsonObject { put("unknown", JsonObject(emptyMap())) }
        }
        (encoder as JsonEncoder).encodeJsonElement(el)
    }
    override fun deserialize(decoder: Decoder): ConnectionTarget {
        val (k, p) = SwiftEnum.single((decoder as JsonDecoder).obj())
        return when (k) {
            "room" -> ConnectionTarget.RoomRef(p.str("_0").uppercase())
            "outdoor" -> ConnectionTarget.Outdoor
            "unknown" -> ConnectionTarget.Unknown
            else -> throw SerializationException("unknown ConnectionTarget $k")
        }
    }
}

/** 门窗：`offset` 为沿墙（起点→终点）到开口起点的距离（米），`width` 开口宽度（米）。 */
@Serializable
data class Opening(
    @Serializable(EntityIdSerializer::class) val id: EntityID = newId(),
    @Serializable(EntityIdSerializer::class) val wallId: EntityID,
    val kind: OpeningKind,
    val offset: Double,
    val width: Double,
    val openFraction: Double = 1.0,
    val locked: Boolean = false,
    val connects: List<ConnectionTarget> = emptyList(),
    /** #16 窗外遮挡（可选；nil = 未知）。 */
    val exteriorObstruction: ObstructionLevel? = null,
)

@Serializable enum class HeightClass { low, medium, tall }
@Serializable enum class ObstacleKind { bed, sofa, table, wardrobe, desk, other }

/** 家具/障碍物：矩形，`center` 与 `size`（米），`rotationDegrees` 逆时针。 */
@Serializable
data class Obstacle(
    @Serializable(EntityIdSerializer::class) val id: EntityID = newId(),
    val kind: ObstacleKind,
    val center: Vec2,
    val size: Vec2,
    val rotationDegrees: Double = 0.0,
    val heightClass: HeightClass = HeightClass.medium,
) {
    /** 点是否落在（可旋转的）家具矩形内。 */
    fun contains(p: Vec2, margin: Double = 0.0): Boolean {
        val a = -rotationDegrees * Math.PI / 180
        val d = p - center
        val lx = d.x * cos(a) - d.y * sin(a)
        val ly = d.x * sin(a) + d.y * cos(a)
        return kotlin.math.abs(lx) <= size.x / 2 + margin && kotlin.math.abs(ly) <= size.y / 2 + margin
    }
}

@Serializable enum class FanKind { pedestal, circulator, desk, tower, ceiling }

/** 风扇。`yawDegrees`：出风方向，自 +X 逆时针。`power`：无量纲档位 0…1（用 [Fan.of] 构造会夹取）。 */
@Serializable
data class Fan(
    @Serializable(EntityIdSerializer::class) val id: EntityID = newId(),
    val kind: FanKind = FanKind.pedestal,
    val position: Vec2,
    val yawDegrees: Double = 0.0,
    val power: Double = 0.6,
    val referenceAirflow: Double? = null,
) {
    companion object {
        /** 与 Swift `Fan.init` 一致：power 夹取到 0…1。 */
        fun of(id: EntityID = newId(), kind: FanKind = FanKind.pedestal, position: Vec2, yawDegrees: Double = 0.0,
               power: Double = 0.6, referenceAirflow: Double? = null) =
            Fan(id, kind, position, yawDegrees, power.coerceIn(0.0, 1.0), referenceAirflow)
    }
}

/** JSON：`{"circle":{"center":{..},"radius":r}}` / `{"polygon":{"_0":[..]}}`。 */
@Serializable(TargetShapeSerializer::class)
sealed interface TargetShape {
    data class Circle(val center: Vec2, val radius: Double) : TargetShape
    data class Polygon(val points: List<Vec2>) : TargetShape
}

object TargetShapeSerializer : KSerializer<TargetShape> {
    override val descriptor = SwiftEnum.emptyDescriptor
    private val listSer = ListSerializer(Vec2.serializer())
    override fun serialize(encoder: Encoder, value: TargetShape) {
        val je = encoder as JsonEncoder
        val el = when (value) {
            is TargetShape.Circle -> buildJsonObject {
                put("circle", buildJsonObject {
                    put("center", je.json.encodeToJsonElement(Vec2.serializer(), value.center))
                    put("radius", JsonPrimitive(value.radius))
                })
            }
            is TargetShape.Polygon -> buildJsonObject {
                put("polygon", buildJsonObject { put("_0", je.json.encodeToJsonElement(listSer, value.points)) })
            }
        }
        je.encodeJsonElement(el)
    }
    override fun deserialize(decoder: Decoder): TargetShape {
        val jd = decoder as JsonDecoder
        val (k, p) = SwiftEnum.single(jd.obj())
        return when (k) {
            "circle" -> TargetShape.Circle(
                jd.json.decodeFromJsonElement(Vec2.serializer(), p["center"] ?: throw SerializationException("center")),
                p.str("radius").toDouble())
            "polygon" -> TargetShape.Polygon(jd.json.decodeFromJsonElement(listSer, p["_0"] ?: throw SerializationException("_0")))
            else -> throw SerializationException("unknown TargetShape $k")
        }
    }
}

@Serializable enum class TargetObjective { wantBreeze, avoidDirectDraft }

@Serializable
data class Target(
    @Serializable(EntityIdSerializer::class) val id: EntityID = newId(),
    val name: String,
    val shape: TargetShape,
    val objective: TargetObjective,
) {
    val center: Vec2 get() = when (val s = shape) {
        is TargetShape.Circle -> s.center
        is TargetShape.Polygon -> s.points.fold(Vec2.ZERO) { a, b -> a + b } * (1.0 / maxOf(s.points.size, 1))
    }
}

@Serializable enum class EnvironmentSource { weatherKit, manual, assumed }
@Serializable enum class ObstructionLevel { open, partial, nearBuilding, lightWell, unknown }

object ObstructionMapSerializer : KSerializer<Map<EntityID, ObstructionLevel>> by UuidKeyedMapSerializer(ObstructionLevel.serializer())
object OpeningStatesSerializer : KSerializer<Map<EntityID, Double>> by UuidKeyedMapSerializer(Double.serializer())

/**
 * 外部环境快照（Swift 名 `Environment`）。`windFromDegrees`：气象惯例“来向”，相对真北顺时针。
 * `windSpeed` m/s，`temperature` ℃，`rain` mm/h。
 */
@Serializable
data class Environment(
    val source: EnvironmentSource,
    @Serializable(Iso8601Serializer::class) val observedAt: Instant,
    val windFromDegrees: Double,
    val windSpeed: Double? = null,
    val temperature: Double? = null,
    val rain: Double? = null,
    @Serializable(ObstructionMapSerializer::class) val obstructionByOpening: Map<EntityID, ObstructionLevel> = emptyMap(),
) {
    /** 风“去向”在户型坐标中的单位向量（考虑住宅朝向）。 */
    fun windToVector(homeOrientationDegrees: Double): Vec2 {
        val toBearing = (windFromDegrees + 180).rem(360.0) // 与 Swift truncatingRemainder 一致
        val rel = (toBearing - homeOrientationDegrees) * Math.PI / 180
        return Vec2(sin(rel), cos(rel))
    }
}

/** 方案：几何版本 + 环境快照 + 布局快照。 */
@Serializable
data class Scenario(
    @Serializable(EntityIdSerializer::class) val id: EntityID = newId(),
    val name: String,
    @Serializable(EntityIdSerializer::class) val roomId: EntityID,
    val geometryRevision: Int,
    val environmentSnapshot: Environment? = null,
    val fans: List<Fan>,
    @Serializable(OpeningStatesSerializer::class) val openingStates: Map<EntityID, Double> = emptyMap(),
    val targets: List<Target> = emptyList(),
    @Serializable(Iso8601Serializer::class) val createdAt: Instant = Instant.now(),
)

/** 体感反馈（仅本地保存）。 */
@Serializable enum class ComfortRating { tooWeak, justRight, tooStrong, stuffy }

@Serializable
data class ComfortFeedback(
    @Serializable(EntityIdSerializer::class) val id: EntityID = newId(),
    @Serializable(EntityIdSerializer::class) val roomId: EntityID,
    val scenarioHash: String? = null,
    val rating: ComfortRating,
    val note: String? = null,
    @Serializable(Iso8601Serializer::class) val createdAt: Instant = Instant.now(),
)

