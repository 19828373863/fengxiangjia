package app.fengxiangjia.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.domain.Target
import app.fengxiangjia.core.editor.Viewport
import app.fengxiangjia.core.geometry.segment
import app.fengxiangjia.core.simulation.FlowVisualization
import kotlin.math.cos
import kotlin.math.sin

/** 编辑模式。 */
enum class EditMode(val title: String) { VIEW("查看"), SHAPE("户型"), OPENING("门窗"), FAN("风扇"), TARGET("目标"), FURNITURE("家具") }

/** 拖动/点选命中的对象。 */
sealed interface Hit {
    data class Vertex(val index: Int) : Hit
    data class Item(val id: EntityID) : Hit
}

/** 风速 → 颜色（固定图例 0–1.5 m/s：蓝→绿→黄→红）。 */
fun speedColor(speed: Double): Color {
    val t = FlowVisualization.normalized(speed).toFloat()
    return when {
        t < 0.33f -> lerp(Color(0xFF4A90E2), Color(0xFF43B581), t / 0.33f)
        t < 0.66f -> lerp(Color(0xFF43B581), Color(0xFFF5C542), (t - 0.33f) / 0.33f)
        else -> lerp(Color(0xFFF5C542), Color(0xFFE5533D), (t - 0.66f) / 0.34f)
    }
}
private fun lerp(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

/**
 * 户型 + 气流画布。`vis` 必须来自当前输入对应的同一次 SimulationResult；`stale` 为 true 时风线降为灰色并标“待更新”。
 */
@Composable
fun RoomCanvas(
    room: Room, vis: FlowVisualization?, stale: Boolean, mode: EditMode, selected: EntityID?,
    onTap: (Vec2, Hit?) -> Unit, onDrag: (Hit, Vec2) -> Unit, onDragEnd: (Hit, Vec2) -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    val vp = Viewport.fitting(room.polygon, size.width.toDouble(), size.height.toDouble(), 48.0)
    fun w2s(p: Vec2) = vp.toScreen(p).let { Offset(it.x.toFloat(), it.y.toFloat()) }
    fun s2w(o: Offset) = vp.toWorld(Vec2(o.x.toDouble(), o.y.toDouble()))
    fun hit(o: Offset): Hit? {
        val p = s2w(o); val r = 28.0 / vp.scale
        if (mode == EditMode.SHAPE) room.polygon.withIndex().minByOrNull { it.value.distanceTo(p) }?.let { if (it.value.distanceTo(p) < r) return Hit.Vertex(it.index) }
        room.fans.minByOrNull { it.position.distanceTo(p) }?.let { if (it.position.distanceTo(p) < r && mode in setOf(EditMode.FAN, EditMode.VIEW)) return Hit.Item(it.id) }
        room.targets.minByOrNull { it.center.distanceTo(p) }?.let { if (it.center.distanceTo(p) < r * 1.5 && mode == EditMode.TARGET) return Hit.Item(it.id) }
        room.obstacles.firstOrNull { it.contains(p) }?.let { if (mode == EditMode.FURNITURE) return Hit.Item(it.id) }
        if (mode == EditMode.OPENING) room.openings.firstOrNull { o -> room.segment(o)?.let { (a, b) -> ((a + b) * 0.5).distanceTo(p) < r * 2 } == true }?.let { return Hit.Item(it.id) }
        return null
    }
    var dragging by remember { mutableStateOf<Hit?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }

    Canvas(modifier
        .onSizeChanged { size = it }
        .pointerInput(room, mode) { detectTapGestures { o -> onTap(s2w(o), hit(o)) } }
        .pointerInput(room, mode) {
            detectDragGestures(
                onDragStart = { o -> dragging = hit(o); dragPos = o },
                onDrag = { ch, d -> ch.consume(); dragPos += d; dragging?.let { onDrag(it, s2w(dragPos)) } },
                onDragEnd = { dragging?.let { onDragEnd(it, s2w(dragPos)) }; dragging = null },
                onDragCancel = { dragging = null },
            )
        }) {
        // 地面
        val path = Path().apply { room.polygon.forEachIndexed { i, p -> val o = w2s(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; close() }
        drawPath(path, Color(0xFFF7F4EE))
        // 家具
        for (ob in room.obstacles) drawObstacle(ob, ::w2s, vp.scale.toFloat(), ob.id == selected)
        // 目标区
        for (t in room.targets) drawTarget(t, ::w2s, vp.scale.toFloat(), t.id == selected)
        // 气流
        vis?.let { v ->
            for (s in v.streamlines) for (i in 1 until s.points.size) {
                val c = if (stale) Color.LightGray else speedColor(s.speeds[i])
                drawLine(c.copy(alpha = 0.85f), w2s(s.points[i - 1]), w2s(s.points[i]), strokeWidth = 3f, cap = StrokeCap.Round)
            }
            if (!stale) for (a in v.arrows) {
                val p = w2s(a.position); val d = Offset(a.direction.x.toFloat(), -a.direction.y.toFloat()) * 9f
                drawLine(speedColor(a.speed), p - d, p + d, strokeWidth = 2.5f)
                drawCircle(speedColor(a.speed), 3f, p + d)
            }
        }
        // 墙
        drawPath(path, Color(0xFF3A3A3A), style = Stroke(width = 6f))
        // 门窗
        for (o in room.openings) room.segment(o)?.let { (a, b) ->
            val col = when (o.kind) { OpeningKind.window -> Color(0xFF4AA3DF); OpeningKind.door -> Color(0xFFB07A4A); OpeningKind.passage -> Color.White }
            drawLine(Color.White, w2s(a), w2s(b), strokeWidth = 8f)
            drawLine(col.copy(alpha = 0.35f + 0.65f * o.openFraction.toFloat()), w2s(a), w2s(b), strokeWidth = if (o.id == selected) 10f else 6f)
        }
        // 风扇
        for (f in room.fans) drawFan(f, ::w2s, f.id == selected)
        // 顶点把手
        if (mode == EditMode.SHAPE) for (p in room.polygon) drawCircle(Color(0xFF2F6FED), 10f, w2s(p))
        if (stale && vis != null) drawRect(Color.White.copy(alpha = 0.15f))
    }
}

private fun DrawScope.drawObstacle(ob: Obstacle, w2s: (Vec2) -> Offset, scale: Float, sel: Boolean) {
    val c = w2s(ob.center)
    rotate(-ob.rotationDegrees.toFloat(), c) {
        val w = (ob.size.x * scale).toFloat(); val h = (ob.size.y * scale).toFloat()
        val col = when (ob.heightClass) { HeightClass.low -> Color(0xFFD9D2C5); HeightClass.medium -> Color(0xFFB8AE9C); HeightClass.tall -> Color(0xFF8C8270) }
        drawRect(col, Offset(c.x - w / 2, c.y - h / 2), androidx.compose.ui.geometry.Size(w, h))
        if (sel) drawRect(Color(0xFF2F6FED), Offset(c.x - w / 2, c.y - h / 2), androidx.compose.ui.geometry.Size(w, h), style = Stroke(3f))
    }
}

private fun DrawScope.drawTarget(t: Target, w2s: (Vec2) -> Offset, scale: Float, sel: Boolean) {
    val col = if (t.objective == TargetObjective.wantBreeze) Color(0xFF43B581) else Color(0xFFE5533D)
    when (val s = t.shape) {
        is TargetShape.Circle -> {
            drawCircle(col.copy(alpha = 0.12f), (s.radius * scale).toFloat(), w2s(s.center))
            drawCircle(col, (s.radius * scale).toFloat(), w2s(s.center), style = Stroke(if (sel) 4f else 2f))
        }
        is TargetShape.Polygon -> {
            val p = Path().apply { s.points.forEachIndexed { i, v -> val o = w2s(v); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; close() }
            drawPath(p, col.copy(alpha = 0.12f)); drawPath(p, col, style = Stroke(if (sel) 4f else 2f))
        }
    }
}

private fun DrawScope.drawFan(f: Fan, w2s: (Vec2) -> Offset, sel: Boolean) {
    val c = w2s(f.position)
    drawCircle(if (sel) Color(0xFF2F6FED) else Color(0xFF1F2937), 14f, c)
    if (f.kind != FanKind.ceiling) {
        val yaw = Math.toRadians(f.yawDegrees)
        val d = Offset(cos(yaw).toFloat(), -sin(yaw).toFloat()) * (24f + 30f * f.power.toFloat())
        drawLine(Color(0xFF1F2937), c, c + d, strokeWidth = 4f, cap = StrokeCap.Round)
    } else drawCircle(Color.White, 6f, c)
}
