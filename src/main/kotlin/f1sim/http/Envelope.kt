package f1sim.http

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The shared response envelope. Every successful body is `{ data, errors, meta }`.
 *
 * Kept ad-hoc with JsonElement so each route can return its own data shape
 * without needing a generic-over-T container.
 */
object Envelope {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Serializable
    data class ApiError(val code: String, val message: String)

    fun success(data: JsonElement, elapsedMs: Long): String {
        val obj = buildJsonObject {
            put("data", data)
            put("errors", json.encodeToJsonElement(ListSerializer(ApiError.serializer()), emptyList()))
            put("meta", buildJsonObject { put("elapsed_ms", elapsedMs) })
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    fun failure(error: ApiError, elapsedMs: Long): String {
        val obj = buildJsonObject {
            put("data", JsonNull)
            put("errors", json.encodeToJsonElement(ListSerializer(ApiError.serializer()), listOf(error)))
            put("meta", buildJsonObject { put("elapsed_ms", elapsedMs) })
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /** Helper for routes: encode arbitrary data with its serializer into the envelope. */
    fun <T> encode(data: T, serializer: KSerializer<T>): JsonElement =
        json.encodeToJsonElement(serializer, data)
}
