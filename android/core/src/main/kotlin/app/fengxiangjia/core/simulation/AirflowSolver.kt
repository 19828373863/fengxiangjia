package app.fengxiangjia.core.simulation

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.geometry.*
import app.fengxiangjia.core.home.openingsAffecting
import app.fengxiangjia.core.weather.ObstructionModel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 轻量二维气流示意模型 fxj-2d-0.1（生活用途，非工程 CFD）。逐行移植自 iOS `AirflowSolver.swift`，
 * 运算顺序保持一致；跨平台一致性由 shared/fixtures/solver-golden.json 校验。
 */
object AirflowSolver {
    private data class FanSpec(val u0: Double, val decay: Double)

    private fun spec(f: Fan): FanSpec {
        val p = f.power
        return when (f.kind) {
            FanKind.pedestal -> FanSpec(3.0 * p, 1.5 + 2.5 * p)
            FanKind.circulator -> FanSpec(3.5 * p, 2.5 + 3.5 * p)
            FanKind.desk -> FanSpec(2.2 * p, 1.0 + 1.5 * p)
            FanKind.tower -> FanSpec(2.0 * p, 1.2 + 1.5 * p)
            FanKind.ceiling -> FanSpec(1.2 * p, 1.5)
        }
    }

    fun solve(input: SimulationInput): SimulationResult {
        val hash = ScenarioHash.compute(input)
        val room = input.room ?: return SimulationResult(hash, SimulationResult.MODEL_VERSION,
            SimulationStatus.InvalidInput(listOf("roomNotFound")), null, null, emptyList())
        val blocking = RoomValidator.validate(room).filter { it !is RoomIssue.ItemOutsideRoom }
        if (blocking.isNotEmpty()) return SimulationResult(hash, SimulationResult.MODEL_VERSION,
            SimulationStatus.InvalidInput(blocking.map { it.swiftDescription }), null, null, emptyList())

        val field = makeGrid(room, input.options)
        val explanations = mutableListOf<Explanation>()

        for (fan in room.fans.sortedBy { it.id }) {
            val sp = spec(fan)
            if (fan.kind == FanKind.ceiling) addRadial(field, fan.position, sp.u0, sp.decay)
            else {
                val yaw = fan.yawDegrees * Math.PI / 180
                val dir = Vec2(cos(yaw), sin(yaw))
                if (isBlocked(field, fan.position, dir, 0.4)) explanations += Explanation.FanBlocked(fan.id)
                addJet(field, fan.position, dir, sp.u0, sp.decay, 0.15)
            }
        }

        val paths = outdoorPathOpenings(room, input.home)
        val windTo = input.environment?.windToVector(input.home.orientationDegrees)
        val windSpeed = input.environment?.windSpeed ?: 0.0
        val windward = mutableListOf<Triple<Opening, Wall, Double>>()
        var leeward = 0; var indirect = 0
        for ((o, direct) in paths) {
            if (!direct) { indirect += 1; continue }
            val w = room.wall(o.wallId) ?: continue
            val wt = windTo ?: continue
            val d = w.outwardNormal.dot(wt)
            val level = input.environment?.obstructionByOpening?.get(o.id)
            val factor = ObstructionModel.windFactor(level, input.home.floorLevel)
            if (level != null && level != ObstructionLevel.open) explanations += Explanation.ObstructionReduced(o.id, level)
            if (d < -0.3) windward += Triple(o, w, windSpeed * factor * o.openFraction) else if (d > 0.3) leeward += 1
        }
        val cross = windward.isNotEmpty() && (leeward > 0 || indirect > 0) && windSpeed > 0.1
        var exchange = Level.none
        if (paths.isNotEmpty()) {
            if (input.environment == null) explanations += Explanation.NoEnvironment
            val eff = windward.fold(0.0) { acc, t -> acc + t.third * t.first.width }
            if (cross) {
                explanations += Explanation.CrossVentilation
                for ((o, w, u) in windward) {
                    val c = w.pointAtOffset(o.offset + o.width / 2)
                    addJet(field, c - w.outwardNormal * 0.05, w.outwardNormal * -1.0, u * 0.35, 4.0, o.width / 2)
                }
                exchange = if (eff >= 3) Level.high else if (eff >= 1) Level.medium else Level.low
            } else {
                if (paths.size == 1) explanations += Explanation.SingleSidedOpening
                exchange = Level.low
            }
            for (fan in room.fans) {
                if (fan.kind == FanKind.ceiling) continue
                val yaw = fan.yawDegrees * Math.PI / 180
                val dir = Vec2(cos(yaw), sin(yaw))
                val aids = paths.any { (o, direct) ->
                    val w = if (direct) room.wall(o.wallId) else null
                    if (w == null) false else {
                        val c = w.pointAtOffset(o.offset + o.width / 2)
                        c.distanceTo(fan.position) <= 1.5 && (c - fan.position).normalized.dot(dir) > 0.7
                    }
                }
                if (aids && paths.size >= 2) {
                    explanations += Explanation.FanAidsExhaust(fan.id)
                    exchange = Level.entries[minOf(exchange.ordinal + 1, Level.high.ordinal)]
                    break
                }
            }
        } else if (room.fans.isNotEmpty()) explanations += Explanation.ClosedRoomInternalOnly

        smooth(field, input.options.smoothingIterations)

        var inside = 0; var circ = 0; var sum = 0.0
        for (i in field.mask.indices) if (field.mask[i]) {
            inside += 1
            val s = field.speed(i); sum += s
            if (s >= SimulationMetrics.CIRCULATION_THRESHOLD) circ += 1
        }
        val targets = mutableListOf<TargetMetrics>()
        for (t in room.targets) {
            var n = 0; var breeze = 0; var draft = 0; var ts = 0.0
            for (iy in 0 until field.ny) for (ix in 0 until field.nx) {
                val i = field.index(ix, iy)
                if (!field.mask[i] || !t.containsPoint(field.center(ix, iy))) continue
                n += 1
                val s = field.speed(i); ts += s
                if (s >= SimulationMetrics.BREEZE_THRESHOLD) breeze += 1
                if (s >= SimulationMetrics.DRAFT_THRESHOLD) draft += 1
            }
            val m = TargetMetrics(t.id, t.objective, if (n > 0) breeze.toDouble() / n else 0.0,
                if (n > 0) draft.toDouble() / n else 0.0, if (n > 0) ts / n else 0.0)
            targets += m
            when (t.objective) {
                TargetObjective.wantBreeze -> explanations += if (m.breezeCoverage >= 0.5) Explanation.TargetCovered(t.id) else Explanation.TargetMissed(t.id)
                TargetObjective.avoidDirectDraft -> if (m.draftExposure > 0.2) explanations += Explanation.TargetDraft(t.id)
            }
        }
        val metrics = SimulationMetrics(if (inside > 0) circ.toDouble() / inside else 0.0, if (inside > 0) sum / inside else 0.0,
            exchange, paths.isNotEmpty(), cross, targets)
        return SimulationResult(hash, SimulationResult.MODEL_VERSION, SimulationStatus.Ok, field, metrics, explanations)
    }

    /** 本房间开启的、可通向室外的开口；second = 直接通室外。 */
    internal fun outdoorPathOpenings(room: Room, home: Home): List<Pair<Opening, Boolean>> {
        val all = home.openingsAffecting(room.id)
        val affecting = all.filter { it.first.openFraction > 0 }
        val related = all.map { it.first.id }.toSet()
        val isolated = home.copy(rooms = home.rooms.map { r -> r.copy(openings = r.openings.filter { it.id !in related }) })
        return affecting.mapNotNull { (o, host) ->
            val c = Topology.connections(o, host, home)
            val other: ConnectionTarget? = if (host.id == room.id) c.lastOrNull() else ConnectionTarget.RoomRef(host.id)
            when (other) {
                ConnectionTarget.Outdoor -> o to true
                is ConnectionTarget.RoomRef -> if (Topology.reachesOutdoor(other.id, isolated)) o to false else null
                else -> null
            }
        }
    }

    internal fun makeGrid(room: Room, opt: SimulationOptions): FlowField {
        val minX = room.polygon.minOf { it.x }; val maxX = room.polygon.maxOf { it.x }
        val minY = room.polygon.minOf { it.y }; val maxY = room.polygon.maxOf { it.y }
        val cell = maxOf(opt.cellSize, maxOf(maxX - minX, maxY - minY) / maxOf(opt.maxCellsPerAxis, 4).toDouble())
        val nx = maxOf(ceil((maxX - minX) / cell).toInt(), 1); val ny = maxOf(ceil((maxY - minY) / cell).toInt(), 1)
        val f = FlowField(Vec2(minX, minY), cell, nx, ny, DoubleArray(nx * ny), DoubleArray(nx * ny), BooleanArray(nx * ny))
        val solids = room.obstacles.filter { it.heightClass != HeightClass.low }
        for (iy in 0 until ny) for (ix in 0 until nx) {
            val c = f.center(ix, iy)
            f.mask[f.index(ix, iy)] = room.contains(c) && solids.none { it.contains(c) }
        }
        return f
    }

    private fun isBlocked(f: FlowField, a: Vec2, b: Vec2): Boolean {
        val d = b - a; val len = d.length
        if (len <= 0) return false
        val step = f.cellSize * 0.5
        var t = step
        while (t < len - step) {
            val p = a + d * (t / len)
            val c = f.cell(p) ?: return true
            if (!f.mask[f.index(c.first, c.second)]) return true
            t += step
        }
        return false
    }

    private fun isBlocked(f: FlowField, a: Vec2, dir: Vec2, distance: Double): Boolean =
        isBlocked(f, a, a + dir * distance) || (f.cell(a + dir * distance)?.let { !f.mask[f.index(it.first, it.second)] } ?: true)

    private fun addJet(f: FlowField, p: Vec2, dir: Vec2, u0: Double, decay: Double, spread0: Double) {
        if (u0 <= 0) return
        for (iy in 0 until f.ny) for (ix in 0 until f.nx) {
            val i = f.index(ix, iy)
            if (!f.mask[i]) continue
            val c = f.center(ix, iy); val r = c - p
            val along = r.dot(dir)
            if (along <= 0) continue
            val lateral = abs(r.cross(dir)); val width = spread0 + 0.22 * along
            val amp = u0 * exp(-along / decay) * exp(-(lateral / width) * (lateral / width))
            if (!(amp > 0.01) || isBlocked(f, p, c)) continue
            f.u[i] += dir.x * amp; f.v[i] += dir.y * amp
        }
    }

    private fun addRadial(f: FlowField, p: Vec2, u0: Double, decay: Double) {
        for (iy in 0 until f.ny) for (ix in 0 until f.nx) {
            val i = f.index(ix, iy)
            if (!f.mask[i]) continue
            val r = f.center(ix, iy) - p; val d = r.length
            if (!(d > 0.05) || isBlocked(f, p, f.center(ix, iy))) continue
            val amp = u0 * exp(-d / decay)
            val n = r.normalized
            f.u[i] += n.x * amp; f.v[i] += n.y * amp
        }
    }

    private val neighbors = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))

    private fun smooth(f: FlowField, iterations: Int) {
        if (iterations <= 0) return
        repeat(iterations) {
            val nu = f.u.copyOf(); val nv = f.v.copyOf()
            for (iy in 0 until f.ny) for (ix in 0 until f.nx) {
                val i = f.index(ix, iy)
                if (!f.mask[i]) continue
                var su = 0.0; var sv = 0.0
                for (nb in neighbors) {
                    val x = ix + nb[0]; val y = iy + nb[1]
                    if (x < 0 || y < 0 || x >= f.nx || y >= f.ny) continue
                    val j = f.index(x, y)
                    if (f.mask[j]) { su += f.u[j]; sv += f.v[j] }
                }
                nu[i] = 0.6 * f.u[i] + 0.1 * su
                nv[i] = 0.6 * f.v[i] + 0.1 * sv
            }
            f.u = nu; f.v = nv
        }
    }
}
