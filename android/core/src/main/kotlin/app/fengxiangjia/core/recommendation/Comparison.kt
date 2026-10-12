package app.fengxiangjia.core.recommendation

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.simulation.*
import java.time.Instant
import kotlin.math.abs

/** 从当前房间布局捕获方案。 */
fun Scenario.Companion.capture(name: String, room: Room, geometryRevision: Int, environment: Environment?, createdAt: Instant = Instant.now(), id: EntityID = newId()) =
    Scenario(id = id, name = name, roomId = room.id, geometryRevision = geometryRevision, environmentSnapshot = environment,
        fans = room.fans, openingStates = room.openings.associate { it.id to it.openFraction }, targets = room.targets, createdAt = createdAt)

/** 把方案布局套到房间几何上（几何以当前房间为准）。 */
fun Scenario.appliedTo(room: Room) = room.copy(fans = fans, targets = targets,
    openings = room.openings.map { o -> openingStates[o.id]?.let { o.copy(openFraction = it) } ?: o })

enum class Preference { a, b, similar }

data class ComparisonDimension(val kind: Kind, val levelA: Level, val levelB: Level, val preferred: Preference) {
    enum class Kind { targetBreeze, directDraft, outdoorExchange, circulation, adjustmentCost }
}

sealed interface ComparisonNote {
    data object EnvironmentUnified : ComparisonNote
    data class GeometryChanged(val side: Preference) : ComparisonNote
    data object ClosedRoomNoExchange : ComparisonNote
    data class Invalid(val side: Preference) : ComparisonNote
}

sealed interface Suggestion {
    data class PreferForBreeze(val side: Preference) : Suggestion
    data class ReduceDraft(val side: Preference) : Suggestion
    data object OpenOppositeWindow : Suggestion
    data class SimilarChooseCheaper(val side: Preference) : Suggestion
    data object AimFanAtTarget : Suggestion
}

class ScenarioComparison(
    val environment: Environment?, val resultA: SimulationResult, val resultB: SimulationResult,
    val dimensions: List<ComparisonDimension>, val notes: List<ComparisonNote>, val suggestions: List<Suggestion>,
) {
    companion object {
        /** 用同一环境快照、同一网格参数比较两方案。 */
        fun compare(home: Home, roomId: EntityID, a: Scenario, b: Scenario, currentGeometryRevision: Int,
                    environment: Environment?, options: SimulationOptions = SimulationOptions()): ScenarioComparison? {
            val base = home.rooms.firstOrNull { it.id == roomId } ?: return null
            val notes = mutableListOf<ComparisonNote>()
            if (a.environmentSnapshot != b.environmentSnapshot) notes += ComparisonNote.EnvironmentUnified
            if (a.geometryRevision != currentGeometryRevision) notes += ComparisonNote.GeometryChanged(Preference.a)
            if (b.geometryRevision != currentGeometryRevision) notes += ComparisonNote.GeometryChanged(Preference.b)
            fun solve(s: Scenario): Pair<SimulationResult, Room> {
                val r = s.appliedTo(base)
                val h = home.copy(rooms = home.rooms.map { if (it.id == roomId) r else it })
                return AirflowSolver.solve(SimulationInput(h, roomId, environment, options)) to r
            }
            val (ra, roomA) = solve(a); val (rb, roomB) = solve(b)
            val ma = ra.metrics; val mb = rb.metrics
            if (ma == null || mb == null) {
                if (ma == null) notes += ComparisonNote.Invalid(Preference.a)
                if (mb == null) notes += ComparisonNote.Invalid(Preference.b)
                return ScenarioComparison(environment, ra, rb, emptyList(), notes, emptyList())
            }
            val dims = mutableListOf<ComparisonDimension>()
            fun fractionLevel(x: Double) = if (x >= 0.6) Level.high else if (x >= 0.3) Level.medium else if (x > 0.05) Level.low else Level.none
            fun add(k: ComparisonDimension.Kind, la: Level, lb: Level, higherIsBetter: Boolean) {
                val pref = if (la == lb) Preference.similar else if ((la > lb) == higherIsBetter) Preference.a else Preference.b
                dims += ComparisonDimension(k, la, lb, pref)
            }
            fun meanBreeze(m: SimulationMetrics): Double? { val t = m.targets.filter { it.objective == TargetObjective.wantBreeze }; return if (t.isEmpty()) null else t.sumOf { it.breezeCoverage } / t.size }
            fun maxDraft(m: SimulationMetrics): Double? { val t = m.targets.filter { it.objective == TargetObjective.avoidDirectDraft }; return t.maxOfOrNull { it.draftExposure } }
            val ba = meanBreeze(ma); val bb = meanBreeze(mb)
            if (ba != null && bb != null) add(ComparisonDimension.Kind.targetBreeze, fractionLevel(ba), fractionLevel(bb), true)
            val da = maxDraft(ma); val db = maxDraft(mb)
            if (da != null && db != null) add(ComparisonDimension.Kind.directDraft, fractionLevel(da), fractionLevel(db), false)
            add(ComparisonDimension.Kind.outdoorExchange, ma.outdoorExchange, mb.outdoorExchange, true)
            add(ComparisonDimension.Kind.circulation, fractionLevel(ma.circulationCoverage), fractionLevel(mb.circulationCoverage), true)
            add(ComparisonDimension.Kind.adjustmentCost, Level.none, adjustmentCost(roomA, roomB), false)
            if (!ma.hasOutdoorPath && !mb.hasOutdoorPath) notes += ComparisonNote.ClosedRoomNoExchange

            val sug = mutableListOf<Suggestion>()
            val benefit = dims.filter { it.kind != ComparisonDimension.Kind.adjustmentCost }
            dims.firstOrNull { it.kind == ComparisonDimension.Kind.targetBreeze }?.let { if (it.preferred != Preference.similar) sug += Suggestion.PreferForBreeze(it.preferred) }
            dims.firstOrNull { it.kind == ComparisonDimension.Kind.directDraft }?.let { if (it.preferred != Preference.similar) sug += Suggestion.ReduceDraft(it.preferred) }
            if (benefit.all { it.preferred == Preference.similar }) sug += Suggestion.SimilarChooseCheaper(Preference.a)
            if (!ma.crossVentilation && !mb.crossVentilation && (ma.hasOutdoorPath || mb.hasOutdoorPath) && environment != null) sug += Suggestion.OpenOppositeWindow
            dims.firstOrNull { it.kind == ComparisonDimension.Kind.targetBreeze }?.let { if (it.levelA < Level.medium && it.levelB < Level.medium) sug += Suggestion.AimFanAtTarget }
            return ScenarioComparison(environment, ra, rb, dims, notes, sug.take(3))
        }

        /** 调整成本：A→B 需要移动/转动/调档的风扇与改动开口数量，归为定性等级。 */
        fun adjustmentCost(a: Room, b: Room): Level {
            var cost = 0.0
            for (fb in b.fans) {
                val fa = a.fans.firstOrNull { it.id == fb.id }
                if (fa == null) { cost += 2; continue }
                cost += minOf(fa.position.distanceTo(fb.position), 3.0)
                var dy = abs(fa.yawDegrees - fb.yawDegrees).rem(360.0); if (dy > 180) dy = 360 - dy
                if (dy > 10) cost += 0.5
                if (abs(fa.power - fb.power) > 0.05) cost += 0.25
            }
            cost += a.fans.count { fa -> b.fans.none { it.id == fa.id } }
            for (ob in b.openings) if (a.openings.firstOrNull { it.id == ob.id }?.let { abs(it.openFraction - ob.openFraction) > 0.05 } == true) cost += 0.5
            return if (cost < 0.05) Level.none else if (cost < 1) Level.low else if (cost < 3) Level.medium else Level.high
        }
    }
}
