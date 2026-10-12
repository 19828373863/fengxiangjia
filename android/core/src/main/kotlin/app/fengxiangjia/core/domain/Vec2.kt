package app.fengxiangjia.core.domain

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** 室内二维右手笛卡尔坐标点/向量，单位：米。+Y 为户型“上”方向。与 Swift `Vec2` 同构。 */
@Serializable
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    fun dot(o: Vec2) = x * o.x + y * o.y
    fun cross(o: Vec2) = x * o.y - y * o.x
    val length: Double get() = sqrt(x * x + y * y)
    val normalized: Vec2 get() { val l = length; return if (l > 0) Vec2(x / l, y / l) else ZERO }
    fun distanceTo(o: Vec2) = (this - o).length

    companion object { val ZERO = Vec2(0.0, 0.0) }
}
