package f1sim.http.routes

import f1sim.game.DriverMarketService
import f1sim.http.Envelope
import f1sim.http.timed
import io.javalin.Javalin
import io.javalin.http.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull

/**
 * /api/market/driver — driver market routes.
 *
 *   GET    /api/market/driver/available         — state + free agents + open seats
 *   GET    /api/market/driver/offers            — player's pending offers
 *   POST   /api/market/driver/offers            — submit/update offer
 *   DELETE /api/market/driver/offers/{driverId} — withdraw offer
 *
 * The market is only "active" during the DRIVER_MARKET phase. Submitting
 * offers outside that phase returns BAD_STATE.
 */
class DriverMarketRoutes(private val driverMarketService: DriverMarketService) {

    fun register(app: Javalin) {
        app.get("/api/market/driver/available", ::available)
        app.get("/api/market/driver/offers", ::offers)
        app.post("/api/market/driver/offers", ::submit)
        app.delete("/api/market/driver/offers/{driverId}", ::withdraw)
    }

    private fun available(ctx: Context) = ctx.timed {
        val v = driverMarketService.viewAvailable()
        Envelope.encode(v, DriverMarketService.AvailableDto.serializer())
    }

    private fun offers(ctx: Context) = ctx.timed {
        val v = driverMarketService.viewPlayerOffers()
        Envelope.encode(v, ListSerializer(DriverMarketService.PlayerOfferDto.serializer()))
    }

    private fun submit(ctx: Context) = ctx.timed {
        val req = Envelope.json.decodeFromString(
            DriverMarketService.SubmitOfferRequest.serializer(),
            ctx.body(),
        )
        val offer = driverMarketService.submitOffer(req)
        Envelope.encode(offer, DriverMarketService.PlayerOfferDto.serializer())
    }

    private fun withdraw(ctx: Context) = ctx.timed {
        driverMarketService.withdrawOffer(ctx.pathParam("driverId"))
        JsonNull
    }
}
