package app.fengxiangjia.android

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.fengxiangjia.android.sensors.hasCompass
import app.fengxiangjia.android.sensors.rememberCompassHeading
import app.fengxiangjia.core.domain.EnvironmentSource
import app.fengxiangjia.core.home.Bearing
import app.fengxiangjia.core.recommendation.LayoutSearch
import app.fengxiangjia.core.simulation.Level
import app.fengxiangjia.core.simulation.FlowVisualization
import app.fengxiangjia.core.simulation.ScenarioHash
import app.fengxiangjia.core.store.EntitlementManager
import app.fengxiangjia.core.store.EntitlementState
import app.fengxiangjia.core.store.FreeLimits
import app.fengxiangjia.core.store.PaidFeature
import app.fengxiangjia.core.store.StoreClientError
import app.fengxiangjia.core.weather.WeatherCache
import app.fengxiangjia.core.weather.WeatherFreshness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Activity-owned permission and store actions. */
class SuggestActions(
    val requestLocation: () -> Unit = {},
    val services: Services? = null,
)

@Composable
fun SuggestScreen(model: AppModel, @Suppress("UNUSED_PARAMETER") rev: Int, actions: SuggestActions, onApplied: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("建议", style = MaterialTheme.typography.headlineLarge)
            Text("让风扇少搬一点，让家里舒服一点。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RecommendationSection(model, onApplied)
        HorizontalDivider()
        WeatherSection(model, actions)
        HorizontalDivider()
        OrientationSection(model)
        HorizontalDivider()
        PurchaseSection(model, actions.services)
        HorizontalDivider()
        PrivacySection()
        Spacer(Modifier.height(8.dp))
    }
}

private fun kindTitle(k: LayoutSearch.Kind) = when (k) {
    LayoutSearch.Kind.leastEffort -> "最省事"
    LayoutSearch.Kind.strongerBreeze -> "风感更强"
    LayoutSearch.Kind.lessDraft -> "少直吹"
}

private fun kindDescription(k: LayoutSearch.Kind) = when (k) {
    LayoutSearch.Kind.leastEffort -> "尽量少挪动，看看能否改善送风。"
    LayoutSearch.Kind.strongerBreeze -> "优先照顾你标记的希望有风区域。"
    LayoutSearch.Kind.lessDraft -> "照顾风感，同时减少目标区域的直吹。"
}

@Composable
private fun RecommendationSection(model: AppModel, onApplied: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var candidates by remember(model.selectedRoomId) { mutableStateOf<List<LayoutSearch.Candidate>?>(null) }
    var previews by remember(model.selectedRoomId) { mutableStateOf<Map<LayoutSearch.Kind, FlowVisualization?>>(emptyMap()) }
    var chosenKind by rememberSaveable(model.selectedRoomId) { mutableStateOf(LayoutSearch.Kind.leastEffort) }
    var showPlacement by rememberSaveable(model.selectedRoomId) { mutableStateOf(false) }
    var forHash by remember(model.selectedRoomId) { mutableStateOf<String?>(null) }
    var error by remember(model.selectedRoomId) { mutableStateOf<String?>(null) }
    val room = model.room
    val stale = candidates != null && forHash != model.currentHash()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(room?.let { "为「${it.name}」找个好摆法" } ?: "先准备一个房间", style = MaterialTheme.typography.titleLarge)
        when {
            room == null -> Text("在「我家」中创建房间，放好风扇后就可以比较摆法。", style = MaterialTheme.typography.bodyMedium)
            room.fans.isEmpty() -> {
                Text("先去「试摆」放一台风扇，再来看看三种摆法。", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onApplied, modifier = Modifier.heightIn(min = 48.dp)) { Text("去放风扇") }
            }
            else -> {
                Text("比较少搬动、风感更强和少直吹。先在「试摆」标记目标区域，建议会更贴近你的需要。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    enabled = !busy,
                    colors = if (candidates.isNullOrEmpty() || stale) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    onClick = {
                        model.paywall(PaidFeature.autoRecommendation)?.let { model.reportMessage(it); return@Button }
                        // Search captures immutable input on main and never touches AppModel in the worker.
                        val input = model.simulationInput() ?: return@Button
                        val requestedHash = ScenarioHash.compute(input)
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                val (found, views) = withContext(Dispatchers.Default) {
                                    val found = LayoutSearch.search(input.home, input.roomId, environment = input.environment)
                                    found to found.associate { it.kind to FlowVisualization.of(it.result) }
                                }
                                ensureActive()
                                if (model.selectedRoomId != input.roomId || model.currentHash() != requestedHash) {
                                    error = "计算期间房间或风况有变化，请重新生成。"
                                } else {
                                    candidates = found
                                    previews = views
                                    forHash = requestedHash
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "这次没有算出建议。输入已保留，可以重新生成。"
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(if (busy) "正在比较摆法…" else if (error != null || stale) "重新生成建议" else if (candidates != null) "再算一次" else "生成三种摆法") }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "正在比较风扇摆法" })
                    Text("正在比较位置与出风方向，完成后会列出建议。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { ErrorText(it) }
                if (stale) ErrorText("房间或风况已变化。下面是旧建议，重新生成后才能采用。")
                if (candidates?.isEmpty() == true) {
                    Text("暂时没有找到有效摆法。检查风扇是否在房间内、是否与家具重叠，再重新生成。", style = MaterialTheme.typography.bodyMedium)
                }
                val list = candidates.orEmpty()
                if (list.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEach { candidate ->
                            FilterChip(selected = chosenKind == candidate.kind,
                                onClick = { chosenKind = candidate.kind; showPlacement = false },
                                label = { Text(kindTitle(candidate.kind)) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                        }
                    }
                    val candidate = list.firstOrNull { it.kind == chosenKind } ?: list.first()
                    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(kindTitle(candidate.kind), style = MaterialTheme.typography.titleMedium)
                            Text(if (candidate.isCurrent) "这一类建议与当前摆法接近。" else kindDescription(candidate.kind), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            RoomCanvas(candidate.room, previews[candidate.kind], stale, EditMode.VIEW, candidate.fan.id,
                                onTap = { _, _ -> }, onDrag = { _, _ -> }, onDragEnd = { _, _ -> },
                                modifier = Modifier.fillMaxWidth().height(220.dp), interactive = false)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CandidateMeasure("调整", candidate.cost, Modifier.weight(1f))
                                CandidateMeasure("风感", candidate.breeze, Modifier.weight(1f))
                                CandidateMeasure("目标直吹", candidate.draft, Modifier.weight(1f))
                            }
                            TextButton(onClick = { showPlacement = !showPlacement }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) { Text(if (showPlacement) "收起摆位详情" else "查看摆位详情") }
                            if (showPlacement) {
                                val minX = candidate.room.polygon.minOf { it.x }
                                val minY = candidate.room.polygon.minOf { it.y }
                                Text("平面位置：横向 ${number(candidate.fan.position.x - minX)} m，纵向 ${number(candidate.fan.position.y - minY)} m\n出风朝平面图 ${number(candidate.fan.yawDegrees, 0)}°\n位置从平面图左下角起算；0°向右、90°向上。", style = MaterialTheme.typography.bodySmall)
                            }
                            if (!stale) Button(
                                    enabled = !stale && !busy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    onClick = {
                                        // Recheck action time as well as the disabled presentation.
                                        if (model.selectedRoomId != room.id || model.currentHash() != forHash) {
                                            error = "输入已变化，请重新生成后再采用。"
                                            return@Button
                                        }
                                        if (candidate.isCurrent || model.applyCandidate(candidate, forHash)) onApplied()
                                        else error = model.lastError ?: "没有应用成功，请检查房间后重试。"
                                    },
                                ) { Text(if (candidate.isCurrent) "查看当前摆法" else "采用这个摆法") }
                        }
                    }
                }
                if (candidates?.isNotEmpty() == true) {
                    Text("三种摆法的风线与比较来自同一轮模拟。目标直吹仅统计标为「减少直吹」的区域。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("生活用途的简化模拟，供试摆比较；搬动前确认插座与通道安全。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CandidateMeasure(label: String, level: Level, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(levelLabel(level), style = MaterialTheme.typography.titleMedium)
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
private fun number(value: Double, digits: Int = 1) = String.format(Locale.ROOT, "%.$digits" + "f", value)
private fun finiteNumber(text: String) = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

@Composable
private fun WeatherSection(model: AppModel, actions: SuggestActions) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val services = actions.services
    val loc = model.home.location
    val env = model.environment
    val weather = model.weather
    var expanded by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var source by remember(services) { mutableStateOf(services?.weatherSource ?: WeatherSourceChoice.MOCK) }
    var direction by remember(env?.windFromDegrees) { mutableStateOf(number(env?.windFromDegrees ?: 180.0, 0)) }
    var speed by remember(env?.windSpeed) { mutableStateOf(number(env?.windSpeed ?: 2.0)) }
    var manualError by remember { mutableStateOf<String?>(null) }
    var latitude by remember(loc) { mutableStateOf(loc?.let { number(it.latitude, 2) } ?: "") }
    var longitude by remember(loc) { mutableStateOf(loc?.let { number(it.longitude, 2) } ?: "") }
    var locationError by remember { mutableStateOf<String?>(null) }
    val sourceLabel = when {
        env == null -> "仅考虑室内循环"
        env.source == EnvironmentSource.manual -> "手动风况"
        weather?.forecast?.isDemo == true -> "演示风况"
        weather?.forecast != null -> "区域天气"
        else -> "假设风况"
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ExpandableHeading("室外风况", sourceLabel, expanded) { expanded = !expanded }
        env?.let {
            Text("风从 ${Bearing.name(it.windFromDegrees)}吹来 · ${it.windSpeed?.let { v -> "${number(v)} m/s" } ?: "风速未设置"}", style = MaterialTheme.typography.bodyLarge)
            if (it.source != EnvironmentSource.manual) Text("数据时间 ${timeFmt.format(it.observedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        weather?.forecast?.let { forecast ->
            val freshness = WeatherCache.freshness(forecast, Instant.now())
            Text(when (freshness) {
                WeatherFreshness.fresh -> "1 小时内更新"
                WeatherFreshness.stale -> "超过 1 小时，请留意天气变化"
                WeatherFreshness.expired -> "天气已过期，请更新或改用手动风况"
            } + if (weather.fromCache) " · 来自缓存" else "", style = MaterialTheme.typography.bodySmall, color = if (freshness == WeatherFreshness.expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (forecast.isDemo) Text("演示数据不代表你家当前的天气。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (forecast.location != loc) ErrorText("天气对应先前的位置，请更新后再参考。")
            forecast.attribution?.let { attribution ->
                Text("来源：${attribution.serviceName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                attribution.legalPageURL?.let { url ->
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure { model.reportMessage("未能打开数据条款，请稍后重试。") }
                    }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("查看数据来源与条款") }
                }
            }
        }
        (error ?: weather?.error?.userMessage)?.let { ErrorText("$it。当前风况会保留，也可以改用手动输入。") }
        if (expanded) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("考虑室外风", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = env != null, onCheckedChange = { checked ->
                    if (!checked) model.setEnvironment(null, null) else model.setEnvironment(180.0, 2.0)
                    error = null
                    manualError = null
                }, modifier = Modifier.semantics { contentDescription = "考虑室外风" })
            }
            Text("手动输入", style = MaterialTheme.typography.titleMedium)
            Text("来向是风吹来的方向：北 0°，东 90°，南 180°，西 270°。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(direction, { direction = it; manualError = null }, label = { Text("来向（°）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = manualError != null, modifier = Modifier.weight(1f))
                OutlinedTextField(speed, { speed = it; manualError = null }, label = { Text("风速（m/s）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = manualError != null, modifier = Modifier.weight(1f))
            }
            manualError?.let { ErrorText(it) }
            OutlinedButton(onClick = {
                val d = finiteNumber(direction)
                val s = finiteNumber(speed)
                if (d == null || d !in 0.0..360.0 || s == null || s !in 0.0..60.0) manualError = "请输入 0–360° 的来向和 0–60 m/s 的风速。"
                else {
                    model.setEnvironment(d, s)
                    error = null
                    manualError = null

                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("采用手动风况") }
            Spacer(Modifier.height(8.dp))
            Text("读取区域天气", style = MaterialTheme.typography.titleMedium)
            Text("区域预报用于比较摆法，不能代替窗边测量。离线时可继续使用手动风况。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WeatherSourceChoice.entries.forEach { choice ->
                    FilterChip(selected = source == choice, onClick = { source = choice; services?.weatherSource = choice }, label = { Text(choice.title) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Text(if (loc == null) "尚未设置位置。先设置大致位置，再读取天气。" else "已保存大致位置：${number(loc.latitude, 2)}, ${number(loc.longitude, 2)}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = actions.requestLocation, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("使用手机的大致位置") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(latitude, { latitude = it; locationError = null }, label = { Text("纬度") }, singleLine = true, isError = locationError != null, modifier = Modifier.weight(1f))
                OutlinedTextField(longitude, { longitude = it; locationError = null }, label = { Text("经度") }, singleLine = true, isError = locationError != null, modifier = Modifier.weight(1f))
            }
            locationError?.let { ErrorText(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    val lat = finiteNumber(latitude)
                    val lon = finiteNumber(longitude)
                    if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) locationError = "纬度应为 −90 至 90，经度应为 −180 至 180。"
                    else {
                        model.setLocation(lat, lon)
                        locationError = null

                    }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("保存位置") }
                if (loc != null) TextButton(onClick = { model.clearLocation(); locationError = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("清除位置") }
            }
            Text("位置保存前会粗化到约 1 km；读取在线天气时，将把粗略坐标发送给 Open-Meteo。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(enabled = !busy && services != null && loc != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                val service = services ?: return@FilledTonalButton
                if (source == WeatherSourceChoice.OPEN_METEO) model.paywall(PaidFeature.liveWeather)?.let { model.reportMessage(it); return@FilledTonalButton }
                val requestLocation = model.home.location ?: return@FilledTonalButton
                val requestHome = model.home.id
                val requestSource = source
                val requestEnvironment = model.environment
                busy = true
                error = null
                scope.launch {
                    try {
                        val outcome = service.weather(requestLocation, requestSource, force = true)
                        ensureActive()
                        if (model.home.id != requestHome || model.home.location != requestLocation || service.weatherSource != requestSource || model.environment != requestEnvironment) error = "读取期间位置或风况有变化，请重新读取。"
                        else if (outcome.environment == null) error = outcome.error?.userMessage ?: "这次没有取得有效天气"
                        else {
                            model.applyWeather(outcome)
                            error = outcome.error?.userMessage
                            if (outcome.error == null && model.lastSaveSucceeded) model.reportMessage(if (requestSource == WeatherSourceChoice.MOCK) "已采用演示风况。" else "已更新区域天气。")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = "读取天气失败，请检查网络后重试"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "正在读取…" else if (error != null) "重试读取天气" else if (source == WeatherSourceChoice.MOCK) "采用演示风况" else "读取在线天气") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun OrientationSection(model: AppModel) {
    val context = LocalContext.current
    val available = remember(context) { hasCompass(context) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf(false) }
    val heading = rememberCompassHeading(calibrating && expanded)
    var manual by remember(model.home.orientationDegrees) { mutableStateOf(number(model.home.orientationDegrees, 0)) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ExpandableHeading("住宅朝向", "平面图上方朝 ${Bearing.name(model.home.orientationDegrees)} · ${number(model.home.orientationDegrees, 0)}°", expanded) { expanded = !expanded; if (!expanded) calibrating = false }
        if (expanded) {
            Text("朝向把平面图与真实东南西北对应起来，门窗与室外风会按这个方向计算。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(manual, { manual = it; error = null }, label = { Text("平面图上方的朝向（°）") }, supportingText = { Text("北 0° · 东 90° · 南 180° · 西 270°") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = error != null, modifier = Modifier.fillMaxWidth())
            error?.let { ErrorText(it) }
            OutlinedButton(onClick = {
                val value = finiteNumber(manual)
                if (value == null || value !in 0.0..360.0) error = "请输入 0–360° 的有效朝向。"
                else { model.setOrientation(value); error = null }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保存朝向") }
            if (!available) Text("此设备没有可用的指南针，请手动设置朝向。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                Text("手机平放，顶部对准平面图上方那面墙的方向。指南针容易受金属与电器干扰，请核对读数。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { calibrating = !calibrating }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (calibrating) "停止读取指南针" else "用指南针辅助校准") }
                if (calibrating) {
                    if (heading == null || !heading.isFinite()) Text("等待指南针读数…也可以手动输入。", style = MaterialTheme.typography.bodySmall)
                    else {
                        Text("${number(heading, 0)}° · ${Bearing.name(heading)}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = "指南针读数 ${heading.toInt()} 度" })
                        FilledTonalButton(onClick = { model.setOrientation(heading); calibrating = false; error = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("采用这个方向") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PurchaseSection(model: AppModel, services: Services?) {
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var price by remember(services) { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var storeLoading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var lookupRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(services, expanded, lookupRevision) {
        val service = services ?: return@LaunchedEffect
        if (!expanded) return@LaunchedEffect
        storeLoading = true
        message = null
        price = null
        try {
            try {
                price = withTimeout(12_000) { service.store.displayPrice() }.takeIf { it.isNotBlank() && it != "—" }
            } catch (e: TimeoutCancellationException) {
                message = "Google Play 暂时没有响应，请稍后重试。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: StoreClientError.ProductUnavailable) {
                message = "商店商品尚未提供，当前无法购买。"
            } catch (e: Exception) {
                message = "暂时无法连接 Google Play，请稍后重试。"
            }
            try {
                val entitlement = withTimeout(12_000) { service.entitlements.refresh(System.currentTimeMillis() / 1000) }
                ensureActive()
                service.saveEntitlement(entitlement)
                model.setEntitlement(entitlement)
            } catch (e: TimeoutCancellationException) {
                message = "Google Play 暂时没有响应，已有购买状态会保留。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "暂时无法核对购买，已有购买状态会保留。"
            }
        } finally {
            storeLoading = false
        }
    }
    val status = when {
        model.entitlement.isUnlocked -> "已购买完整版"
        model.fullExperience -> "测试版完整体验已开放"
        else -> "免费体验"
    }
    fun run(operation: suspend EntitlementManager.(Long) -> Pair<EntitlementState, EntitlementManager.Message>) {
        val service = services ?: return
        busy = true
        message = null
        scope.launch {
            try {
                val (entitlement, result) = withTimeout(150_000) { service.entitlements.operation(System.currentTimeMillis() / 1000) }
                ensureActive()
                service.saveEntitlement(entitlement)
                model.setEntitlement(entitlement)
                message = result.text
            } catch (e: TimeoutCancellationException) {
                message = "商店处理超时，请稍后恢复购买来核对结果。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "商店暂不可用，请稍后重试。"
            } finally {
                busy = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ExpandableHeading("功能与购买", status, expanded) { expanded = !expanded }
        if (model.fullExperience && !model.entitlement.isUnlocked) Text("多房间、保存方案、自动建议都可以直接体验。测试体验与正式购买状态分开记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (expanded) {
            Text("完整版支持多房间、不限数量的方案、自动建议与在线天气；摄像头扫描需设备与服务支持。", style = MaterialTheme.typography.bodyMedium)
            if (!model.fullExperience && !model.entitlement.isUnlocked) Text("免费版可编辑 ${FreeLimits.ROOMS} 个房间，保存 ${FreeLimits.SCENARIOS} 个方案；手动试摆、风线和 A/B 比较可用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (storeLoading || busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (busy) "正在等待 Google Play 处理…" else "正在核对商店状态…", style = MaterialTheme.typography.bodySmall)
            }
            if (!model.entitlement.isUnlocked) Button(enabled = !busy && !storeLoading && services != null && price != null, onClick = { run { purchase(it) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(price?.let { "一次性购买 · $it" } ?: "购买暂不可用") }
            OutlinedButton(enabled = !busy && !storeLoading && services != null, onClick = { run { restore(it) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("恢复已有购买") }
            if (price == null && !storeLoading) TextButton(enabled = !busy && services != null, onClick = { lookupRevision++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新连接商店") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun PrivacySection() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ExpandableHeading("隐私与权限", "户型与方案保存在本机", expanded) { expanded = !expanded }
        if (expanded) {
            Text("无需登录，户型与方案默认不上网传输。相机只在扫描时使用，画面不保存、不上传。", style = MaterialTheme.typography.bodyMedium)
            Text("点击「使用手机的大致位置」时，只读取一次系统最近的可用位置，不持续定位。位置会粗化到约 1 km 后保存；在线天气会发送这份粗略坐标。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("购买由 Google Play 处理，本应用不接触支付信息。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExpandableHeading(title: String, summary: String, expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { stateDescription = if (expanded) "已展开" else "已收起" }, contentPadding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = if (expanded) "收起$title" else "展开$title")
        }
    }
}

@Composable
private fun ErrorText(message: String) = Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

private fun levelLabel(level: Level) = when (level) {
    Level.none -> "无"
    Level.low -> "低"
    Level.medium -> "中"
    Level.high -> "高"
}
