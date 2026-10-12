package app.fengxiangjia.core

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.domain.Target
import app.fengxiangjia.core.editor.RoomTemplate
import app.fengxiangjia.core.editor.Viewport
import app.fengxiangjia.core.geometry.*
import app.fengxiangjia.core.home.*
import app.fengxiangjia.core.library.SampleData
import app.fengxiangjia.core.persistence.DocumentCodec
import kotlin.test.*

private fun v(x: Number, y: Number) = Vec2(x.toDouble(), y.toDouble())

class GeometryTopologyTest {
    private val rect = listOf(v(0, 0), v(4, 0), v(4, 3), v(0, 3))

    @Test fun areaAndWalls() {
        val r = Room(name = "r", polygon = rect)
        assertEquals(12.0, r.area, 1e-9); assertEquals(4, r.walls.size)
        assertEquals(4.0, r.walls[0].length, 1e-9); assertEquals(-1.0, r.walls[0].outwardNormal.y, 1e-9)
    }

    @Test fun validRoomHasNoIssues() {
        val r0 = Room(name = "r", polygon = rect)
        val r = r0.copy(openings = listOf(Opening(wallId = r0.wallIds[0], kind = OpeningKind.window, offset = 1.0, width = 1.5)),
            fans = listOf(Fan(position = v(2, 1))))
        assertEquals(emptyList(), RoomValidator.validate(r))
    }

    @Test fun selfIntersectionDetected() {
        val bow = Room(name = "bow", polygon = listOf(v(0, 0), v(2, 2), v(2, 0), v(0, 2)))
        assertTrue(RoomValidator.validate(bow).any { it is RoomIssue.SelfIntersecting })
    }

    @Test fun lShapeIsSimple() {
        val l = listOf(v(0, 0), v(4, 0), v(4, 2), v(2, 2), v(2, 4), v(0, 4))
        assertNull(Geometry.selfIntersection(l)); assertEquals(12.0, Geometry.signedArea(l), 1e-9)
        assertFalse(Geometry.contains(l, v(3, 3))); assertTrue(Geometry.contains(l, v(1, 3)))
    }

    @Test fun clockwiseAndDegenerate() {
        assertTrue(RoomIssue.ClockwiseWinding in RoomValidator.validate(Room(name = "cw", polygon = rect.reversed())))
        val deg = Room(name = "d", polygon = listOf(v(0, 0), v(0.01, 0), v(4, 0), v(4, 3)))
        assertTrue(RoomIssue.DegenerateEdge(0) in RoomValidator.validate(deg))
        assertEquals(listOf(RoomIssue.TooFewVertices), RoomValidator.validate(Room(name = "x", polygon = listOf(v(0, 0), v(1, 0)))))
    }

    @Test fun openingMustStayWithinWall() {
        val r = Room(name = "r", polygon = rect)
        val o = Opening(wallId = r.wallIds[1], kind = OpeningKind.door, offset = 2.5, width = 0.9)
        assertTrue(RoomIssue.OpeningOutsideWall(o.id) in RoomValidator.validate(r.copy(openings = listOf(o))))
        val ghost = Opening(wallId = newId(), kind = OpeningKind.door, offset = 0.0, width = 0.9)
        assertEquals(listOf(RoomIssue.OpeningUnknownWall(ghost.id)), RoomValidator.validate(r.copy(openings = listOf(ghost))))
    }

    @Test fun overlappingOpeningsAndNarrow() {
        val r = Room(name = "r", polygon = rect)
        val a = Opening(wallId = r.wallIds[0], kind = OpeningKind.window, offset = 0.5, width = 1.5)
        val b = Opening(wallId = r.wallIds[0], kind = OpeningKind.window, offset = 1.5, width = 1.0)
        val c = Opening(wallId = r.wallIds[2], kind = OpeningKind.window, offset = 0.5, width = 0.05)
        val issues = RoomValidator.validate(r.copy(openings = listOf(a, b, c)))
        assertTrue(RoomIssue.OpeningsOverlap(a.id, b.id) in issues); assertTrue(RoomIssue.OpeningTooNarrow(c.id) in issues)
    }

    @Test fun fanOutsideRoomFlagged() {
        val f = Fan(position = v(5, 1))
        assertEquals(listOf(RoomIssue.ItemOutsideRoom(f.id)), RoomValidator.validate(Room(name = "r", polygon = rect, fans = listOf(f))))
    }

    private fun twoRooms(doorBetween: Boolean): Triple<Home, Room, Room> {
        val a0 = Room(name = "A", polygon = rect)
        val ops = mutableListOf(Opening(wallId = a0.wallIds[0], kind = OpeningKind.window, offset = 1.0, width = 1.0))
        if (doorBetween) ops += Opening(wallId = a0.wallIds[1], kind = OpeningKind.door, offset = 1.0, width = 0.9)
        val a = a0.copy(openings = ops)
        val b = Room(name = "B", polygon = listOf(v(4, 0), v(7, 0), v(7, 3), v(4, 3)), fans = listOf(Fan(position = v(5, 1))))
        return Triple(Home(name = "h", rooms = listOf(a, b)), a, b)
    }

    @Test fun openingConnectsIndoorOutdoor() {
        val (home, a, b) = twoRooms(true)
        assertEquals(listOf(ConnectionTarget.RoomRef(a.id), ConnectionTarget.Outdoor), Topology.connections(a.openings[0], a, home))
        assertEquals(listOf(ConnectionTarget.RoomRef(a.id), ConnectionTarget.RoomRef(b.id)), Topology.connections(a.openings[1], a, home))
        assertTrue(Topology.reachesOutdoor(b.id, home))
    }

    @Test fun noThroughWallWithoutOpening() { val (h, _, b) = twoRooms(false); assertFalse(Topology.reachesOutdoor(b.id, h)) }

    @Test fun closedOpeningBlocksPath() {
        val (h, a, b) = twoRooms(true)
        val h2 = h.updatingRoom(a.id) { r -> r.copy(openings = r.openings.mapIndexed { i, o -> if (i == 1) o.copy(openFraction = 0.0) else o }) }
        assertFalse(Topology.reachesOutdoor(b.id, h2))
    }

    @Test fun refreshConnections() {
        val (h0, _, b) = twoRooms(true)
        val h = Topology.refreshConnections(h0)
        assertEquals(ConnectionTarget.RoomRef(b.id), h.rooms[0].openings[1].connects.last())
        assertEquals(ConnectionTarget.Outdoor, h.rooms[0].openings[0].connects.last())
    }

    @Test fun normalizeWindingKeepsOpeningPosition() {
        val r0 = Room(name = "cw", polygon = rect.reversed())
        val r = r0.copy(openings = listOf(Opening(wallId = r0.wallIds[0], kind = OpeningKind.window, offset = 0.5, width = 1.0)))
        val before = r.segment(r.openings[0])!!
        val n = r.normalizedWinding()
        assertTrue(Geometry.signedArea(n.polygon) > 0)
        val after = n.segment(n.openings[0])!!
        fun key(p: Vec2) = "${Math.round(p.x * 1000)},${Math.round(p.y * 1000)}"
        assertEquals(setOf(key(before.first), key(before.second)), setOf(key(after.first), key(after.second)))
        assertEquals(emptyList(), RoomValidator.validate(n))
    }

    @Test fun swiftRoundingIsHalfAwayFromZero() {
        assertEquals(3.0, swiftRounded(2.5)); assertEquals(-3.0, swiftRounded(-2.5)); assertEquals(0.0, swiftRounded(0.49999999999999994))
    }

    @Test fun sampleDocumentMatchesSwift() {
        // document-v2.json = Swift SampleData + 方案/反馈/位置；去掉这些后应与 Kotlin SampleData 相同
        val swift = DocumentCodec.decode(Fixtures.text("document-v2.json"))
        val expected = swift.copy(home = swift.home.copy(location = null), scenarios = emptyList(), feedback = emptyList())
        assertEquals(expected, SampleData.sampleDocument())
    }

    @Test fun templatesAndViewport() {
        assertEquals(6, RoomTemplate.DEFAULT_L_SHAPE.polygon.size)
        assertTrue(Geometry.signedArea(RoomTemplate.DEFAULT_L_SHAPE.polygon) > 0)
        val vp = Viewport.fitting(rect, 400.0, 300.0)
        val p = v(1.3, 2.2); val back = vp.toWorld(vp.toScreen(p))
        assertEquals(p.x, back.x, 1e-9); assertEquals(p.y, back.y, 1e-9)
        val z = vp.zoomed(2.0, v(100, 100)); val w0 = vp.toWorld(v(100, 100)); val w1 = z.toWorld(v(100, 100))
        assertEquals(w0.x, w1.x, 1e-9); assertEquals(w0.y, w1.y, 1e-9)
    }
}

class MultiRoomTest {
    private fun makeHome(): List<Any> {
        val living = Room(name = "客厅", polygon = listOf(v(0, 0), v(4, 0), v(4, 3), v(0, 3)))
        val (h1, bed) = Home(name = "h", rooms = listOf(living)).addAdjacentRoom(living.id, 0, 3.0, "卧室", RoomPurpose.bedroom)
        val (h2, kitchen) = h1.addAdjacentRoom(living.id, 1, 2.0, "厨房", RoomPurpose.kitchen)
        return listOf(h2, living.id, bed, kitchen)
    }

    @Test fun adjacentRoomsValidAndShareWalls() {
        val (h, _, bed, kitchen) = makeHome(); h as Home
        assertEquals(emptyList(), HomeValidator.validate(h))
        val b = h.rooms.first { it.id == bed }
        assertTrue(Geometry.signedArea(b.polygon) > 0); assertEquals(12.0, b.area, 1e-9)
        assertEquals(6.0, h.rooms.first { it.id == kitchen }.area, 1e-9)
    }

    @Test fun overlapRejected() {
        val (h, living) = makeHome(); h as Home
        assertFailsWith<EditException> { h.addAdjacentRoom(living as String, 0, 1.0, "重叠") }
        val a = Room(name = "a", polygon = listOf(v(0, 0), v(2, 0), v(2, 2), v(0, 2)))
        val b = Room(name = "b", polygon = listOf(v(1, 1), v(3, 1), v(3, 3), v(1, 3)))
        assertTrue(HomeIssue.RoomsOverlap(a.id, b.id) in HomeValidator.validate(Home(name = "x", rooms = listOf(a, b))))
    }

    @Test fun interiorDoorConnectsRoomsAndExteriorWindow() {
        val (h0, living, bed) = makeHome(); h0 as Home; living as String; bed as String
        val door = Opening(wallId = h0.rooms.first { it.id == living }.wallIds[0], kind = OpeningKind.door, offset = 1.0, width = 0.9)
        var h = h0.updatingRoom(living) { it.copy(openings = listOf(door)) }
        h = h.updatingRoom(bed) { it.copy(openings = listOf(Opening(wallId = it.wallIds[2], kind = OpeningKind.window, offset = 1.0, width = 1.2))) }
        h = Topology.refreshConnections(h)
        assertEquals(listOf(ConnectionTarget.RoomRef(living), ConnectionTarget.RoomRef(bed)), h.rooms.first { it.id == living }.openings[0].connects)
        assertEquals(ConnectionTarget.Outdoor, h.rooms.first { it.id == bed }.openings[0].connects.last())
        assertTrue(Topology.reachesOutdoor(living, h))
        assertEquals(setOf(door.id, h.rooms.first { it.id == bed }.openings[0].id), h.openingsAffecting(bed).map { it.first.id }.toSet())
    }

    @Test fun chainWithoutDoorsStaysClosed() {
        val (h0, living, bed, kitchen) = makeHome(); h0 as Home
        val h = Topology.refreshConnections(h0.updatingRoom(kitchen as String) {
            it.copy(openings = listOf(Opening(wallId = it.wallIds[2], kind = OpeningKind.window, offset = 0.5, width = 1.0)))
        })
        assertTrue(Topology.reachesOutdoor(kitchen, h))
        assertFalse(Topology.reachesOutdoor(living as String, h)); assertFalse(Topology.reachesOutdoor(bed as String, h))
    }

    @Test fun removeRoomRefreshesConnections() {
        val (h0, living, bed) = makeHome(); h0 as Home; living as String
        val h = Topology.refreshConnections(h0.updatingRoom(living) {
            it.copy(openings = listOf(Opening(wallId = it.wallIds[0], kind = OpeningKind.door, offset = 1.0, width = 0.9)))
        })
        assertEquals(ConnectionTarget.RoomRef(bed as String), h.rooms.first { it.id == living }.openings[0].connects.last())
        assertEquals(ConnectionTarget.Outdoor, h.removeRoom(bed).rooms.first { it.id == living }.openings[0].connects.last())
    }

    @Test fun bearings() {
        assertEquals("南", Bearing.name(180.0)); assertEquals(0.0, Bearing.normalize(360.0))
        assertEquals(90.0, Bearing.compassBearing(v(1, 0), 0.0), 1e-9)
        assertNull(Bearing.circularMean(listOf(0.0, 180.0)))
        assertEquals(0.0, Bearing.circularMean(listOf(350.0, 10.0))!!.let { if (it > 180) it - 360 else it }, 1e-9)
    }
}

