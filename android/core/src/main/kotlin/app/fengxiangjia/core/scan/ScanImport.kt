package app.fengxiangjia.core.scan

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.geometry.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * #17/#60 扫描导入（与采集框架解耦的纯逻辑，移植自 iOS `ScanImport.swift`）。
 * 采集坐标：y 向上、右手系（RoomPlan 与 ARCore 相同）；户型坐标 = (x, -z)。
 */
data class ScannedWall(val start: Vec2, val end: Vec2, val height: Double? = null) {
    companion object {
        /** 由竖直平面中心 (x,z)、平面局部 x 轴 (x,z) 与宽度构造（ARCore `Plane.centerPose` + `extentX`）。 */
        fun fromPlane(centerX: Double, centerZ: Double, axisX: Double, axisZ: Double, width: Double, height: Double?): ScannedWall {
            val c = Vec2(centerX, -centerZ); val d = Vec2(axisX, -axisZ).normalized
            return ScannedWall(c - d * (width / 2), c + d * (width / 2), height)
        }
        /** 由地面上依次标记的墙角（世界 x,z）生成闭合墙段。 */
        fun fromCorners(cornersXZ: List<Pair<Double, Double>>, height: Double? = null): List<ScannedWall> {
            val pts = cornersXZ.map { Vec2(it.first, -it.second) }
            if (pts.size < 2) return emptyList()
            return pts.indices.map { ScannedWall(pts[it], pts[(it + 1) % pts.size], height) }.filter { it.start != it.end }
        }
    }
}

data class ScannedOpening(val kind: Kind, val center: Vec2, val width: Double) {
    sealed interface Kind { data class Door(val isOpen: Boolean) : Kind; data object Window : Kind; data object Opening : Kind }
    companion object { fun at(kind: Kind, centerX: Double, centerZ: Double, width: Double) = ScannedOpening(kind, Vec2(centerX, -centerZ), width) }
}

sealed interface ScanWarning {
    /** 墙体未闭合，已用外接矩形近似。 */
    data object NotClosedApproximated : ScanWarning
    data object TooFewWalls : ScanWarning
    data class OpeningUnmatched(val count: Int) : ScanWarning
    data class Validation(val issues: List<RoomIssue>) : ScanWarning

    val token: String get() = when (this) {
        NotClosedApproximated -> "notClosedApproximated"; TooFewWalls -> "tooFewWalls"
        is OpeningUnmatched -> "openingUnmatched:$count"; is Validation -> "validation:" + issues.joinToString(";") { it.swiftDescription }
    }
}

data class ScanImportResult(val room: Room, val warnings: List<ScanWarning>, val rotationDegrees: Double, val heightEstimate: Double?)

object ScanImport {
    const val SNAP_DISTANCE = 0.3

    fun convert(walls: List<ScannedWall>, openings: List<ScannedOpening>, name: String = "扫描的房间", alignToAxes: Boolean = true): ScanImportResult? {
        val usable = walls.filter { it.start.distanceTo(it.end) >= 0.2 }
        if (usable.size < 2) return null
        val warnings = mutableListOf<ScanWarning>()

        // 1. 最长墙对齐 x 轴（只取 90° 模）
        val longest = usable.maxByOrNull { it.start.distanceTo(it.end) }!!
        val d = longest.end - longest.start
        var angle = if (alignToAxes) -atan2(d.y, d.x) else 0.0
        val quarter = Math.PI / 2
        angle = angle - swiftRounded(angle / quarter) * quarter + 0
        fun rot(p: Vec2) = Vec2(p.x * cos(angle) - p.y * sin(angle), p.x * sin(angle) + p.y * cos(angle))
        val segs = usable.map { rot(it.start) to rot(it.end) }
        val ops = openings.map { it.copy(center = rot(it.center)) }

        // 2. 端点聚类吸附
        val points = mutableListOf<Vec2>()
        fun cluster(p: Vec2): Int {
            val i = points.indexOfFirst { it.distanceTo(p) <= SNAP_DISTANCE }
            if (i >= 0) return i
            points += p; return points.size - 1
        }
        val edges = segs.map { cluster(it.first) to cluster(it.second) }.filter { it.first != it.second }.toMutableList()
        val sums = MutableList(points.size) { Vec2.ZERO }; val counts = IntArray(points.size)
        for (s in segs) {
            val a = cluster(s.first); val b = cluster(s.second)
            sums[a] = sums[a] + s.first; counts[a]++; sums[b] = sums[b] + s.second; counts[b]++
        }
        val centers = points.indices.map { if (counts[it] > 0) sums[it] * (1.0 / counts[it]) else points[it] }

        // 3. 链接成闭合环
        var polygonIdx: List<Int>? = null
        if (edges.size >= 3) {
            val first = edges.removeAt(0)
            val loop = mutableListOf(first.first, first.second)
            while (loop.last() != loop[0]) {
                val last = loop.last()
                val k = edges.indexOfFirst { it.first == last || it.second == last }
                if (k < 0) break
                val e = edges.removeAt(k)
                loop += if (e.first == last) e.second else e.first
            }
            if (loop.size >= 4 && loop.last() == loop[0]) polygonIdx = loop.dropLast(1)
        }
        var polygon: List<Vec2> = if (polygonIdx != null) polygonIdx.map { centers[it] } else {
            warnings += if (usable.size < 3) ScanWarning.TooFewWalls else ScanWarning.NotClosedApproximated
            val xs = centers.map { it.x }; val ys = centers.map { it.y }
            listOf(Vec2(xs.min(), ys.min()), Vec2(xs.max(), ys.min()), Vec2(xs.max(), ys.max()), Vec2(xs.min(), ys.max()))
        }
        // 4. 平移到原点、1 cm 取整、逆时针
        val minX = polygon.minOf { it.x }; val minY = polygon.minOf { it.y }
        fun place(p: Vec2) = Vec2(swiftRounded((p.x - minX) * 100) / 100, swiftRounded((p.y - minY) * 100) / 100)
        polygon = polygon.map(::place)
        if (Geometry.signedArea(polygon) < 0) polygon = polygon.reversed()
        val heights = usable.mapNotNull { it.height }
        var room = Room(name = name, polygon = polygon, height = if (heights.isEmpty()) 2.7 else heights.sum() / heights.size)

        // 5. 门窗匹配最近墙段
        var unmatched = 0
        for (o in ops) {
            val c = place(o.center)
            val best = room.walls.map { w -> val cp = Geometry.closestPointOnSegment(c, w.start, w.end); Triple(w, cp.first.distanceTo(c), cp.second) }
                .minByOrNull { it.second }
            if (best == null || best.second > SNAP_DISTANCE || best.first.length < 0.2) { unmatched++; continue }
            val (w, _, along) = best
            val width = o.width.coerceAtLeast(RoomValidator.MIN_OPENING_WIDTH).coerceAtMost(w.length)
            val offset = (along - width / 2).coerceAtLeast(0.0).coerceAtMost(w.length - width)
            val (kind, frac) = when (val k = o.kind) {
                is ScannedOpening.Kind.Door -> OpeningKind.door to if (k.isOpen) 1.0 else 0.0
                ScannedOpening.Kind.Window -> OpeningKind.window to 0.0
                ScannedOpening.Kind.Opening -> OpeningKind.passage to 1.0
            }
            val clash = room.openings.any { it.wallId == w.id && offset < it.offset + it.width && it.offset < offset + width }
            if (clash) { unmatched++; continue }
            room = room.copy(openings = room.openings + Opening(wallId = w.id, kind = kind, offset = swiftRounded(offset * 100) / 100, width = width, openFraction = frac))
        }
        if (unmatched > 0) warnings += ScanWarning.OpeningUnmatched(unmatched)
        val issues = RoomValidator.validate(room)
        if (issues.isNotEmpty()) warnings += ScanWarning.Validation(issues)
        return ScanImportResult(room, warnings, angle * 180 / Math.PI, if (heights.isEmpty()) null else room.height)
    }
}

/** 扫描采集会话的纯数据记录（Android ARCore 采集层只负责填它）。 */
data class CaptureSession(
    val cornersXZ: List<Pair<Double, Double>> = emptyList(),
    val wallPlanes: List<ScannedWall> = emptyList(),
    val openings: List<ScannedOpening> = emptyList(),
    val floorY: Double? = null,
    val ceilingHeight: Double? = null,
    val usedDepth: Boolean = false,
) {
    fun addCorner(x: Double, z: Double) = copy(cornersXZ = cornersXZ + (x to z))
    fun undoCorner() = copy(cornersXZ = cornersXZ.dropLast(1))
    /** 优先用用户描出的墙角；不足 3 个时退回 ARCore 检测到的墙面平面。 */
    fun toWalls(): List<ScannedWall> = if (cornersXZ.size >= 3) ScannedWall.fromCorners(cornersXZ, ceilingHeight) else wallPlanes
    fun convert(name: String = "扫描的房间") = ScanImport.convert(toWalls(), openings, name)
}
