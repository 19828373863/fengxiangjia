package app.fengxiangjia.core

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.domain.Target
import app.fengxiangjia.core.persistence.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainModelTest {
    private val t0 = Instant.ofEpochSecond(1_800_000_000)

    private fun makeDoc(): HomeDocument {
        val room0 = Room(name = "客厅", polygon = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 3.0), Vec2(0.0, 3.0)))
        val win = Opening(wallId = room0.wallIds[0], kind = OpeningKind.window, offset = 1.0, width = 1.2,
            connects = listOf(ConnectionTarget.RoomRef(room0.id), ConnectionTarget.Outdoor))
        val r = room0.copy(
            openings = listOf(win),
            fans = listOf(Fan.of(position = Vec2(1.0, 1.0), yawDegrees = 45.0, power = 0.5)),
            obstacles = listOf(Obstacle(kind = ObstacleKind.sofa, center = Vec2(2.0, 2.5), size = Vec2(2.0, 0.8))),
            targets = listOf(Target(name = "沙发", shape = TargetShape.Circle(Vec2(2.0, 2.5), 0.6), objective = TargetObjective.wantBreeze)),
        )
        val env = Environment(EnvironmentSource.manual, t0, 90.0, windSpeed = 2.5, obstructionByOpening = mapOf(win.id to ObstructionLevel.partial))
        val home = Home(name = "我家", location = Location(1.35, 103.82), orientationDegrees = 30.0, rooms = listOf(r))
        val sc = Scenario(name = "A", roomId = r.id, geometryRevision = 0, environmentSnapshot = env, fans = r.fans, createdAt = t0)
        return HomeDocument(home = home, scenarios = listOf(sc))
    }

    @Test fun roundTrip() { val d = makeDoc(); assertEquals(d, DocumentCodec.decode(DocumentCodec.encode(d))) }

    @Test fun encodedContainsSchemaVersion() = assertTrue(DocumentCodec.encode(makeDoc()).contains("\"schemaVersion\": 2"))

    @Test fun wallIdsMatchVertices() {
        val r = Room(name = "x", polygon = listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(0.0, 1.0)))
        assertEquals(3, r.wallIds.size); assertEquals(3, r.wallIds.toSet().size)
        assertTrue(r.wallIds.all { it == it.uppercase() })
    }

    @Test fun fanPowerClamped() {
        assertEquals(1.0, Fan.of(position = Vec2.ZERO, power = 3.0).power)
        assertEquals(0.0, Fan.of(position = Vec2.ZERO, power = -1.0).power)
    }

    @Test fun rejectsFutureVersion() =
        assertEquals(PersistenceException.UnsupportedFutureVersion(99), assertFailsWith<PersistenceException> { DocumentCodec.decode("""{"schemaVersion": 99, "home": {}}""") })

    @Test fun rejectsMissingVersion() {
        assertIs<PersistenceException.MissingSchemaVersion>(assertFailsWith<PersistenceException> { DocumentCodec.decode("""{"home": {}}""") })
    }

    @Test fun corruptDataThrows() {
        assertFailsWith<PersistenceException> { DocumentCodec.decode("not json") }
        assertIs<PersistenceException.Corrupt>(assertFailsWith<PersistenceException> { DocumentCodec.decode("""{"schemaVersion": 1, "home": 3}""") })
    }

    @Test fun windVectorConvention() {
        val v = Environment(EnvironmentSource.assumed, t0, 0.0).windToVector(0.0) // 北风吹向南
        assertEquals(0.0, v.x, 1e-9); assertEquals(-1.0, v.y, 1e-9)
        val w = Environment(EnvironmentSource.assumed, t0, 270.0).windToVector(90.0)
        assertEquals(0.0, w.x, 1e-9); assertEquals(1.0, w.y, 1e-9)
    }

    @Test fun nilOptionalsAreOmittedLikeSwift() {
        val json = DocumentCodec.encode(HomeDocument(home = Home(name = "x")))
        assertTrue(!json.contains("location") && !json.contains("floorLevel"))
    }
}
