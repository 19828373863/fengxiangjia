package app.fengxiangjia.core.weather

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.geometry.Topology

/** #16 窗外遮挡与楼层的简化外部风况模型（系数未校准，用于相对比较）。 */
object ObstructionModel {
    fun levelFactor(l: ObstructionLevel?) = when (l ?: ObstructionLevel.unknown) {
        ObstructionLevel.open -> 1.0
        ObstructionLevel.partial, ObstructionLevel.unknown -> 0.6
        ObstructionLevel.nearBuilding -> 0.35
        ObstructionLevel.lightWell -> 0.25
    }
    fun floorFactor(floor: Int?) = when { floor == null -> 0.85; floor < 2 -> 0.7; floor <= 5 -> 0.85; else -> 1.0 }
    fun windFactor(l: ObstructionLevel?, floor: Int?) = minOf(levelFactor(l) * floorFactor(floor), 1.0)

    fun obstructionMap(home: Home): Map<EntityID, ObstructionLevel> {
        val out = linkedMapOf<EntityID, ObstructionLevel>()
        for (r in home.rooms) for (o in r.openings)
            if (Topology.connections(o, r, home).last() == ConnectionTarget.Outdoor) out[o.id] = o.exteriorObstruction ?: ObstructionLevel.unknown
        return out
    }

    fun apply(env: Environment?, home: Home): Environment? = env?.copy(obstructionByOpening = env.obstructionByOpening + obstructionMap(home))
}
