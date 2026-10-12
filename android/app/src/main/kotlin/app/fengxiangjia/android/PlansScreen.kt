package app.fengxiangjia.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fengxiangjia.core.domain.EntityID
import app.fengxiangjia.core.domain.Environment
import app.fengxiangjia.core.domain.Home
import app.fengxiangjia.core.domain.Room
import app.fengxiangjia.core.domain.Scenario
import app.fengxiangjia.core.recommendation.ScenarioComparison
import app.fengxiangjia.core.recommendation.appliedTo
import app.fengxiangjia.core.simulation.FlowVisualization
import app.fengxiangjia.core.weather.ObstructionModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val planDateFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault())
private data class ComparisonInput(val home: Home, val a: Scenario, val b: Scenario, val geometryRevision: Int, val environment: Environment?)
private data class ComparedPlans(val input: ComparisonInput, val comparison: ScenarioComparison, val visualA: FlowVisualization?, val visualB: FlowVisualization?)

@Composable
fun PlansScreen(model: AppModel, rev: Int, onApplied: () -> Unit) {
    // Each room retains its own A/B choice, including when returning from another tab.
    val roomStates = rememberSaveableStateHolder()
    roomStates.SaveableStateProvider(model.selectedRoomId) { RoomPlans(model, rev, onApplied) }
}

@Composable
private fun RoomPlans(model: AppModel, @Suppress("UNUSED_PARAMETER") rev: Int, onApplied: () -> Unit) {
    val room = model.room
    val list = model.scenariosForRoom()
    val compatible = list.filter(model::canApplyScenario)
    var a by rememberSaveable { mutableStateOf<EntityID?>(compatible.getOrNull(0)?.id) }
    var b by rememberSaveable { mutableStateOf<EntityID?>(compatible.getOrNull(1)?.id) }
    var delete by remember { mutableStateOf<Scenario?>(null) }
    var comparison by remember { mutableStateOf<ComparedPlans?>(null) }
    var comparing by remember { mutableStateOf(false) }
    var compareError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val sa = compatible.firstOrNull { it.id == a }
    val sb = compatible.firstOrNull { it.id == b }
    val home = model.home
    val geometryRevision = model.document.geometryRevision
    val environment = ObstructionModel.apply(model.environment, home)
    val comparisonInput = if (room != null && sa != null && sb != null && sa.id != sb.id)
        ComparisonInput(home, sa, sb, geometryRevision, environment) else null

    LaunchedEffect(compatible.map { it.id }) {
        val ids = compatible.map { it.id }
        if (a !in ids) a = ids.firstOrNull()
        if (b !in ids || b == a) b = ids.firstOrNull { it != a }
    }
    LaunchedEffect(comparisonInput, retry) {
        comparison = null; compareError = null; comparing = false
        val input = comparisonInput ?: return@LaunchedEffect
        comparing = true
        try {
            // Immutable snapshots prevent an edit or a room switch from changing
            // inputs halfway through a comparison. Cancellation discards old work.
            comparison = withContext(Dispatchers.Default) {
                ScenarioComparison.compare(input.home, input.a.roomId, input.a, input.b, input.geometryRevision, input.environment)?.let { result ->
                    ComparedPlans(input, result, FlowVisualization.of(result.resultA), FlowVisualization.of(result.resultB))
                }
            }
            if (comparison == null) compareError = "无法比较这两个方案。请重新保存当前房间的摆法后再试。"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { compareError = "比较暂时未完成，已保留你的选择。可以重试。" }
        finally { comparing = false }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        ScreenHeading("方案", "保存喜欢的摆法，换一种再比较。")
        Spacer(Modifier.height(18.dp))
        if (model.home.rooms.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                model.home.rooms.forEach { r -> FilterChip(r.id == model.selectedRoomId, { model.selectRoom(r.id) }, label = { Text(r.name) }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
            Spacer(Modifier.height(12.dp))
        }
        Text(room?.name ?: "我的房间", style = MaterialTheme.typography.titleLarge)
        SupportingText("${list.size} 个本机保存方案", Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(16.dp))
        if (list.isEmpty()) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(FxjIcons.Plans, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("把舒服的摆法留下来", style = MaterialTheme.typography.titleLarge)
                    Text("在「试摆」中调整好风扇后，保存第一个方案。之后随时可以恢复，或与另一个摆法比较。", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onApplied, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("去试摆并保存") }
                }
            }
        } else {
            list.forEach { scenario ->
                val canApply = model.canApplyScenario(scenario)
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(scenario.name, style = MaterialTheme.typography.titleMedium)
                                SupportingText("${planDateFormat.format(scenario.createdAt)} · ${scenario.fans.size} 台风扇")
                            }
                            IconButton(onClick = { delete = scenario }, modifier = Modifier.size(48.dp)) {
                                Icon(FxjIcons.Delete, "删除方案「${scenario.name}」", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (!canApply) SupportingText("户型结构已改变。请在试摆中核对位置并保存新方案。", Modifier.padding(top = 8.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(a == scenario.id, {
                                if (b == scenario.id) b = a
                                a = scenario.id
                            }, label = { Text("作为 A") }, enabled = canApply, modifier = Modifier.heightIn(min = 48.dp))
                            FilterChip(b == scenario.id, {
                                if (a == scenario.id) a = b
                                b = scenario.id
                            }, label = { Text("作为 B") }, enabled = canApply && compatible.size >= 2, modifier = Modifier.heightIn(min = 48.dp))
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { if (model.applyScenario(scenario.id)) onApplied() }, enabled = canApply, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("恢复") }
                        }
                    }
                }
            }
            if (compatible.size < 2) {
                Text(if (compatible.isEmpty()) "先保存当前户型的摆法" else "再保存一个不同的摆法", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                SupportingText("同一房间有两个方案后，就能比较风感、循环和调整成本。", Modifier.padding(top = 6.dp))
                OutlinedButton(onClick = onApplied, modifier = Modifier.fillMaxWidth().padding(top = 14.dp).heightIn(min = 48.dp)) { Text("去试摆并保存新方案") }
            } else if (sa != null && sb != null && room != null) {
                Spacer(Modifier.height(12.dp))
                Text("比较这两种摆法", style = MaterialTheme.typography.headlineSmall)
                SupportingText("统一使用当前风况 · 图例 0–${FlowVisualization.LEGEND_MAX} m/s", Modifier.padding(top = 6.dp, bottom = 16.dp))
                if (comparing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    SupportingText("正在计算 A 与 B…", Modifier.padding(top = 10.dp))
                }
                val ready = comparison?.takeIf { it.input == comparisonInput }
                if (ready != null) {
                    // Stacked previews remain readable at large system font sizes.
                    PlanPreview("A", sa, sa.appliedTo(room), ready.visualA)
                    Spacer(Modifier.height(12.dp))
                    PlanPreview("B", sb, sb.appliedTo(room), ready.visualB)
                    Spacer(Modifier.height(20.dp))
                    ComparisonDetails(ready.comparison)
                }
                compareError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新比较") }
                }
            }
        }
    }
    delete?.let { scenario ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除「${scenario.name}」？") },
            text = { Text("将永久删除这个保存方案。当前房间与正在使用的摆法会保留。") },
            confirmButton = { TextButton(onClick = { model.deleteScenario(scenario.id); delete = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除方案") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("保留") } })
    }
}

@Composable
private fun PlanPreview(side: String, scenario: Scenario, room: Room, visual: FlowVisualization?) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(side, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
                Text(scenario.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            RoomCanvas(room, visual, false, EditMode.VIEW, null, { _, _ -> }, { _, _ -> }, { _, _ -> },
                Modifier.fillMaxWidth().height(230.dp), interactive = false)
        }
    }
}

@Composable
private fun ComparisonDetails(comparison: ScenarioComparison) {
    if (comparison.dimensions.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text("比较项目", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("A / B", Modifier.width(68.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("选择参考", Modifier.width(68.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        comparison.dimensions.forEach { dimension ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Labels.dimension(dimension.kind), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("${Labels.level(dimension.levelA)} / ${Labels.level(dimension.levelB)}", Modifier.width(68.dp), style = MaterialTheme.typography.bodyMedium)
                Text(Labels.pref(dimension.preferred), Modifier.width(68.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    comparison.notes.forEach { SupportingText(Labels.note(it), Modifier.padding(top = 10.dp)) }
    SectionTitle("怎么选")
    if (comparison.suggestions.isEmpty()) SupportingText("暂时没有明显差别或可用建议，请结合实际体感选择。")
    comparison.suggestions.forEach { Text(Labels.suggestion(it), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp)) }
    SupportingText("简化二维示意，用来比较摆法。请结合家中的实际体感调整。", Modifier.padding(top = 12.dp))
}
