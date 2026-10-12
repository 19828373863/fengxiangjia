package app.fengxiangjia.android

import android.graphics.Bitmap

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.domain.Target
import app.fengxiangjia.core.editor.FanPlacement
import app.fengxiangjia.core.geometry.segment
import app.fengxiangjia.core.simulation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.*

class Computed(val result:SimulationResult,val vis:FlowVisualization?,val input:SimulationInput)
@Composable
fun rememberSimulation(model:AppModel,rev:Int,retry:Int=0):Pair<Computed?,Boolean> {
    val input=model.simulationInput()
    val hash=input?.let { ScenarioHash.compute(it) }
    var computed by remember(model) { mutableStateOf<Computed?>(null) }
    LaunchedEffect(hash,retry) {
        if(input==null) { computed=null; return@LaunchedEffect }
        if(computed?.result?.scenarioHash==hash) return@LaunchedEffect
        try {
            val result=withContext(Dispatchers.Default) { val r=AirflowSolver.solve(input); Computed(r,FlowVisualization.of(r),input) }
            if(model.currentHash()==hash) computed=result
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { model.reportMessage("气流计算未完成，请点「结果」重试，户型与摆位已保留") }
    }
    return computed to (computed==null || computed?.result?.scenarioHash!=hash)
}
fun staleReasonText(c:Computed?,now:SimulationInput?):String? {
    if(c==null || now==null) return null
    return Staleness.reasons(c.result,c.input,now).takeIf { it.isNotEmpty() }?.joinToString("、") { it.label }
}
private enum class TrialSheet { ITEMS, PROPERTIES, RESULT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrialScreen(model:AppModel,rev:Int,onShareImage:(Bitmap)->Unit={}) {
    val room=model.room?:return Text("请先创建房间")
    var mode by rememberSaveable(room.id) { mutableStateOf(if(room.fans.isEmpty()) EditMode.FAN else EditMode.VIEW) }
    var selected by remember(room.id) { mutableStateOf<EntityID?>(null) }
    var vertex by remember(room.id) { mutableStateOf<Int?>(null) }
    var preview by remember(room.id) { mutableStateOf<Room?>(null) }
    var sheet by remember(room.id) { mutableStateOf<TrialSheet?>(null) }
    var saveDialog by rememberSaveable(room.id) { mutableStateOf(false) }
    var name by rememberSaveable(room.id) { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    val captureLayer=rememberGraphicsLayer()
    val scope=rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val (computed,stale)=rememberSimulation(model,rev,retry)
    val shown=preview?:room
    val selectedName=selected?.let { itemName(room,it) }?:vertex?.let { "墙角 ${it+1}" }
    val state=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    val colors=MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp,top=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(room.name,style=MaterialTheme.typography.headlineSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(if(room.fans.isEmpty()) "房间建好了，先放一台风扇" else "移动风扇，看见风的变化",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
            }
            IconButton(onClick={ model.undo(); preview=null },enabled=model.editor()?.canUndo==true) { Icon(Icons.Outlined.Refresh,contentDescription="撤销上次编辑") }
            TextButton(onClick={ model.redo() },enabled=model.editor()?.canRedo==true) { Text("重做") }
        }
        Column(Modifier.padding(horizontal=16.dp,vertical=4.dp),verticalArrangement=Arrangement.spacedBy(0.dp)) {
            for(row in listOf(listOf(EditMode.VIEW,EditMode.FAN,EditMode.OPENING),listOf(EditMode.SHAPE,EditMode.TARGET,EditMode.FURNITURE)))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    for(m in row) FilterChip(selected=mode==m,onClick={ mode=m; selected=null; vertex=null; preview=null },label={ Text(m.title) },modifier=Modifier.weight(1f))
                }
        }
        Text(hint(mode),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant,modifier=Modifier.padding(horizontal=20.dp,vertical=2.dp))
        if(room.fans.isEmpty()) TextButton(onClick={
            freePosition(room)?.let { p-> if(model.edit { selected=it.placeFan(p) }) { mode=EditMode.FAN; sheet=TrialSheet.PROPERTIES } }
        },modifier=Modifier.padding(horizontal=12.dp)) { Text("添加第一台风扇") }
        RoomCanvas(shown,if(preview==null) computed?.vis else null,stale || preview!=null,mode,selected,
            onTap={ p,hit->
                when(hit) {
                    is Hit.Item->{ selected=hit.id; vertex=null }
                    is Hit.Vertex->{ vertex=hit.index; selected=null }
                    null->{ vertex=null; selected=null
                        when(mode) {
                            EditMode.FAN->model.edit { selected=it.placeFan(p) }
                            EditMode.OPENING->model.edit { selected=it.addOpening(OpeningKind.window,p) }
                            EditMode.TARGET->model.edit { selected=it.addTarget("有风区域 ${room.targets.size+1}",p,objective=TargetObjective.wantBreeze) }
                            EditMode.FURNITURE->model.edit { selected=it.addFurniture(ObstacleKind.other,p) }
                            else->Unit
                        }
                    }
                }
            },
            onDrag={ hit,p->
                preview=when(hit) {
                    is Hit.Vertex->room.copy(polygon=room.polygon.toMutableList().also { it[hit.index]=p })
                    is Hit.Item->room.copy(fans=room.fans.map { if(it.id==hit.id) it.copy(position=p) else it },
                        targets=room.targets.map { t->if(t.id==hit.id) t.copy(shape=when(val s=t.shape) { is TargetShape.Circle->s.copy(center=p); is TargetShape.Polygon->s.copy(points=s.points.map { it-t.center+p }) }) else t },
                        obstacles=room.obstacles.map { if(it.id==hit.id) it.copy(center=p) else it },
                        openings=room.openings.map { o->if(o.id==hit.id) {
                            val e=model.editor(); runCatching { e!!.snapOpening(p,o.width,o.id) }.getOrNull()?.let { (wall,offset)->o.copy(wallId=wall,offset=offset) }?:o
                        } else o })
                }
            },
            onDragEnd={ hit,p->
                preview=null
                when(hit) {
                    is Hit.Vertex->{ vertex=hit.index; model.edit { it.moveVertex(hit.index,p) } }
                    is Hit.Item->{ selected=hit.id
                        when {
                            room.fans.any { it.id==hit.id }->model.edit { it.moveFan(hit.id,p) }
                            room.targets.any { it.id==hit.id }->model.edit { it.moveTarget(hit.id,p) }
                            room.obstacles.any { it.id==hit.id }->model.edit { it.moveFurniture(hit.id,p) }
                            room.openings.any { it.id==hit.id }->model.edit { it.moveOpening(hit.id,p) }
                        }
                    }
                }
            },modifier=Modifier.fillMaxWidth().weight(1f),onDragCancel={ preview=null },captureLayer=captureLayer)
        Legend(stale || preview!=null)
        if(selectedName!=null) Surface(color=colors.secondaryContainer,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp)) {
            Row(Modifier.padding(start=14.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(selectedName,style=MaterialTheme.typography.titleSmall); Text("可直接拖动，或打开属性精确调整",style=MaterialTheme.typography.bodySmall) }
                TextButton(onClick={ sheet=TrialSheet.PROPERTIES }) { Text("调整") }
                IconButton(onClick={ selected=null; vertex=null }) { Icon(Icons.Outlined.Close,"取消选择") }
            }
        } else {
            val metrics=if(!stale) computed?.result?.metrics else null
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(if(metrics==null) "正在更新气流" else "循环覆盖 ${(metrics.circulationCoverage*100).roundToInt()}%",style=MaterialTheme.typography.titleSmall)
                Text(if(metrics==null) "请稍候" else "对外换气 ${Labels.level(metrics.outdoorExchange)}",style=MaterialTheme.typography.bodyMedium,color=colors.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={ sheet=TrialSheet.ITEMS },modifier=Modifier.weight(1f)) { Text("物品") }
            OutlinedButton(onClick={ sheet=TrialSheet.RESULT },modifier=Modifier.weight(1f)) { Text("结果") }
            Button(onClick={ saveDialog=true },modifier=Modifier.weight(1.3f)) { Text("保存方案") }
        }
    }
    if(sheet!=null) ModalBottomSheet(onDismissRequest={ sheet=null },sheetState=state) {
        Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState()).imePadding().padding(start=24.dp,end=24.dp,bottom=28.dp)) {
            when(sheet) {
                TrialSheet.ITEMS->{
                    Text("房间里的物品",style=MaterialTheme.typography.headlineSmall)
                    Text("点击物品调整属性；新物品也可在图上放置",style=MaterialTheme.typography.bodyMedium,color=colors.onSurfaceVariant)
                    ItemList(room) { selected=it; vertex=null; sheet=TrialSheet.PROPERTIES }
                    SectionTitle("添加物品")
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={ val p=freePosition(room); if(p!=null && model.edit { selected=it.placeFan(p) }) { mode=EditMode.FAN; sheet=TrialSheet.PROPERTIES } else if(p==null) model.reportMessage("没有合适的空地，请在图上选择摆位") },modifier=Modifier.weight(1f)) { Text("加风扇") }
                        OutlinedButton(onClick={ val p=freePosition(room); if(p!=null && model.edit { selected=it.addTarget("有风区域 ${room.targets.size+1}",p,objective=TargetObjective.wantBreeze) }) { mode=EditMode.TARGET; sheet=TrialSheet.PROPERTIES } },modifier=Modifier.weight(1f)) { Text("加目标") }
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={ mode=EditMode.OPENING; sheet=null }) { Text("到图上加门窗") }
                        TextButton(onClick={ mode=EditMode.FURNITURE; sheet=null }) { Text("到图上加家具") }
                    }
                }
                TrialSheet.PROPERTIES->{
                    Text(selectedName?:"物品已移除",style=MaterialTheme.typography.headlineSmall)
                    SelectionPanel(model,room,selected,vertex) { selected=null; vertex=null; sheet=null }
                    TextButton(onClick={ sheet=TrialSheet.ITEMS }) { Text("查看其他物品") }
                }
                TrialSheet.RESULT->{
                    Text("这次试摆的结果",style=MaterialTheme.typography.headlineSmall)
                    ResultPanel(computed?.result,stale || preview!=null,room)
                    OutlinedButton(enabled=!stale && !exporting && preview==null,onClick={
                        val hash=model.currentHash()
                        exporting=true
                        scope.launch {
                            try {
                                val bitmap=captureLayer.toImageBitmap().asAndroidBitmap()
                                if(model.currentHash()==hash) { sheet=null; onShareImage(bitmap) }
                                else model.reportMessage("房间刚刚发生变化，请等待气流更新后再导出")
                            } catch(e:CancellationException) { throw e }
                            catch(e:Exception) { model.reportMessage("图片未能导出，当前摆位已保留，请重试") }
                            finally { exporting=false }
                        }
                    },modifier=Modifier.fillMaxWidth()) { Text("导出气流图片") }
                    if(stale) {
                        staleReasonText(computed,model.simulationInput())?.let { Text("等待更新：$it",color=colors.error) }
                        OutlinedButton(onClick={ retry++ }) { Text("重新计算") }
                    }
                    SectionTitle("实际体感")
                    Text("摆好风扇后记一次感受，记录只留在本机",style=MaterialTheme.typography.bodyMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        for((rating,label) in listOf(ComfortRating.tooWeak to "风太弱",ComfortRating.justRight to "刚好",ComfortRating.tooStrong to "太强",ComfortRating.stuffy to "还是闷"))
                            OutlinedButton(onClick={ model.addFeedback(rating,null) }) { Text(label) }
                    }
                }
                null->Unit
            }
        }
    }
    if(saveDialog) AlertDialog(onDismissRequest={ saveDialog=false },title={ Text("保存这次摆法") },text={
        Column { Text("以后可以恢复这台风扇的位置、朝向和档位。",style=MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(12.dp)); OutlinedTextField(name,{ name=it.take(60) },label={ Text("方案名称") },placeholder={ Text("例如：沙发微风") },singleLine=true) }
    },confirmButton={ TextButton(onClick={ if(model.saveScenario(name.ifBlank { "方案 ${model.scenariosForRoom().size+1}" })!=null) { name=""; saveDialog=false } }) { Text("保存方案") } },dismissButton={ TextButton(onClick={ saveDialog=false }) { Text("取消") } })
}
private fun hint(mode:EditMode)=when(mode) { EditMode.VIEW->"拖动风扇试摆 · 点选物品查看属性"; EditMode.FAN->"点空地放风扇 · 拖动移动位置"; EditMode.OPENING->"点墙边加窗 · 在属性里改成门"; EditMode.SHAPE->"拖动墙角调整户型 · 点墙角输入坐标"; EditMode.TARGET->"点房间内加目标 · 选择想有风或避免直吹"; EditMode.FURNITURE->"点房间内加家具 · 在属性里修改种类和尺寸" }
private fun itemName(r:Room,id:EntityID)=r.fans.indexOfFirst { it.id==id }.takeIf { it>=0 }?.let { "风扇 ${it+1} · ${Labels.fanKind(r.fans[it].kind)}" }
    ?:r.openings.find { it.id==id }?.let { Labels.opening(it.kind) }
    ?:r.targets.find { it.id==id }?.let { "目标 · ${it.name}" }?:r.obstacles.find { it.id==id }?.let { furnitureName(it.kind) }
private fun freePosition(r:Room):Vec2? {
    val minX=r.polygon.minOf { it.x }; val maxX=r.polygon.maxOf { it.x }; val minY=r.polygon.minOf { it.y }; val maxY=r.polygon.maxOf { it.y }
    return listOf(.5,.3,.7,.15,.85).flatMap { x->listOf(.5,.3,.7,.15,.85).map { y->Vec2(minX+(maxX-minX)*x,minY+(maxY-minY)*y) } }.firstOrNull { p->FanPlacement.check(p,r)==null && r.fans.all { it.position.distanceTo(p)>.5 } }
}
@Composable private fun Legend(stale:Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        for((speed,label) in listOf(.1 to "微弱",.5 to "微风",1.0 to "明显",1.5 to "强")) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) { Surface(color=speedColor(speed),shape=RoundedCornerShape(3.dp),modifier=Modifier.size(8.dp)) {}; Text(label,style=MaterialTheme.typography.labelSmall) }
        if(stale) CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=1.5.dp)
    }
}
@Composable private fun ItemList(room:Room,onSelect:(EntityID)->Unit) {
    val ids=room.fans.map { it.id }+room.openings.map { it.id }+room.targets.map { it.id }+room.obstacles.map { it.id }
    if(ids.isEmpty()) Text("房间还没有物品，先添加一台风扇",modifier=Modifier.padding(vertical=16.dp))
    for(id in ids) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text(itemName(room,id)?:"物品",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium); TextButton(onClick={ onSelect(id) }) { Text("调整") } }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SelectionPanel(model:AppModel,room:Room,selected:EntityID?,vertex:Int?,onDeleted:()->Unit) {
    val fan=room.fans.find { it.id==selected }; val op=room.openings.find { it.id==selected }
    val target=room.targets.find { it.id==selected }; val ob=room.obstacles.find { it.id==selected }
    if(fan!=null) {
        SectionTitle("类型")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { for(k in FanKind.entries) FilterChip(fan.kind==k,{ model.edit { it.setFanKind(fan.id,k) } },label={ Text(Labels.fanKind(k)) }) }
        var yaw by remember(fan.id,fan.yawDegrees) { mutableFloatStateOf(fan.yawDegrees.toFloat()) }
        SectionTitle("出风方向 · ${yaw.roundToInt()}°")
        Text("0° 向右，90° 向上，180° 向左，270° 向下",style=MaterialTheme.typography.bodySmall)
        Slider(yaw,{ yaw=it },valueRange=0f..355f,onValueChangeFinished={ model.edit { it.setFanYaw(fan.id,yaw.toDouble()) } })
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={ model.edit { it.setFanYaw(fan.id,fan.yawDegrees+15) } }) { Text("逆时针 15°") }
            OutlinedButton(onClick={ model.edit { it.setFanYaw(fan.id,fan.yawDegrees-15) } }) { Text("顺时针 15°") }
        }
        SectionTitle("风力档位")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { for((power,label) in listOf(.3 to "轻柔",.6 to "适中",1.0 to "强风")) FilterChip(abs(fan.power-power)<.05,{ model.edit { it.setFanPower(fan.id,power) } },label={ Text(label) }) }
        var power by remember(fan.id,fan.power) { mutableFloatStateOf(fan.power.toFloat()) }
        Text("${(power*10).roundToInt()} / 10",style=MaterialTheme.typography.bodySmall)
        Slider(power,{ power=it },steps=9,onValueChangeFinished={ model.edit { it.setFanPower(fan.id,power.toDouble()) } })
        PositionEditor(fan.position,roomOrigin(room)) { p->model.edit { it.moveFan(fan.id,p) } }
        for(t in room.targets) TextButton(onClick={ model.edit { it.aimFan(fan.id,t.center) } }) { Text("对准「${t.name}」") }
    }
    if(op!=null) {
        SectionTitle("门窗类型")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { for(k in OpeningKind.entries) FilterChip(op.kind==k,{ model.edit { e->e.replace(e.room.copy(openings=e.room.openings.map { if(it.id==op.id) it.copy(kind=k) else it })) } },label={ Text(Labels.opening(k)) }) }
        var fraction by remember(op.id,op.openFraction) { mutableFloatStateOf(op.openFraction.toFloat()) }
        SectionTitle("开启比例 · ${(fraction*100).roundToInt()}%")
        Slider(fraction,{ fraction=it },onValueChangeFinished={ model.edit { it.setOpeningFraction(op.id,fraction.toDouble()) } })
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick={ model.edit { it.setOpeningFraction(op.id,0.0) } }) { Text("关闭") }; OutlinedButton(onClick={ model.edit { it.setOpeningFraction(op.id,1.0) } }) { Text("完全打开") } }
        var width by remember(op.id,op.width) { mutableStateOf("%.2f".format(java.util.Locale.US,op.width)) }
        NumberEditor("宽度（米）",width,{ width=it }) { value->model.edit { e->e.replace(e.room.copy(openings=e.room.openings.map { if(it.id==op.id) it.copy(width=value) else it })) } }
        room.segment(op)?.let { (a,b)->PositionEditor((a+b)*.5,roomOrigin(room)) { p->model.edit { it.moveOpening(op.id,p) } } }
        if(op.kind==OpeningKind.window) {
            SectionTitle("窗外遮挡")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { for(level in ObstructionLevel.entries) FilterChip((op.exteriorObstruction?:ObstructionLevel.unknown)==level,{ model.edit { e->e.replace(e.room.copy(openings=e.room.openings.map { if(it.id==op.id) it.copy(exteriorObstruction=level) else it })) } },label={ Text(Labels.obstruction(level)) }) }
        }
    }
    if(target!=null) {
        SectionTitle("希望这里")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(target.objective==TargetObjective.wantBreeze,{ model.edit { it.setTargetObjective(target.id,TargetObjective.wantBreeze) } },label={ Text("吹到微风") }); FilterChip(target.objective==TargetObjective.avoidDirectDraft,{ model.edit { it.setTargetObjective(target.id,TargetObjective.avoidDirectDraft) } },label={ Text("避免直吹") }) }
        PositionEditor(target.center,roomOrigin(room)) { p->model.edit { it.moveTarget(target.id,p) } }
        if(target.shape is TargetShape.Circle) {
            val circle=target.shape as TargetShape.Circle
            var radius by remember(target.id,circle.radius) { mutableFloatStateOf(circle.radius.toFloat()) }
            SectionTitle("区域半径 · %.1f m".format(radius))
            Slider(radius,{ radius=it },valueRange=.2f..3f,onValueChangeFinished={ model.edit { e->e.replace(e.room.copy(targets=e.room.targets.map { if(it.id==target.id) it.copy(shape=circle.copy(radius=radius.toDouble())) else it })) } })
        }
    }
    if(ob!=null) {
        SectionTitle("家具种类")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { for(kind in ObstacleKind.entries) FilterChip(ob.kind==kind,{ model.edit { e->e.replace(e.room.copy(obstacles=e.room.obstacles.map { if(it.id==ob.id) it.copy(kind=kind) else it })) } },label={ Text(furnitureName(kind)) }) }
        SectionTitle("挡风高度")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { for(h in HeightClass.entries) FilterChip(ob.heightClass==h,{ model.edit { e->e.replace(e.room.copy(obstacles=e.room.obstacles.map { if(it.id==ob.id) it.copy(heightClass=h) else it })) } },label={ Text(when(h) { HeightClass.low->"矮"; HeightClass.medium->"中"; HeightClass.tall->"高" }) }) }
        PositionEditor(ob.center,roomOrigin(room)) { p->model.edit { it.moveFurniture(ob.id,p) } }
        var width by remember(ob.id,ob.size.x) { mutableStateOf("%.2f".format(java.util.Locale.US,ob.size.x)) }
        var depth by remember(ob.id,ob.size.y) { mutableStateOf("%.2f".format(java.util.Locale.US,ob.size.y)) }
        NumberEditor("家具宽度（米）",width,{ width=it }) { v->model.edit { e->e.replace(e.room.copy(obstacles=e.room.obstacles.map { if(it.id==ob.id) it.copy(size=Vec2(v,ob.size.y)) else it })) } }
        NumberEditor("家具深度（米）",depth,{ depth=it }) { v->model.edit { e->e.replace(e.room.copy(obstacles=e.room.obstacles.map { if(it.id==ob.id) it.copy(size=Vec2(ob.size.x,v)) else it })) } }
        var rotation by remember(ob.id,ob.rotationDegrees) { mutableFloatStateOf(ob.rotationDegrees.toFloat()) }
        SectionTitle("旋转 · ${rotation.roundToInt()}°")
        Slider(rotation,{ rotation=it },valueRange=0f..355f,onValueChangeFinished={ model.edit { e->e.replace(e.room.copy(obstacles=e.room.obstacles.map { if(it.id==ob.id) it.copy(rotationDegrees=rotation.toDouble()) else it })) } })
    }
    if(vertex!=null && vertex in room.polygon.indices) {
        Text("调整墙角不会自动移动屋内物品；请保持物品在房间内。",style=MaterialTheme.typography.bodyMedium)
        PositionEditor(room.polygon[vertex],roomOrigin(room)) { p->model.edit { it.moveVertex(vertex,p) } }
    }
    if(selected!=null && (fan?:op?:target?:ob)!=null) {
        Spacer(Modifier.height(16.dp))
        TextButton(onClick={ if(model.edit { it.removeItem(selected) }) onDeleted() },colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("移除${itemName(room,selected)}") }
        Text("可用试摆页的撤销恢复这次移除",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
private fun roomOrigin(room:Room)=Vec2(room.polygon.minOf { it.x },room.polygon.minOf { it.y })
@Composable private fun PositionEditor(worldPosition:Vec2,origin:Vec2,onMove:(Vec2)->Boolean) {
    val position=worldPosition-origin
    SectionTitle("位置（米）")
    Text("平面图左下为原点，向右为 X，向上为 Y",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    var x by remember(position.x) { mutableStateOf("%.2f".format(java.util.Locale.US,position.x)) }
    var y by remember(position.y) { mutableStateOf("%.2f".format(java.util.Locale.US,position.y)) }
    var error by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(x,{ x=it; error=null },label={ Text("X") },singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.weight(1f))
        OutlinedTextField(y,{ y=it; error=null },label={ Text("Y") },singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.weight(1f))
    }
    error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        for((label,delta) in listOf("左移" to Vec2(-.1,0.0),"右移" to Vec2(.1,0.0),"上移" to Vec2(0.0,.1),"下移" to Vec2(0.0,-.1))) TextButton(onClick={ onMove(worldPosition+delta) },modifier=Modifier.weight(1f),contentPadding=PaddingValues(4.dp)) { Text(label) }
    }
    OutlinedButton(onClick={ val a=x.toDoubleOrNull(); val b=y.toDoubleOrNull(); if(a==null || b==null || !a.isFinite() || !b.isFinite()) error="请输入有效的米数" else if(!onMove(Vec2(a,b)+origin)) error="这个位置不可用，请调整坐标或返回图上拖动" },modifier=Modifier.fillMaxWidth()) { Text("应用位置") }
}
@Composable private fun NumberEditor(label:String,text:String,onText:(String)->Unit,onApply:(Double)->Boolean) {
    var error by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(top=8.dp)) {
        OutlinedTextField(text,{ onText(it); error=null },label={ Text(label) },keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.weight(1f),isError=error!=null)
        TextButton(onClick={ val value=text.toDoubleOrNull(); if(value==null || !value.isFinite() || value<=0 || value>30) error="请输入 0 到 30 之间的米数" else if(!onApply(value)) error="尺寸不适合当前房间，请调整后重试" }) { Text("应用") }
    }
    error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
}
@Composable private fun ResultPanel(result:SimulationResult?,stale:Boolean,room:Room) {
    if(stale) Text("输入已改变，结果正在更新；以下旧结果暂不用于判断。",color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(vertical=12.dp))
    if(result==null) { Text("正在计算当前房间的气流，请稍候。",Modifier.padding(vertical=16.dp)); return }
    when(val status=result.status) {
        is SimulationStatus.InvalidInput->Text("这个户型暂时无法计算，请检查门窗或屋内物品的位置。",color=MaterialTheme.colorScheme.error)
        SimulationStatus.Ok->{
            val metrics=result.metrics!!
            SectionTitle("室内循环覆盖 ${(metrics.circulationCoverage*100).roundToInt()}%")
            Text("表示房间内形成空气流动的范围，不代表降温百分比。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            SectionTitle("对外换气 ${Labels.level(metrics.outdoorExchange)}"+(if(metrics.crossVentilation) " · 有穿堂风" else ""))
            for(e in result.explanations) Text(Labels.explanation(e,room),style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(vertical=6.dp))
            Spacer(Modifier.height(12.dp)); Text("风线是简化模拟，用于比较摆法。实际风感还会受到风扇性能、房间高度与现场环境影响。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
