package f1sim.game

import f1sim.db.Database
import f1sim.save.SaveSession
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Game-state orchestration. Three responsibilities:
 *   - read the current game state from the loaded save's `game` row
 *   - advance one phase via [PhaseMachine] and persist
 *   - report the actions available in the current phase
 *
 * Season length is queried from the `races` table per year. Until off-season
 * pipeline generates next-year calendars, [countRoundsInSeason] falls back to
 * a default for any season with no calendar rows.
 *
 * Transition hooks are stubbed — each branch returns `emptyList()` for now.
 * That's where the off-season pipeline, race sim, R&D ticks, etc. eventually
 * plug in. They run inside the same transaction as the state write, so a
 * failing hook means the phase doesn't advance.
 */
class GameService(private val db: Database) {

    private val log = LoggerFactory.getLogger(GameService::class.java)

    // Used only when a year has no calendar yet (future seasons before the
    // off-season pipeline has generated their races). Real game flow should
    // never hit this for the current playable year.
    private val fallbackRoundsPerSeason = 24

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    @Serializable
    data class GameOverviewDto(
        val saveId: String,
        val saveName: String,
        val managerName: String,
        val difficulty: String,
        val year: Int,
        val round: Int,
        val phase: String,
    )

    @Serializable
    data class PhaseStateDto(
        val year: Int,
        val round: Int,
        val phase: String,
    )

    @Serializable
    data class AdvanceResultDto(
        val previous: PhaseStateDto,
        val current: PhaseStateDto,
        val events: List<TransitionEventDto>,
    )

    @Serializable
    data class TransitionEventDto(
        val type: String,
        val message: String,
    )

    @Serializable
    data class ActionsDto(
        val phase: String,
        val actions: List<ActionDto>,
    )

    @Serializable
    data class ActionDto(
        val name: String,
        val label: String,
        val method: String,
        val endpoint: String,
    )

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    fun overview(): GameOverviewDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT save_id, save_name, player_manager_name, difficulty,
                       current_season_year, current_round, current_phase
                  FROM game
                """.trimIndent()
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "Loaded save has no game row" }
                    GameOverviewDto(
                        saveId = rs.getString("save_id"),
                        saveName = rs.getString("save_name"),
                        managerName = rs.getString("player_manager_name"),
                        difficulty = rs.getString("difficulty"),
                        year = rs.getInt("current_season_year"),
                        round = rs.getInt("current_round"),
                        phase = rs.getString("current_phase"),
                    )
                }
            }
        }
    }

    fun advance(): AdvanceResultDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn ->
            conn.autoCommit = false
            try {
                val before = readPhaseState(conn)
                val roundsThisSeason = countRoundsInSeason(conn, before.year)
                val after = PhaseMachine.next(before, roundsThisSeason)
                log.info(
                    "Transition: {} y{} r{} -> {} y{} r{} (season has {} rounds)",
                    before.phase, before.year, before.round,
                    after.phase, after.year, after.round,
                    roundsThisSeason,
                )
                val events = runTransitionHooks(conn, before, after)
                writePhaseState(conn, after)
                conn.commit()
                AdvanceResultDto(
                    previous = before.toDto(),
                    current = after.toDto(),
                    events = events,
                )
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun actions(): ActionsDto {
        SaveSession.requireLoaded()
        val state = db.withConnection { readPhaseState(it) }
        val advance = ActionDto(
            name = "advance",
            label = advanceLabel(state.phase),
            method = "POST",
            endpoint = "/api/game/advance",
        )
        return ActionsDto(phase = state.phase.name, actions = listOf(advance))
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun readPhaseState(conn: Connection): GameState {
        conn.prepareStatement(
            "SELECT current_season_year, current_round, current_phase FROM game"
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "No game row in current save" }
                return GameState(
                    year = rs.getInt("current_season_year"),
                    round = rs.getInt("current_round"),
                    phase = Phase.valueOf(rs.getString("current_phase")),
                )
            }
        }
    }

    private fun writePhaseState(conn: Connection, state: GameState) {
        conn.prepareStatement(
            """
            UPDATE game SET
              current_season_year = ?,
              current_round = ?,
              current_phase = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setInt(1, state.year)
            stmt.setInt(2, state.round)
            stmt.setString(3, state.phase.name)
            stmt.executeUpdate()
        }
    }

    private fun countRoundsInSeason(conn: Connection, year: Int): Int {
        conn.prepareStatement("SELECT COUNT(*) FROM races WHERE season_year = ?").use { stmt ->
            stmt.setInt(1, year)
            stmt.executeQuery().use { rs ->
                check(rs.next())
                val count = rs.getInt(1)
                return if (count > 0) count else {
                    log.warn(
                        "No calendar for year {} — using fallback round count {}. " +
                            "The off-season pipeline should generate next-year calendars " +
                            "before play reaches them.",
                        year, fallbackRoundsPerSeason,
                    )
                    fallbackRoundsPerSeason
                }
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun runTransitionHooks(
        conn: Connection,
        from: GameState,
        to: GameState,
    ): List<TransitionEventDto> {
        return when (to.phase) {
            Phase.PRE_SEASON -> emptyList()
            Phase.PRACTICE -> emptyList()
            Phase.QUALIFYING -> emptyList()
            Phase.SPRINT_QUALIFYING -> emptyList()
            Phase.SPRINT -> emptyList()
            Phase.RACE -> emptyList()
            Phase.POST_RACE -> emptyList()
            Phase.BETWEEN_ROUNDS -> emptyList()
            Phase.END_OF_SEASON -> emptyList()
            Phase.OFF_SEASON -> emptyList()
        }
    }

    private fun advanceLabel(from: Phase): String = when (from) {
        Phase.OFF_SEASON -> "Begin pre-season"
        Phase.PRE_SEASON -> "Start first race weekend"
        Phase.PRACTICE -> "Go to qualifying"
        Phase.QUALIFYING -> "Start the race"
        Phase.SPRINT_QUALIFYING -> "Start the sprint"
        Phase.SPRINT -> "Go to qualifying"
        Phase.RACE -> "View race result"
        Phase.POST_RACE -> "Advance to next round"
        Phase.BETWEEN_ROUNDS -> "Start next race weekend"
        Phase.END_OF_SEASON -> "Enter off-season"
    }

    private fun GameState.toDto() = PhaseStateDto(
        year = year,
        round = round,
        phase = phase.name,
    )
}
