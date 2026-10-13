package app.fengxiangjia.android

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.editor.EditError
import app.fengxiangjia.core.editor.RoomEditor
import app.fengxiangjia.core.editor.RoomTemplate
import app.fengxiangjia.core.editor.FanPlacement
import app.fengxiangjia.core.geometry.Topology
import app.fengxiangjia.core.geometry.RoomValidator
import app.fengxiangjia.core.geometry.contains
import app.fengxiangjia.core.home.HomeIssue
import app.fengxiangjia.core.home.HomeValidator
import app.fengxiangjia.core.home.addAdjacentRoom
import app.fengxiangjia.core.home.removeRoom
import app.fengxiangjia.core.library.SampleData
import app.fengxiangjia.core.persistence.DocumentExport
import app.fengxiangjia.core.persistence.HomeDocument
import app.fengxiangjia.core.persistence.HomeLoadState
import app.fengxiangjia.core.persistence.HomeRepository
import app.fengxiangjia.core.persistence.DocumentCodec
import app.fengxiangjia.core.persistence.LocalFileStorage
import app.fengxiangjia.core.recommendation.ScenarioComparison
import app.fengxiangjia.core.recommendation.appliedTo
import app.fengxiangjia.core.recommendation.capture
import app.fengxiangjia.core.simulation.ScenarioHash
import app.fengxiangjia.core.simulation.SimulationInput
import app.fengxiangjia.core.weather.ObstructionModel
import app.fengxiangjia.core.store.EntitlementState
import app.fengxiangjia.core.store.FreeLimits
import app.fengxiangjia.core.store.PaidFeature
import app.fengxiangjia.core.weather.WeatherOutcome
import app.fengxiangjia.core.weather.WeatherCache
import app.fengxiangjia.core.weather.WeatherForecast
import app.fengxiangjia.core.weather.coarsenedLocation
import app.fengxiangjia.core.recommendation.LayoutSearch
import java.time.Instant
import java.io.File
import kotlinx.serialization.json.*

/**
 * 应用状态与操作（纯 Kotlin，不依赖 Android，可在 JVM 单测）。
 * 所有房间编辑经 [RoomEditor]，保证撤销/重做与校验规则与 iOS 一致；每次成功修改后自动保存。
 */
class AppModel(
    private val repo: HomeRepository?,
    /** Explicit local test access, independent of a purchased Play entitlement. Release callers default to false. */
    val fullExperience: Boolean = false,
    private val clock: () -> Instant = Instant::now,
) {
    var document: HomeDocument; private set
    var selectedRoomId: EntityID; private set
    /** 手动环境（天气 provider 未接入前的演示/手动输入）。null = 未设置。 */
    var environment: Environment?; private set
    var loadMessage: String? = null; private set
    var lastError: String? = null; private set
    private val editors = mutableMapOf<EntityID, RoomEditor>()
    var revision = 0; private set
    /** Durable home + session status, kept separate from transient Snackbar messages. */
    var lastSaveSucceeded: Boolean = true; private set
    private var documentNeedsSave = false
    private var sessionNeedsSave = false
    /** 权益（#65）：由 Activity 从 Play 结算同步；默认未解锁，免费额度与 iOS 相同。 */
    var entitlement = EntitlementState(); private set
    /** 最近一次天气结果（含新鲜度、归属、错误），null = 使用手动风向。 */
    var weather: WeatherOutcome? = null; private set
    private val listeners = mutableListOf<() -> Unit>()
    private val sessionFile: File? get() = repo?.file?.let { File(it.parentFile, it.name + ".session.json") }

    init {
        val st = repo?.load() ?: HomeLoadState.Empty
        document = when (st) {
            is HomeLoadState.Loaded -> st.document
            is HomeLoadState.Failed -> { loadMessage = st.message; SampleData.sampleDocument() }
            else -> SampleData.sampleDocument()
        }
        selectedRoomId = document.home.rooms.firstOrNull()?.id ?: SampleData.ROOM_ID
        environment = Environment(EnvironmentSource.manual, clock(), 180.0, windSpeed = 2.0)
        if (st is HomeLoadState.Loaded) restoreSession()
        if (st is HomeLoadState.Failed) {
            documentNeedsSave = true
            lastSaveSucceeded = false
            runCatching { repo?.preserveUnreadableFile() }.onSuccess { backup ->
                if (backup != null) loadMessage = "${st.message}；原文件已保留到本机恢复副本，正在展示示例"
            }.onFailure {
                lastSaveSucceeded = false
                loadMessage = "${st.message}；恢复副本无法保存，原文件已保留。请检查本机存储后重试"
            }
        }
    }

    /** Return an idempotent unsubscribe callback for DisposableEffect and other short-lived observers. */
    fun observe(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners.remove(l); Unit }
    }
    private fun notifyObservers() { listeners.toList().forEach { listener -> runCatching { listener() } } }
    private fun changed(save: Boolean = true, successMessage: String? = null): Boolean {
        revision++
        val retryingFailure = !lastSaveSucceeded
        if (save || documentNeedsSave || !lastSaveSucceeded || repo?.file?.exists() == false) {
            documentNeedsSave = true
            runCatching { repo?.save(document) }.onSuccess { documentNeedsSave = false }
        }
        sessionNeedsSave = true
        runCatching { saveSession() }.onSuccess { sessionNeedsSave = false }
        lastSaveSucceeded = !documentNeedsSave && !sessionNeedsSave
        if (!lastSaveSucceeded) lastError = "保存失败，请重试；当前修改仍在本次会话中"
        else if (successMessage != null) lastError = successMessage
        else if (retryingFailure) lastError = null
        notifyObservers()
        return lastSaveSucceeded
    }

    fun retrySave(): Boolean {
        lastError = null
        return changed(save = true, successMessage = "修改已重新保存到本机")
    }

    val home get() = document.home
    val room: Room? get() = home.rooms.firstOrNull { it.id == selectedRoomId }
    fun editor(): RoomEditor? = room?.let { r ->
        editors.getOrPut(r.id) { RoomEditor(r) }.also { if (it.room != r) it.sync(r) }
    }

    /** 当前输入（风线与建议共用这一份）。遮挡设置按住宅写入环境快照。 */
    fun simulationInput(): SimulationInput? = room?.let { SimulationInput(home, it.id, ObstructionModel.apply(environment, home)) }
    fun currentHash(): String? = simulationInput()?.let { ScenarioHash.compute(it) }

    fun selectRoom(id: EntityID) { if (home.rooms.any { it.id == id } && selectedRoomId != id) { selectedRoomId = id; changed(false) } }
    fun reportMessage(m: String) { lastError = m; notifyObservers() }
    fun clearError() { lastError = null; notifyObservers() }

    /** 执行房间编辑；失败时记录中文错误，不改变状态。 */
    fun edit(op: (RoomEditor) -> Unit): Boolean {
        val e = editor() ?: return false
        // Multi-step operations (e.g. remove + replace) must never partially mutate undo history.
        val staged = RoomEditor(e.room).also { it.gridSize = e.gridSize; it.wallSnapDistance = e.wallSnapDistance }
        return try {
            op(staged)
            require(staged.room.id == e.room.id)
            validateRoom(staged.room)
            lastError = null
            if (staged.room != e.room) {
                e.replace(staged.room)
                commitRoom(e.room)
            }
            true
        } catch (err: EditError) { reportMessage(err.userMessage); false }
        catch (_: IllegalArgumentException) { reportMessage("输入无效，请检查房间尺寸与物体位置"); false }
        catch (_: Exception) { reportMessage("这次修改未完成，请重试"); false }
    }

    fun undo() = restoreEditHistory(redo = false)
    fun redo() = restoreEditHistory(redo = true)

    private fun restoreEditHistory(redo: Boolean) {
        val editor = editor() ?: return
        if (if (redo) !editor.canRedo else !editor.canUndo) return
        val currentName = editor.room.name
        if (redo) editor.redo() else editor.undo()
        val restored = editor.room.copy(name = currentName)
        try {
            validateRoom(restored)
            editor.sync(restored)
            lastError = null
            commitRoom(restored)
        } catch (_: Exception) {
            // A subsequently added room can make an older shape invalid. Restore both stacks unchanged.
            if (redo) editor.undo() else editor.redo()
            reportMessage("其他房间或物体已变化，无法恢复这一步；请手动调整")
        }
    }

    private fun commitRoom(r: Room) {
        val geometryChanged = home.rooms.first { it.id == r.id }.let { previous ->
            previous.polygon != r.polygon || previous.wallIds != r.wallIds || previous.obstacles != r.obstacles ||
                previous.openings.map { it.copy(openFraction = 1.0, connects = emptyList(), exteriorObstruction = null) } !=
                r.openings.map { it.copy(openFraction = 1.0, connects = emptyList(), exteriorObstruction = null) }
        }
        val h = Topology.refreshConnections(home.copy(rooms = home.rooms.map { if (it.id == r.id) r else it }))
        document = if (geometryChanged) withGeometryChange(h, r.id) else document.copy(home = h)
        syncEditors()
        changed()
    }

    fun setEnvironment(windFrom: Double?, windSpeed: Double?) {
        if (windFrom?.isFinite() == false || windSpeed?.let { !it.isFinite() || it < 0.0 || it > 100.0 } == true) {
            reportMessage("请输入有效风向和 0–100 m/s 的风速"); return
        }
        environment = windFrom?.let { Environment(EnvironmentSource.manual, clock(), normalize(it), windSpeed = windSpeed) }
        weather = null
        changed(false, "风况已保存")
    }

    fun setOrientation(deg: Double) {
        if (!deg.isFinite()) { reportMessage("请输入有效朝向"); return }
        document = document.copy(home = home.copy(orientationDegrees = normalize(deg))); changed(successMessage = "住宅朝向已保存")
    }
    fun setFloor(floor: Int?) { document = document.copy(home = home.copy(floorLevel = floor)); changed(successMessage = "楼层设置已保存") }

    // —— 权益 / 付费墙 ——
    fun setEntitlement(e: EntitlementState) { entitlement = e; changed(false) }
    /** 返回 null = 允许；否则为中文提示（界面据此弹出购买页）。 */
    fun paywall(f: PaidFeature): String? = if (fullExperience) null else when (f) {
        PaidFeature.multiRoom -> if (entitlement.canAddRoom(home.rooms.size)) null else "免费版可编辑 ${FreeLimits.ROOMS} 个房间，解锁完整版可添加更多"
        PaidFeature.unlimitedScenarios -> if (entitlement.canSaveScenario(document.scenarios.size)) null else "免费版可保存 ${FreeLimits.SCENARIOS} 个方案，解锁完整版不限数量"
        else -> if (entitlement.allows(f)) null else "${featureName(f)}属于完整版功能"
    }
    private fun featureName(f: PaidFeature) = when (f) {
        PaidFeature.autoRecommendation -> "自动推荐"; PaidFeature.liveWeather -> "实时天气"; PaidFeature.roomScan -> "摄像头扫描"
        PaidFeature.multiRoom -> "多房间"; PaidFeature.unlimitedScenarios -> "无限方案"
    }
    private fun gate(f: PaidFeature): Boolean { val m = paywall(f) ?: return true; reportMessage(m); return false }

    // —— 位置 / 天气 ——
    /** 住宅位置只按 0.01°（约 1 km）粗化后保存。 */
    fun setLocation(lat: Double, lon: Double, label: String? = null) {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            reportMessage("请输入有效的纬度（−90–90）和经度（−180–180）"); return
        }
        document = document.copy(home = home.copy(location = Location(lat, lon, label).coarsenedLocation())); changed(successMessage = "大致位置已保存到本机")
    }
    fun clearLocation() { document = document.copy(home = home.copy(location = null)); changed(successMessage = "已清除住宅位置") }
    fun applyWeather(o: WeatherOutcome) {
        weather = o
        o.environment?.let { environment = it }
        changed(false)
    }

    // —— 自动推荐 ——
    fun canRecommend() = paywall(PaidFeature.autoRecommendation) == null
    fun recommend(): List<LayoutSearch.Candidate> {
        val r = room ?: return emptyList()
        if (!gate(PaidFeature.autoRecommendation)) return emptyList()
        return LayoutSearch.search(home, r.id, null, ObstructionModel.apply(environment, home))
    }
    fun applyCandidate(c: LayoutSearch.Candidate, expectedHash: String? = null): Boolean {
        if (expectedHash != null && expectedHash != currentHash()) { reportMessage("房间或风况已变化，请重新生成建议"); return false }
        if (c.room.id != selectedRoomId) { reportMessage("房间已切换，请重新生成建议"); return false }
        return edit { it.replace(c.room) }.also { if (it && lastSaveSucceeded) reportMessage("已采用新摆法，可继续调整") }
    }

    fun addRoom(template: RoomTemplate, name: String): EntityID? {
        if (!gate(PaidFeature.multiRoom)) return null
        val r = Room(name = name, polygon = template.polygon.map { it + Vec2(0.0, (home.rooms.maxOfOrNull { x -> x.polygon.maxOf { p -> p.y } } ?: 0.0) + 1.0) })
        return appendRoom(r)
    }

    /** Create a dimensioned room. Replacement is local to the selected room; other rooms remain intact. */
    fun createRoom(template: RoomTemplate, name: String, width: Double, depth: Double, replaceCurrent: Boolean = true): EntityID? {
        if (!width.isFinite() || !depth.isFinite() || width !in 1.0..30.0 || depth !in 1.0..30.0) {
            reportMessage("房间宽度和进深须为 1–30 米"); return null
        }
        if (!replaceCurrent && !gate(PaidFeature.multiRoom)) return null
        val original = if (replaceCurrent) room else null
        val sized = when (template) {
            is RoomTemplate.Rectangle -> RoomTemplate.Rectangle(width, depth)
            is RoomTemplate.LShape -> {
                val cutWidthRatio = (template.cutWidth / template.width).takeIf { it.isFinite() } ?: 0.4
                val cutDepthRatio = (template.cutDepth / template.depth).takeIf { it.isFinite() } ?: 0.375
                RoomTemplate.LShape(width, depth, width * cutWidthRatio.coerceIn(0.1, 0.9), depth * cutDepthRatio.coerceIn(0.1, 0.9))
            }
        }
        val offset = original?.let { Vec2(it.polygon.minOf { p -> p.x }, it.polygon.minOf { p -> p.y }) }
            ?: Vec2(0.0, (home.rooms.maxOfOrNull { it.polygon.maxOf { p -> p.y } } ?: -1.0) + 1.0)
        val replacement = Room(id = original?.id ?: newId(), name = name.trim().ifBlank { "我的房间" }, polygon = sized.polygon.map { it + offset })
        val nextHome = Topology.refreshConnections(home.copy(rooms = if (original == null) home.rooms + replacement else home.rooms.map { if (it.id == original.id) replacement else it }))
        if (HomeValidator.validate(nextHome).any { it is HomeIssue.RoomsOverlap }) {
            reportMessage("新尺寸与其他房间重叠，请缩小尺寸或新增房间"); return null
        }
        val nextDocument = withGeometryChange(nextHome, original?.id)
        document = nextDocument.copy(
            scenarios = nextDocument.scenarios.filter { it.roomId != original?.id },
            feedback = document.feedback.filter { it.roomId != original?.id },
        )
        editors.remove(replacement.id)
        selectedRoomId = replacement.id
        syncEditors()
        changed(successMessage = "已创建「${replacement.name}」，可以开始试摆")
        return replacement.id
    }

    fun addAdjacentRoom(wallIndex: Int, depth: Double, name: String): Boolean {
        if (!gate(PaidFeature.multiRoom)) return false
        if (!depth.isFinite() || depth !in 1.0..30.0) { reportMessage("房间进深须为 1–30 米"); return false }
        return try {
            val (h, id) = home.addAdjacentRoom(selectedRoomId, wallIndex, depth, name.trim().ifBlank { "新房间" })
            document = withGeometryChange(h)
            selectedRoomId = id; syncEditors(); changed(successMessage = "已添加相邻房间"); true
        } catch (_: Exception) { reportMessage("这面墙外已有房间或位置无效"); false }
    }

    fun deleteRoom(id: EntityID) {
        val deleted = home.rooms.firstOrNull { it.id == id } ?: return
        if (home.rooms.size <= 1) { reportMessage("至少保留一个房间"); return }
        val next = withGeometryChange(home.removeRoom(id), id)
        document = next.copy(scenarios = next.scenarios.filter { it.roomId != id }, feedback = next.feedback.filter { it.roomId != id })
        editors.remove(id)
        if (selectedRoomId == id) selectedRoomId = home.rooms.first().id
        syncEditors(); changed(successMessage = "已删除「${deleted.name}」及其方案")
    }

    fun renameRoom(id: EntityID, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { reportMessage("请填写房间名称"); return }
        if (home.rooms.none { it.id == id }) return
        document = document.copy(home = home.copy(rooms = home.rooms.map { if (it.id == id) it.copy(name = trimmed) else it }))
        syncEditors(); changed(successMessage = "房间名称已保存")
    }

    // —— 方案 ——
    fun saveScenario(name: String): Scenario? {
        val r = room ?: return null
        if (!gate(PaidFeature.unlimitedScenarios)) return null
        val title = name.trim()
        if (title.isEmpty()) { reportMessage("请填写方案名称"); return null }
        val previous = document
        val s = Scenario.capture(title, r, document.geometryRevision, environment, clock())
        document = document.copy(scenarios = document.scenarios + s)
        if (runCatching { repo?.save(document) }.isFailure) {
            lastSaveSucceeded = false
            document = previous; reportMessage("方案未保存，请检查本机存储后重试"); return null
        }
        documentNeedsSave = false
        changed(false, "已保存方案「${s.name}」"); return s
    }

    fun deleteScenario(id: EntityID) {
        val deleted = document.scenarios.firstOrNull { it.id == id } ?: return
        document = document.copy(scenarios = document.scenarios.filter { it.id != id })
        changed(successMessage = "已删除方案「${deleted.name}」")
    }

    fun canApplyScenario(s: Scenario): Boolean {
        val r = home.rooms.firstOrNull { it.id == s.roomId } ?: return false
        return s.geometryRevision == document.geometryRevision && runCatching { validateRoom(s.appliedTo(r)) }.isSuccess
    }

    fun applyScenario(id: EntityID): Boolean {
        val s = document.scenarios.firstOrNull { it.id == id } ?: return false
        val r = home.rooms.firstOrNull { it.id == s.roomId } ?: return false
        if (!canApplyScenario(s)) { reportMessage("户型、门窗或家具已变化，请基于当前房间重新保存方案"); return false }
        val previousSelection = selectedRoomId
        selectedRoomId = r.id
        return edit { it.replace(s.appliedTo(r)) }.also { applied ->
            if (applied) {
                // Restoring layout intentionally retains the current wind for useful A/B comparison.
                changed(false, "已恢复「${s.name}」，沿用当前风况")
            } else selectedRoomId = previousSelection
        }
    }

    fun scenariosForRoom() = document.scenarios.filter { it.roomId == selectedRoomId }

    /** A/B 比较：统一使用当前环境快照。 */
    fun compare(a: EntityID, b: EntityID): ScenarioComparison? {
        val sa = document.scenarios.firstOrNull { it.id == a } ?: return null
        val sb = document.scenarios.firstOrNull { it.id == b } ?: return null
        if (sa.roomId != sb.roomId) return null
        return ScenarioComparison.compare(home, sa.roomId, sa, sb, document.geometryRevision, ObstructionModel.apply(environment, home))
    }

    fun addFeedback(rating: ComfortRating, note: String?) {
        val r = room ?: return
        document = document.copy(feedback = document.feedback + ComfortFeedback(roomId = r.id, scenarioHash = currentHash(), rating = rating, note = note, createdAt = clock()))
        changed(successMessage = "已记录你的体感反馈")
    }

    fun exportJson(includeLocation: Boolean = false) = DocumentExport.json(document, DocumentExport.Options(includeLocation = includeLocation))
    fun exportFileName() = DocumentExport.fileName(document, clock())

    fun resetToSample() {
        editors.clear(); document = SampleData.sampleDocument(); selectedRoomId = SampleData.ROOM_ID
        environment = Environment(EnvironmentSource.manual, clock(), 180.0, windSpeed = 2.0); weather = null
        changed(successMessage = "已恢复示例住宅")
    }
    fun deleteAllData() {
        if (runCatching { repo?.delete(); sessionFile?.let(LocalFileStorage::delete) }.isFailure) {
            lastSaveSucceeded = false
            reportMessage("本机数据未能全部删除，请重试"); return
        }
        editors.clear(); document = SampleData.sampleDocument(); selectedRoomId = SampleData.ROOM_ID
        environment = Environment(EnvironmentSource.manual, clock(), 180.0, windSpeed = 2.0); weather = null
        documentNeedsSave = false; sessionNeedsSave = false; lastSaveSucceeded = true
        revision++; reportMessage("本机住宅、方案与反馈已删除，正在展示内置示例")
    }

    /** External captures use their own local origin, so place them above existing rooms as a new room. */
    fun importRoom(r: Room) {
        if (!gate(PaidFeature.multiRoom)) return
        val positioned = try {
            require(r.polygon.isNotEmpty() && r.polygon.all { it.x.isFinite() && it.y.isFinite() })
            val nextY = (home.rooms.maxOfOrNull { existing -> existing.polygon.maxOf { it.y } } ?: 0.0) + 1.0
            val offset = Vec2(-r.polygon.minOf { it.x }, nextY - r.polygon.minOf { it.y })
            r.copy(
                polygon = r.polygon.map { it + offset },
                fans = r.fans.map { it.copy(position = it.position + offset) },
                obstacles = r.obstacles.map { it.copy(center = it.center + offset) },
                targets = r.targets.map { target -> target.copy(shape = when (val shape = target.shape) {
                    is TargetShape.Circle -> shape.copy(center = shape.center + offset)
                    is TargetShape.Polygon -> shape.copy(points = shape.points.map { it + offset })
                }) },
            )
        } catch (_: Exception) {
            reportMessage("房间数据无效或与已有房间重叠，请检查户型"); return
        }
        appendRoom(positioned)
    }

    private fun appendRoom(r: Room): EntityID? {
        return try {
            validateRoom(r)
            require(home.rooms.none { it.id == r.id })
            val nextHome = Topology.refreshConnections(home.copy(rooms = home.rooms + r))
            require(HomeValidator.validate(nextHome).none { it is HomeIssue.RoomsOverlap })
            document = withGeometryChange(nextHome)
            selectedRoomId = r.id; syncEditors(); changed(successMessage = "已添加「${r.name}」"); r.id
        } catch (_: Exception) { reportMessage("房间数据无效或与已有房间重叠，请检查户型"); null }
    }

    /** Global schema revision with per-room compatibility preserved for unrelated room changes. */
    private fun withGeometryChange(nextHome: Home, changedRoomId: EntityID? = null): HomeDocument {
        val previousRevision = document.geometryRevision
        val nextRevision = previousRevision + 1
        return document.copy(home = nextHome, geometryRevision = nextRevision,
            scenarios = document.scenarios.map { scenario ->
                if (scenario.roomId != changedRoomId && scenario.geometryRevision == previousRevision)
                    scenario.copy(geometryRevision = nextRevision) else scenario
            })
    }

    private fun syncEditors() {
        editors.keys.retainAll(home.rooms.map { it.id }.toSet())
        home.rooms.forEach { editors[it.id]?.sync(it) }
    }

    /** Validate whole staged state as replacement callers may bypass individual editor safeguards. */
    private fun validateRoom(r: Room) {
        fun finite(p: Vec2) = p.x.isFinite() && p.y.isFinite()
        require(r.polygon.all(::finite))
        require(r.openings.all { it.offset.isFinite() && it.width.isFinite() && it.openFraction.isFinite() })
        require(r.obstacles.all { finite(it.center) && finite(it.size) && it.size.x > 0 && it.size.y > 0 && it.rotationDegrees.isFinite() })
        require(r.fans.all { finite(it.position) && it.yawDegrees.isFinite() && it.power.isFinite() && it.power in 0.0..1.0 })
        require(r.targets.all { target -> when (val shape = target.shape) {
            is TargetShape.Circle -> finite(shape.center) && shape.radius.isFinite() && shape.radius > 0 && r.contains(shape.center)
            is TargetShape.Polygon -> shape.points.size >= 3 && shape.points.all { finite(it) && r.contains(it) }
        } })
        val issues = RoomValidator.validate(r)
        if (issues.isNotEmpty()) throw EditError.InvalidGeometry(issues)
        r.fans.forEach { FanPlacement.check(it.position, r)?.let { error -> throw error } }
        val proposedHome = home.copy(rooms = home.rooms.map { if (it.id == r.id) r else it })
        require(HomeValidator.validate(proposedHome).none { it is HomeIssue.RoomsOverlap })
    }

    private fun normalize(degrees: Double) = ((degrees % 360) + 360) % 360

    /** Android session sidecar keeps cross-platform HomeDocument schema unchanged. */
    private fun saveSession() {
        val file = sessionFile ?: return
        val state = buildJsonObject {
            put("version", 1); put("homeId", home.id); put("selectedRoomId", selectedRoomId)
            put("environment", environment?.let { DocumentCodec.json.encodeToJsonElement(Environment.serializer(), it) } ?: JsonNull)
            weather?.forecast?.let { put("weatherForecast", DocumentCodec.json.encodeToJsonElement(WeatherForecast.serializer(), it)) }
        }
        LocalFileStorage.write(file, state.toString())
    }

    private fun restoreSession() {
        val file = sessionFile ?: return
        if (!file.isFile) return
        runCatching {
            val state = Json.parseToJsonElement(file.readText()).jsonObject
            require(state["version"]?.jsonPrimitive?.intOrNull == 1)
            if (state["homeId"]?.jsonPrimitive?.content != home.id) return@runCatching
            state["selectedRoomId"]?.jsonPrimitive?.content?.let { id -> if (home.rooms.any { it.id == id }) selectedRoomId = id }
            state["environment"]?.let { element ->
                environment = if (element == JsonNull) null else DocumentCodec.json.decodeFromJsonElement(Environment.serializer(), element)
                require(environment?.let { it.windFromDegrees.isFinite() && it.windSpeed?.isFinite() != false } != false)
            }
            state["weatherForecast"]?.let { element ->
                val forecast = DocumentCodec.json.decodeFromJsonElement(WeatherForecast.serializer(), element)
                weather = WeatherOutcome(environment, forecast, WeatherCache.freshness(forecast, clock()), true, null)
            }
        }.onFailure {
            environment = Environment(EnvironmentSource.manual, clock(), 180.0, windSpeed = 2.0); weather = null
            sessionNeedsSave = true; lastSaveSucceeded = false
            loadMessage = "房间已恢复；上次风况无法读取，请重新确认风向"
        }
    }
}
