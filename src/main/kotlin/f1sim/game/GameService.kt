package f1sim.game

import f1sim.db.Database
import f1sim.http.NotFoundException
import f1sim.save.SaveSession
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Game-state orchestration. Responsibilities:
 *   - read the current game state (overview)
 *   - advance one phase via [PhaseMachine] and persist
 *   - report the actions available in the current phase
 *   - let the player pick their team after save creation
 *
 * Season length is queried from the `races` table per year. Sprint format
 * for the current round is also queried per advance — PhaseMachine takes
 * both as parameters.
 *
 * Transition hooks are stubbed — each branch returns `emptyList()` for now.
 * That's where the off-season pipeline, race sim, R&D ticks, etc. eventually
 * plug in. They run inside the same transaction as the state write, so a
 * failing hook means the phase doesn't advance.
 */
class GameService(private val db: Database) {

    private val log = LoggerFactory.getLogger(GameService::class.java)

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
        val playerTeam: PlayerTeamDto? = null,
    )

    @Serializable
    data class PlayerTeamDto(
        val id: String,
        val name: String,
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

    @Serializable
    data class SelectTeamRequest(val teamId: String)

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    fun overview(): GameOverviewDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn -> readOverview(conn) }
    }

    fun advance(): AdvanceResultDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn ->
            conn.autoCommit = false
            try {
                val before = readPhaseState(conn)
                val roundsThisSeason = countRoundsInSeason(conn, before.year)
                val isSprintWeekend = isSprintRound(conn, before.year, before.round)
                val after = PhaseMachine.next(before, roundsThisSeason, isSprintWeekend)
                log.info(
                    "Transition: {} y{} r{} -> {} y{} r{} (season has {} rounds; sprint? {})",
                    before.phase, before.year, before.round,
                    after.phase, after.year, after.round,
                    roundsThisSeason, isSprintWeekend,
                )
                val events = runTransitionHooks(conn, before, after)
                writePhaseState(conn, after)
                touchLastPlayed(conn)
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

    fun selectTeam(teamId: String): GameOverviewDto {
        SaveSession.requireLoaded()
        require(teamId.isNotBlank()) { "teamId must not be blank" }

        return db.withConnection { conn ->
            // Validate the team exists in the loaded save.
            val exists = conn.prepareStatement("SELECT 1 FROM teams WHERE id = ?").use { stmt ->
                stmt.setString(1, teamId)
                stmt.executeQuery().use { rs -> rs.next() }
            }
            if (!exists) throw NotFoundException("No team with id $teamId")

            conn.prepareStatement("UPDATE game SET player_team_id = ?").use { stmt ->
                stmt.setString(1, teamId)
                stmt.executeUpdate()
            }
            log.info("Player team set to {}", teamId)
            readOverview(conn)
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun readOverview(conn: Connection): GameOverviewDto {
        conn.prepareStatement(
            """
            SELECT g.save_id, g.save_name, g.player_manager_name, g.difficulty,
                   g.current_season_year, g.current_round, g.current_phase,
                   g.player_team_id, t.name AS player_team_name
              FROM game g
              LEFT JOIN teams t ON t.id = g.player_team_id
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "Loaded save has no game row" }
                val teamId = rs.getString("player_team_id")
                val teamName = rs.getString("player_team_name")
                val playerTeam = if (teamId != null) {
                    PlayerTeamDto(id = teamId, name = teamName ?: teamId)
                } else null
                return GameOverviewDto(
                    saveId = rs.getString("save_id"),
                    saveName = rs.getString("save_name"),
                    managerName = rs.getString("player_manager_name"),
                    difficulty = rs.getString("difficulty"),
                    year = rs.getInt("current_season_year"),
                    round = rs.getInt("current_round"),
                    phase = rs.getString("current_phase"),
                    playerTeam = playerTeam,
                )
            }
        }
    }

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

    private fun touchLastPlayed(conn: Connection) {
        val saveId = SaveSession.requireLoaded().saveId
        conn.prepareStatement(
            "UPDATE public.saves SET last_played_at = now() WHERE save_id = ?"
        ).use { stmt ->
            stmt.setObject(1, saveId)
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
                        "No calendar for year {} — using fallback round count {}.",
                        year, fallbackRoundsPerSeason,
                    )
                    fallbackRoundsPerSeason
                }
            }
        }
    }

    /**
     * Returns true if the given (year, round) is a SPRINT race. Round 0 or any
     * round not in the calendar returns false.
     */
    private fun isSprintRound(conn: Connection, year: Int, round: Int): Boolean {
        if (round <= 0) return false
        conn.prepareStatement(
            "SELECT session_format FROM races WHERE season_year = ? AND round = ?"
        ).use { stmt ->
            stmt.setInt(1, year)
            stmt.setInt(2, round)
            stmt.executeQuery().use { rs ->
                return rs.next() && rs.getString("session_format") == "SPRINT"
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
