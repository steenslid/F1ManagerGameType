package f1sim.http.routes

import f1sim.game.TeamRdService
import f1sim.http.Envelope
import f1sim.http.timed
import io.javalin.Javalin
import io.javalin.http.Context

/**
 * /api/team/rd — player R&D budget.
 *
 *   GET  /api/team/rd   — current car + R&D budget + cash + grid car ratings
 *   POST /api/team/rd   — set the player team's annual R&D budget
 */
class TeamRdRoutes(private val teamRdService: TeamRdService) {

    fun register(app: Javalin) {
        app.get("/api/team/rd", ::view)
        app.post("/api/team/rd", ::setBudget)
    }

    private fun view(ctx: Context) = ctx.timed {
        val view = teamRdService.view()
        Envelope.encode(view, TeamRdService.RdStateDto.serializer())
    }

    private fun setBudget(ctx: Context) = ctx.timed {
        val req = Envelope.json.decodeFromString(
            TeamRdService.SetRdRequest.serializer(),
            ctx.body(),
        )
        val view = teamRdService.setBudget(req)
        Envelope.encode(view, TeamRdService.RdStateDto.serializer())
    }
}
