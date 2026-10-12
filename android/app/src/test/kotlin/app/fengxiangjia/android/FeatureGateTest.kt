package app.fengxiangjia.android

import app.fengxiangjia.android.sensors.CompassMath
import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.editor.RoomTemplate
import app.fengxiangjia.core.persistence.HomeRepository
import app.fengxiangjia.core.store.*
import app.fengxiangjia.core.weather.WeatherOutcome
import app.fengxiangjia.core.weather.WeatherFreshness
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class FeatureGateTest {
    private val t0 = Instant.parse("2027-01-15T00:00:00Z")
    private fun model() = AppModel(HomeRepository(Files.createTempDirectory("fxj").toFile())) { t0 }

    @Test fun freeTierMatchesIos() {
        val m = model()
        assertEquals(1, m.home.rooms.size)
        assertNull(m.addRoom(RoomTemplate.DEFAULT_RECTANGLE, "x")); assertEquals(1, m.home.rooms.size)
        assertNotNull(m.lastError)
        repeat(FreeLimits.SCENARIOS) { assertNotNull(m.saveScenario("s$it")) }
        assertNull(m.saveScenario("over")); assertEquals(FreeLimits.SCENARIOS, m.document.scenarios.size)
        assertTrue(m.recommend().isEmpty()); assertFalse(m.canRecommend())
        assertNotNull(m.paywall(PaidFeature.roomScan)); assertNotNull(m.paywall(PaidFeature.liveWeather))
    }

    @Test fun unlockedAllowsEverything() {
        val m = model(); m.setEntitlement(EntitlementState(EntitlementCache(true, 0)))
        assertNotNull(m.addRoom(RoomTemplate.DEFAULT_RECTANGLE, "x"))
        PaidFeature.entries.forEach { assertNull(m.paywall(it)) }
    }

    @Test fun recommendationAppliesCandidate() {
        val m = model(); m.setEntitlement(EntitlementState(EntitlementCache(true, 0)))
        val c = m.recommend()
        assertTrue(c.isNotEmpty())
        val pick = c.first { !it.isCurrent }
        m.applyCandidate(pick)
        assertEquals(pick.fan.position, m.room!!.fans.first { it.id == pick.fan.id }.position)
        assertTrue(m.editor()!!.canUndo)
    }

    @Test fun locationIsCoarsened() {
        val m = model(); m.setLocation(1.352083, 103.819836)
        assertEquals(1.35, m.home.location!!.latitude, 1e-9); assertEquals(103.82, m.home.location!!.longitude, 1e-9)
        m.clearLocation(); assertNull(m.home.location)
    }

    @Test fun weatherOutcomeReplacesEnvironment() {
        val m = model()
        val e = Environment(EnvironmentSource.weatherKit, t0, 90.0, windSpeed = 3.0)
        m.applyWeather(WeatherOutcome(e, null, WeatherFreshness.fresh, false, null))
        assertEquals(90.0, m.environment!!.windFromDegrees, 0.0)
        // 失败结果不清掉已有环境（回退到手动/上次）
        m.applyWeather(WeatherOutcome(null, null, null, false, app.fengxiangjia.core.weather.WeatherError.Offline))
        assertEquals(90.0, m.environment!!.windFromDegrees, 0.0)
    }

    @Test fun staleReasonShownAfterOrientationChange() {
        val m = model()
        val before = m.simulationInput()!!
        val c = Computed(app.fengxiangjia.core.simulation.AirflowSolver.solve(before), null, before)
        assertNull(staleReasonText(c, before))
        m.setOrientation(90.0)
        assertEquals("住宅朝向已改", staleReasonText(c, m.simulationInput()))
    }

    @Test fun compassSmoothingWrapsShortestArc() {
        assertEquals(350.0, CompassMath.smooth(null, -10.0), 1e-9)
        val s = CompassMath.smooth(355.0, 5.0, 0.5)
        assertEquals(0.0, s, 1e-9)
    }
}
