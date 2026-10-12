package app.fengxiangjia.core.editor

import app.fengxiangjia.core.domain.Vec2

/** 户型模板（逆时针，原点在左下角，米）。 */
sealed interface RoomTemplate {
    val polygon: List<Vec2>
    data class Rectangle(val width: Double, val depth: Double) : RoomTemplate {
        override val polygon get() = listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth))
    }
    /** L 形：整体 width×depth，右上角切去 cutWidth×cutDepth。 */
    data class LShape(val width: Double, val depth: Double, val cutWidth: Double, val cutDepth: Double) : RoomTemplate {
        override val polygon: List<Vec2> get() {
            val cw = cutWidth.coerceIn(0.1, width - 0.1); val cd = cutDepth.coerceIn(0.1, depth - 0.1)
            return listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth - cd), Vec2(width - cw, depth - cd), Vec2(width - cw, depth), Vec2(0.0, depth))
        }
    }
    companion object {
        val DEFAULT_RECTANGLE = Rectangle(4.0, 3.0)
        val DEFAULT_L_SHAPE = LShape(5.0, 4.0, 2.0, 1.5)
    }
}

/** 编辑器视口：世界（米，+Y 上）↔ 屏幕（像素，+Y 下）。 */
data class Viewport(val scale: Double = 60.0, val offset: Vec2 = Vec2(40.0, 400.0)) {
    fun toScreen(w: Vec2) = Vec2(offset.x + w.x * scale, offset.y - w.y * scale)
    fun toWorld(s: Vec2) = Vec2((s.x - offset.x) / scale, (offset.y - s.y) / scale)
    fun panned(by: Vec2) = copy(offset = offset + by)
    fun zoomed(factor: Double, anchor: Vec2): Viewport {
        val w = toWorld(anchor)
        val s = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        return Viewport(s, Vec2(anchor.x - w.x * s, anchor.y + w.y * s))
    }
    companion object {
        const val MIN_SCALE = 20.0
        const val MAX_SCALE = 400.0
        fun fitting(polygon: List<Vec2>, width: Double, height: Double, padding: Double = 32.0): Viewport {
            val minX = polygon.minOfOrNull { it.x } ?: 0.0; val maxX = polygon.maxOfOrNull { it.x } ?: 1.0
            val minY = polygon.minOfOrNull { it.y } ?: 0.0; val maxY = polygon.maxOfOrNull { it.y } ?: 1.0
            val s = minOf((width - 2 * padding) / maxOf(maxX - minX, 0.1), (height - 2 * padding) / maxOf(maxY - minY, 0.1))
            val cs = s.coerceIn(MIN_SCALE, MAX_SCALE)
            return Viewport(cs, Vec2(width / 2 - (minX + maxX) / 2 * cs, height / 2 + (minY + maxY) / 2 * cs))
        }
    }
}
