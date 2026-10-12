package app.fengxiangjia.core.simulation

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.domain.Target
import kotlin.math.floor
import kotlin.math.sqrt

/** 模拟输入：单房间 + 所属住宅（用于拓扑）+ 外部环境快照。 */
data class SimulationInput(val home: Home, val roomId: EntityID, val environment: Environment?, val options: SimulationOptions = SimulationOptions()) {
    val room: Room? get() = home.rooms.firstOrNull { it.id == roomId }
}

data class SimulationOptions(val cellSize: Double = 0.1, val maxCellsPerAxis: Int = 80, val smoothingIterations: Int = 12)

/** 网格向量场（m/s）。`mask[i] == false` 为墙外或障碍。索引 = iy * nx + ix。 */
class FlowField(val origin: Vec2, val cellSize: Double, val nx: Int, val ny: Int,
                var u: DoubleArray, var v: DoubleArray, val mask: BooleanArray) {
    fun index(ix: Int, iy: Int) = iy * nx + ix
    fun center(ix: Int, iy: Int) = Vec2(origin.x + (ix + 0.5) * cellSize, origin.y + (iy + 0.5) * cellSize)
    fun cell(p: Vec2): Pair<Int, Int>? {
        val ix = floor((p.x - origin.x) / cellSize).toInt(); val iy = floor((p.y - origin.y) / cellSize).toInt()
        if (ix < 0 || iy < 0 || ix >= nx || iy >= ny) return null
        return ix to iy
    }
    fun speed(i: Int) = sqrt(u[i] * u[i] + v[i] * v[i])

    /** 双线性插值；墙外/障碍返回 null。 */
    fun sample(p: Vec2): Vec2? {
        val (cx, cy) = cell(p) ?: return null
        if (!mask[index(cx, cy)]) return null
        val fx = (p.x - origin.x) / cellSize - 0.5; val fy = (p.y - origin.y) / cellSize - 0.5
        val x0 = floor(fx).toInt().coerceAtMost(nx - 1).coerceAtLeast(0); val y0 = floor(fy).toInt().coerceAtMost(ny - 1).coerceAtLeast(0)
        val x1 = minOf(x0 + 1, nx - 1); val y1 = minOf(y0 + 1, ny - 1)
        val tx = (fx - x0).coerceAtLeast(0.0).coerceAtMost(1.0); val ty = (fy - y0).coerceAtLeast(0.0).coerceAtMost(1.0)
        var acc = Vec2.ZERO; var wsum = 0.0
        for ((ix, iy, w) in listOf(Triple(x0, y0, (1 - tx) * (1 - ty)), Triple(x1, y0, tx * (1 - ty)), Triple(x0, y1, (1 - tx) * ty), Triple(x1, y1, tx * ty))) {
            val i = index(ix, iy)
            if (!mask[i] || w <= 0) continue
            acc = acc + Vec2(u[i], v[i]) * w; wsum += w
        }
        return if (wsum > 0) acc * (1 / wsum) else Vec2(u[index(cx, cy)], v[index(cx, cy)])
    }
}

/** 定性等级（避免伪精确分数）。 */
enum class Level { none, low, medium, high }

data class TargetMetrics(val targetId: EntityID, val objective: TargetObjective, val breezeCoverage: Double, val draftExposure: Double, val meanSpeed: Double)

data class SimulationMetrics(
    val circulationCoverage: Double, val meanSpeed: Double, val outdoorExchange: Level,
    val hasOutdoorPath: Boolean, val crossVentilation: Boolean, val targets: List<TargetMetrics>,
) {
    companion object {
        const val BREEZE_THRESHOLD = 0.3
        const val DRAFT_THRESHOLD = 0.8
        const val CIRCULATION_THRESHOLD = 0.15
    }
}

/** 解释项。`token` 与黄金样例中的字符串一致。 */
sealed interface Explanation {
    val token: String
    data object ClosedRoomInternalOnly : Explanation { override val token = "closedRoomInternalOnly" }
    data object SingleSidedOpening : Explanation { override val token = "singleSidedOpening" }
    data object CrossVentilation : Explanation { override val token = "crossVentilation" }
    data object NoEnvironment : Explanation { override val token = "noEnvironment" }
    data object StaleEnvironment : Explanation { override val token = "staleEnvironment" }
    data class FanBlocked(val fanId: EntityID) : Explanation { override val token get() = "fanBlocked:$fanId" }
    data class TargetCovered(val targetId: EntityID) : Explanation { override val token get() = "targetCovered:$targetId" }
    data class TargetMissed(val targetId: EntityID) : Explanation { override val token get() = "targetMissed:$targetId" }
    data class TargetDraft(val targetId: EntityID) : Explanation { override val token get() = "targetDraft:$targetId" }
    data class FanAidsExhaust(val fanId: EntityID) : Explanation { override val token get() = "fanAidsExhaust:$fanId" }
    data class ObstructionReduced(val openingId: EntityID, val level: ObstructionLevel) : Explanation { override val token get() = "obstructionReduced:$openingId:${level.name}" }
}

sealed interface SimulationStatus {
    data object Ok : SimulationStatus
    data class InvalidInput(val reasons: List<String>) : SimulationStatus
}

class SimulationResult(
    val scenarioHash: String, val modelVersion: String, val status: SimulationStatus,
    val field: FlowField?, val metrics: SimulationMetrics?, val explanations: List<Explanation>,
) {
    /** 结果是否仍对应当前输入（任何变化 → 失效，界面需标“待更新”）。 */
    fun isCurrent(input: SimulationInput) = modelVersion == MODEL_VERSION && scenarioHash == ScenarioHash.compute(input)
    companion object { const val MODEL_VERSION = "fxj-2d-0.1" }
}

fun Target.containsPoint(p: Vec2) = when (val s = shape) {
    is TargetShape.Circle -> s.center.distanceTo(p) <= s.radius
    is TargetShape.Polygon -> app.fengxiangjia.core.geometry.Geometry.contains(s.points, p)
}
