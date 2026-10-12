package app.fengxiangjia.core.recommendation

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.editor.FanPlacement
import app.fengxiangjia.core.geometry.swiftRounded
import app.fengxiangjia.core.simulation.*
import kotlin.math.abs

/**
 * #13 约束下的有限布局搜索（移植 iOS `LayoutSearch.swift`，候选顺序与平局规则一致）：
 * 枚举风扇位置 × 朝向，粗网格评估，选出“最省事 / 送风更强 / 少直吹”，再用标准精度重算（风线与建议同源）。
 */
object LayoutSearch {
    enum class Kind { leastEffort, strongerBreeze, lessDraft }

    data class Candidate(val kind: Kind, val fan: Fan, val room: Room, val result: SimulationResult,
                         val cost: Level, val breeze: Level, val draft: Level, val isCurrent: Boolean)

    data class Options(
        val positionSpacing: Double = 0.5, val yawStepDegrees: Double = 45.0, val maxCandidates: Int = 320,
        val coarse: SimulationOptions = SimulationOptions(cellSize = 0.2, maxCellsPerAxis = 40, smoothingIterations = 6),
        val final: SimulationOptions = SimulationOptions(),
    )

    private data class Score(val breeze: Double, val draft: Double, val cost: Double, val fan: Fan)

    fun search(home: Home, roomId: EntityID, fanId: EntityID? = null, environment: Environment?, options: Options = Options()): List<Candidate> {
        val room = home.rooms.firstOrNull { it.id == roomId } ?: return emptyList()
        val fi = if (fanId != null) room.fans.indexOfFirst { it.id == fanId }.takeIf { it >= 0 } ?: return emptyList()
                 else if (room.fans.isEmpty()) return emptyList() else 0
        val current = room.fans[fi]

        fun evaluate(fan: Fan, opt: SimulationOptions): Pair<SimulationResult, Room> {
            val r = room.copy(fans = room.fans.toMutableList().also { it[fi] = fan })
            val h = home.copy(rooms = home.rooms.map { if (it.id == roomId) r else it })
            return AirflowSolver.solve(SimulationInput(h, roomId, environment, opt)) to r
        }
        fun score(res: SimulationResult, fan: Fan): Score? {
            val m = res.metrics ?: return null
            val want = m.targets.filter { it.objective == TargetObjective.wantBreeze }
            val avoid = m.targets.filter { it.objective == TargetObjective.avoidDirectDraft }
            val breeze = if (want.isEmpty()) m.circulationCoverage else want.fold(0.0) { a, t -> a + (t.breezeCoverage + 0.1 * minOf(t.meanSpeed, 1.0)) } / want.size
            val draft = avoid.maxOfOrNull { it.draftExposure } ?: 0.0
            return Score(breeze, draft, moveCost(current, fan), fan)
        }

        var fans = mutableListOf(current)
        val minX = room.polygon.minOf { it.x }; val maxX = room.polygon.maxOf { it.x }
        val minY = room.polygon.minOf { it.y }; val maxY = room.polygon.maxOf { it.y }
        val positions = mutableListOf<Vec2>()
        var y = minY + options.positionSpacing / 2
        while (y < maxY) {
            var x = minX + options.positionSpacing / 2
            while (x < maxX) {
                val p = Vec2(swiftRounded(x * 100) / 100, swiftRounded(y * 100) / 100)
                if (FanPlacement.check(p, room) == null) positions += p
                x += options.positionSpacing
            }
            y += options.positionSpacing
        }
        val yaws = mutableListOf<Double>(); var yy = 0.0
        while (yy < 360.0) { yaws += yy; yy += options.yawStepDegrees }
        for (p in positions) for (yaw in yaws) fans += current.copy(position = p, yawDegrees = yaw)
        for (yaw in yaws) fans += current.copy(yawDegrees = yaw)
        if (fans.size > options.maxCandidates) {
            val stride = (fans.size - 1).toDouble() / (options.maxCandidates - 1)
            val src = fans
            fans = (0 until options.maxCandidates).map { src[swiftRounded(it * stride).toInt()] }.toMutableList()
        }

        val scores = fans.mapNotNull { f -> score(evaluate(f, options.coarse).first, f) }
        val base = scores.firstOrNull() ?: return emptyList()

        // Swift max(by:) / min(by:) 在并列时保留第一个
        fun <T> maxBy(xs: List<T>, less: (T, T) -> Boolean): T? { var r: T? = null; for (e in xs) if (r == null || less(r, e)) r = e; return r }
        fun <T> minBy(xs: List<T>, less: (T, T) -> Boolean): T? { var r: T? = null; for (e in xs) if (r == null || less(e, r)) r = e; return r }

        val stronger = maxBy(scores) { a, b -> if (a.breeze != b.breeze) a.breeze < b.breeze else if (a.draft != b.draft) a.draft > b.draft else a.cost > b.cost }!!
        val lessDraft = minBy(scores.filter { it.breeze >= minOf(base.breeze, stronger.breeze * 0.5) }) { a, b ->
            if (a.draft != b.draft) a.draft < b.draft else if (a.breeze != b.breeze) a.breeze > b.breeze else a.cost < b.cost } ?: base
        val improving = scores.filter { it.breeze >= base.breeze + 0.1 && it.draft <= base.draft + 1e-9 }
        val least = minBy(improving) { a, b -> if (a.cost != b.cost) a.cost < b.cost else a.breeze > b.breeze } ?: base

        return listOf(Kind.leastEffort to least, Kind.strongerBreeze to stronger, Kind.lessDraft to lessDraft).map { (kind, s) ->
            val (res, r) = evaluate(s.fan, options.final)
            val m = res.metrics
            val want = m?.targets?.filter { it.objective == TargetObjective.wantBreeze }.orEmpty()
            val avoid = m?.targets?.filter { it.objective == TargetObjective.avoidDirectDraft }.orEmpty()
            fun lvl(x: Double) = if (x >= 0.6) Level.high else if (x >= 0.3) Level.medium else if (x > 0.05) Level.low else Level.none
            val breeze = if (want.isEmpty()) (m?.circulationCoverage ?: 0.0) else want.fold(0.0) { a, t -> a + t.breezeCoverage } / want.size
            Candidate(kind, s.fan, r, res,
                if (s.cost < 0.05) Level.none else if (s.cost < 1) Level.low else if (s.cost < 3) Level.medium else Level.high,
                lvl(breeze), lvl(avoid.maxOfOrNull { it.draftExposure } ?: 0.0), s.cost < 0.05)
        }
    }

    /** 调整成本（米 + 转向折算）。 */
    fun moveCost(a: Fan, b: Fan): Double {
        var dy = abs(a.yawDegrees - b.yawDegrees).rem(360.0); if (dy > 180) dy = 360 - dy
        return a.position.distanceTo(b.position) + if (dy > 1) 0.3 + dy / 360 else 0.0
    }
}
