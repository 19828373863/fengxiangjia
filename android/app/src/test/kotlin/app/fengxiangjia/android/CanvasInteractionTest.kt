package app.fengxiangjia.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.domain.Target
import app.fengxiangjia.core.editor.Viewport
import app.fengxiangjia.core.library.SampleData
import org.junit.Assert.*
import org.junit.Test

class CanvasInteractionTest {
    @Test fun largeRoomFitsInsidePhoneInsteadOfCroppingAtMinimumEditorZoom() {
        val polygon=listOf(Vec2(0.0,0.0),Vec2(30.0,0.0),Vec2(30.0,30.0),Vec2(0.0,30.0))
        val viewport=fitCanvas(polygon,IntSize(360,240),36f)
        for(p in polygon) {
            val screen=viewport.toScreen(p)
            assertTrue(screen.x in 36.0..324.0)
            assertTrue(screen.y in 36.0..204.0)
            assertEquals(p.x,viewport.toWorld(screen).x,1e-9)
            assertEquals(p.y,viewport.toWorld(screen).y,1e-9)
        }
    }
    @Test fun doorCanBeSelectedNearEitherEndNotOnlyItsMidpoint() {
        val r=SampleData.sampleDocument().home.rooms.first()
        val vp=Viewport(100.0,Vec2(0.0,400.0))
        val window=r.openings.first()
        for(point in listOf(Vec2(1.52,.03),Vec2(3.48,-.03))) {
            val screen=vp.toScreen(point)
            assertEquals(Hit.Item(window.id),canvasHit(r,vp,Offset(screen.x.toFloat(),screen.y.toFloat()),EditMode.OPENING,24f))
        }
    }
    @Test fun furnitureAndTargetAreReachableInDefaultTrialMode() {
        val r=SampleData.sampleDocument().home.rooms.first()
        val vp=Viewport(100.0,Vec2(0.0,400.0))
        val center=r.targets.first().center
        val p=vp.toScreen(center)
        assertEquals(Hit.Item(r.targets.first().id),canvasHit(r,vp,Offset(p.x.toFloat(),p.y.toFloat()),EditMode.VIEW,24f))
        val table=r.obstacles.first { it.kind==ObstacleKind.table }
        val q=vp.toScreen(table.center)
        assertEquals(Hit.Item(table.id),canvasHit(r,vp,Offset(q.x.toFloat(),q.y.toFloat()),EditMode.VIEW,24f))
    }
    @Test fun nearbyTargetsDoNotStealFanTouchTarget() {
        val r=SampleData.sampleDocument().home.rooms.first()
        val f=r.fans.first()
        val withTarget=r.copy(targets=listOf(Target(name="有风区",shape=TargetShape.Circle(f.position,.6),objective=TargetObjective.wantBreeze)))
        val vp=Viewport(100.0,Vec2(0.0,400.0)); val p=vp.toScreen(f.position)
        assertEquals(Hit.Item(f.id),canvasHit(withTarget,vp,Offset(p.x.toFloat(),p.y.toFloat()),EditMode.VIEW,24f))
        assertEquals(Hit.Item(withTarget.targets.first().id),canvasHit(withTarget,vp,Offset(p.x.toFloat(),p.y.toFloat()),EditMode.TARGET,24f))
    }
    @Test fun screenTapUsesPhysicalDensityRadius() {
        val r=SampleData.sampleDocument().home.rooms.first()
        val f=r.fans.first(); val vp=Viewport(100.0,Vec2(0.0,400.0)); val p=vp.toScreen(f.position)
        assertEquals(Hit.Item(f.id),canvasHit(r,vp,Offset(p.x.toFloat()+50f,p.y.toFloat()),EditMode.FAN,72f))
        assertNull(canvasHit(r,vp,Offset(p.x.toFloat()+90f,p.y.toFloat()),EditMode.FAN,72f))
    }
}
