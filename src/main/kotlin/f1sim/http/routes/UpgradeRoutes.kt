package f1sim.http.routes

import f1sim.game.UpgradeService
import f1sim.http.Envelope
import f1sim.http.timed
import io.javalin.Javalin
import io.javalin.http.Context

/**
 * /api/team/upgrades — in-season R&D upgrade projects.
 *
 *   GET  /api/team/upgrades   — sizes, in-progress projects, car areas, cash
 *   POST /api/team/upgrades   — commission an upgrade { area, size }
 */
class UpgradeRoutes(private val upgradeService: UpgradeService) {

    fun register(app: Javalin) {
        app.get("/api/team/upgrades", ::view)
        app.post("/api/team/upgrades", ::commission)
    }

    private fun view(ctx: Context) = ctx.timed {
        Envelope.encode(upgradeService.view(), UpgradeService.UpgradeStateDto.serializer())
    }

    private fun commission(ctx: Context) = ctx.timed {
        val req = Envelope.json.decodeFromString(
            UpgradeService.CommissionRequest.serializer(), ctx.body(),
        )
        Envelope.encode(upgradeService.commission(req), UpgradeService.UpgradeStateDto.serializer())
    }
}
