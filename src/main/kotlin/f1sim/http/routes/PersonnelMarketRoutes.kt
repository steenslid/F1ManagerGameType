package f1sim.http.routes

import f1sim.game.PersonnelMarketService
import f1sim.http.Envelope
import f1sim.http.timed
import io.javalin.Javalin
import io.javalin.http.Context

/**
 * /api/personnel/market — hire and fire the player's department heads.
 *
 *   GET  /api/personnel/market          — role slots + signable free agents
 *   POST /api/personnel/market/hire     — { personnelId, role }
 *   POST /api/personnel/market/release  — { personnelId }
 */
class PersonnelMarketRoutes(private val service: PersonnelMarketService) {

    fun register(app: Javalin) {
        app.get("/api/personnel/market", ::view)
        app.post("/api/personnel/market/hire", ::hire)
        app.post("/api/personnel/market/release", ::release)
    }

    private fun view(ctx: Context) = ctx.timed {
        Envelope.encode(service.view(), PersonnelMarketService.StaffMarketDto.serializer())
    }

    private fun hire(ctx: Context) = ctx.timed {
        val req = Envelope.json.decodeFromString(
            PersonnelMarketService.HireRequest.serializer(), ctx.body(),
        )
        Envelope.encode(service.hire(req), PersonnelMarketService.StaffMarketDto.serializer())
    }

    private fun release(ctx: Context) = ctx.timed {
        val req = Envelope.json.decodeFromString(
            PersonnelMarketService.ReleaseRequest.serializer(), ctx.body(),
        )
        Envelope.encode(service.release(req), PersonnelMarketService.StaffMarketDto.serializer())
    }
}
