package hr.sting.hermes.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

val HermesJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    explicitNulls = false
}

fun JsonObject?.str(key: String): String =
    this?.get(key)?.jsonPrimitive?.contentOrNull ?: ""

fun JsonObject?.strOrNull(key: String): String? =
    this?.get(key)?.jsonPrimitive?.contentOrNull

fun JsonObject?.int(key: String, fallback: Int = 0): Int =
    this?.get(key)?.jsonPrimitive?.intOrNull ?: fallback

fun JsonObject?.long(key: String, fallback: Long = 0L): Long =
    this?.get(key)?.jsonPrimitive?.longOrNull ?: fallback

fun JsonObject?.double(key: String, fallback: Double = 0.0): Double =
    this?.get(key)?.jsonPrimitive?.doubleOrNull ?: fallback

fun JsonObject?.bool(key: String, fallback: Boolean = false): Boolean =
    this?.get(key)?.jsonPrimitive?.booleanOrNull ?: fallback

fun JsonObject?.obj(key: String): JsonObject? = this?.get(key)?.let {
    runCatching { it.jsonObject }.getOrNull()
}

fun JsonObject?.arr(key: String): JsonArray = this?.get(key)?.let {
    runCatching { it as JsonArray }.getOrNull()
} ?: JsonArray(emptyList())

fun JsonObject?.objects(key: String): List<JsonObject> =
    arr(key).mapNotNull { runCatching { it.jsonObject }.getOrNull() }

fun jsonObject(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (key, value) ->
        when (value) {
            null -> Unit
            is String -> put(key, value)
            is Boolean -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            is Double -> put(key, value)
            is JsonElement -> put(key, value)
            else -> put(key, value.toString())
        }
    }
}

fun String.asJsonString(): String = JsonPrimitive(this).toString()

fun JsonObject.pretty(): String = HermesJson.encodeToString(JsonElement.serializer(), this)
