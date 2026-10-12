package app.fengxiangjia.core.persistence

import app.fengxiangjia.core.domain.ComfortFeedback
import app.fengxiangjia.core.domain.Home
import app.fengxiangjia.core.domain.Scenario
import app.fengxiangjia.core.domain.newId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File

/** 持久化根对象（与 iOS `HomeDocument` 同 schema）。 */
@Serializable
data class HomeDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val geometryRevision: Int = 0,
    val home: Home,
    val scenarios: List<Scenario> = emptyList(),
    /** v2 新增：体感反馈。 */
    val feedback: List<ComfortFeedback> = emptyList(),
) {
    companion object { const val CURRENT_SCHEMA_VERSION = 2 }
}

sealed class PersistenceException(msg: String) : Exception(msg) {
    data object MissingSchemaVersion : PersistenceException("missingSchemaVersion") { private fun readResolve(): Any = MissingSchemaVersion }
    data class UnsupportedFutureVersion(val version: Int) : PersistenceException("unsupportedFutureVersion($version)")
    data class Corrupt(val reason: String) : PersistenceException("corrupt($reason)")
}

/** 编解码与迁移：按 JSON 对象逐版本升级 v0 → v1 → ... → current。 */
object DocumentCodec {
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false     // 与 Swift synthesized Codable 一致：nil 不写键
        ignoreUnknownKeys = true  // 前向兼容：iOS 新增可选字段不致崩溃
    }

    fun encode(doc: HomeDocument): String = json.encodeToString(HomeDocument.serializer(), doc)

    fun decode(text: String): HomeDocument {
        val root = try { Json.parseToJsonElement(text) } catch (e: Exception) { throw PersistenceException.Corrupt("not json") }
        var obj = root as? JsonObject ?: throw PersistenceException.Corrupt("root is not an object")
        val version = (obj["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: throw PersistenceException.MissingSchemaVersion
        if (version > HomeDocument.CURRENT_SCHEMA_VERSION) throw PersistenceException.UnsupportedFutureVersion(version)
        var v = version
        while (v < HomeDocument.CURRENT_SCHEMA_VERSION) {
            val step = migrations[v] ?: throw PersistenceException.Corrupt("no migration from v$v")
            obj = JsonObject(step(obj) + ("schemaVersion" to JsonPrimitive(v + 1)))
            v += 1
        }
        return try { json.decodeFromJsonElement(HomeDocument.serializer(), obj) }
        catch (e: Exception) { throw PersistenceException.Corrupt(e.message ?: e.toString()) }
    }

    private fun JsonObject.with(vararg kv: Pair<String, JsonElement>) = JsonObject(this + kv)
    private val emptyArr = JsonArray(emptyList())

    /** 迁移表：key = 源版本（与 Swift 迁移逐条对应）。 */
    internal val migrations: Map<Int, (JsonObject) -> JsonObject> = mapOf(
        // v0：开发期原型（顶点键名 points，无 wallIds、geometryRevision）
        0 to { doc ->
            var home = doc["home"] as? JsonObject ?: throw PersistenceException.Corrupt("missing home")
            val rooms = (home["rooms"] as? JsonArray ?: emptyArr).map { el ->
                var r = el as? JsonObject ?: throw PersistenceException.Corrupt("room")
                r["points"]?.let { pts -> r = JsonObject(r - "points" + ("polygon" to pts)) }
                val n = (r["polygon"] as? JsonArray)?.size ?: 0
                if (r["wallIds"] == null) r = r.with("wallIds" to JsonArray(List(n) { JsonPrimitive(newId()) }))
                for (k in listOf("openings", "obstacles", "fans", "targets")) if (r[k] == null) r = r.with(k to emptyArr)
                if (r["purpose"] == null) r = r.with("purpose" to JsonPrimitive("other"))
                r
            }
            home = home.with("rooms" to JsonArray(rooms))
            if (home["constraints"] == null) home = home.with("constraints" to emptyArr)
            if (home["orientationDegrees"] == null) home = home.with("orientationDegrees" to JsonPrimitive(0))
            var d = doc.with("home" to home)
            if (d["geometryRevision"] == null) d = d.with("geometryRevision" to JsonPrimitive(0))
            if (d["scenarios"] == null) d = d.with("scenarios" to emptyArr)
            d
        },
        // v1 → v2：新增 feedback
        1 to { doc -> if (doc["feedback"] == null) doc.with("feedback" to emptyArr) else doc },
    )
}

/** 首页加载状态。 */
sealed interface HomeLoadState {
    data object Loading : HomeLoadState
    data object Empty : HomeLoadState
    data class Loaded(val document: HomeDocument) : HomeLoadState
    data class Failed(val message: String) : HomeLoadState
}

/** 本地文件存储（App 私有目录，仅本地，不上传）。 */
class HomeRepository(directory: File, fileName: String = "home.json") {
    val file = File(directory, fileName)

    fun load(): HomeLoadState {
        if (!file.exists()) return HomeLoadState.Empty
        return try { HomeLoadState.Loaded(DocumentCodec.decode(file.readText())) }
        catch (e: PersistenceException.UnsupportedFutureVersion) { HomeLoadState.Failed("文件来自较新版本（schema v${e.version}），请更新 App") }
        catch (e: PersistenceException) { HomeLoadState.Failed("保存的数据已损坏，无法读取") }
        catch (e: Exception) { HomeLoadState.Failed("读取失败：${e.message}") }
    }

    fun save(doc: HomeDocument) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(DocumentCodec.encode(doc))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun delete() { if (file.exists()) file.delete() }
}

