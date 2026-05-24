package f1sim.http.routes

import f1sim.game.RaceWeekendService
import f1sim.http.Envelope
import f1sim.http.timed
import io.javalin.Javalin
import io.javalin.http.Context

/**
 * /api/race-weekend — player decisions during a race weekend.
 *
 * Practice:
 *   GET  /api/race-weekend/practice     — current focus selections
 *   POST /api/race-weekend/practice     — set focus for one driver
 *
 * Strategy:
 *   GET  /api/race-weekend/strategy     — current strategy selections + grid
 *   POST /api/race-weekend/strategy     — set strategy for one driver
 */
class RaceWeekendRoutes(private val raceWeekendService: RaceWeekendService) {

    fun register(app: Javalin) {
        app.get("/api/race-weekend/practice", ::viewPractice)
        app.post("/api/race-weekend/practice", ::setPracticeFocus)
        app.get("/api/race-weekend/strategy", ::viewStrategy)
        app.post("/api/race-weekend/strategy", ::setStrategy)
    }

    private fun viewPractice(ctx: Context) = ctx.timed {
        val view = raceWeekendService.viewPractice()
        Envelope.encode(view, RaceWeekendService.PracticeViewDto.serializer())
    }

    private fun setPracticeFocus(ctx: Context) = ctx.timed {
        val req = Envelope.json.decodeFromString(
            RaceWeekendService.SetPracticeFocusRequest.serializer(),
            ctx.body(),
        )
        val view = raceWeekendService.setPracticeFocus(req)
        Envelope.encode(view, RaceWeekendService.PracticeViewDto.serializer())
    }

    private fun viewStrategy(ctx: Context) = ctx.timed {
        val view = raceWeekendService.viewStrategy()
        Envelope.encode(view, RaceWeekendService.StrategyViewDto.serializer())
    }

    private fun setStrategy(ctx: Context) = ctx.timed {
        val req = Envelope.json.decodeFromString(
            RaceWeekendService.SetStrategyRequest.serializer(),
            ctx.body(),
        )
        val view = raceWeekendService.setStrategy(req)
        Envelope.encode(view, RaceWeekendService.StrategyViewDto.serializer())
    }
}
