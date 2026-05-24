package f1sim.http.routes

import f1sim.game.StandingsService
import f1sim.http.Envelope
import f1sim.http.timed
import io.javalin.Javalin
import io.javalin.http.Context

/**
 * /api/standings — season standings.
 *
 * Query params:
 *   ?type=driver | team | both   (default: both)
 *   ?season={year}               (default: current season)
 */
class StandingsRoutes(private val standingsService: StandingsService) {

    fun register(app: Javalin) {
        app.get("/api/standings", ::list)
    }

    private fun list(ctx: Context) = ctx.timed {
        val type = ctx.queryParam("type") ?: "both"
        val season = ctx.queryParam("season")?.toIntOrNull()
        val response = standingsService.standings(type, season)
        Envelope.encode(response, StandingsService.StandingsResponseDto.serializer())
    }
}
