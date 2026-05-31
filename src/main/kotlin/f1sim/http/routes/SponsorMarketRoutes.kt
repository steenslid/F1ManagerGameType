package f1sim.http.routes

import f1sim.game.SponsorMarketService
import f1sim.http.Envelope
import f1sim.http.timed
import io.javalin.Javalin
import io.javalin.http.Context

/**
 * /api/sponsors/market — player sponsor portfolio management.
 *
 *   GET  /api/sponsors/market         — current deals + signable sponsors + slots
 *   POST /api/sponsors/market/sign    — sign a new sponsor
 *   POST /api/sponsors/market/renew   — renew/extend an existing deal
 *   POST /api/sponsors/market/cancel  — drop a deal (frees a slot)
 */
class SponsorMarketRoutes(private val service: SponsorMarketService) {

    fun register(app: Javalin) {
        app.get("/api/sponsors/market", ::view)
        app.post("/api/sponsors/market/sign", ::sign)
        app.post("/api/sponsors/market/renew", ::renew)
        app.post("/api/sponsors/market/cancel", ::cancel)
    }

    private fun view(ctx: Context) = ctx.timed {
        Envelope.encode(service.view(), SponsorMarketService.SponsorMarketDto.serializer())
    }

    private fun sign(ctx: Context) = ctx.timed {
        val req = Envelope.json.decodeFromString(
            SponsorMarketService.SignRequest.serializer(), ctx.body(),
        )
        Envelope.encode(service.sign(req), SponsorMarketService.SponsorMarketDto.serializer())
    }

    private fun renew(ctx: Context) = ctx.timed {
        val req = Envelope.json.decodeFromString(
            SponsorMarketService.RenewRequest.serializer(), ctx.body(),
        )
        Envelope.encode(service.renew(req), SponsorMarketService.SponsorMarketDto.serializer())
    }

    private fun cancel(ctx: Context) = ctx.timed {
        val req = Envelope.json.decodeFromString(
            SponsorMarketService.CancelRequest.serializer(), ctx.body(),
        )
        Envelope.encode(service.cancel(req), SponsorMarketService.SponsorMarketDto.serializer())
    }
}
