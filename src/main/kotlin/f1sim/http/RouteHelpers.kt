package f1sim.http

import io.javalin.http.Context
import kotlinx.serialization.json.JsonElement
import java.sql.ResultSet

/**
 * Thrown by route handlers when a requested resource doesn't exist. The [timed]
 * helper maps it to HTTP 404 with error code "NOT_FOUND".
 */
class NotFoundException(message: String) : RuntimeException(message)

/**
 * Wraps a route handler body in:
 *   * Timing (writes elapsed_ms into the response meta)
 *   * Envelope encoding (`{ data, errors, meta }`)
 *   * Exception → HTTP status mapping
 *
 * Status mapping:
 *   NotFoundException        -> 404 NOT_FOUND
 *   IllegalArgumentException -> 400 BAD_REQUEST  (e.g. malformed input)
 *   IllegalStateException    -> 400 BAD_STATE    (e.g. no save loaded)
 *   anything else            -> 500 INTERNAL_ERROR
 */
internal inline fun Context.timed(block: () -> JsonElement) {
    val start = System.nanoTime()
    try {
        val data = block()
        val elapsed = (System.nanoTime() - start) / 1_000_000
        contentType("application/json")
        result(Envelope.success(data, elapsed))
    } catch (t: Throwable) {
        val elapsed = (System.nanoTime() - start) / 1_000_000
        val (httpStatus, code) = when (t) {
            is NotFoundException -> 404 to "NOT_FOUND"
            is IllegalArgumentException -> 400 to "BAD_REQUEST"
            is IllegalStateException -> 400 to "BAD_STATE"
            else -> 500 to "INTERNAL_ERROR"
        }
        status(httpStatus)
        contentType("application/json")
        result(Envelope.failure(Envelope.ApiError(code, t.message ?: code), elapsed))
    }
}

/** Read an INT column allowing SQL NULL → Kotlin null. */
internal fun ResultSet.getIntOrNull(col: String): Int? {
    val v = getInt(col)
    return if (wasNull()) null else v
}
