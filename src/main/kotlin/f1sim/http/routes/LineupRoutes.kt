package f1sim.http.routes

import f1sim.game.LineupService
import f1sim.http.Envelope
import f1sim.http.timed
import io.javalin.Javalin
import io.javalin.http.Context

/**
 * /api/team/lineup — mid-season driver lineup (reserve / junior call-up).
 *
 *   GET  /api/team/lineup        — current race drivers + call-up candidates
 *   POST /api/team/lineup/swap   — swap a race driver out for a call-up
 *
 * Swaps are only honoured during BETWEEN_ROUNDS (enforced in the service).
 */
class LineupRoutes(private val lineupService: LineupService) {

    fun register(app: Javalin) {
        app.get("/api/team/lineup", ::view)
        app.post("/api/team/lineup/swap", ::swap)
    }

    private fun view(ctx: Context) = ctx.timed {
        val view = lineupService.viewLineup()
        Envelope.encode(view, LineupService.LineupDto.serializer())
    }

    private fun swap(ctx: Context) = ctx.timed {
        val req = Envelope.json.decodeFromString(
            LineupService.SwapRequest.serializer(),
            ctx.body(),
        )
        val view = lineupService.swap(req)
        Envelope.encode(view, LineupService.LineupDto.serializer())
    }
}
