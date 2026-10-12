package app.fengxiangjia.core

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.editor.*
import app.fengxiangjia.core.geometry.*
import kotlin.test.*

class EditorTest {
    private fun ed() = RoomEditor(Room(name = "r", polygon = RoomTemplate.DEFAULT_RECTANGLE.polygon))

    @Test fun undoRedoVertexMove() {
        val e = ed(); val before = e.room
        e.moveVertex(2, Vec2(4.52, 3.01))
        assertEquals(Vec2(4.5, 3.0), e.room.polygon[2]); assertTrue(e.canUndo)
        e.undo(); assertEquals(before, e.room); e.redo(); assertEquals(Vec2(4.5, 3.0), e.room.polygon[2])
    }

    @Test fun invalidVertexMoveRejectedAndNotRecorded() {
        val e = ed()
        assertFailsWith<EditError.InvalidGeometry> { e.moveVertex(0, Vec2(4.0, 3.5)) }
        assertFalse(e.canUndo)
    }

    @Test fun openingSnapsToNearestWallAndRejectsOverlap() {
        val e = ed()
        val id = e.addOpening(OpeningKind.window, Vec2(2.0, -0.2))
        val o = e.room.openings.single { it.id == id }
        assertEquals(e.room.wallIds[0], o.wallId); assertEquals(1.4, o.offset, 1e-9)
        assertFailsWith<EditError.OverlapsOpening> { e.addOpening(OpeningKind.window, Vec2(2.2, 0.1)) }
        assertFailsWith<EditError.NoWallNearby> { e.addOpening(OpeningKind.door, Vec2(2.0, 1.5)) }
        assertEquals(emptyList(), RoomValidator.validate(e.room))
    }

    @Test fun fanPlacementRules() {
        val e = ed()
        assertFailsWith<EditError.TooCloseToWall> { e.placeFan(Vec2(0.1, 1.0)) }
        assertFailsWith<EditError.OutsideRoom> { e.placeFan(Vec2(9.0, 1.0)) }
        val id = e.placeFan(Vec2(2.0, 1.5))
        e.aimFan(id, Vec2(2.0, 3.0)); assertEquals(90.0, e.room.fans[0].yawDegrees)
        e.setFanYaw(id, -7.0); assertEquals(355.0, e.room.fans[0].yawDegrees)
        e.setFanPower(id, 0.87); assertEquals(0.9, e.room.fans[0].power, 1e-12)
        e.removeItem(id); assertTrue(e.room.fans.isEmpty()); e.undo(); assertEquals(1, e.room.fans.size)
    }

    @Test fun templateResetsOpenings() {
        val e = ed(); e.addOpening(OpeningKind.door, Vec2(4.0, 1.0))
        e.apply(RoomTemplate.DEFAULT_L_SHAPE)
        assertEquals(6, e.room.polygon.size); assertEquals(6, e.room.wallIds.size); assertTrue(e.room.openings.isEmpty())
    }

    @Test fun targetsMoveAndObjective() {
        val e = ed()
        val t = e.addTarget("床", Vec2(1.0, 1.0), 9.0, TargetObjective.wantBreeze)
        assertEquals(3.0, (e.room.targets[0].shape as TargetShape.Circle).radius)
        e.moveTarget(t, Vec2(2.0, 2.0)); assertEquals(Vec2(2.0, 2.0), e.room.targets[0].center)
        e.setTargetObjective(t, TargetObjective.avoidDirectDraft); assertEquals(TargetObjective.avoidDirectDraft, e.room.targets[0].objective)
    }
}
