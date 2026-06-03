package f1sim.http.routes

import f1sim.game.BoardService
import f1sim.http.Envelope
import f1sim.http.timed
import io.javalin.Javalin
import io.javalin.http.Context

/**
 * /api/board — the player's season board objective and how they're tracking.
 */
class BoardRoutes(private val boardService: BoardService) {

    fun register(app: Javalin) {
        app.get("/api/board", ::view)
    }

    private fun view(ctx: Context) = ctx.timed {
        Envelope.encode(boardService.view(), BoardService.BoardDto.serializer())
    }
}
