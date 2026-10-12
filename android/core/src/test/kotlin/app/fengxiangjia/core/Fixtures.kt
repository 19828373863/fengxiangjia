package app.fengxiangjia.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File

object Fixtures {
    val dir: File = File(System.getProperty("fxj.fixtures") ?: "../../shared/fixtures")
    fun text(name: String) = File(dir, name).readText()
    fun json(name: String): JsonElement = Json.parseToJsonElement(text(name))

    /** Swift 与 Kotlin JSON 的语义比较：数值按 Double 比较（5 == 5.0），对象键无序。 */
    fun semanticEquals(a: JsonElement, b: JsonElement, path: String = "$"): String? = when {
        a is JsonObject && b is JsonObject -> {
            if (a.keys != b.keys) "$path keys ${a.keys} != ${b.keys}"
            else a.keys.firstNotNullOfOrNull { semanticEquals(a[it]!!, b[it]!!, "$path.$it") }
        }
        a is JsonArray && b is JsonArray ->
            if (a.size != b.size) "$path size ${a.size} != ${b.size}"
            else a.indices.firstNotNullOfOrNull { semanticEquals(a[it], b[it], "$path[$it]") }
        a is JsonNull && b is JsonNull -> null
        a is JsonPrimitive && b is JsonPrimitive -> {
            val same = if (a.isString || b.isString) a.isString == b.isString && a.content == b.content
            else if (a.booleanOrNull != null || b.booleanOrNull != null) a.booleanOrNull == b.booleanOrNull
            else a.doubleOrNull == b.doubleOrNull
            if (same) null else "$path ${a} != ${b}"
        }
        else -> "$path type mismatch $a vs $b"
    }

    /** 把 Swift `[k,v,k,v]` 字典数组按键排序，便于比较（Swift 字典顺序随机）。 */
    fun sortKeyedArrays(e: JsonElement, keys: Set<String> = setOf("obstructionByOpening", "openingStates")): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (k, v) ->
            if (k in keys && v is JsonArray) JsonArray(v.chunked(2).sortedBy { (it[0] as JsonPrimitive).content }.flatten())
            else sortKeyedArrays(v, keys)
        })
        is JsonArray -> JsonArray(e.map { sortKeyedArrays(it, keys) })
        else -> e
    }
}
