package app.fengxiangjia.core

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.editor.RoomEditor
import app.fengxiangjia.core.geometry.*
import app.fengxiangjia.core.scan.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ScanImportTest {
    private val cases = Fixtures.json("scan-golden.json").jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
    private fun v(a: JsonElement) = a.jsonArray.let { Vec2(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }
    private fun kind(s: String) = when (s) { "doorOpen" -> ScannedOpening.Kind.Door(true); "doorClosed" -> ScannedOpening.Kind.Door(false); "window" -> ScannedOpening.Kind.Window; else -> ScannedOpening.Kind.Opening }

    @Test fun matchesSwiftGolden() {
        assertEquals(6, cases.size)
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val walls = c["walls"]!!.jsonArray.map { it.jsonObject.let { w -> ScannedWall(v(w["start"]!!), v(w["end"]!!), w["height"]?.jsonPrimitive?.double) } }
            val ops = c["openings"]!!.jsonArray.map { it.jsonObject.let { o -> ScannedOpening(kind(o["kind"]!!.jsonPrimitive.content), v(o["center"]!!), o["width"]!!.jsonPrimitive.double) } }
            val r = ScanImport.convert(walls, ops)
            val e = c["result"]!!
            if (e is JsonNull) { assertNull(r, name); continue }
            e as JsonObject; r!!
            assertEquals(e["polygon"]!!.jsonArray.map(::v), r.room.polygon, name)
            assertEquals(e["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }, r.warnings.map { it.token }, name)
            assertEquals(e["rotationDegrees"]!!.jsonPrimitive.double, r.rotationDegrees, 1e-9, name)
            assertEquals(e["height"]!!.jsonPrimitive.double, r.room.height!!, 1e-12, name)
            assertEquals(e["openings"]!!.jsonArray.map { it.jsonObject.let { o -> listOf(o["kind"]!!.jsonPrimitive.content, o["wallIndex"]!!.jsonPrimitive.int, o["offset"]!!.jsonPrimitive.double, o["width"]!!.jsonPrimitive.double, o["openFraction"]!!.jsonPrimitive.double) } },
                r.room.openings.map { listOf(it.kind.name, r.room.wallIds.indexOf(it.wallId), it.offset, it.width, it.openFraction) }, name)
        }
    }

    @Test fun planeCoordinateConvention() {
        val w = ScannedWall.fromPlane(1.0, -2.0, 1.0, 0.0, 4.0, 2.5)
        assertEquals(Vec2(-1.0, 2.0), w.start); assertEquals(Vec2(3.0, 2.0), w.end)
        assertEquals(Vec2(0.0, -1.0), ScannedOpening.at(ScannedOpening.Kind.Window, 0.0, 1.0, 1.0).center)
    }

    @Test fun cornerTraceProducesEditableRoom() {
        // 用户在地面上顺时针（俯视）走了一圈 4×3 m，带少量抖动
        var s = CaptureSession()
        for ((x, z) in listOf(0.0 to 0.0, 4.02 to 0.01, 3.98 to -3.0, 0.01 to -2.97)) s = s.addCorner(x, z)
        s = s.addCorner(9.0, 9.0).undoCorner()
        val r = s.convert()!!
        assertEquals(emptyList(), r.warnings.map { it.token })
        assertEquals(4, r.room.polygon.size); assertEquals(12.0, r.room.area, 0.3)
        assertTrue(Geometry.signedArea(r.room.polygon) > 0)
        val e = RoomEditor(r.room)
        e.addOpening(OpeningKind.window, e.room.walls[0].pointAtOffset(2.0))
        e.moveVertex(2, e.room.polygon[2] + Vec2(0.5, 0.0))
        assertEquals(emptyList(), RoomValidator.validate(e.room))
    }

    @Test fun fallsBackToWallPlanesWhenFewCorners() {
        val planes = listOf(ScannedWall.fromPlane(2.0, 0.0, 1.0, 0.0, 4.0, null), ScannedWall.fromPlane(4.0, 1.5, 0.0, 1.0, 3.0, null),
            ScannedWall.fromPlane(2.0, 3.0, -1.0, 0.0, 4.0, null), ScannedWall.fromPlane(0.0, 1.5, 0.0, -1.0, 3.0, null))
        val r = CaptureSession(cornersXZ = listOf(0.0 to 0.0), wallPlanes = planes).convert()!!
        assertEquals(4, r.room.polygon.size); assertEquals(12.0, r.room.area, 0.1)
    }
}
