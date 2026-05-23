package f1sim.http.routes

import f1sim.db.Database
import f1sim.http.Envelope
import f1sim.save.SaveSession
import io.javalin.Javalin
import io.javalin.http.Context
import kotlinx.serialization.Serializable

/**
 * /api/game — game state queries against the currently loaded save.
 *
 * Stub: only `/state` for now, reading from the `game` table. The phase
 * machine endpoints (/actions, /advance) will follow once the phase logic
 * is built.
 */
class GameRoutes(private val db: Database) {

    @Serializable
    data class GameState(
        val saveId: String,
        val saveName: String,
        val managerName: String,
        val difficulty: String,
        val currentSeasonYear: Int,
        val currentRound: Int,
        val currentPhase: String,
    )

    fun register(app: Javalin) {
        app.get("/api/game/state", ::state)
    }

    private fun state(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded() // throws if nothing loaded -> 400 BAD_STATE
        val state = db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT save_id, save_name, player_manager_name, difficulty,
                       current_season_year, current_round, current_phase
                  FROM game
                """.trimIndent()
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "Loaded save has no game row" }
                    GameState(
                        saveId = rs.getString("save_id"),
                        saveName = rs.getString("save_name"),
                        managerName = rs.getString("player_manager_name"),
                        difficulty = rs.getString("difficulty"),
                        currentSeasonYear = rs.getInt("current_season_year"),
                        currentRound = rs.getInt("current_round"),
                        currentPhase = rs.getString("current_phase"),
                    )
                }
            }
        }
        Envelope.encode(state, GameState.serializer())
    }
}
