package app.fengxiangjia.core.home

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.geometry.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

sealed interface HomeIssue {
    data class RoomsOverlap(val a: EntityID, val b: EntityID) : HomeIssue
    data class InRoom(val roomId: EntityID, val issue: RoomIssue) : HomeIssue
}

class EditException(msg: String) : Exception(msg)

object HomeValidator {
    fun validate(home: Home): List<HomeIssue> {
        val out = mutableListOf<HomeIssue>()
        for (r in home.rooms) out += RoomValidator.validate(r).map { HomeIssue.InRoom(r.id, it) }
        for (i in home.rooms.indices) for (j in i + 1 until home.rooms.size)
            if (overlaps(home.rooms[i].polygon, home.rooms[j].polygon)) out += HomeIssue.RoomsOverlap(home.rooms[i].id, home.rooms[j].id)
        return out
    }

    internal fun overlaps(a: List<Vec2>, b: List<Vec2>): Boolean {
        fun strictlyInside(p: Vec2, poly: List<Vec2>): Boolean {
            if (!Geometry.contains(poly, p)) return false
            for (k in poly.indices) if (Geometry.distanceToSegment(p, poly[k], poly[(k + 1) % poly.size]) < 0.01) return false
            return true
        }
        fun samples(p: List<Vec2>) = p.indices.flatMap { listOf(p[it], (p[it] + p[(it + 1) % p.size]) * 0.5) }
        if (samples(a).any { strictlyInside(it, b) } || samples(b).any { strictlyInside(it, a) }) return true
        for (i in a.indices) for (j in b.indices) {
            val p1 = a[i]; val p2 = a[(i + 1) % a.size]; val q1 = b[j]; val q2 = b[(j + 1) % b.size]
            val d1 = (p2 - p1).cross(q1 - p1); val d2 = (p2 - p1).cross(q2 - p1)
            val d3 = (q2 - q1).cross(p1 - q1); val d4 = (q2 - q1).cross(p2 - q1)
            if (((d1 > 1e-6 && d2 < -1e-6) || (d1 < -1e-6 && d2 > 1e-6)) && ((d3 > 1e-6 && d4 < -1e-6) || (d3 < -1e-6 && d4 > 1e-6))) return true
        }
        return a.size == b.size && a.map { "${it.x},${it.y}" }.toSet() == b.map { "${it.x},${it.y}" }.toSet()
    }
}

/** 在某房间某面墙外侧新建贴墙矩形房间（共享该墙段）。返回新住宅与新房间 id。 */
fun Home.addAdjacentRoom(roomId: EntityID, wallIndex: Int, depth: Double, name: String,
                         purpose: RoomPurpose = RoomPurpose.other, newRoomId: EntityID = newId()): Pair<Home, EntityID> {
    val host = rooms.firstOrNull { it.id == roomId } ?: throw EditException("notFound")
    val w = host.walls.getOrNull(wallIndex) ?: throw EditException("notFound")
    val n = w.outwardNormal * maxOf(depth, 0.5)
    val room = Room(id = newRoomId, name = name, polygon = listOf(w.end, w.start, w.start + n, w.end + n), purpose = purpose)
    val candidate = copy(rooms = rooms + room)
    if (HomeValidator.validate(candidate).any { it is HomeIssue.RoomsOverlap }) throw EditException("invalidGeometry")
    return Topology.refreshConnections(candidate) to room.id
}

fun Home.removeRoom(id: EntityID): Home = Topology.refreshConnections(copy(rooms = rooms.filter { it.id != id }))

/** 影响某房间的全部开口：自有开口 + 其他房间上通向本房间的开口。 */
fun Home.openingsAffecting(roomId: EntityID): List<Pair<Opening, Room>> {
    val out = mutableListOf<Pair<Opening, Room>>()
    for (r in rooms) for (o in r.openings) {
        if (r.id == roomId) { out += o to r; continue }
        if (Topology.connections(o, r, this).contains(ConnectionTarget.RoomRef(roomId))) out += o to r
    }
    return out
}

fun Home.updatingRoom(id: EntityID, f: (Room) -> Room): Home = copy(rooms = rooms.map { if (it.id == id) f(it) else it })

/** #14 方位。 */
object Bearing {
    fun normalize(d: Double): Double {
        val r = d.rem(360.0)
        val v = if (r < 0) r + 360 else r
        return if (v >= 360 - 1e-9) 0.0 else v
    }
    fun clockwiseFromPlanY(v: Vec2) = normalize(atan2(v.x, v.y) * 180 / Math.PI)
    fun orientation(heading: Double, planDirection: Vec2) = normalize(heading - clockwiseFromPlanY(planDirection))
    fun compassBearing(planDirection: Vec2, orientation: Double) = normalize(orientation + clockwiseFromPlanY(planDirection))
    fun circularMean(samples: List<Double>, maxSpreadDegrees: Double = 30.0): Double? {
        if (samples.isEmpty()) return null
        val s = samples.sumOf { sin(it * Math.PI / 180) }; val c = samples.sumOf { cos(it * Math.PI / 180) }
        val r = sqrt(s * s + c * c) / samples.size
        if (r < cos(maxSpreadDegrees * Math.PI / 180)) return null
        return normalize(atan2(s, c) * 180 / Math.PI)
    }
    private val names = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
    fun name(bearing: Double) = names[((normalize(bearing) + 22.5) / 45).toInt() % 8]
}

fun Wall.facingBearing(orientation: Double) = Bearing.compassBearing(outwardNormal, orientation)
