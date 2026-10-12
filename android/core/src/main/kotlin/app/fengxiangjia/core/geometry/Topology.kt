package app.fengxiangjia.core.geometry

import app.fengxiangjia.core.domain.*

/** 室内外连接拓扑：区域之间只能经由开口连通；共享墙体但无开口的房间互不连通。 */
object Topology {
    const val SHARED_WALL_TOLERANCE = 0.05

    fun connections(opening: Opening, room: Room, home: Home): List<ConnectionTarget> {
        val seg = room.segment(opening); val host = room.wall(opening.wallId)
        if (seg == null || host == null) return listOf(ConnectionTarget.RoomRef(room.id), ConnectionTarget.Unknown)
        val (a, b) = seg
        val mid = (a + b) * 0.5
        for (other in home.rooms) {
            if (other.id == room.id) continue
            for (w in other.walls) {
                if (host.direction.dot(w.direction) >= -0.99) continue
                if (Geometry.distanceToSegment(a, w.start, w.end) <= SHARED_WALL_TOLERANCE &&
                    Geometry.distanceToSegment(b, w.start, w.end) <= SHARED_WALL_TOLERANCE &&
                    Geometry.distanceToSegment(mid, w.start, w.end) <= SHARED_WALL_TOLERANCE)
                    return listOf(ConnectionTarget.RoomRef(room.id), ConnectionTarget.RoomRef(other.id))
            }
        }
        return listOf(ConnectionTarget.RoomRef(room.id), ConnectionTarget.Outdoor)
    }

    fun refreshConnections(home: Home): Home =
        home.copy(rooms = home.rooms.map { r -> r.copy(openings = r.openings.map { o -> o.copy(connects = connections(o, r, home)) }) })

    data class Edge(val from: ConnectionTarget, val to: ConnectionTarget, val openingId: EntityID)

    fun graph(home: Home): List<Edge> = home.rooms.flatMap { r ->
        r.openings.map { o -> val c = connections(o, r, home); Edge(c[0], c[1], o.id) }
    }

    /** 房间是否经开启（openFraction > 0）的开口通向室外（可经其他房间）。 */
    fun reachesOutdoor(roomId: EntityID, home: Home): Boolean {
        val openIds = home.rooms.flatMap { r -> r.openings.filter { it.openFraction > 0 }.map { it.id } }.toSet()
        val edges = graph(home).filter { it.openingId in openIds }
        val start: ConnectionTarget = ConnectionTarget.RoomRef(roomId)
        val seen = mutableSetOf(start)
        val frontier = ArrayDeque(listOf(start))
        while (frontier.isNotEmpty()) {
            val n = frontier.removeLast()
            for (e in edges) {
                val nx = if (e.from == n) e.to else if (e.to == n) e.from else null
                if (nx == null || nx in seen) continue
                if (nx == ConnectionTarget.Outdoor) return true
                seen += nx; frontier.addLast(nx)
            }
        }
        return false
    }
}
