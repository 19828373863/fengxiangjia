package app.fengxiangjia.core

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.persistence.DocumentCodec
import app.fengxiangjia.core.persistence.HomeDocument
import app.fengxiangjia.core.recommendation.*
import app.fengxiangjia.core.simulation.*
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.test.*

/** 跨平台黄金样例：Swift FXJCore 输出 vs Kotlin 移植。 */
class GoldenSolverTest {
    private val golden = Fixtures.json("solver-golden.json").jsonObject
    private val cases = golden["cases"]!!.jsonArray.map { it.jsonObject }
    private val tol = 1e-9

    private fun input(c: JsonObject): SimulationInput {
        val doc = DocumentCodec.json.decodeFromJsonElement(HomeDocument.serializer(), c["document"]!!)
        val env = c["environment"]!!.takeIf { it !is JsonNull }?.let { DocumentCodec.json.decodeFromJsonElement(Environment.serializer(), it) }
        return SimulationInput(doc.home, c["roomId"]!!.jsonPrimitive.content, env)
    }

    @Test fun modelVersionMatches() = assertEquals(golden["modelVersion"]!!.jsonPrimitive.content, SimulationResult.MODEL_VERSION)

    @Test fun hasAllCases() = assertEquals(10, cases.size)

    @Test fun scenarioHashIsByteIdentical() {
        for (c in cases) assertEquals(c["scenarioHash"]!!.jsonPrimitive.content, ScenarioHash.compute(input(c)), c["name"].toString())
    }

    @Test fun iosGoldenHashesForSample() {
        // 与 iOS `GoldenScenarios`（#21）中写死的值一致
        assertEquals("376307d75efd12390770a1ef3adb4c6a7172f75239df3c2e985a8aaac5610a6b", cases[0]["scenarioHash"]!!.jsonPrimitive.content)
        assertEquals("d8bb83d50ae79f0a132c28302be8fd2567bd775ca9e8810d9a276cc8c25543a0", cases[1]["scenarioHash"]!!.jsonPrimitive.content)
    }

    @Test fun statusExplanationsAndMetricsMatch() {
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val r = AirflowSolver.solve(input(c))
            val st = c["status"]!!.jsonArray.map { it.jsonPrimitive.content }
            when (val s = r.status) {
                SimulationStatus.Ok -> assertEquals(listOf("ok"), st, name)
                is SimulationStatus.InvalidInput -> assertEquals(st, listOf("invalidInput") + s.reasons, name)
            }
            assertEquals(c["explanations"]!!.jsonArray.map { it.jsonPrimitive.content }, r.explanations.map { it.token }, name)
            val m = c["metrics"]?.jsonObject
            if (m == null) { assertNull(r.metrics, name); continue }
            val k = r.metrics!!
            assertEquals(m["circulationCoverage"]!!.jsonPrimitive.double, k.circulationCoverage, tol, name)
            assertEquals(m["meanSpeed"]!!.jsonPrimitive.double, k.meanSpeed, tol, name)
            assertEquals(m["outdoorExchange"]!!.jsonPrimitive.int, k.outdoorExchange.ordinal, name)
            assertEquals(m["hasOutdoorPath"]!!.jsonPrimitive.boolean, k.hasOutdoorPath, name)
            assertEquals(m["crossVentilation"]!!.jsonPrimitive.boolean, k.crossVentilation, name)
            val ts = m["targets"]!!.jsonArray.map { it.jsonObject }
            assertEquals(ts.size, k.targets.size, name)
            ts.zip(k.targets).forEach { (e, a) ->
                assertEquals(e["targetId"]!!.jsonPrimitive.content, a.targetId)
                assertEquals(e["breezeCoverage"]!!.jsonPrimitive.double, a.breezeCoverage, tol, name)
                assertEquals(e["draftExposure"]!!.jsonPrimitive.double, a.draftExposure, tol, name)
                assertEquals(e["meanSpeed"]!!.jsonPrimitive.double, a.meanSpeed, tol, name)
            }
        }
    }

    @Test fun flowFieldMatchesCellByCell() {
        var cells = 0; var maxErr = 0.0
        for (c in cases) {
            val fe = c["field"]?.jsonObject ?: continue
            val f = AirflowSolver.solve(input(c)).field!!
            val name = c["name"]!!.jsonPrimitive.content
            assertEquals(fe["nx"]!!.jsonPrimitive.int, f.nx, name); assertEquals(fe["ny"]!!.jsonPrimitive.int, f.ny, name)
            assertEquals(fe["cellSize"]!!.jsonPrimitive.double, f.cellSize, 0.0, name)
            val mask = fe["mask"]!!.jsonArray.map { it.jsonPrimitive.int == 1 }
            assertEquals(mask, f.mask.toList(), "$name mask")
            val u = fe["u"]!!.jsonArray; val v = fe["v"]!!.jsonArray
            for (i in 0 until f.nx * f.ny) {
                maxErr = maxOf(maxErr, abs(u[i].jsonPrimitive.double - f.u[i]), abs(v[i].jsonPrimitive.double - f.v[i]))
                cells++
            }
        }
        assertTrue(cells > 10_000)
        assertTrue(maxErr <= tol, "max |Δu,Δv| = $maxErr")
        println("golden flow field: $cells cells, max abs error $maxErr")
    }

    @Test fun visualizationCountsMatch() {
        for (c in cases) {
            val ve = c["visualization"]?.jsonObject ?: continue
            val vis = FlowVisualization.of(AirflowSolver.solve(input(c)))!!
            val name = c["name"]!!.jsonPrimitive.content
            assertEquals(ve["streamlines"]!!.jsonPrimitive.int, vis.streamlines.size, name)
            assertEquals(ve["arrows"]!!.jsonPrimitive.int, vis.arrows.size, name)
            assertEquals(ve["streamlinePoints"]!!.jsonArray.map { it.jsonPrimitive.int }, vis.streamlines.map { it.points.size }, name)
            assertEquals(vis.scenarioHash, c["scenarioHash"]!!.jsonPrimitive.content)
        }
    }

    @Test fun comparisonMatchesSwift() {
        val g = Fixtures.json("comparison.json").jsonObject
        val sample = app.fengxiangjia.core.library.SampleData.sampleDocument()
        val room = sample.home.rooms[0]
        val a = Scenario.capture("A", room, 0, null)
        val b = DocumentCodec.json.decodeFromJsonElement(Scenario.serializer(), g["scenarioB"]!!)
        val env = DocumentCodec.json.decodeFromJsonElement(Environment.serializer(), g["environment"]!!)
        val cmp = ScenarioComparison.compare(sample.home, room.id, a, b, 0, env)!!
        assertEquals(g["hashA"]!!.jsonPrimitive.content, cmp.resultA.scenarioHash)
        assertEquals(g["hashB"]!!.jsonPrimitive.content, cmp.resultB.scenarioHash)
        assertEquals(g["dimensions"]!!.jsonArray.map { it.jsonObject.let { d -> listOf(d["kind"]!!.jsonPrimitive.content, d["levelA"]!!.jsonPrimitive.content, d["levelB"]!!.jsonPrimitive.content, d["preferred"]!!.jsonPrimitive.content) } },
            cmp.dimensions.map { listOf(it.kind.name, it.levelA.ordinal.toString(), it.levelB.ordinal.toString(), it.preferred.name) })
        assertEquals(0, g["notes"]!!.jsonArray.size); assertEquals(cmp.notes, emptyList())
        assertEquals(g["suggestions"]!!.jsonArray.size, cmp.suggestions.size)
    }
}

class SimulationBehaviourTest {
    private val sample = app.fengxiangjia.core.library.SampleData.sampleDocument().home
    private val env = Environment(EnvironmentSource.manual, java.time.Instant.ofEpochSecond(1_800_000_000), 180.0, windSpeed = 3.0)

    @Test fun closedRoomNeverIncreasesOutdoorExchange() {
        val closed = sample.copy(rooms = sample.rooms.map { it.copy(openings = emptyList()) })
        val r = AirflowSolver.solve(SimulationInput(closed, sample.rooms[0].id, env))
        assertEquals(Level.none, r.metrics!!.outdoorExchange)
        assertTrue(Explanation.ClosedRoomInternalOnly in r.explanations)
    }

    @Test fun anyInputChangeInvalidatesResult() {
        val input = SimulationInput(sample, sample.rooms[0].id, env)
        val r = AirflowSolver.solve(input)
        assertTrue(r.isCurrent(input))
        val moved = sample.copy(rooms = sample.rooms.map { it.copy(fans = it.fans.map { f -> f.copy(position = f.position + Vec2(0.01, 0.0)) }) })
        assertFalse(r.isCurrent(input.copy(home = moved)))
        assertFalse(r.isCurrent(input.copy(environment = env.copy(windSpeed = 3.5))))
        assertFalse(r.isCurrent(input.copy(home = sample.copy(orientationDegrees = 10.0))))
    }

    @Test fun deterministic() {
        val input = SimulationInput(sample, sample.rooms[0].id, env)
        val a = AirflowSolver.solve(input).field!!; val b = AirflowSolver.solve(input).field!!
        assertContentEquals(a.u, b.u); assertContentEquals(a.v, b.v)
    }

    @Test fun abCompareUsesSharedEnvironmentNote() {
        val room = sample.rooms[0]
        val a = Scenario.capture("A", room, 0, env)
        val b = Scenario.capture("B", room, 1, null)
        val cmp = ScenarioComparison.compare(sample, room.id, a, b, 0, env)!!
        assertTrue(ComparisonNote.EnvironmentUnified in cmp.notes)
        assertTrue(ComparisonNote.GeometryChanged(Preference.b) in cmp.notes)
        assertEquals(Level.none, ScenarioComparison.adjustmentCost(room, room))
    }

    @Test fun legendFixed() { assertEquals(1.0, FlowVisualization.normalized(9.0)); assertEquals(0.0, FlowVisualization.normalized(-1.0)) }
}
