package app.fengxiangjia.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.fengxiangjia.core.domain.Room
import app.fengxiangjia.core.editor.RoomTemplate
import app.fengxiangjia.core.geometry.Topology
import app.fengxiangjia.core.geometry.area
import app.fengxiangjia.core.home.Bearing
import app.fengxiangjia.core.library.SampleData
import app.fengxiangjia.core.store.PaidFeature
import kotlin.math.roundToInt

private data class RoomDraft(val name: String, val width: Double, val depth: Double, val lShape: Boolean, val replace: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: AppModel, @Suppress("UNUSED_PARAMETER") rev: Int, onShare: (String, String) -> Unit, onScan: () -> Unit, onOpenRoom: () -> Unit) {
    val room = model.room
    var create by rememberSaveable { mutableStateOf(false) }
    var adjacent by rememberSaveable { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Room?>(null) }
    var delete by remember { mutableStateOf<Room?>(null) }
    var reset by remember { mutableStateOf(false) }
    var deleteAll by remember { mutableStateOf(false) }
    var pendingReplacement by remember { mutableStateOf<RoomDraft?>(null) }
    var environmentExpanded by rememberSaveable { mutableStateOf(false) }
    var dataExpanded by rememberSaveable { mutableStateOf(false) }

    fun createRoom(draft: RoomDraft) {
        val id = model.createRoom(if (draft.lShape) RoomTemplate.DEFAULT_L_SHAPE else RoomTemplate.DEFAULT_RECTANGLE,
            draft.name, draft.width, draft.depth, draft.replace)
        if (id != null) { create = false; pendingReplacement = null; onOpenRoom() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        ScreenHeading("我家", "从房间开始，试出舒服的摆法。")
        Spacer(Modifier.height(20.dp))
        if (model.home.rooms.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                model.home.rooms.forEach { r ->
                    FilterChip(selected = r.id == model.selectedRoomId, onClick = { model.selectRoom(r.id) },
                        label = { Text(r.name) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (room != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(room.name, style = MaterialTheme.typography.titleLarge)
                    SupportingText("%.1f ㎡ · %d 门窗 · %d 台风扇".format(room.area, room.openings.size, room.fans.size))
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) { Icon(FxjIcons.More, "管理房间") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("修改房间名称") }, onClick = { menu = false; rename = room })
                        DropdownMenuItem(text = { Text("在墙外添加相邻房间") }, onClick = { menu = false; adjacent = true })
                        DropdownMenuItem(text = { Text("删除「${room.name}」") }, enabled = model.home.rooms.size > 1,
                            onClick = { menu = false; delete = room })
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    RoomCanvas(room, null, false, EditMode.VIEW, null, { _, _ -> }, { _, _ -> }, { _, _ -> },
                        Modifier.fillMaxWidth().height(244.dp).clip(MaterialTheme.shapes.medium), interactive = false)
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (room.id == SampleData.ROOM_ID && room.name.startsWith("示例")) "示例户型 · 可自由修改" else "我的户型 · 自动保存",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(if (Topology.reachesOutdoor(room.id, model.home)) "通向室外" else "封闭房间",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenRoom, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                Text("开始试摆", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(10.dp)); Icon(FxjIcons.Arrow, null, Modifier.size(20.dp))
            }
            Spacer(Modifier.height(8.dp))
            SupportingText("移动风扇、调整朝向，看看风能不能吹到想要的位置。")
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { create = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(10.dp)) {
                Icon(FxjIcons.Add, null, Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)); Text("创建房间")
            }
            OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(10.dp)) {
                Icon(FxjIcons.Scan, null, Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)); Text("扫描房间")
            }
        }
        if (model.fullExperience) SupportingText("完整体验版 · 房间、方案和自动建议均可使用", Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(24.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ExpandableHeading("环境设置", "住宅朝向、楼层与室外风况", environmentExpanded) { environmentExpanded = !environmentExpanded }
        if (environmentExpanded) { HomeEnvironment(model); Spacer(Modifier.height(16.dp)) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ExpandableHeading("数据与隐私", "仅保存在本机，无需登录", dataExpanded) { dataExpanded = !dataExpanded }
        if (dataExpanded) {
            OutlinedButton(onClick = { onShare(model.exportFileName(), model.exportJson()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("导出户型与方案") }
            SupportingText("导出为 JSON，默认不包含位置与体感备注。", Modifier.padding(top = 8.dp, bottom = 8.dp))
            TextButton(onClick = { reset = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("恢复示例住宅") }
            TextButton(onClick = { deleteAll = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除本机全部数据") }
        }
    }
    if (create) ModalBottomSheet(onDismissRequest = { create = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CreateRoomForm(room, model.paywall(PaidFeature.multiRoom) == null, onClose = { create = false }) { draft ->
            val unmodifiedSample = room == SampleData.sampleDocument().home.rooms.firstOrNull() &&
                model.document.scenarios.none { it.roomId == room?.id } && model.document.feedback.none { it.roomId == room?.id }
            if (draft.replace && room != null && !unmodifiedSample) pendingReplacement = draft else createRoom(draft)
        }
    }
    pendingReplacement?.let { draft ->
        AlertDialog(onDismissRequest = { pendingReplacement = null }, title = { Text("替换「${room?.name.orEmpty()}」？") },
            text = { Text("将改为「${draft.name}」，并清除这个房间原有的门窗、家具、风扇、保存方案和体感记录。其他房间会保留。") },
            confirmButton = { TextButton(onClick = { createRoom(draft) }) { Text("确认替换") } },
            dismissButton = { TextButton(onClick = { pendingReplacement = null }) { Text("返回修改") } })
    }
    if (adjacent && room != null) ModalBottomSheet(onDismissRequest = { adjacent = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AdjacentRoomForm(room, onClose = { adjacent = false }) { wall, depth, name -> if (model.addAdjacentRoom(wall, depth, name)) { adjacent = false; onOpenRoom() } }
    }
    rename?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("房间名称") },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text("名称") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { model.renameRoom(target.id, name.trim()); rename = null }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } })
    }
    delete?.let { target ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除「${target.name}」？") },
            text = { Text("这个房间以及它的 ${model.document.scenarios.count { it.roomId == target.id }} 个保存方案将永久删除。其他房间会保留。") },
            confirmButton = { TextButton(onClick = { model.deleteRoom(target.id); delete = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除房间") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("保留") } })
    }
    if (reset || deleteAll) AlertDialog(onDismissRequest = { reset = false; deleteAll = false },
        title = { Text(if (deleteAll) "删除本机全部数据？" else "恢复示例住宅？") },
        text = { Text(if (deleteAll) "本机户型、方案和体感记录将永久删除。随后会打开一份内置示例供体验。" else "现有住宅、保存方案和体感记录将替换为内置示例，无法撤销。可先导出数据备份。") },
        confirmButton = { TextButton(onClick = {
            if (deleteAll) model.deleteAllData() else model.resetToSample()
            reset = false; deleteAll = false
        }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(if (deleteAll) "确认删除" else "确认恢复") } },
        dismissButton = { TextButton(onClick = { reset = false; deleteAll = false }) { Text("取消") } })
}

@Composable
private fun ExpandableHeading(title: String, subtitle: String, expanded: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium); SupportingText(subtitle)
        }
        Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun HomeEnvironment(model: AppModel) {
    var orientation by remember(model.home.orientationDegrees) { mutableFloatStateOf(model.home.orientationDegrees.toFloat()) }
    Text("户型上方朝${Bearing.name(orientation.toDouble())} · ${orientation.roundToInt()}°", style = MaterialTheme.typography.bodyLarge)
    Slider(orientation, { orientation = it }, valueRange = 0f..359f, onValueChangeFinished = { model.setOrientation(orientation.toDouble()) })
    var floor by remember(model.home.floorLevel) { mutableStateOf(model.home.floorLevel?.toString().orEmpty()) }
    OutlinedTextField(floor, { floor = it.filter(Char::isDigit).take(3); model.setFloor(floor.toIntOrNull()) }, label = { Text("楼层（可不填）") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    Spacer(Modifier.height(16.dp))
    val environment = model.environment
    var from by remember(environment?.windFromDegrees) { mutableFloatStateOf(environment?.windFromDegrees?.toFloat() ?: 180f) }
    var speed by remember(environment?.windSpeed) { mutableFloatStateOf(environment?.windSpeed?.toFloat() ?: 2f) }
    fun push() = model.setEnvironment(from.toDouble(), speed.toDouble())
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("考虑室外风", style = MaterialTheme.typography.titleSmall); SupportingText("手动设置假设风况") }
        Switch(checked = environment != null, onCheckedChange = { if (it) push() else model.setEnvironment(null, null) })
    }
    if (environment != null) {
        Text("风从${Bearing.name(from.toDouble())}吹来 · ${from.roundToInt()}°")
        Slider(from, { from = it }, valueRange = 0f..359f, onValueChangeFinished = ::push)
        Text("风速 %.1f m/s".format(speed)); Slider(speed, { speed = it }, valueRange = 0f..10f, onValueChangeFinished = ::push)
    }
    SupportingText("这是生活示意风况。需要天气数据时，可在「建议」页选择来源。")
}

@Composable
private fun CreateRoomForm(current: Room?, canAdd: Boolean, onClose: () -> Unit, onSubmit: (RoomDraft) -> Unit) {
    var name by rememberSaveable { mutableStateOf("我的客厅") }
    var width by rememberSaveable { mutableStateOf("4") }
    var depth by rememberSaveable { mutableStateOf("3") }
    var lShape by rememberSaveable { mutableStateOf(false) }
    var replace by rememberSaveable { mutableStateOf(true) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val w = width.toDoubleOrNull(); val d = depth.toDoubleOrNull()
    val valid = name.isNotBlank() && w != null && d != null && w.isFinite() && d.isFinite() && w in 1.0..30.0 && d in 1.0..30.0
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("创建我的房间", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(FxjIcons.Close, "关闭创建房间") }
        }
        SupportingText("先输入大致尺寸，门窗和家具可以在试摆时继续添加。")
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("房间名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = submitted && name.isBlank())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(width, { width = it.replace(',', '.').take(6) }, label = { Text("宽度（米）") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = submitted && (w == null || !w.isFinite() || w !in 1.0..30.0))
            OutlinedTextField(depth, { depth = it.replace(',', '.').take(6) }, label = { Text("进深（米）") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = submitted && (d == null || !d.isFinite() || d !in 1.0..30.0))
        }
        SupportingText("尺寸可填 1–30 米的小数。")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(!lShape, { lShape = false }, label = { Text("矩形") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            FilterChip(lShape, { lShape = true }, label = { Text("L 形") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
        }
        if (lShape) SupportingText("L 形会在右上角留出凹口；创建后可拖动墙角校准。")
        if (current != null) {
            Row(Modifier.fillMaxWidth().clickable { replace = true }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(replace, { replace = true }); Text("替换「${current.name}」", Modifier.weight(1f))
            }
            if (canAdd) Row(Modifier.fillMaxWidth().clickable { replace = false }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(!replace, { replace = false }); Text("保留现有房间，新增一间", Modifier.weight(1f))
            }
        }
        if (submitted && !valid) Text("请填写房间名称和 1–30 米的有效尺寸。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { submitted = true; if (valid) onSubmit(RoomDraft(name.trim(), w!!, d!!, lShape, replace)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("创建并开始试摆") }
    }
}

@Composable
private fun AdjacentRoomForm(room: Room, onClose: () -> Unit, onSubmit: (Int, Double, String) -> Unit) {
    var wall by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("相邻房间") }
    var depth by rememberSaveable { mutableStateOf("3") }
    var error by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("添加相邻房间", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(FxjIcons.Close, "关闭添加相邻房间") }
        }
        SupportingText("沿「${room.name}」的一面墙向外创建。墙编号从底边开始，沿户型逆时针排列。")
        room.wallIds.indices.toList().chunked(3).forEach { indices ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { indices.forEach { i -> FilterChip(wall == i, { wall = i }, label = { Text("第 ${i + 1} 面墙") }, modifier = Modifier.heightIn(min = 48.dp)) } }
        }
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("房间名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(depth, { depth = it.replace(',', '.').take(6) }, label = { Text("向外进深（米）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        if (error) Text("请填写房间名称和 1–30 米的有效进深。", color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            val d = depth.toDoubleOrNull(); error = name.isBlank() || d == null || !d.isFinite() || d !in 1.0..30.0
            if (!error) onSubmit(wall, d!!, name.trim())
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("添加并打开") }
    }
}
