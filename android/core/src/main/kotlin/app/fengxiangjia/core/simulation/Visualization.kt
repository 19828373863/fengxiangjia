package app.fengxiangjia.core.simulation

import app.fengxiangjia.core.domain.Vec2

/** 由同一 SimulationResult 派生的可视化几何（流线、箭头、图例），保证“看到的”与“建议”同源。 */
class FlowVisualization private constructor(val scenarioHash: String, val streamlines: List<Streamline>, val arrows: List<Arrow>) {
    data class Streamline(val points: List<Vec2>, val speeds: List<Double>)
    data class Arrow(val position: Vec2, val direction: Vec2, val speed: Double)

    companion object {
        /** 固定图例范围（m/s）：A/B 比较必须共用。 */
        const val LEGEND_MIN = 0.0
        const val LEGEND_MAX = 1.5
        fun normalized(speed: Double) = ((speed - LEGEND_MIN) / (LEGEND_MAX - LEGEND_MIN)).coerceIn(0.0, 1.0)

        fun of(result: SimulationResult, seedSpacing: Double = 0.4, arrowSpacing: Double = 0.5, minSpeed: Double = 0.05, maxLength: Double = 6.0): FlowVisualization? {
            val f = result.field ?: return null
            return FlowVisualization(result.scenarioHash, streamlines(f, seedSpacing, minSpeed, maxLength), arrows(f, arrowSpacing, minSpeed))
        }

        private fun seeds(f: FlowField, spacing: Double): List<Vec2> {
            val out = mutableListOf<Vec2>()
            val w = f.nx * f.cellSize; val h = f.ny * f.cellSize
            var y = spacing / 2
            while (y < h) {
                var x = spacing / 2
                while (x < w) {
                    val p = f.origin + Vec2(x, y)
                    f.cell(p)?.let { if (f.mask[f.index(it.first, it.second)]) out += p }
                    x += spacing
                }
                y += spacing
            }
            return out
        }

        private fun streamlines(f: FlowField, spacing: Double, minSpeed: Double, maxLength: Double): List<Streamline> {
            val step = f.cellSize * 0.5
            return seeds(f, spacing).mapNotNull { seed ->
                var p = seed; val pts = mutableListOf(seed); val sp = mutableListOf<Double>()
                val v0 = f.sample(seed)
                if (v0 == null || v0.length < minSpeed) return@mapNotNull null
                sp += v0.length
                var len = 0.0
                while (len < maxLength) {
                    val v1 = f.sample(p); if (v1 == null || v1.length < minSpeed) break
                    val mid = p + v1.normalized * (step / 2)
                    val v2 = f.sample(mid); if (v2 == null || v2.length < minSpeed) break
                    val next = p + v2.normalized * step
                    if (f.sample(next) == null) break
                    p = next; len += step
                    pts += p; sp += v2.length
                }
                if (pts.size >= 4) Streamline(pts, sp) else null
            }
        }

        private fun arrows(f: FlowField, spacing: Double, minSpeed: Double) = seeds(f, spacing).mapNotNull { p ->
            val v = f.sample(p)
            if (v == null || v.length < minSpeed) null else Arrow(p, v.normalized, v.length)
        }
    }
}
