package app.fengxiangjia.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.fengxiangjia.android.sensors.hasCompass
import app.fengxiangjia.android.sensors.rememberCompassHeading
import app.fengxiangjia.core.home.Bearing
import app.fengxiangjia.core.recommendation.LayoutSearch
import app.fengxiangjia.core.store.EntitlementManager
import app.fengxiangjia.core.store.FreeLimits
import app.fengxiangjia.core.weather.WeatherFreshness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 界面需要 Activity 提供的能力（权限、结算），便于在 JVM 测试里替换。 */
class SuggestActions(
    val requestLocation: () -> Unit = {},
    val services: Services? = null,
)

/** #65「建议」页：自动推荐、天气、朝向、完整版。 */
@Composable
fun SuggestScreen(model: AppModel, @Suppress("UNUSED_PARAMETER") rev: Int, actions: SuggestActions, onApplied: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        RecommendationSection(model, onApplied)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        WeatherSection(model, actions)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        OrientationSection(model)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        PurchaseSection(model, actions.services)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        PrivacySection()
    }
}

private fun kindTitle(k: LayoutSearch.Kind) = when (k) {
    LayoutSearch.Kind.leastEffort -> "最省事"; LayoutSearch.Kind.strongerBreeze -> "送风更强"; LayoutSearch.Kind.lessDraft -> "少直吹"
}

@Composable
private fun RecommendationSection(model: AppModel, onApplied: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var candidates by remember(model.selectedRoomId) { mutableStateOf<List<LayoutSearch.Candidate>?>(null) }
    var forHash by remember { mutableStateOf<String?>(null) }
    SectionTitle("自动推荐")
    val room = model.room
    when {
        room == null -> Text("没有房间")
        room.fans.isEmpty() -> Text("先在「试摆」里放一台风扇，再来生成推荐")
        else -> {
            Button(enabled = !busy, onClick = {
                if (!model.canRecommend()) { model.recommend(); return@Button }
                busy = true
                scope.launch {
                    val hash = model.currentHash()
                    candidates = withContext(Dispatchers.Default) { model.recommend() }
                    forHash = hash; busy = false
                }
            }) { Text(if (busy) "计算中…" else "为「${room.name}」生成推荐") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            val list = candidates
            if (list != null && forHash != model.currentHash()) Text("房间或天气已变化，推荐可能不再适用，请重新生成", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (list != null && list.isEmpty()) Text("没有找到可用的摆位")
            list?.forEach { c ->
                Card(Modifier.fillMaxWidth().padding(top = 8.dp).semantics(mergeDescendants = true) {}) {
                    Column(Modifier.padding(12.dp)) {
                        Text(kindTitle(c.kind) + if (c.isCurrent) "（当前摆法）" else "", style = MaterialTheme.typography.titleSmall)
                        Text("位置 (%.1f, %.1f) m · 朝向 %.0f°".format(c.fan.position.x, c.fan.position.y, c.fan.yawDegrees))
                        Text("搬动：${levelLabel(c.cost)} · 风感：${levelLabel(c.breeze)} · 直吹：${levelLabel(c.draft)}", style = MaterialTheme.typography.bodySmall)
                        if (!c.isCurrent) TextButton(onClick = { model.applyCandidate(c); onApplied() }) { Text("采用并去试摆") }
                    }
                }
            }
            Text("推荐基于简化模型，仅供参考；搬动前请确认插座与通道安全。", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

@Composable
private fun WeatherSection(model: AppModel, actions: SuggestActions) {
    val scope = rememberCoroutineScope()
    val services = actions.services
    var busy by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf(services?.weatherSource ?: WeatherSourceChoice.MOCK) }
    SectionTitle("天气")
    val loc = model.home.location
    Text(if (loc == null) "尚未设置住宅位置" else "住宅位置：%.2f, %.2f（已粗化到约 1 km）".format(loc.latitude, loc.longitude))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = actions.requestLocation) { Text("使用大致位置") }
        if (loc != null) TextButton(onClick = { model.clearLocation() }) { Text("清除位置") }
    }
    var lat by remember { mutableStateOf("") }; var lon by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        OutlinedTextField(lat, { lat = it }, label = { Text("纬度") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(lon, { lon = it }, label = { Text("经度") }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            val a = lat.toDoubleOrNull(); val b = lon.toDoubleOrNull()
            if (a != null && b != null && a in -90.0..90.0 && b in -180.0..180.0) model.setLocation(a, b)
        }) { Text("保存") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        for (s in WeatherSourceChoice.entries) FilterChip(source == s, { source = s; services?.weatherSource = s }, label = { Text(s.title) })
    }
    Button(enabled = !busy && services != null, onClick = {
        if (source == WeatherSourceChoice.OPEN_METEO) model.paywall(app.fengxiangjia.core.store.PaidFeature.liveWeather)?.let { model.reportMessage(it); return@Button }
        busy = true
        scope.launch { val o = services!!.weather(model, force = true); model.applyWeather(o); busy = false }
    }) { Text(if (busy) "获取中…" else "获取当前天气") }
    model.weather?.let { w ->
        w.environment?.let { e -> Text("风从 ${Bearing.name(e.windFromDegrees)}（%.0f°）吹来，%.1f m/s · %s".format(e.windFromDegrees, e.windSpeed ?: 0.0, timeFmt.format(e.observedAt))) }
        val fresh = when (w.freshness) { WeatherFreshness.fresh -> "最新"; WeatherFreshness.stale -> "稍旧（超过 1 小时）"; WeatherFreshness.expired -> "已过期"; null -> null }
        fresh?.let { Text("数据状态：$it" + if (w.fromCache) " · 来自缓存" else "", style = MaterialTheme.typography.bodySmall) }
        if (w.forecast?.isDemo == true) Text("当前为演示天气，不代表真实情况", style = MaterialTheme.typography.bodySmall)
        w.forecast?.attribution?.let { Text("天气数据：${it.serviceName}" + (it.legalPageURL?.let { u -> "（$u）" } ?: ""), style = MaterialTheme.typography.labelSmall) }
        w.error?.let { Text(it.userMessage + "；仍可在「试摆」中手动设置风向", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun OrientationSection(model: AppModel) {
    val ctx = LocalContext.current
    val available = remember { hasCompass(ctx) }
    var calibrating by remember { mutableStateOf(false) }
    val heading = rememberCompassHeading(calibrating)
    SectionTitle("住宅朝向")
    Text("当前：平面图上方朝 ${Bearing.name(model.home.orientationDegrees)}（%.0f°）".format(model.home.orientationDegrees))
    if (!available) Text("此设备没有指南针，请手动输入朝向", style = MaterialTheme.typography.bodySmall)
    else {
        Text("手机平放，顶部对准平面图「上方」那面墙的方向，然后点「采用」。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { calibrating = !calibrating }) { Text(if (calibrating) "停止" else "用指南针校准") }
            if (calibrating && heading != null) {
                Text("%.0f° %s".format(heading, Bearing.name(heading)), modifier = Modifier.semantics { contentDescription = "指南针读数 ${heading.toInt()} 度" })
                Button(onClick = { model.setOrientation(heading); calibrating = false }) { Text("采用") }
            }
        }
        Text("指南针读数是磁北，易受金属与电器干扰，误差可能有 10–20°。", style = MaterialTheme.typography.labelSmall)
    }
    var manual by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(manual, { manual = it }, label = { Text("手动输入（度）") }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton(onClick = { manual.toDoubleOrNull()?.let(model::setOrientation) }) { Text("保存") }
    }
}

@Composable
private fun PurchaseSection(model: AppModel, services: Services?) {
    val scope = rememberCoroutineScope()
    var price by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(services) {
        services ?: return@LaunchedEffect
        price = runCatching { services.store.displayPrice() }.getOrNull()
        val s = services.entitlements.refresh(System.currentTimeMillis() / 1000); services.saveEntitlement(s); model.setEntitlement(s)
    }
    SectionTitle("完整版")
    if (model.entitlement.isUnlocked) { Text("已解锁完整版，感谢支持"); return }
    Text("免费版：${FreeLimits.ROOMS} 个房间、${FreeLimits.SCENARIOS} 个方案、手动风向。\n完整版（一次性购买）：多房间、无限方案、自动推荐、实时天气、摄像头扫描。")
    fun run(op: suspend EntitlementManager.(Long) -> Pair<app.fengxiangjia.core.store.EntitlementState, EntitlementManager.Message>) {
        val sv = services ?: return; busy = true
        scope.launch {
            val (s, m) = sv.entitlements.op(System.currentTimeMillis() / 1000)
            sv.saveEntitlement(s); model.setEntitlement(s); message = m.text; busy = false
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy && services != null, onClick = { run { purchase(it) } }) { Text("购买" + (price?.let { "（$it）" } ?: "")) }
        OutlinedButton(enabled = !busy && services != null, onClick = { run { restore(it) } }) { Text("恢复购买") }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun PrivacySection() {
    SectionTitle("隐私与权限")
    Text("• 户型与方案只保存在本机，不上传。\n• 相机仅在扫描时使用，画面不保存、不上传。\n• 位置仅在你点「使用大致位置」时读取一次，保存前粗化到约 1 km；选用 Open-Meteo 时会把粗略坐标发送给该服务查询天气。\n• 购买由 Google Play 处理，本应用不接触支付信息。",
        style = MaterialTheme.typography.bodySmall)
}

private fun levelLabel(l: app.fengxiangjia.core.simulation.Level) = when (l) {
    app.fengxiangjia.core.simulation.Level.none -> "无"; app.fengxiangjia.core.simulation.Level.low -> "低"
    app.fengxiangjia.core.simulation.Level.medium -> "中"; app.fengxiangjia.core.simulation.Level.high -> "高"
}
