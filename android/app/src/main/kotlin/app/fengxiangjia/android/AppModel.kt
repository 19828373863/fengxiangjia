package app.fengxiangjia.android

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.editor.EditError
import app.fengxiangjia.core.editor.RoomEditor
import app.fengxiangjia.core.editor.RoomTemplate
import app.fengxiangjia.core.geometry.Topology
import app.fengxiangjia.core.home.addAdjacentRoom
import app.fengxiangjia.core.home.removeRoom
import app.fengxiangjia.core.library.SampleData
import app.fengxiangjia.core.persistence.DocumentExport
import app.fengxiangjia.core.persistence.HomeDocument
import app.fengxiangjia.core.persistence.HomeLoadState
import app.fengxiangjia.core.persistence.HomeRepository
import app.fengxiangjia.core.recommendation.ScenarioComparison
import app.fengxiangjia.core.recommendation.appliedTo
import app.fengxiangjia.core.recommendation.capture
import app.fengxiangjia.core.simulation.ScenarioHash
import app.fengxiangjia.core.simulation.SimulationInput
import app.fengxiangjia.core.weather.ObstructionModel
import java.time.Instant

/**
 * 应用状态与操作（纯 Kotlin，不依赖 Android，可在 JVM 单测）。
 * 所有房间编辑经 [RoomEditor]，保证撤销/重做与校验规则与 iOS 一致；每次成功修改后自动保存。
 */
class AppModel(private val repo: HomeRepository?, private val clock: () -> Instant = Instant::now) {
    var document: HomeDocument; private set
    var selectedRoomId: EntityID; private set
    /** 手动环境（天气 provider 未接入前的演示/手动输入）。null = 未设置。 */
    var environment: Environment?; private set
    var loadMessage: String? = null; private set
    var lastError: String? = null; private set
    private val editors = mutableMapOf<EntityID, RoomEditor>()
    var revision = 0; private set
    private val listeners = mutableListOf<() -> Unit>()

    init {
        val st = repo?.load() ?: HomeLoadState.Empty
        document = when (st) {
            is HomeLoadState.Loaded -> st.document
            is HomeLoadState.Failed -> { loadMessage = st.message; SampleData.sampleDocument() }
            else -> SampleData.sampleDocument()
        }
        selectedRoomId = document.home.rooms.firstOrNull()?.id ?: SampleData.ROOM_ID
        environment = Environment(EnvironmentSource.manual, clock(), 180.0, windSpeed = 2.0)
    }

    fun observe(l: () -> Unit) { listeners += l }
    private fun changed(save: Boolean = true) {
        revision++
        if (save) runCatching { repo?.save(document) }.onFailure { lastError = "保存失败：${it.message}" }
        listeners.forEach { it() }
    }

    val home get() = document.home
    val room: Room? get() = home.rooms.firstOrNull { it.id == selectedRoomId }
    fun editor(): RoomEditor? = room?.let { r -> editors.getOrPut(r.id) { RoomEditor(r) }.also { if (it.room != r) editors[r.id] = RoomEditor(r) } }

    /** 当前输入（风线与建议共用这一份）。遮挡设置按住宅写入环境快照。 */
    fun simulationInput(): SimulationInput? = room?.let { SimulationInput(home, it.id, ObstructionModel.apply(environment, home)) }
    fun currentHash(): String? = simulationInput()?.let { ScenarioHash.compute(it) }

    fun selectRoom(id: EntityID) { if (home.rooms.any { it.id == id }) { selectedRoomId = id; changed(false) } }
    fun clearError() { lastError = null; listeners.forEach { it() } }

    /** 执行房间编辑；失败时记录中文错误，不改变状态。 */
    fun edit(op: (RoomEditor) -> Unit): Boolean {
        val e = editor() ?: return false
        return try {
            op(e); commitRoom(e.room); lastError = null; true
        } catch (err: EditError) { lastError = err.userMessage; listeners.forEach { it() }; false }
    }

    fun undo() { editor()?.let { if (it.canUndo) { it.undo(); commitRoom(it.room) } } }
    fun redo() { editor()?.let { if (it.canRedo) { it.redo(); commitRoom(it.room) } } }

    private fun commitRoom(r: Room) {
        val geometryChanged = home.rooms.first { it.id == r.id }.let { it.polygon != r.polygon || it.wallIds != r.wallIds }
        val h = Topology.refreshConnections(home.copy(rooms = home.rooms.map { if (it.id == r.id) r else it }))
        document = document.copy(home = h, geometryRevision = document.geometryRevision + if (geometryChanged) 1 else 0)
        editors[r.id]?.sync(h.rooms.first { x -> x.id == r.id })
        changed()
    }

    fun setEnvironment(windFrom: Double?, windSpeed: Double?) {
        environment = windFrom?.let { Environment(EnvironmentSource.manual, clock(), it, windSpeed = windSpeed) }
        changed(false)
    }

    fun setOrientation(deg: Double) { document = document.copy(home = home.copy(orientationDegrees = ((deg % 360) + 360) % 360)); changed() }
    fun setFloor(floor: Int?) { document = document.copy(home = home.copy(floorLevel = floor)); changed() }

    fun addRoom(template: RoomTemplate, name: String): EntityID {
        val r = Room(name = name, polygon = template.polygon.map { it + Vec2(0.0, (home.rooms.maxOfOrNull { x -> x.polygon.maxOf { p -> p.y } } ?: 0.0) + 1.0) })
        document = document.copy(home = Topology.refreshConnections(home.copy(rooms = home.rooms + r)))
        selectedRoomId = r.id; changed(); return r.id
    }

    fun addAdjacentRoom(wallIndex: Int, depth: Double, name: String): Boolean = try {
        val (h, id) = home.addAdjacentRoom(selectedRoomId, wallIndex, depth, name)
        document = document.copy(home = h); selectedRoomId = id; changed(); true
    } catch (e: Exception) { lastError = "这面墙外已有房间或位置无效"; listeners.forEach { it() }; false }

    fun deleteRoom(id: EntityID) {
        if (home.rooms.size <= 1) { lastError = "至少保留一个房间"; listeners.forEach { it() }; return }
        document = document.copy(home = home.removeRoom(id), scenarios = document.scenarios.filter { it.roomId != id })
        editors.remove(id); selectedRoomId = home.rooms.first().id; changed()
    }

    fun renameRoom(id: EntityID, name: String) {
        document = document.copy(home = home.copy(rooms = home.rooms.map { if (it.id == id) it.copy(name = name) else it })); changed()
    }

    // —— 方案 ——
    fun saveScenario(name: String): Scenario? {
        val r = room ?: return null
        val s = Scenario.capture(name, r, document.geometryRevision, environment, clock())
        document = document.copy(scenarios = document.scenarios + s); changed(); return s
    }

    fun deleteScenario(id: EntityID) { document = document.copy(scenarios = document.scenarios.filter { it.id != id }); changed() }

    fun applyScenario(id: EntityID) {
        val s = document.scenarios.firstOrNull { it.id == id } ?: return
        val r = home.rooms.firstOrNull { it.id == s.roomId } ?: return
        selectedRoomId = r.id
        edit { it.replace(s.appliedTo(r)) }
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
        changed()
    }

    fun exportJson(includeLocation: Boolean = false) = DocumentExport.json(document, DocumentExport.Options(includeLocation = includeLocation))
    fun exportFileName() = DocumentExport.fileName(document, clock())

    fun resetToSample() { editors.clear(); document = SampleData.sampleDocument(); selectedRoomId = SampleData.ROOM_ID; changed() }
    fun deleteAllData() { repo?.delete(); editors.clear(); document = SampleData.sampleDocument(); selectedRoomId = SampleData.ROOM_ID; changed(false) }

    /** 外部导入（扫描结果等）：作为新房间加入并选中。 */
    fun importRoom(r: Room) {
        document = document.copy(home = Topology.refreshConnections(home.copy(rooms = home.rooms + r)))
        selectedRoomId = r.id; changed()
    }
}
