package app.fengxiangjia.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fengxiangjia.core.editor.RoomTemplate
import app.fengxiangjia.core.geometry.Topology
import app.fengxiangjia.core.geometry.area
import app.fengxiangjia.core.home.Bearing
import kotlin.math.roundToInt

@Composable
fun HomeScreen(model: AppModel, @Suppress("UNUSED_PARAMETER") rev: Int, onShare: (String, String) -> Unit, onScan: () -> Unit, onOpenRoom: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(model.home.name, style = MaterialTheme.typography.headlineSmall)
        Text("本地保存，不上传；不需要登录", style = MaterialTheme.typography.bodySmall)

        SectionTitle("房间")
        for (r in model.home.rooms) {
            val outdoor = Topology.reachesOutdoor(r.id, model.home)
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { model.selectRoom(r.id); onOpenRoom() }) {
                Row(Modifier.padding(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name + if (r.id == model.selectedRoomId) "（当前）" else "", style = MaterialTheme.typography.titleSmall)
                        Text("%.1f ㎡ · %d 门窗 · %d 风扇 · %s".format(r.area, r.openings.size, r.fans.size, if (outdoor) "可通室外" else "封闭"),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (model.home.rooms.size > 1) TextButton(onClick = { model.deleteRoom(r.id) }) { Text("删除") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { model.addRoom(RoomTemplate.DEFAULT_RECTANGLE, "新房间"); onOpenRoom() }) { Text("+ 矩形") }
            OutlinedButton(onClick = { model.addRoom(RoomTemplate.DEFAULT_L_SHAPE, "L 形房间"); onOpenRoom() }) { Text("+ L 形") }
            OutlinedButton(onClick = onScan) { Text("📷 扫描") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, n) in listOf("下", "右", "上", "左").withIndex())
                TextButton(onClick = { if (model.addAdjacentRoom(i, 3.0, "相邻房间")) onOpenRoom() }) { Text("贴${n}墙加房间") }
        }

        SectionTitle("住宅朝向与楼层")
        var orient by remember { mutableFloatStateOf(model.home.orientationDegrees.toFloat()) }
        Text("户型“上”方向朝 ${Bearing.name(orient.toDouble())}（${orient.roundToInt()}°）")
        Slider(orient, { orient = it }, valueRange = 0f..359f, onValueChangeFinished = { model.setOrientation(orient.toDouble()) })
        var floor by remember { mutableStateOf(model.home.floorLevel?.toString() ?: "") }
        OutlinedTextField(floor, { floor = it.filter(Char::isDigit).take(3); model.setFloor(floor.toIntOrNull()) }, label = { Text("楼层（可不填）") })

        SectionTitle("室外风况（手动 / 演示）")
        Text("实时天气尚未接入（见 #51），请手动设置或使用演示值。", style = MaterialTheme.typography.bodySmall)
        val env = model.environment
        var on by remember { mutableStateOf(env != null) }
        var from by remember { mutableFloatStateOf(env?.windFromDegrees?.toFloat() ?: 180f) }
        var speed by remember { mutableFloatStateOf(env?.windSpeed?.toFloat() ?: 2f) }
        fun push() = model.setEnvironment(if (on) from.toDouble() else null, speed.toDouble())
        Row { Checkbox(on, { on = it; push() }); Text("考虑室外风", Modifier.padding(top = 12.dp)) }
        if (on) {
            Text("风从${Bearing.name(from.toDouble())}吹来（${from.roundToInt()}°）")
            Slider(from, { from = it }, valueRange = 0f..359f, onValueChangeFinished = ::push)
            Text("风速 %.1f m/s".format(speed))
            Slider(speed, { speed = it }, valueRange = 0f..10f, onValueChangeFinished = ::push)
        }

        SectionTitle("数据")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onShare(model.exportFileName(), model.exportJson()) }) { Text("导出 JSON") }
            OutlinedButton(onClick = { model.resetToSample() }) { Text("恢复示例") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("删除全部数据") }
        }
        Text("导出默认不含位置与备注。", style = MaterialTheme.typography.bodySmall)
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        confirmButton = { TextButton(onClick = { model.deleteAllData(); confirmDelete = false }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        title = { Text("删除全部数据？") }, text = { Text("本机保存的户型、方案和反馈将被删除，无法恢复。") })
}
