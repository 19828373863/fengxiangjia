package app.fengxiangjia.core.geometry

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.domain.Target
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Swift `Double.rounded()`（四舍五入，0.5 远离零）。Kotlin `round` 是银行家舍入，不能直接用。 */
fun swiftRounded(x: Double): Double {
    if (x.isNaN() || x.isInfinite()) return x
    val a = abs(x); val f = floor(a)
    val r = if (a - f >= 0.5) f + 1 else f
    return if (x < 0) -r else r
}

object Geometry {
    const val EPSILON = 1e-6

    /** 鞋带公式有向面积（逆时针为正）。 */
    fun signedArea(p: List<Vec2>): Double {
        if (p.size < 3) return 0.0
        var s = 0.0
        for (i in p.indices) s += p[i].cross(p[(i + 1) % p.size])
        return s / 2
    }

    fun segmentsIntersect(a: Vec2, b: Vec2, c: Vec2, d: Vec2, eps: Double = EPSILON): Boolean {
        fun orient(p: Vec2, q: Vec2, r: Vec2): Int { val v = (q - p).cross(r - p); return if (abs(v) < eps) 0 else if (v > 0) 1 else -1 }
        fun onSeg(p: Vec2, q: Vec2, r: Vec2) =
            minOf(p.x, r.x) - eps <= q.x && q.x <= maxOf(p.x, r.x) + eps && minOf(p.y, r.y) - eps <= q.y && q.y <= maxOf(p.y, r.y) + eps
        val o1 = orient(a, b, c); val o2 = orient(a, b, d); val o3 = orient(c, d, a); val o4 = orient(c, d, b)
        if (o1 != o2 && o3 != o4) return true
        if (o1 == 0 && onSeg(a, c, b)) return true
        if (o2 == 0 && onSeg(a, d, b)) return true
        if (o3 == 0 && onSeg(c, a, d)) return true
        if (o4 == 0 && onSeg(c, b, d)) return true
        return false
    }

    /** 第一对相交的非相邻边索引；简单多边形返回 null。 */
    fun selfIntersection(p: List<Vec2>): Pair<Int, Int>? {
        val n = p.size
        if (n < 4) return null
        for (i in 0 until n) for (j in i + 1 until n) {
            if (j == i + 1 || (i == 0 && j == n - 1)) continue
            if (segmentsIntersect(p[i], p[(i + 1) % n], p[j], p[(j + 1) % n])) return i to j
        }
        return null
    }

    /** 射线法（边界上视为在内）。 */
    fun contains(p: List<Vec2>, q: Vec2): Boolean {
        val n = p.size
        if (n < 3) return false
        for (i in 0 until n) if (distanceToSegment(q, p[i], p[(i + 1) % n]) < 1e-9) return true
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val a = p[i]; val b = p[j]
            if ((a.y > q.y) != (b.y > q.y) && q.x < (b.x - a.x) * (q.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    fun distanceToSegment(q: Vec2, a: Vec2, b: Vec2) = q.distanceTo(closestPointOnSegment(q, a, b).first)

    /** 线段上最近点及沿线段距起点长度。 */
    fun closestPointOnSegment(q: Vec2, a: Vec2, b: Vec2): Pair<Vec2, Double> {
        val ab = b - a
        val len2 = ab.dot(ab)
        if (len2 <= 0) return a to 0.0
        val t = ((q - a).dot(ab) / len2).coerceIn(0.0, 1.0)
        return (a + ab * t) to t * sqrt(len2)
    }
}

/** 由多边形边派生墙段。 */
val Room.walls: List<Wall>
    get() {
        val n = polygon.size
        if (n < 2 || wallIds.size != n) return emptyList()
        return (0 until n).map { Wall(wallIds[it], id, it, polygon[it], polygon[(it + 1) % n]) }
    }

fun Room.wall(id: EntityID): Wall? = walls.firstOrNull { it.id == id }
val Room.area: Double get() = abs(Geometry.signedArea(polygon))
fun Room.contains(q: Vec2) = Geometry.contains(polygon, q)

/** 开口两端点。 */
fun Room.segment(of: Opening): Pair<Vec2, Vec2>? {
    val w = wall(of.wallId) ?: return null
    return w.pointAtOffset(of.offset) to w.pointAtOffset(of.offset + of.width)
}

/** 顺时针则翻转为逆时针，保持 wallId 与墙段对应、开口位置不变。 */
fun Room.normalizedWinding(): Room {
    if (Geometry.signedArea(polygon) >= 0) return this
    val n = polygon.size
    val old = walls
    val newIds = (0 until n).map { j -> wallIds[((n - 2 - j) % n + n) % n] }
    val ops = openings.map { o ->
        val w = old.firstOrNull { it.id == o.wallId } ?: return@map o
        o.copy(offset = w.length - o.offset - o.width)
    }
    return copy(polygon = polygon.reversed(), wallIds = newIds, openings = ops)
}

fun Target.contains(p: Vec2): Boolean = when (val s = shape) {
    is TargetShape.Circle -> s.center.distanceTo(p) <= s.radius
    is TargetShape.Polygon -> Geometry.contains(s.points, p)
}
