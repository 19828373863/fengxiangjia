package app.fengxiangjia.core

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.library.SampleData
import app.fengxiangjia.core.persistence.DocumentCodec
import app.fengxiangjia.core.persistence.HomeDocument
import app.fengxiangjia.core.recommendation.LayoutSearch
import app.fengxiangjia.core.simulation.*
import app.fengxiangjia.core.store.*
import app.fengxiangjia.core.weather.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.test.*

class LayoutSearchGoldenTest {
    private val g = Fixtures.json("recommendation-golden.json").jsonObject

    @Test fun candidatesMatchSwift() {
        val cases = g["layoutSearch"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, cases.size)
        for (c in cases) {
            val doc = DocumentCodec.json.decodeFromJsonElement(HomeDocument.serializer(), c["document"]!!)
            val env = c["environment"]!!.takeIf { it !is JsonNull }?.let { DocumentCodec.json.decodeFromJsonElement(Environment.serializer(), it) }
            val got = LayoutSearch.search(doc.home, SampleData.ROOM_ID, environment = env)
            val exp = c["candidates"]!!.jsonArray.map { it.jsonObject }
            val name = c["name"]!!.jsonPrimitive.content
            assertEquals(exp.size, got.size, name)
            exp.zip(got).forEach { (e, a) ->
                assertEquals(e["kind"]!!.jsonPrimitive.content, a.kind.name, name)
                assertEquals(e["x"]!!.jsonPrimitive.double, a.fan.position.x, 0.0, name); assertEquals(e["y"]!!.jsonPrimitive.double, a.fan.position.y, 0.0, name)
                assertEquals(e["yaw"]!!.jsonPrimitive.double, a.fan.yawDegrees, 0.0, name)
                assertEquals(listOf(e["cost"], e["breeze"], e["draft"]).map { it!!.jsonPrimitive.int }, listOf(a.cost, a.breeze, a.draft).map { it.ordinal }, name)
                assertEquals(e["isCurrent"]!!.jsonPrimitive.boolean, a.isCurrent, name)
                assertEquals(e["hash"]!!.jsonPrimitive.content, a.result.scenarioHash, name)
                assertTrue(a.result.isCurrent(SimulationInput(doc.home.copy(rooms = listOf(a.room)), SampleData.ROOM_ID, env)), "推荐结果与绘制同源")
            }
        }
    }

    @Test fun noFanNoCandidates() {
        val h = SampleData.sampleDocument().home.let { it.copy(rooms = it.rooms.map { r -> r.copy(fans = emptyList()) }) }
        assertTrue(LayoutSearch.search(h, SampleData.ROOM_ID, environment = null).isEmpty())
    }

    @Test fun mockWeatherMatchesSwift() = runBlocking {
        val f = MockWeatherProvider().forecast(Location(1.35, 103.82), Instant.ofEpochSecond(1_800_001_234))
        val exp = g["mockWeather"]!!.jsonArray.map { it.jsonObject }
        assertEquals(exp.size, f.hours.size); assertTrue(f.isDemo)
        exp.zip(f.hours).forEach { (e, h) ->
            assertEquals(e["t"]!!.jsonPrimitive.long, h.date.epochSecond)
            assertEquals(e["from"]!!.jsonPrimitive.double, h.windFromDegrees, 1e-9); assertEquals(e["speed"]!!.jsonPrimitive.double, h.windSpeed, 1e-12)
            assertEquals(e["temp"]!!.jsonPrimitive.double, h.temperature!!, 1e-12); assertEquals(e["rain"]!!.jsonPrimitive.double, h.precipitation!!, 0.0)
        }
    }
}

class StalenessTest {
    private val home = SampleData.sampleDocument().home
    private val env = Environment(EnvironmentSource.manual, Instant.ofEpochSecond(1_800_000_000), 180.0, windSpeed = 3.0)
    private val input = SimulationInput(home, SampleData.ROOM_ID, env)
    private val result = AirflowSolver.solve(input)

    @Test fun unchangedHasNoReasons() = assertEquals(emptySet(), Staleness.reasons(result, input, input))
    @Test fun weather() = assertEquals(setOf(StaleReason.weather), Staleness.reasons(result, input, input.copy(environment = env.copy(windSpeed = 4.0))))
    @Test fun orientationAndFloor() = assertEquals(setOf(StaleReason.orientation, StaleReason.floor),
        Staleness.reasons(result, input, input.copy(home = home.copy(orientationDegrees = 30.0, floorLevel = 3))))
    @Test fun layoutAndOpenings() {
        val h = home.copy(rooms = home.rooms.map { r -> r.copy(fans = r.fans.map { it.copy(power = 1.0) }, openings = r.openings.map { it.copy(openFraction = 0.0) }) })
        assertEquals(setOf(StaleReason.layout, StaleReason.openings), Staleness.reasons(result, input, input.copy(home = h)))
    }
    @Test fun obstruction() = assertEquals(setOf(StaleReason.obstruction),
        Staleness.reasons(result, input, input.copy(environment = env.copy(obstructionByOpening = mapOf("X" to ObstructionLevel.partial)))))
}

class WeatherServiceTest {
    private val loc = Location(1.3521, 103.8198)
    private val t0 = Instant.ofEpochSecond(1_800_000_000)

    @Test fun noLocation() = runBlocking { assertEquals(WeatherError.NoLocation, WeatherService(MockWeatherProvider()).environment(null, t0).error) }

    @Test fun freshCacheThenFallbackOnFailure() = runBlocking {
        val ok = WeatherService(MockWeatherProvider())
        val o1 = ok.environment(loc, t0); assertNotNull(o1.environment); assertFalse(o1.fromCache)
        assertTrue(ok.environment(loc, t0.plusSeconds(60)).fromCache)
        val failing = WeatherService(MockWeatherProvider(WeatherError.Offline), ok.cache)
        val o2 = failing.environment(loc, t0.plusSeconds(2 * 3600))
        assertTrue(o2.fromCache); assertEquals(WeatherFreshness.stale, o2.freshness); assertEquals(WeatherError.Offline, o2.error)
        val o3 = failing.environment(loc, t0.plusSeconds(7 * 3600))
        assertNull(o3.environment); assertEquals(WeatherError.Offline, o3.error)
    }

    @Test fun openMeteoParsesAndCoarsens() = runBlocking {
        var asked = ""
        val body = """{"latitude":1.35,"longitude":103.82,"hourly":{"time":[1800000000,1800003600],"temperature_2m":[29.1,null],"precipitation":[0.0,0.2],"wind_speed_10m":[3.4,2.0],"wind_direction_10m":[360.0,190.0]}}"""
        val p = OpenMeteoProvider(fetch = { asked = it; body })
        val f = p.forecast(loc, t0)
        assertTrue(asked.contains("latitude=1.35&longitude=103.82")); assertFalse(asked.contains("1.3521"))
        assertTrue(asked.contains("wind_speed_unit=ms"))
        assertEquals(2, f.hours.size); assertEquals(0.0, f.hours[0].windFromDegrees); assertNull(f.hours[1].temperature)
        assertFalse(f.isDemo); assertTrue(f.attribution!!.serviceName.contains("Open-Meteo"))
        val env = WeatherService(p).environment(loc, t0).environment!!
        assertEquals(3.4, env.windSpeed); assertEquals(EnvironmentSource.assumed, env.source)
    }

    @Test fun openMeteoErrors() = runBlocking {
        assertFailsWith<WeatherError.Unavailable> { OpenMeteoProvider(fetch = { """{"error":true,"reason":"bad"}""" }).forecast(loc, t0) }
        assertFailsWith<WeatherError.Offline> { OpenMeteoProvider(fetch = { throw java.io.IOException("x") }).forecast(loc, t0) }
        Unit
    }
}

class EntitlementTest {
    @Test fun freeTierMatchesIos() {
        val s = EntitlementState()
        assertTrue(s.canAddRoom(0)); assertFalse(s.canAddRoom(1)); assertTrue(s.canSaveScenario(2)); assertFalse(s.canSaveScenario(3))
        assertFalse(s.allows(PaidFeature.roomScan))
    }

    @Test fun purchaseFlow() = runBlocking {
        val c = MockStoreClient(nextOutcome = PurchaseOutcome.Cancelled)
        val m = EntitlementManager(c, EntitlementCache())
        assertEquals(EntitlementManager.Message.cancelled, m.purchase(1).second); assertFalse(m.state.isUnlocked)
        c.nextOutcome = PurchaseOutcome.Pending; assertEquals(EntitlementManager.Message.pending, m.purchase(2).second); assertFalse(m.state.isUnlocked)
        c.nextOutcome = PurchaseOutcome.Purchased; assertEquals(EntitlementManager.Message.unlocked, m.purchase(3).second); assertTrue(m.state.isUnlocked)
        assertTrue(m.state.allows(PaidFeature.multiRoom))
    }

    @Test fun offlineKeepsCacheAndRestore() = runBlocking {
        val c = MockStoreClient(owned = true, failNetwork = true)
        val m = EntitlementManager(c, EntitlementCache(true, 100))
        assertTrue(m.refresh(200).isUnlocked)
        assertEquals(EntitlementManager.Message.offline, m.restore(300).second)
        c.failNetwork = false; c.owned = false
        assertEquals(EntitlementManager.Message.restoredNothing, m.restore(400).second); assertFalse(m.state.isUnlocked)
        assertTrue(EntitlementState(EntitlementCache(true, 0)).needsRecheck(EntitlementCache.OFFLINE_GRACE_SECONDS + 1))
    }
}
