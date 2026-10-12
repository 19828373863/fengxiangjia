package app.fengxiangjia.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fengxiangjia.core.domain.EntityID
import app.fengxiangjia.core.simulation.FlowVisualization
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun PlansScreen(model: AppModel, @Suppress("UNUSED_PARAMETER") rev: Int, onApplied: () -> Unit) {
    val list = model.scenariosForRoom()
    var a by remember { mutableStateOf<EntityID?>(list.getOrNull(0)?.id) }
    var b by remember { mutableStateOf<EntityID?>(list.getOrNull(1)?.id) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("${model.room?.name ?: ""} 的方案", style = MaterialTheme.typography.headlineSmall)
        if (list.isEmpty()) Text("还没有方案。到「试摆」里调整风扇后点“保存方案”。")
        for (s in list) Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(s.name, style = MaterialTheme.typography.titleSmall)
                Text("${fmt.format(s.createdAt)} · ${s.fans.size} 台风扇" + if (s.geometryRevision != model.document.geometryRevision) " · 户型已改" else "", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(a == s.id, { a = s.id }, label = { Text("设为 A") })
                    FilterChip(b == s.id, { b = s.id }, label = { Text("设为 B") })
                    TextButton(onClick = { model.applyScenario(s.id); onApplied() }) { Text("套用") }
                    TextButton(onClick = { model.deleteScenario(s.id) }) { Text("删除") }
                }
            }
        }
        val ia = a; val ib = b
        if (ia != null && ib != null && ia != ib) {
            val cmp = remember(ia, ib, model.revision) { model.compare(ia, ib) }
            if (cmp != null) {
                SectionTitle("A / B 比较（同一天气、同一图例 0–${FlowVisualization.LEGEND_MAX} m/s）")
                for (d in cmp.dimensions) Row(Modifier.fillMaxWidth()) {
                    Text(Labels.dimension(d.kind), Modifier.weight(1f)); Text("A ${Labels.level(d.levelA)}", Modifier.width(56.dp))
                    Text("B ${Labels.level(d.levelB)}", Modifier.width(56.dp)); Text(Labels.pref(d.preferred), Modifier.width(64.dp))
                }
                for (n in cmp.notes) Text("ⓘ " + Labels.note(n), style = MaterialTheme.typography.bodySmall)
                SectionTitle("建议")
                if (cmp.suggestions.isEmpty()) Text("没有特别建议")
                for (s in cmp.suggestions) Text("• " + Labels.suggestion(s))
            }
        } else if (list.size >= 2) Text("选择两个不同的方案进行比较")
    }
}
