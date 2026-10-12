package app.fengxiangjia.core

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.domain.Target
import app.fengxiangjia.core.persistence.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 与 Swift 生成的 shared/fixtures 互通。 */
class PersistenceTest {
    private val t0 = Instant.ofEpochSecond(1_800_000_000)

    @Test fun decodesSwiftDocumentV2() {
        val d = DocumentCodec.decode(Fixtures.text("document-v2.json"))
        assertEquals(2, d.schemaVersion)
        val room = d.home.rooms.single()
        assertEquals("示例客厅", room.name)
        assertEquals(listOf(ConnectionTarget.RoomRef("00000000-0000-0000-0000-00000000F002"), ConnectionTarget.Outdoor), room.openings[0].connects)
        assertEquals(TargetShape.Circle(Vec2(2.5, 3.4), 0.8), room.targets[0].shape)
        val sc = d.scenarios.single()
        assertEquals(mapOf("00000000-0000-0000-0000-0000000B0001" to 1.0, "00000000-0000-0000-0000-0000000B0002" to 0.5), sc.openingStates)
        assertEquals(ObstructionLevel.partial, sc.environmentSnapshot!!.obstructionByOpening["00000000-0000-0000-0000-0000000B0001"])
        assertEquals(t0, sc.createdAt)
        assertEquals(ComfortRating.justRight, d.feedback.single().rating)
        assertEquals(Location(1.35, 103.82, "SG"), d.home.location)
    }

    @Test fun reencodeIsSemanticallyIdenticalToSwift() {
        val swift = Fixtures.sortKeyedArrays(Fixtures.json("document-v2.json"))
        val kotlin = Fixtures.sortKeyedArrays(Json.parseToJsonElement(DocumentCodec.encode(DocumentCodec.decode(Fixtures.text("document-v2.json")))))
        assertNull(Fixtures.semanticEquals(swift, kotlin))
    }

    @Test fun polygonTargetShapeMatchesSwiftShape() {
        val t = Target(id = "00000000-0000-0000-0000-0000000E0002", name = "书桌",
            shape = TargetShape.Polygon(listOf(Vec2(3.5, 1.5), Vec2(4.5, 1.5))), objective = TargetObjective.avoidDirectDraft)
        val s = DocumentCodec.json.encodeToString(Target.serializer(), t).replace(Regex("\\s"), "")
        assertTrue(s.contains("\"shape\":{\"polygon\":{\"_0\":[{\"x\":3.5,\"y\":1.5},{\"x\":4.5,\"y\":1.5}]}}"), s)
    }

    @Test fun migrateV1AddsFeedback() {
        val v1 = DocumentCodec.decode(Fixtures.text("document-v1.json"))
        val v2 = DocumentCodec.decode(Fixtures.text("document-v2.json"))
        assertEquals(2, v1.schemaVersion); assertEquals(emptyList(), v1.feedback); assertEquals(v2.scenarios, v1.scenarios)
    }

    @Test fun migrateV0() {
        val d = DocumentCodec.decode(Fixtures.text("document-v0.json"))
        val r = d.home.rooms.single()
        assertEquals(2, d.schemaVersion); assertEquals(4, r.polygon.size); assertEquals(4, r.wallIds.size)
        assertEquals(RoomPurpose.other, r.purpose); assertEquals("00000000-0000-0000-0000-00000000A001", r.id)
        assertEquals(d, DocumentCodec.decode(DocumentCodec.encode(d)))
    }

    @Test fun exportRedactsByDefault() {
        val doc = DocumentCodec.decode(Fixtures.text("document-v2.json"))
        val data = DocumentExport.json(doc)
        val back = DocumentCodec.decode(data)
        assertNull(back.home.location); assertNull(back.feedback[0].note)
        assertFalse(data.contains("103.8"))
        val full = DocumentCodec.decode(DocumentExport.json(doc, DocumentExport.Options(includeLocation = true, includeFeedbackNotes = true)))
        assertNotNull(full.home.location); assertEquals("ok", full.feedback[0].note)
    }

    @Test fun exportFileNameMatchesIos() =
        assertEquals("fengxiangjia-20270115-0800.json", DocumentExport.fileName(HomeDocument(home = Home(name = "x")), t0))

    @Test fun repositorySaveLoadDelete() {
        val dir = Files.createTempDirectory("fxj").toFile()
        val repo = HomeRepository(dir)
        assertEquals(HomeLoadState.Empty, repo.load())
        val doc = DocumentCodec.decode(Fixtures.text("document-v2.json"))
        repo.save(doc)
        assertEquals(HomeLoadState.Loaded(doc), repo.load())
        repo.file.writeText("""{"schemaVersion": 7, "home": {}}""")
        assertTrue((repo.load() as HomeLoadState.Failed).message.contains("v7"))
        repo.delete(); assertFalse(repo.file.exists()); assertEquals(HomeLoadState.Empty, repo.load())
    }

    @Test fun unknownKeysIgnoredForForwardCompat() {
        val obj = Json.parseToJsonElement(Fixtures.text("document-v2.json")) as JsonObject
        val extra = JsonObject(obj + ("futureField" to kotlinx.serialization.json.JsonPrimitive("x")))
        assertEquals(DocumentCodec.decode(Fixtures.text("document-v2.json")), DocumentCodec.decode(extra.toString()))
    }
}
