package f1sim.http.routes

import f1sim.game.GameService
import f1sim.http.Envelope
import f1sim.http.timed
import io.javalin.Javalin
import io.javalin.http.Context

/**
 * /api/game — overview, available actions, phase advance, team selection.
 */
class GameRoutes(private val gameService: GameService) {

    fun register(app: Javalin) {
        app.get("/api/game/state", ::state)
        app.get("/api/game/actions", ::actions)
        app.post("/api/game/advance", ::advance)
        app.post("/api/game/select-team", ::selectTeam)
    }

    private fun state(ctx: Context) = ctx.timed {
        val overview = gameService.overview()
        Envelope.encode(overview, GameService.GameOverviewDto.serializer())
    }

    private fun actions(ctx: Context) = ctx.timed {
        val actions = gameService.actions()
        Envelope.encode(actions, GameService.ActionsDto.serializer())
    }

    private fun advance(ctx: Context) = ctx.timed {
        val result = gameService.advance()
        Envelope.encode(result, GameService.AdvanceResultDto.serializer())
    }

    private fun selectTeam(ctx: Context) = ctx.timed {
        val req = Envelope.json.decodeFromString(
            GameService.SelectTeamRequest.serializer(),
            ctx.body(),
        )
        val overview = gameService.selectTeam(req.teamId)
        Envelope.encode(overview, GameService.GameOverviewDto.serializer())
    }
}
