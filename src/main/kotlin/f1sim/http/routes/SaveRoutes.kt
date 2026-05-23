package f1sim.http.routes

import f1sim.http.Envelope
import f1sim.http.timed
import f1sim.save.SaveService
import io.javalin.Javalin
import io.javalin.http.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import java.util.UUID

/**
 * /api/saves — list, create, load, delete.
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
