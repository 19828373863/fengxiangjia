package app.fengxiangjia.android

import app.fengxiangjia.core.store.EntitlementCache
import app.fengxiangjia.core.store.EntitlementState

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.editor.RoomTemplate
import app.fengxiangjia.core.library.SampleData
import app.fengxiangjia.core.persistence.DocumentCodec
import app.fengxiangjia.core.persistence.HomeRepository
import app.fengxiangjia.core.simulation.AirflowSolver
import app.fengxiangjia.core.simulation.Explanation
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class AppModelTest {
    private val t0 = Instant.ofEpochSecond(1_800_000_000)
    private fun model(dir: java.io.File = Files.createTempDirectory("fxj").toFile()) = AppModel(HomeRepository(dir)) { t0 }

    @Test fun firstLaunchShowsSampleRoom() {
        val m = model(); assertEquals(SampleData.ROOM_ID, m.selectedRoomId); assertEquals("示例客厅", m.room!!.name)
    }

    @Test fun editAutosavesAndReloads() {
        val dir = Files.createTempDirectory("fxj").toFile()
        val m = model(dir)
        assertTrue(m.edit { it.placeFan(Vec2(2.0, 1.5)) })
        val m2 = model(dir); assertEquals(2, m2.room!!.fans.size)
    }

    @Test fun failedEditKeepsStateAndReportsChineseError() {
        val m = model(); val before = m.document
        assertFalse(m.edit { it.placeFan(Vec2(99.0, 1.0)) })
        assertEquals(before, m.document); assertEquals("位置在房间外", m.lastError)
    }

    @Test fun undoRedoSurvivesTopologyRefresh() {
        val m = model()
        m.edit { it.addOpening(OpeningKind.window, Vec2(-0.1, 2.0)) }
        assertEquals(3, m.room!!.openings.size)
        assertTrue(m.room!!.openings.all { it.connects.isNotEmpty() })
        assertTrue(m.editor()!!.canUndo)
        m.undo(); assertEquals(2, m.room!!.openings.size); m.redo(); assertEquals(3, m.room!!.openings.size)
    }

    @Test fun anyEditInvalidatesCurrentHash() {
        val m = model(); val r = AirflowSolver.solve(m.simulationInput()!!)
        assertTrue(r.isCurrent(m.simulationInput()!!))
        m.edit { e -> e.moveFan(e.room.fans[0].id, Vec2(3.0, 1.0)) }
        assertFalse(r.isCurrent(m.simulationInput()!!))
    }

    @Test fun scenariosCompareUnderSameEnvironment() {
        val m = model()
        val a = m.saveScenario("A")!!
        m.edit { e -> e.moveFan(e.room.fans[0].id, Vec2(1.0, 1.0)) }
        val b = m.saveScenario("B")!!
        m.setEnvironment(90.0, 4.0)
        val cmp = m.compare(a.id, b.id)!!
        assertEquals(cmp.environment, cmp.resultA.let { m.simulationInput()!!.environment })
        assertTrue(cmp.dimensions.isNotEmpty())
        m.applyScenario(a.id); assertEquals(Vec2(4.3, 0.8), m.room!!.fans[0].position)
    }

    @Test fun geometryRevisionBumpsOnShapeEditOnly() {
        val m = model(); val g = m.document.geometryRevision
        m.edit { e -> e.moveFan(e.room.fans[0].id, Vec2(3.0, 1.0)) }; assertEquals(g, m.document.geometryRevision)
        m.edit { it.moveVertex(2, Vec2(5.5, 4.0)) }; assertEquals(g + 1, m.document.geometryRevision)
    }

    @Test fun exportIsRedactedAndValid() {
        val m = model(); m.addFeedback(ComfortRating.justRight, "秘密")
        val back = DocumentCodec.decode(m.exportJson())
        assertNull(back.home.location); assertNull(back.feedback[0].note)
        assertEquals("fengxiangjia-20270115-0800.json", m.exportFileName())
    }

    @Test fun roomsAddDeleteAndAdjacent() {
        val m = model(); m.setEntitlement(EntitlementState(EntitlementCache(true, 0)))
        m.addRoom(RoomTemplate.DEFAULT_L_SHAPE, "L"); assertEquals(2, m.home.rooms.size)
        m.selectRoom(SampleData.ROOM_ID); assertTrue(m.addAdjacentRoom(1, 3.0, "卧室")); assertEquals(3, m.home.rooms.size)
        m.deleteRoom(m.selectedRoomId); assertEquals(2, m.home.rooms.size)
    }

    @Test fun closedRoomExplanationLabel() {
        val txt = Labels.explanation(Explanation.ClosedRoomInternalOnly, null)
        assertTrue(txt.contains("不会增加对外换气"))
    }

    @Test fun deleteAllDataRemovesFile() {
        val dir = Files.createTempDirectory("fxj").toFile(); val m = model(dir)
        m.edit { it.placeFan(Vec2(2.0, 1.5)) }; m.deleteAllData()
        assertFalse(java.io.File(dir, "home.json").exists())
    }
}
