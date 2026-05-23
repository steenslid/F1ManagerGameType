package f1sim.http.routes

import f1sim.http.Envelope
import f1sim.save.SaveService
import io.javalin.Javalin
import io.javalin.http.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import java.util.UUID

/**
 * /api/saves — list, create, load, delete.
 *
 * Per API spec from the design doc:
 *   GET    /api/saves
 *   POST   /api/saves
 *   POST   /api/saves/{id}/load
 *   DELETE /api/saves/{id}
 */
class SaveRoutes(private val saveService: SaveService) {

    fun register(app: Javalin) {
        app.get("/api/saves", ::list)
        app.post("/api/saves", ::create)
        app.post("/api/saves/{id}/load", ::load)
        app.delete("/api/saves/{id}", ::delete)
    }

    private fun list(ctx: Context) = ctx.timed {
        val saves = saveService.list()
        Envelope.encode(saves, ListSerializer(SaveService.SaveSummary.serializer()))
    }

    private fun create(ctx: Context) = ctx.timed {
        val req = Envelope.json.decodeFromString(
            SaveService.CreateSaveRequest.serializer(),
            ctx.body()
        )
        val summary = saveService.create(req)
        ctx.status(201)
        Envelope.encode(summary, SaveService.SaveSummary.serializer())
    }

    private fun load(ctx: Context) = ctx.timed {
        val id = UUID.fromString(ctx.pathParam("id"))
        val summary = saveService.load(id)
        Envelope.encode(summary, SaveService.SaveSummary.serializer())
    }

    private fun delete(ctx: Context) = ctx.timed {
        val id = UUID.fromString(ctx.pathParam("id"))
        saveService.delete(id)
        JsonNull
    }
}

/**
 * Inline helper: time the handler, write envelope, set content type.
 * Kept here as an extension so routes stay compact.
 */
internal inline fun Context.timed(block: () -> kotlinx.serialization.json.JsonElement) {
    val start = System.nanoTime()
    try {
        val data = block()
        val elapsed = (System.nanoTime() - start) / 1_000_000
        contentType("application/json")
        result(Envelope.success(data, elapsed))
    } catch (t: Throwable) {
        val elapsed = (System.nanoTime() - start) / 1_000_000
        val code = when (t) {
            is IllegalArgumentException -> "BAD_REQUEST"
            is IllegalStateException -> "BAD_STATE"
            else -> "INTERNAL_ERROR"
        }
        status(if (code == "INTERNAL_ERROR") 500 else 400)
        contentType("application/json")
        result(Envelope.failure(Envelope.ApiError(code, t.message ?: code), elapsed))
    }
}
