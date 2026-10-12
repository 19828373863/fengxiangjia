package app.fengxiangjia.core.simulation

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.geometry.Topology
import app.fengxiangjia.core.geometry.swiftRounded
import java.security.MessageDigest

/**
 * scenarioHash = SHA-256(规范化 JSON)。与 iOS `ScenarioHash.swift` 逐字节一致：
 * Swift `JSONEncoder(.sortedKeys)` 紧凑输出、nil 字段省略、量化用 Swift `.rounded()`。
 */
object ScenarioHash {
    private fun q(x: Double, step: Double): Long = swiftRounded(x / step).toLong()
    private fun s(x: Double, step: Double) = q(x, step).toString()

    private fun str(v: String): String {
        val sb = StringBuilder("\"")
        for (ch in v) when {
            ch == '"' -> sb.append("\\\""); ch == '\\' -> sb.append("\\\\"); ch == '/' -> sb.append("\\/")
            ch == '\n' -> sb.append("\\n"); ch == '\r' -> sb.append("\\r"); ch == '\t' -> sb.append("\\t")
            ch.code < 0x20 -> sb.append("\\u%04x".format(ch.code))
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }
    private fun strArr(a: List<String>) = a.joinToString(",", "[", "]") { str(it) }
    private fun strArr2(a: List<List<String>>) = a.joinToString(",", "[", "]") { strArr(it) }

    fun canonical(input: SimulationInput): String? {
        val room = input.room ?: return null
        val mm = 0.001
        fun conn(c: ConnectionTarget) = when (c) { is ConnectionTarget.RoomRef -> "r:" + c.id; ConnectionTarget.Outdoor -> "out"; ConnectionTarget.Unknown -> "?" }
        val openings = room.openings.sortedBy { it.id }.map { o ->
            listOf(o.id, o.wallId, o.kind.name, s(o.offset, mm), s(o.width, mm), s(o.openFraction, 0.001),
                Topology.connections(o, room, input.home).joinToString("|") { conn(it) })
        }
        val obstacles = room.obstacles.sortedBy { it.id }.map { b ->
            listOf(b.id, b.kind.name, s(b.center.x, mm), s(b.center.y, mm), s(b.size.x, mm), s(b.size.y, mm), s(b.rotationDegrees, 0.1), b.heightClass.name)
        }
        val fans = room.fans.sortedBy { it.id }.map { f ->
            listOf(f.id, f.kind.name, s(f.position.x, mm), s(f.position.y, mm), s(f.yawDegrees, 0.1), s(f.power, 0.001))
        }
        val targets = room.targets.sortedBy { it.id }.map { t ->
            listOf(t.id, t.objective.name) + when (val sh = t.shape) {
                is TargetShape.Circle -> listOf("c", s(sh.center.x, mm), s(sh.center.y, mm), s(sh.radius, mm))
                is TargetShape.Polygon -> listOf("p") + sh.points.flatMap { listOf(s(it.x, mm), s(it.y, mm)) }
            }
        }
        val env = input.environment?.let { e ->
            listOf(e.source.name, e.observedAt.epochSecond.toString(), s(e.windFromDegrees, 0.1),
                e.windSpeed?.let { s(it, 0.01) } ?: "-", e.temperature?.let { s(it, 0.1) } ?: "-", e.rain?.let { s(it, 0.1) } ?: "-")
        }
        val obstruction = (input.environment?.obstructionByOpening ?: emptyMap()).map { listOf(it.key, it.value.name) }.sortedBy { it[0] }
        val o = input.options
        // 键按字典序（与 Swift .sortedKeys 相同）
        val parts = mutableListOf<String>()
        if (env != null) parts += "\"environment\":" + strArr(env)
        parts += "\"fans\":" + strArr2(fans)
        input.home.floorLevel?.let { parts += "\"floor\":$it" }
        parts += "\"model\":" + str(SimulationResult.MODEL_VERSION)
        parts += "\"obstacles\":" + strArr2(obstacles)
        parts += "\"obstruction\":" + strArr2(obstruction)
        parts += "\"openings\":" + strArr2(openings)
        parts += "\"options\":" + strArr(listOf(s(o.cellSize, mm), o.maxCellsPerAxis.toString(), o.smoothingIterations.toString()))
        parts += "\"orientation\":" + q(input.home.orientationDegrees, 0.1)
        parts += "\"polygon\":" + room.polygon.joinToString(",", "[", "]") { "[${q(it.x, mm)},${q(it.y, mm)}]" }
        parts += "\"targets\":" + strArr2(targets)
        parts += "\"wallIds\":" + strArr(room.wallIds)
        return parts.joinToString(",", "{", "}")
    }

    fun compute(input: SimulationInput): String {
        val c = canonical(input) ?: return "no-room"
        return MessageDigest.getInstance("SHA-256").digest(c.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
