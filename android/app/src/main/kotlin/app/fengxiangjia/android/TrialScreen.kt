package app.fengxiangjia.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.simulation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 计算结果 + 对应可视化（同源）。 */
class Computed(val result: SimulationResult, val vis: FlowVisualization?, val input: SimulationInput)

@Composable
fun rememberSimulation(model: AppModel, rev: Int): Pair<Computed?, Boolean> {
    val input = model.simulationInput()
    val hash = input?.let { ScenarioHash.compute(it) }
    var computed by remember { mutableStateOf<Computed?>(lastComputed) }
    LaunchedEffect(hash) {
        if (input == null) return@LaunchedEffect
        if (computed?.result?.scenarioHash == hash) return@LaunchedEffect
        val c = withContext(Dispatchers.Default) { val r = AirflowSolver.solve(input); Computed(r, FlowVisualization.of(r), input) }
        computed = c; lastComputed = c
    }
    val stale = computed == null || computed!!.result.scenarioHash != hash
    return computed to stale
}

/** 过期原因（#65）：与上次计算的输入比较，给出中文说明。 */
fun staleReasonText(c: Computed?, now: SimulationInput?): String? {
    if (c == null || now == null) return null
    val r = Staleness.reasons(c.result, c.input, now)
    return if (r.isEmpty()) null else r.joinToString("、") { it.label }
}
private var lastComputed: Computed? = null

@Composable
fun TrialScreen(model: AppModel, @Suppress("UNUSED_PARAMETER") rev: Int) {
    val room = model.room ?: return Text("没有房间")
    var mode by remember { mutableStateOf(EditMode.VIEW) }
    var selected by remember { mutableStateOf<EntityID?>(null) }
    var preview by remember { mutableStateOf<Room?>(null) }
    val (computed, stale) = rememberSimulation(model, rev)
    val shown = preview ?: room

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in EditMode.entries) FilterChip(mode == m, { mode = m; selected = null }, label = { Text(m.title) })
            TextButton(onClick = { model.undo() }, enabled = model.editor()?.canUndo == true) { Text("撤销") }
            TextButton(onClick = { model.redo() }, enabled = model.editor()?.canRedo == true) { Text("重做") }
        }
        Text(room.name + "  ·  " + hint(mode), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
        RoomCanvas(
            shown, if (preview == null) computed?.vis else null, stale || preview != null, mode, selected,
            onTap = { p, hit ->
                when {
                    hit is Hit.Item -> selected = hit.id
                    mode == EditMode.FAN -> model.edit { e -> selected = e.placeFan(p) }
                    mode == EditMode.OPENING -> model.edit { e -> selected = e.addOpening(OpeningKind.window, p) }
                    mode == EditMode.TARGET -> model.edit { e -> selected = e.addTarget("目标 ${room.targets.size + 1}", p, objective = TargetObjective.wantBreeze) }
                    mode == EditMode.FURNITURE -> model.edit { e -> selected = e.addFurniture(ObstacleKind.other, p) }
                    else -> selected = null
                }
            },
            onDrag = { hit, p ->
                // 拖动中只预览，不入撤销栈、不触发计算
                preview = when (hit) {
                    is Hit.Vertex -> shown.copy(polygon = shown.polygon.toMutableList().also { it[hit.index] = p })
                    is Hit.Item -> shown.copy(fans = shown.fans.map { if (it.id == hit.id) it.copy(position = p) else it },
                        targets = shown.targets.map { if (it.id == hit.id) it.copy(shape = (it.shape as? TargetShape.Circle)?.copy(center = p) ?: it.shape) else it },
                        obstacles = shown.obstacles.map { if (it.id == hit.id) it.copy(center = p) else it })
                }
            },
            onDragEnd = { hit, p ->
                preview = null
                when (hit) {
                    is Hit.Vertex -> model.edit { it.moveVertex(hit.index, p) }
                    is Hit.Item -> {
                        selected = hit.id
                        when {
                            room.fans.any { it.id == hit.id } -> model.edit { it.moveFan(hit.id, p) }
                            room.targets.any { it.id == hit.id } -> model.edit { it.moveTarget(hit.id, p) }
                            room.obstacles.any { it.id == hit.id } -> model.edit { it.moveFurniture(hit.id, p) }
                            room.openings.any { it.id == hit.id } -> model.edit { it.moveOpening(hit.id, p) }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Legend(stale)
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            SelectionPanel(model, room, selected) { selected = null }
            if (stale) staleReasonText(computed, model.simulationInput())?.let { Text("待更新原因：$it", color = Color(0xFFE5533D), style = MaterialTheme.typography.bodySmall) }
            ResultPanel(computed?.result, stale, room)
            var name by remember { mutableStateOf("") }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, label = { Text("方案名") }, modifier = Modifier.weight(1f), singleLine = true)
                Spacer(Modifier.width(8.dp))
                Button(onClick = { model.saveScenario(name.ifBlank { "方案 ${model.scenariosForRoom().size + 1}" }); name = "" }) { Text("保存方案") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("体感：", Modifier.padding(top = 12.dp))
                for ((r, t) in listOf(ComfortRating.tooWeak to "太弱", ComfortRating.justRight to "刚好", ComfortRating.tooStrong to "太强", ComfortRating.stuffy to "闷"))
                    TextButton(onClick = { model.addFeedback(r, null) }) { Text(t) }
            }
        }
    }
}

private fun hint(m: EditMode) = when (m) {
    EditMode.VIEW -> "拖动风扇试摆，风线实时重算"
    EditMode.SHAPE -> "拖动蓝点调整墙角"
    EditMode.OPENING -> "点墙边添加窗，选中后可改为门"
    EditMode.FAN -> "点空地放风扇，拖动移动"
    EditMode.TARGET -> "点击添加想吹到/不想直吹的区域"
    EditMode.FURNITURE -> "点击添加家具，拖动移动"
}

@Composable
private fun Legend(stale: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((s, t) in listOf(0.1 to "微弱", 0.5 to "微风", 1.0 to "明显", 1.5 to "强")) {
            Surface(color = speedColor(s), modifier = Modifier.size(12.dp)) {}; Text(t, style = MaterialTheme.typography.labelSmall)
        }
        if (stale) Text("计算中 / 待更新", color = Color(0xFFE5533D), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SelectionPanel(model: AppModel, room: Room, selected: EntityID?, onDeleted: () -> Unit) {
    val fan = room.fans.firstOrNull { it.id == selected }
    val op = room.openings.firstOrNull { it.id == selected }
    val target = room.targets.firstOrNull { it.id == selected }
    val ob = room.obstacles.firstOrNull { it.id == selected }
    if (fan != null) {
        Text("风扇 · ${Labels.fanKind(fan.kind)}", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState())) { for (k in FanKind.entries) FilterChip(fan.kind == k, { model.edit { it.setFanKind(fan.id, k) } }, label = { Text(Labels.fanKind(k)) }) }
        var yaw by remember(fan.id) { mutableFloatStateOf(fan.yawDegrees.toFloat()) }
        Text("朝向 ${yaw.toInt()}°")
        Slider(yaw, { yaw = it }, valueRange = 0f..355f, onValueChangeFinished = { model.edit { it.setFanYaw(fan.id, yaw.toDouble()) } })
        var pw by remember(fan.id) { mutableFloatStateOf(fan.power.toFloat()) }
        Text("档位 ${(pw * 10).toInt()}/10")
        Slider(pw, { pw = it }, valueRange = 0f..1f, steps = 9, onValueChangeFinished = { model.edit { it.setFanPower(fan.id, pw.toDouble()) } })
    }
    if (op != null) {
        Text("${Labels.opening(op.kind)} · 宽 %.2f m".format(op.width), style = MaterialTheme.typography.titleSmall)
        Row { for (k in OpeningKind.entries) FilterChip(op.kind == k, {
            model.edit { e -> e.removeItem(op.id); e.replace(e.room.copy(openings = e.room.openings + op.copy(kind = k))) }
        }, label = { Text(Labels.opening(k)) }) }
        var fr by remember(op.id) { mutableFloatStateOf(op.openFraction.toFloat()) }
        Text("开启 ${(fr * 100).toInt()}%")
        Slider(fr, { fr = it }, onValueChangeFinished = { model.edit { it.setOpeningFraction(op.id, fr.toDouble()) } })
    }
    if (target != null) {
        Text("目标区域 · ${target.name}", style = MaterialTheme.typography.titleSmall)
        Row {
            FilterChip(target.objective == TargetObjective.wantBreeze, { model.edit { it.setTargetObjective(target.id, TargetObjective.wantBreeze) } }, label = { Text("想吹到风") })
            FilterChip(target.objective == TargetObjective.avoidDirectDraft, { model.edit { it.setTargetObjective(target.id, TargetObjective.avoidDirectDraft) } }, label = { Text("避免直吹") })
        }
    }
    if (ob != null) {
        Text("家具", style = MaterialTheme.typography.titleSmall)
        Row { for (h in HeightClass.entries) FilterChip(ob.heightClass == h, {
            model.edit { e -> e.replace(e.room.copy(obstacles = e.room.obstacles.map { if (it.id == ob.id) it.copy(heightClass = h) else it })) }
        }, label = { Text(when (h) { HeightClass.low -> "矮（不挡风）"; HeightClass.medium -> "中"; HeightClass.tall -> "高" }) }) }
    }
    if (selected != null && (fan ?: op ?: target ?: ob) != null)
        TextButton(onClick = { model.edit { it.removeItem(selected) }; onDeleted() }) { Text("删除所选", color = Color(0xFFE5533D)) }
}

@Composable
private fun ResultPanel(result: SimulationResult?, stale: Boolean, room: Room) {
    SectionTitle(if (stale) "结果（待更新）" else "结果")
    if (result == null) { Text("计算中…"); return }
    when (val s = result.status) {
        is SimulationStatus.InvalidInput -> Text("户型无效，无法计算：${s.reasons.joinToString()}", color = Color(0xFFE5533D))
        SimulationStatus.Ok -> {
            val m = result.metrics!!
            Text("室内循环 ${(m.circulationCoverage * 100).toInt()}% · 对外换气 ${Labels.level(m.outdoorExchange)}" + if (m.crossVentilation) " · 穿堂风" else "")
            for (e in result.explanations) Text("• " + Labels.explanation(e, room), style = MaterialTheme.typography.bodySmall)
            Text("示意模型 ${result.modelVersion}，非工程级计算", style = MaterialTheme.typography.labelSmall)
        }
    }
}
