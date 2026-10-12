package app.fengxiangjia.core.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 实体 ID：大写 UUID 字符串（与 Swift `UUID.uuidString` 一致）。 */
typealias EntityID = String

fun newId(): EntityID = UUID.randomUUID().toString().uppercase()

/** Swift `JSONEncoder.dateEncodingStrategy = .iso8601`：秒精度、UTC、`Z` 结尾。 */
object Iso8601Serializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Iso8601", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS)))
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/** 大写化 UUID（Swift 解码时接受大小写，编码恒为大写）。 */
object EntityIdSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("EntityID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value.uppercase())
    override fun deserialize(decoder: Decoder): String = decoder.decodeString().uppercase()
}

internal fun JsonDecoder.obj(): JsonObject = decodeJsonElement() as? JsonObject
    ?: throw SerializationException("expected object")

/**
 * Swift `Dictionary<UUID, V>` 的 Codable 编码：键不是 String/Int 时编码为扁平数组 `[k1, v1, k2, v2, ...]`。
 * 为跨平台兼容，Android 端也按此形状读写；解码时也接受普通对象 `{k: v}`。
 */
class UuidKeyedMapSerializer<V>(private val valueSerializer: KSerializer<V>) : KSerializer<Map<EntityID, V>> {
    override val descriptor: SerialDescriptor = ListSerializer(JsonElement.serializer()).descriptor
    override fun serialize(encoder: Encoder, value: Map<EntityID, V>) {
        val je = encoder as JsonEncoder
        val items = value.entries.sortedBy { it.key }.flatMap {
            listOf(JsonPrimitive(it.key.uppercase()), je.json.encodeToJsonElement(valueSerializer, it.value))
        }
        je.encodeJsonElement(JsonArray(items))
    }
    override fun deserialize(decoder: Decoder): Map<EntityID, V> {
        val jd = decoder as JsonDecoder
        return when (val el = jd.decodeJsonElement()) {
            is JsonArray -> {
                if (el.size % 2 != 0) throw SerializationException("odd keyed-array length")
                el.chunked(2).associate { (k, v) -> k.jsonPrimitive.content.uppercase() to jd.json.decodeFromJsonElement(valueSerializer, v) }
            }
            is JsonObject -> el.entries.associate { (k, v) -> k.uppercase() to jd.json.decodeFromJsonElement(valueSerializer, v) }
            else -> throw SerializationException("expected keyed array")
        }
    }
}

/** Swift 带关联值枚举的 Codable 形状：`{"case": {"_0": value}}` / `{"case": {"label": value}}` / `{"case": {}}`。 */
internal object SwiftEnum {
    fun single(o: JsonObject): Pair<String, JsonObject> {
        if (o.size != 1) throw SerializationException("enum object must have exactly one key")
        val (k, v) = o.entries.first()
        return k to (v as? JsonObject ?: throw SerializationException("enum payload must be object"))
    }
    val emptyDescriptor = buildClassSerialDescriptor("SwiftEnum")
}

internal fun JsonObject.str(key: String) = (this[key] ?: throw SerializationException("missing $key")).jsonPrimitive.content
internal fun JsonElement.asObj() = jsonObject
