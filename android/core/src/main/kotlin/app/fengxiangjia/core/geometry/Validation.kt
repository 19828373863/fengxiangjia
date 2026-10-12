package app.fengxiangjia.core.geometry

import app.fengxiangjia.core.domain.*

/** 房间问题。`swiftDescription` 与 Swift `String(describing:)` 一致（用于 SimulationStatus.invalidInput 跨平台比对）。 */
sealed interface RoomIssue {
    val swiftDescription: String
    data object TooFewVertices : RoomIssue { override val swiftDescription = "tooFewVertices" }
    data object WallIdCountMismatch : RoomIssue { override val swiftDescription = "wallIdCountMismatch" }
    data class DegenerateEdge(val index: Int) : RoomIssue { override val swiftDescription get() = "degenerateEdge(index: $index)" }
    data class SelfIntersecting(val edgeA: Int, val edgeB: Int) : RoomIssue { override val swiftDescription get() = "selfIntersecting(edgeA: $edgeA, edgeB: $edgeB)" }
    data object ClockwiseWinding : RoomIssue { override val swiftDescription = "clockwiseWinding" }
    data object ZeroArea : RoomIssue { override val swiftDescription = "zeroArea" }
    data class OpeningUnknownWall(val opening: EntityID) : RoomIssue { override val swiftDescription get() = "openingUnknownWall(opening: $opening)" }
    data class OpeningOutsideWall(val opening: EntityID) : RoomIssue { override val swiftDescription get() = "openingOutsideWall(opening: $opening)" }
    data class OpeningTooNarrow(val opening: EntityID) : RoomIssue { override val swiftDescription get() = "openingTooNarrow(opening: $opening)" }
    data class OpeningInvalidFraction(val opening: EntityID) : RoomIssue { override val swiftDescription get() = "openingInvalidFraction(opening: $opening)" }
    data class OpeningsOverlap(val a: EntityID, val b: EntityID) : RoomIssue { override val swiftDescription get() = "openingsOverlap($a, $b)" }
    data class ItemOutsideRoom(val item: EntityID) : RoomIssue { override val swiftDescription get() = "itemOutsideRoom(item: $item)" }
}

object RoomValidator {
    const val MIN_EDGE_LENGTH = 0.1
    const val MIN_OPENING_WIDTH = 0.2

    fun validate(room: Room): List<RoomIssue> {
        val issues = mutableListOf<RoomIssue>()
        val p = room.polygon
        if (p.size < 3) return listOf(RoomIssue.TooFewVertices)
        if (room.wallIds.size != p.size) return listOf(RoomIssue.WallIdCountMismatch)
        for (i in p.indices) if (p[i].distanceTo(p[(i + 1) % p.size]) < MIN_EDGE_LENGTH) issues += RoomIssue.DegenerateEdge(i)
        Geometry.selfIntersection(p)?.let { (a, b) -> issues += RoomIssue.SelfIntersecting(a, b) }
        val area = Geometry.signedArea(p)
        if (kotlin.math.abs(area) < 0.01) issues += RoomIssue.ZeroArea else if (area < 0) issues += RoomIssue.ClockwiseWinding

        val walls = room.walls.associateBy { it.id }
        val spans = mutableMapOf<EntityID, MutableList<Triple<EntityID, Double, Double>>>()
        for (o in room.openings) {
            val w = walls[o.wallId]
            if (w == null) { issues += RoomIssue.OpeningUnknownWall(o.id); continue }
            if (o.width < MIN_OPENING_WIDTH) issues += RoomIssue.OpeningTooNarrow(o.id)
            if (o.offset < -Geometry.EPSILON || o.offset + o.width > w.length + Geometry.EPSILON) issues += RoomIssue.OpeningOutsideWall(o.id)
            if (o.openFraction !in 0.0..1.0) issues += RoomIssue.OpeningInvalidFraction(o.id)
            for ((other, s, e) in spans[o.wallId].orEmpty())
                if (o.offset < e - Geometry.EPSILON && s < o.offset + o.width - Geometry.EPSILON) issues += RoomIssue.OpeningsOverlap(other, o.id)
            spans.getOrPut(o.wallId) { mutableListOf() } += Triple(o.id, o.offset, o.offset + o.width)
        }
        for (f in room.fans) if (!room.contains(f.position)) issues += RoomIssue.ItemOutsideRoom(f.id)
        for (ob in room.obstacles) if (!room.contains(ob.center)) issues += RoomIssue.ItemOutsideRoom(ob.id)
        return issues
    }
}
