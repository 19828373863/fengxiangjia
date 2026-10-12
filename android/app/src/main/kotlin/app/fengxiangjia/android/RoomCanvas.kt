package app.fengxiangjia.android

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.domain.Target
import app.fengxiangjia.core.editor.Viewport
import app.fengxiangjia.core.geometry.Geometry
import app.fengxiangjia.core.geometry.contains
import app.fengxiangjia.core.geometry.segment
import app.fengxiangjia.core.simulation.FlowVisualization
import kotlin.math.*

enum class EditMode(val title: String) { VIEW("试摆"), SHAPE("户型"), OPENING("门窗"), FAN("风扇"), TARGET("目标"), FURNITURE("家具") }
sealed interface Hit { data class Vertex(val index: Int) : Hit; data class Item(val id: EntityID) : Hit }
fun speedColor(speed: Double): Color {
    val t=FlowVisualization.normalized(speed).toFloat()
    return when { t<.33f->lerp(Color(0xFF588FA8),Color(0xFF408B70),t/.33f); t<.66f->lerp(Color(0xFF408B70),Color(0xFFB79442),(t-.33f)/.33f); else->lerp(Color(0xFFB79442),Color(0xFFBC6550),(t-.66f)/.34f) }
}
private fun lerp(a:Color,b:Color,t:Float)=Color(a.red+(b.red-a.red)*t,a.green+(b.green-a.green)*t,a.blue+(b.blue-a.blue)*t,1f)

/** Previews never restart a gesture or change its world coordinate system. */
@Composable
fun RoomCanvas(room:Room,vis:FlowVisualization?,stale:Boolean,mode:EditMode,selected:EntityID?,
    onTap:(Vec2,Hit?)->Unit,onDrag:(Hit,Vec2)->Unit,onDragEnd:(Hit,Vec2)->Unit,
    modifier:Modifier=Modifier,interactive:Boolean=true,onDragCancel:()->Unit={},captureLayer:GraphicsLayer?=null) {
    val density=LocalDensity.current.density
    val textDensity=density*LocalDensity.current.fontScale
    val colors=MaterialTheme.colorScheme
    val roomNow by rememberUpdatedState(room)
    val tapNow by rememberUpdatedState(onTap)
    val dragNow by rememberUpdatedState(onDrag)
    val endNow by rememberUpdatedState(onDragEnd)
    val cancelNow by rememberUpdatedState(onDragCancel)
    var size by remember { mutableStateOf(IntSize(1,1)) }
    var locked by remember(room.id) { mutableStateOf<Viewport?>(null) }
    val viewport=locked?:fitCanvas(room.polygon,size,48f*density)
    val vpNow by rememberUpdatedState(viewport)
    var pressed by remember(room.id) { mutableStateOf<EntityID?>(null) }
    val paint=remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign=Paint.Align.CENTER } }
    val gestures=if(!interactive) Modifier else Modifier
        .pointerInput(room.id,mode) { detectTapGestures { o->val vp=vpNow; tapNow(vp.toWorld(Vec2(o.x.toDouble(),o.y.toDouble())),canvasHit(roomNow,vp,o,mode,24f*density)) } }
        .pointerInput(room.id,mode) {
            var hit:Hit?=null; var start=Offset.Zero; var pointer=Offset.Zero
            var item=Vec2.ZERO; var destination=Vec2.ZERO; var frozen=vpNow
            detectDragGestures(
                onDragStart={ o->
                    frozen=vpNow; hit=canvasHit(roomNow,frozen,o,mode,28f*density); start=o; pointer=o
                    item=when(val h=hit) {
                        is Hit.Vertex->roomNow.polygon[h.index]
                        is Hit.Item->roomNow.fans.find { it.id==h.id }?.position?:roomNow.targets.find { it.id==h.id }?.center
                            ?:roomNow.obstacles.find { it.id==h.id }?.center
                            ?:roomNow.openings.find { it.id==h.id }?.let { roomNow.segment(it)?.let { (a,b)->(a+b)*.5 } }
                            ?:frozen.toWorld(Vec2(o.x.toDouble(),o.y.toDouble()))
                        null->Vec2.ZERO
                    }
                    destination=item
                    if(hit!=null) { locked=frozen; pressed=(hit as? Hit.Item)?.id }
                },
                onDrag={ change,delta->hit?.let { h->change.consume(); pointer+=delta; val shift=pointer-start
                    destination=item+Vec2(shift.x/frozen.scale,-shift.y/frozen.scale); dragNow(h,destination) } },
                onDragEnd={ hit?.let { endNow(it,destination) }; hit=null; locked=null; pressed=null },
                onDragCancel={ hit=null; locked=null; pressed=null; cancelNow() }
            )
        }
    Canvas(modifier.onSizeChanged { size=it }.then(gestures).drawWithContent {
        if(captureLayer==null) drawContent() else {
            captureLayer.record { this@drawWithContent.drawContent() }
            drawLayer(captureLayer)
        }
    }.semantics { contentDescription="${room.name}平面图，${room.fans.size}台风扇，${room.openings.size}个门窗，${room.targets.size}个目标区域" }) {
        if(room.polygon.size<3 || size.width<=1 || size.height<=1) return@Canvas
        drawRect(colors.background)
        fun screen(p:Vec2)=viewport.toScreen(p).let { Offset(it.x.toFloat(),it.y.toFloat()) }
        val path=Path().apply { room.polygon.forEachIndexed { i,p->val v=screen(p); if(i==0) moveTo(v.x,v.y) else lineTo(v.x,v.y) }; close() }
        drawPath(path,colors.surface)
        clipPath(path) {
            val minX=room.polygon.minOf { it.x }; val maxX=room.polygon.maxOf { it.x }
            val minY=room.polygon.minOf { it.y }; val maxY=room.polygon.maxOf { it.y }
            val step=if(max(maxX-minX,maxY-minY)>12) 1.0 else .5
            var x=floor(minX/step)*step
            while(x<=maxX) { drawLine(colors.outlineVariant.copy(alpha=.35f),screen(Vec2(x,minY)),screen(Vec2(x,maxY)),density*.5f); x+=step }
            var y=floor(minY/step)*step
            while(y<=maxY) { drawLine(colors.outlineVariant.copy(alpha=.35f),screen(Vec2(minX,y)),screen(Vec2(maxX,y)),density*.5f); y+=step }
            for(ob in room.obstacles) {
                val c=screen(ob.center); val w=(ob.size.x*viewport.scale).toFloat(); val h=(ob.size.y*viewport.scale).toFloat()
                rotate(-ob.rotationDegrees.toFloat(),c) {
                    drawRoundRect(colors.secondaryContainer,Offset(c.x-w/2,c.y-h/2),Size(w,h),CornerRadius(3f*density))
                    if(ob.id==selected) drawRoundRect(colors.primary,Offset(c.x-w/2,c.y-h/2),Size(w,h),CornerRadius(3f*density),style=Stroke(2f*density))
                    if(ob.kind==ObstacleKind.sofa) drawLine(colors.onSecondaryContainer.copy(alpha=.25f),Offset(c.x-w/2+4f*density,c.y-h/2+5f*density),Offset(c.x+w/2-4f*density,c.y-h/2+5f*density),2f*density)
                    if(w>36f*density && h>15f*density) label(furnitureName(ob.kind),c,colors.onSecondaryContainer,10f*textDensity,paint)
                }
            }
            for(t in room.targets) drawTarget(t,::screen,viewport.scale.toFloat(),t.id==selected,density,colors.primary,colors.error)
            vis?.let { v->
                for(s in v.streamlines) for(i in 1 until s.points.size) {
                    val col=if(stale) colors.outline else speedColor(s.speeds[i])
                    drawLine(col.copy(alpha=if(stale) .3f else .72f),screen(s.points[i-1]),screen(s.points[i]),1.2f*density,cap=StrokeCap.Round)
                }
                if(!stale) for(a in v.arrows) {
                    val c=screen(a.position); val d=Offset(a.direction.x.toFloat(),-a.direction.y.toFloat()); val end=c+d*4f*density
                    drawLine(speedColor(a.speed).copy(alpha=.72f),c-d*4f*density,end,.9f*density)
                    drawLine(speedColor(a.speed).copy(alpha=.72f),end,end-d*3f*density+Offset(-d.y,d.x)*2f*density,.9f*density)
                    drawLine(speedColor(a.speed).copy(alpha=.72f),end,end-d*3f*density-Offset(-d.y,d.x)*2f*density,.9f*density)
                }
            }
        }
        drawPath(path,colors.onSurface.copy(alpha=.85f),style=Stroke(3f*density,cap=StrokeCap.Round,join=StrokeJoin.Round))
        for(o in room.openings) room.segment(o)?.let { (a,b)->
            val col=if(o.kind==OpeningKind.window) colors.tertiary else colors.secondary
            drawLine(colors.surface,screen(a),screen(b),6f*density)
            drawLine(col.copy(alpha=if(o.openFraction==0.0) .4f else 1f),screen(a),screen(b),(if(o.id==selected) 5 else 3)*density)
            drawCircle(col,2.5f*density,screen(a)); drawCircle(col,2.5f*density,screen(b))
        }
        for((index,f) in room.fans.withIndex()) {
            val c=screen(f.position); val sel=f.id==selected || f.id==pressed
            if(sel) drawCircle(colors.primary.copy(alpha=.14f),27f*density,c)
            val yaw=Math.toRadians(f.yawDegrees); val d=Offset(cos(yaw).toFloat(),-sin(yaw).toFloat()); val end=c+d*34f*density
            if(f.kind!=FanKind.ceiling) {
                drawLine(colors.primary,c,end,2.5f*density,cap=StrokeCap.Round)
                drawLine(colors.primary,end,end-d*7f*density+Offset(-d.y,d.x)*5f*density,2.5f*density,cap=StrokeCap.Round)
                drawLine(colors.primary,end,end-d*7f*density-Offset(-d.y,d.x)*5f*density,2.5f*density,cap=StrokeCap.Round)
            }
            drawCircle(colors.primary,15f*density,c); drawCircle(colors.onPrimary.copy(alpha=.8f),11f*density,c,style=Stroke(density))
            for(angle in listOf(0.0,120.0,240.0)) { val r=Math.toRadians(angle+f.yawDegrees); drawLine(colors.onPrimary,c,c+Offset(cos(r).toFloat(),sin(r).toFloat())*7f*density,4f*density,cap=StrokeCap.Round) }
            drawCircle(colors.primary,2f*density,c)
            val name="风扇 ${index+1}"
            val labelSize=10f*textDensity
            paint.textSize=labelSize
            val halfWidth=paint.measureText(name)/2+3f*density
            val halfHeight=(paint.descent()-paint.ascent())/2+2f*density
            val verticalGap=19f*density+halfHeight
            val horizontalGap=19f*density+halfWidth
            val candidates=listOf(c+Offset(0f,verticalGap),c-Offset(0f,verticalGap),c-Offset(horizontalGap,0f),c+Offset(horizontalGap,0f))
            val labelCenter=candidates.firstOrNull { center->
                listOf(Offset(-halfWidth,-halfHeight),Offset(halfWidth,-halfHeight),Offset(halfWidth,halfHeight),Offset(-halfWidth,halfHeight)).all { corner->
                    val point=center+corner
                    room.contains(viewport.toWorld(Vec2(point.x.toDouble(),point.y.toDouble())))
                }
            }?:candidates[1]
            drawRoundRect(colors.surface,labelCenter-Offset(halfWidth,halfHeight),Size(halfWidth*2,halfHeight*2),CornerRadius(3f*density))
            label(name,labelCenter,colors.onSurface,labelSize,paint)
        }
        if(mode==EditMode.SHAPE && interactive) room.polygon.forEachIndexed { i,p->val c=screen(p); drawCircle(colors.surface,10f*density,c); drawCircle(colors.primary,8f*density,c); label("${i+1}",c,colors.onPrimary,10f*textDensity,paint) }
        if(size.height>100f*density) {
            val minX=room.polygon.minOf { it.x }; val maxX=room.polygon.maxOf { it.x }; val minY=room.polygon.minOf { it.y }; val maxY=room.polygon.maxOf { it.y }
            label("%.1f m".format(maxX-minX),screen(Vec2((minX+maxX)/2,minY))+Offset(0f,22f*density),colors.onSurfaceVariant,11f*textDensity,paint)
            label("%.1f m".format(maxY-minY),screen(Vec2(minX,(minY+maxY)/2))+Offset(-22f*density,0f),colors.onSurfaceVariant,11f*textDensity,paint)
        }
    }
}
internal fun fitCanvas(polygon:List<Vec2>,size:IntSize,padding:Float):Viewport {
    val minX=polygon.minOfOrNull { it.x }?:0.0; val maxX=polygon.maxOfOrNull { it.x }?:1.0; val minY=polygon.minOfOrNull { it.y }?:0.0; val maxY=polygon.maxOfOrNull { it.y }?:1.0
    val scale=min((size.width-2*padding).coerceAtLeast(1f)/(maxX-minX).coerceAtLeast(.1),(size.height-2*padding).coerceAtLeast(1f)/(maxY-minY).coerceAtLeast(.1))
    return Viewport(scale,Vec2(size.width/2.0-(minX+maxX)/2*scale,size.height/2.0+(minY+maxY)/2*scale))
}
internal fun canvasHit(room:Room,vp:Viewport,offset:Offset,mode:EditMode,radius:Float):Hit? {
    val p=vp.toWorld(Vec2(offset.x.toDouble(),offset.y.toDouble())); val r=radius/vp.scale
    if(mode==EditMode.SHAPE) return room.polygon.withIndex().minByOrNull { it.value.distanceTo(p) }?.takeIf { it.value.distanceTo(p)<=r }?.let { Hit.Vertex(it.index) }
    if(mode in setOf(EditMode.VIEW,EditMode.FAN)) room.fans.minByOrNull { it.position.distanceTo(p) }?.takeIf { it.position.distanceTo(p)<=r }?.let { return Hit.Item(it.id) }
    if(mode in setOf(EditMode.VIEW,EditMode.TARGET)) room.targets.minByOrNull { it.center.distanceTo(p) }?.takeIf { it.center.distanceTo(p)<=r }?.let { return Hit.Item(it.id) }
    if(mode in setOf(EditMode.VIEW,EditMode.FURNITURE)) room.obstacles.firstOrNull { it.contains(p) }?.let { return Hit.Item(it.id) }
    if(mode in setOf(EditMode.VIEW,EditMode.OPENING)) room.openings.minByOrNull { o->room.segment(o)?.let { (a,b)->Geometry.distanceToSegment(p,a,b) }?:Double.MAX_VALUE }?.takeIf { o->room.segment(o)?.let { (a,b)->Geometry.distanceToSegment(p,a,b)<=r }==true }?.let { return Hit.Item(it.id) }
    return null
}
private fun DrawScope.label(text:String,center:Offset,color:Color,textSize:Float,paint:Paint) { paint.color=color.toArgb(); paint.textSize=textSize; drawContext.canvas.nativeCanvas.drawText(text,center.x,center.y-(paint.ascent()+paint.descent())/2,paint) }
private fun DrawScope.drawTarget(t:Target,w2s:(Vec2)->Offset,scale:Float,selected:Boolean,density:Float,breeze:Color,draft:Color) {
    val col=if(t.objective==TargetObjective.wantBreeze) breeze else draft
    val stroke=Stroke((if(selected) 2 else 1)*density,pathEffect=PathEffect.dashPathEffect(floatArrayOf(5f*density,4f*density)))
    when(val s=t.shape) {
        is TargetShape.Circle->{ drawCircle(col.copy(alpha=.09f),(s.radius*scale).toFloat(),w2s(s.center)); drawCircle(col,(s.radius*scale).toFloat(),w2s(s.center),style=stroke) }
        is TargetShape.Polygon->{ val path=Path().apply { s.points.forEachIndexed { i,v->val p=w2s(v); if(i==0) moveTo(p.x,p.y) else lineTo(p.x,p.y) }; close() }; drawPath(path,col.copy(alpha=.09f)); drawPath(path,col,style=stroke) }
    }
}
fun furnitureName(k:ObstacleKind)=when(k) { ObstacleKind.bed->"床"; ObstacleKind.sofa->"沙发"; ObstacleKind.table->"桌子"; ObstacleKind.wardrobe->"衣柜"; ObstacleKind.desk->"书桌"; ObstacleKind.other->"家具" }
