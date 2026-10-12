package app.fengxiangjia.core.simulation

import app.fengxiangjia.core.domain.Environment
import app.fengxiangjia.core.geometry.swiftRounded

/** #18 结果过期原因（UI 用）。任何影响 scenarioHash 的输入变化都使旧结果过期。 */
enum class StaleReason(val label: String) {
    weather("天气已变化"), orientation("住宅朝向已改"), floor("楼层已改"), obstruction("窗外遮挡已改"),
    openings("门窗状态已改"), geometry("户型已改"), layout("风扇或目标已改"), model("计算模型已更新"), options("计算精度已改")
}

object Staleness {
    fun reasons(result: SimulationResult, oldInput: SimulationInput, newInput: SimulationInput): Set<StaleReason> {
        if (result.isCurrent(newInput)) return emptySet()
        val r = reasons(oldInput, newInput).toMutableSet()
        if (result.modelVersion != SimulationResult.MODEL_VERSION) r += StaleReason.model
        if (r.isEmpty()) r += StaleReason.model
        return r
    }

    fun reasons(a: SimulationInput, b: SimulationInput): Set<StaleReason> {
        val r = mutableSetOf<StaleReason>()
        fun i(x: Double) = swiftRounded(x).toLong()
        fun envCore(e: Environment?) = e?.let { listOf(it.source.name, it.observedAt.epochSecond.toString(), i(it.windFromDegrees * 10).toString(),
            (it.windSpeed?.let { s -> i(s * 100) } ?: -1).toString(), (it.temperature?.let { s -> i(s * 10) } ?: -9999).toString(),
            (it.rain?.let { s -> i(s * 10) } ?: -1).toString()) }
        if (envCore(a.environment) != envCore(b.environment)) r += StaleReason.weather
        val emptyObs: Map<String, app.fengxiangjia.core.domain.ObstructionLevel> = emptyMap()
        if ((a.environment?.obstructionByOpening ?: emptyObs) != (b.environment?.obstructionByOpening ?: emptyObs)) r += StaleReason.obstruction
        if (i(a.home.orientationDegrees * 10) != i(b.home.orientationDegrees * 10)) r += StaleReason.orientation
        if (a.home.floorLevel != b.home.floorLevel) r += StaleReason.floor
        if (a.options != b.options) r += StaleReason.options
        val ra = a.room; val rb = b.room
        if (ra == null || rb == null) { r += StaleReason.geometry; return r }
        if (ra.polygon != rb.polygon || ra.wallIds != rb.wallIds || ra.obstacles != rb.obstacles) r += StaleReason.geometry
        // connects 是派生缓存，比较时忽略
        fun ops(x: List<app.fengxiangjia.core.domain.Opening>) = x.map { it.copy(connects = emptyList()) }
        if (ops(ra.openings) != ops(rb.openings) || a.home.rooms.size != b.home.rooms.size) r += StaleReason.openings
        if (ra.fans != rb.fans || ra.targets != rb.targets) r += StaleReason.layout
        fun others(inp: SimulationInput) = inp.home.rooms.filter { it.id != inp.roomId }.sortedBy { it.id }.map { ops(it.openings) }
        if (others(a) != others(b)) r += StaleReason.openings
        return r
    }
}
