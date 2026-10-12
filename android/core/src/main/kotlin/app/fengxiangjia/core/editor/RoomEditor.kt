package app.fengxiangjia.core.editor

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.domain.Target
import app.fengxiangjia.core.geometry.*
import kotlin.math.atan2

sealed class EditError(msg: String) : Exception(msg) {
    class InvalidGeometry(val issues: List<RoomIssue>) : EditError("invalidGeometry")
    class OutsideRoom : EditError("outsideRoom")
    class NoWallNearby : EditError("noWallNearby")
    class WallTooShort : EditError("wallTooShort")
    class OverlapsOpening : EditError("overlapsOpening")
    class NotFound : EditError("notFound")
    class TooCloseToWall : EditError("tooCloseToWall")
    class OnFurniture : EditError("onFurniture")

    /** 面向用户的中文说明。 */
    val userMessage: String get() = when (this) {
        is InvalidGeometry -> "该操作会让墙体交叉或门窗越出墙面"
        is OutsideRoom -> "位置在房间外"
        is NoWallNearby -> "附近没有墙，请点在墙边"
        is WallTooShort -> "这面墙太短，放不下"
        is OverlapsOpening -> "与已有门窗重叠"
        is NotFound -> "找不到该对象"
        is TooCloseToWall -> "离墙太近（至少 20 cm）"
        is OnFurniture -> "不能放在高家具上"
    }
}

object FanPlacement {
    const val MIN_WALL_CLEARANCE = 0.2
    fun check(p: Vec2, room: Room): EditError? {
        if (!room.contains(p)) return EditError.OutsideRoom()
        if (room.walls.any { Geometry.distanceToSegment(p, it.start, it.end) < MIN_WALL_CLEARANCE }) return EditError.TooCloseToWall()
        if (room.obstacles.any { it.heightClass != HeightClass.low && it.contains(p) }) return EditError.OnFurniture()
        return null
    }
}

/** 二维户型编辑器（纯逻辑，移植自 iOS `RoomEditor`）。成功的编辑可撤销/重做；失败的编辑抛出 [EditError] 且不改变状态。 */
class RoomEditor(room: Room) {
    var room: Room = room; private set
    private val undoStack = ArrayDeque<Room>()
    private val redoStack = ArrayDeque<Room>()
    var gridSize = 0.05
    var wallSnapDistance = 0.5

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun undo() { val p = undoStack.removeLastOrNull() ?: return; redoStack.addLast(room); room = p }
    fun redo() { val n = redoStack.removeLastOrNull() ?: return; undoStack.addLast(room); room = n }

    private fun commit(r: Room) {
        undoStack.addLast(room)
        if (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
        redoStack.clear(); room = r
    }

    fun snap(p: Vec2) = if (gridSize <= 0) p else Vec2(swiftRounded(p.x / gridSize) * gridSize, swiftRounded(p.y / gridSize) * gridSize)

    /** 同步派生字段（如拓扑刷新后的 connects），不入撤销栈。 */
    fun sync(r: Room) { require(r.id == room.id); room = r }

    /** 外部替换（如扫描导入、方案套用），可撤销。 */
    fun replace(r: Room) = commit(r)

    fun apply(template: RoomTemplate) {
        val poly = template.polygon
        val base = room.copy(polygon = poly, wallIds = poly.map { newId() }, openings = emptyList())
        commit(base.copy(obstacles = base.obstacles.filter { base.contains(it.center) }, fans = base.fans.filter { base.contains(it.position) }))
    }

    fun moveVertex(index: Int, to: Vec2) {
        if (index !in room.polygon.indices) throw EditError.NotFound()
        val r = room.copy(polygon = room.polygon.toMutableList().also { it[index] = snap(to) })
        val issues = RoomValidator.validate(r).filter { it !is RoomIssue.ItemOutsideRoom }
        if (issues.isNotEmpty()) throw EditError.InvalidGeometry(issues)
        commit(r)
    }

    fun addFurniture(kind: ObstacleKind, at: Vec2, size: Vec2 = Vec2(1.0, 0.6)): EntityID {
        val c = snap(at); if (!room.contains(c)) throw EditError.OutsideRoom()
        val ob = Obstacle(kind = kind, center = c, size = size); commit(room.copy(obstacles = room.obstacles + ob)); return ob.id
    }

    fun moveFurniture(id: EntityID, to: Vec2) {
        if (room.obstacles.none { it.id == id }) throw EditError.NotFound()
        val c = snap(to); if (!room.contains(c)) throw EditError.OutsideRoom()
        commit(room.copy(obstacles = room.obstacles.map { if (it.id == id) it.copy(center = c) else it }))
    }

    fun removeItem(id: EntityID) {
        val r = room.copy(obstacles = room.obstacles.filter { it.id != id }, fans = room.fans.filter { it.id != id },
            openings = room.openings.filter { it.id != id }, targets = room.targets.filter { it.id != id })
        if (r == room) throw EditError.NotFound()
        commit(r)
    }

    fun snapOpening(near: Vec2, width: Double, excluding: EntityID? = null): Pair<EntityID, Double> {
        val best = room.walls.map { w -> val c = Geometry.closestPointOnSegment(near, w.start, w.end); Triple(w, c.first.distanceTo(near), c.second) }
            .filter { it.second <= wallSnapDistance }.minByOrNull { it.second } ?: throw EditError.NoWallNearby()
        val (wall, _, along) = best
        if (wall.length < width) throw EditError.WallTooShort()
        var offset = (along - width / 2).coerceAtLeast(0.0).coerceAtMost(wall.length - width)
        offset = swiftRounded(offset / gridSize) * gridSize
        offset = offset.coerceAtLeast(0.0).coerceAtMost(wall.length - width)
        val clash = room.openings.any { o -> o.id != excluding && o.wallId == wall.id &&
            offset < o.offset + o.width - Geometry.EPSILON && o.offset < offset + width - Geometry.EPSILON }
        if (clash) throw EditError.OverlapsOpening()
        return wall.id to offset
    }

    fun addOpening(kind: OpeningKind, near: Vec2, width: Double? = null): EntityID {
        val w = width ?: if (kind == OpeningKind.window) 1.2 else 0.9
        val (wallId, offset) = snapOpening(near, w)
        val o = Opening(wallId = wallId, kind = kind, offset = offset, width = w)
        commit(room.copy(openings = room.openings + o)); return o.id
    }

    fun moveOpening(id: EntityID, near: Vec2) {
        val o = room.openings.firstOrNull { it.id == id } ?: throw EditError.NotFound()
        val (wallId, offset) = snapOpening(near, o.width, id)
        commit(room.copy(openings = room.openings.map { if (it.id == id) it.copy(wallId = wallId, offset = offset) else it }))
    }

    fun setOpeningFraction(id: EntityID, fraction: Double) {
        if (room.openings.none { it.id == id }) throw EditError.NotFound()
        commit(room.copy(openings = room.openings.map { if (it.id == id) it.copy(openFraction = fraction.coerceIn(0.0, 1.0)) else it }))
    }

    // —— 风扇与目标（#7） ——
    fun placeFan(at: Vec2, kind: FanKind = FanKind.pedestal, yawDegrees: Double = 0.0, power: Double = 0.6): EntityID {
        val s = snap(at); FanPlacement.check(s, room)?.let { throw it }
        val f = Fan.of(kind = kind, position = s, yawDegrees = yawDegrees, power = power)
        commit(room.copy(fans = room.fans + f)); return f.id
    }

    private fun updateFan(id: EntityID, f: (Fan) -> Fan) {
        if (room.fans.none { it.id == id }) throw EditError.NotFound()
        commit(room.copy(fans = room.fans.map { if (it.id == id) f(it) else it }))
    }

    fun moveFan(id: EntityID, to: Vec2) {
        val s = snap(to); if (room.fans.none { it.id == id }) throw EditError.NotFound()
        FanPlacement.check(s, room)?.let { throw it }
        updateFan(id) { it.copy(position = s) }
    }

    fun setFanYaw(id: EntityID, degrees: Double) {
        var d = swiftRounded(degrees / 5) * 5
        d = d.rem(360.0); if (d < 0) d += 360
        updateFan(id) { it.copy(yawDegrees = d) }
    }

    fun aimFan(id: EntityID, at: Vec2) {
        val f = room.fans.firstOrNull { it.id == id } ?: throw EditError.NotFound()
        val d = at - f.position
        if (d.length <= 1e-6) return
        setFanYaw(id, atan2(d.y, d.x) * 180 / Math.PI)
    }

    fun setFanPower(id: EntityID, power: Double) = updateFan(id) { it.copy(power = (swiftRounded(power * 10) / 10).coerceIn(0.0, 1.0)) }
    fun setFanKind(id: EntityID, kind: FanKind) = updateFan(id) { it.copy(kind = kind) }

    fun addTarget(name: String, at: Vec2, radius: Double = 0.6, objective: TargetObjective): EntityID {
        val c = snap(at); if (!room.contains(c)) throw EditError.OutsideRoom()
        val t = Target(name = name, shape = TargetShape.Circle(c, radius.coerceIn(0.2, 3.0)), objective = objective)
        commit(room.copy(targets = room.targets + t)); return t.id
    }

    fun moveTarget(id: EntityID, to: Vec2) {
        val t = room.targets.firstOrNull { it.id == id } ?: throw EditError.NotFound()
        val c = snap(to); if (!room.contains(c)) throw EditError.OutsideRoom()
        val shape = when (val s = t.shape) {
            is TargetShape.Circle -> TargetShape.Circle(c, s.radius)
            is TargetShape.Polygon -> { val cen = t.center; TargetShape.Polygon(s.points.map { it - cen + c }) }
        }
        commit(room.copy(targets = room.targets.map { if (it.id == id) it.copy(shape = shape) else it }))
    }

    fun setTargetObjective(id: EntityID, objective: TargetObjective) {
        if (room.targets.none { it.id == id }) throw EditError.NotFound()
        commit(room.copy(targets = room.targets.map { if (it.id == id) it.copy(objective = objective) else it }))
    }

    companion object { const val HISTORY_LIMIT = 100 }
}
