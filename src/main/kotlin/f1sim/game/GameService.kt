package f1sim.game

import f1sim.db.Database
import f1sim.http.NotFoundException
import f1sim.save.SaveSession
import f1sim.sim.RaceSim
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID
import kotlin.random.Random

/**
 * Game-state orchestration. Responsibilities:
 *   - read current state (overview)
 *   - advance one phase via [PhaseMachine] and persist
 *   - report the actions available in the current phase
 *   - let the player pick their team after save creation
 *   - look up the current race (during a weekend)
 *   - drive race-weekend simulation hooks (qualifying, race, post-race)
 *
 * Practice focus (set via [RaceWeekendService]) is consumed in the qualifying
 * and race hooks: drivers with focus = 'SETUP' get a small bonus on
 * stat_qualifying and stat_pace respectively for this race only.
 */
class GameService(private val db: Database) {

    private val log = LoggerFactory.getLogger(GameService::class.java)

    private val fallbackRoundsPerSeason = 24

    /**
     * Multiplier applied to a driver's qualifying / race stat for this weekend
     * when their practice focus = 'SETUP'. Per design doc this should scale by
     * Chief Designer skill (1-3%); v1 uses a flat 1%.
     */
    private val setupBonusMultiplier = 1.01

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

    @Serializable
    data class CurrentRaceDto(
        val raceId: String,
        val seasonYear: Int,
        val round: Int,
        val sessionFormat: String,
        val trackId: String,
        val trackName: String,
        val trackCountry: String,
    )

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

    fun currentRace(): CurrentRaceDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn ->
            val state = readPhaseState(conn)
            if (state.round <= 0) {
                throw NotFoundException("Not in a race weekend (round=${state.round})")
            }
            conn.prepareStatement(
                """
                SELECT r.id, r.season_year, r.round, r.session_format,
                       r.track_id, t.name AS track_name, t.country AS track_country
                  FROM races r
                  JOIN tracks t ON t.id = r.track_id
                 WHERE r.season_year = ? AND r.round = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setInt(1, state.year)
                stmt.setInt(2, state.round)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        throw NotFoundException("No race for y${state.year} r${state.round}")
                    }
                    CurrentRaceDto(
                        raceId = rs.getObject("id", UUID::class.java).toString(),
                        seasonYear = rs.getInt("season_year"),
                        round = rs.getInt("round"),
                        sessionFormat = rs.getString("session_format"),
                        trackId = rs.getString("track_id"),
                        trackName = rs.getString("track_name"),
                        trackCountry = rs.getString("track_country"),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // State persistence helpers
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

    private fun lookupRaceAndSeed(conn: Connection, year: Int, round: Int): Pair<UUID, Long>? {
        conn.prepareStatement(
            """
            SELECT r.id, g.master_rng_seed
              FROM races r
              CROSS JOIN game g
             WHERE r.season_year = ? AND r.round = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setInt(1, year)
            stmt.setInt(2, round)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                return rs.getObject("id", UUID::class.java) to rs.getLong("master_rng_seed")
            }
        }
    }

    private fun fastestLapPointEnabled(conn: Connection, year: Int): Boolean {
        conn.prepareStatement(
            """
            SELECT fastest_lap_point
              FROM regulation_eras
             WHERE start_year <= ?
               AND (end_year IS NULL OR end_year >= ?)
             ORDER BY start_year DESC
             LIMIT 1
            """.trimIndent()
        ).use { stmt ->
            stmt.setInt(1, year)
            stmt.setInt(2, year)
            stmt.executeQuery().use { rs ->
                return rs.next() && rs.getBoolean("fastest_lap_point")
            }
        }
    }

    // ------------------------------------------------------------------
    // Transition hooks
    // ------------------------------------------------------------------

    private fun runTransitionHooks(
        conn: Connection,
        from: GameState,
        to: GameState,
    ): List<TransitionEventDto> {
        @Suppress("UNUSED_PARAMETER")
        return when (to.phase) {
            Phase.PRE_SEASON -> emptyList()
            Phase.PRACTICE -> emptyList()
            Phase.QUALIFYING -> runQualifyingHook(conn, to)
            Phase.SPRINT_QUALIFYING -> emptyList()
            Phase.SPRINT -> emptyList()
            Phase.RACE -> runRaceHook(conn, to)
            Phase.POST_RACE -> runPostRaceHook(conn, to)
            Phase.BETWEEN_ROUNDS -> emptyList()
            Phase.END_OF_SEASON -> emptyList()
            Phase.OFF_SEASON -> emptyList()
        }
    }

    private fun runQualifyingHook(conn: Connection, state: GameState): List<TransitionEventDto> {
        val (raceId, masterSeed) = lookupRaceAndSeed(conn, state.year, state.round)
            ?: run {
                log.warn("No race for y{} r{} — skipping qualifying", state.year, state.round)
                return emptyList()
            }

        // F1 entrants only. LEFT JOIN practice_focus so the SETUP bonus can
        // be applied to drivers who selected it during PRACTICE.
        val entrants = conn.prepareStatement(
            """
            SELECT d.id, d.current_racing_team_id, d.stat_qualifying,
                   pf.focus AS focus
              FROM drivers d
              JOIN teams t ON t.id = d.current_racing_team_id
              LEFT JOIN practice_focus pf
                ON pf.driver_id = d.id AND pf.race_id = ?
             WHERE NOT d.retired
               AND t.series = 'F1'
            """.trimIndent()
        ).use { stmt ->
            stmt.setObject(1, raceId)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val baseStat = rs.getInt("stat_qualifying")
                        val focus = rs.getString("focus")
                        val effectiveStat = if (focus == "SETUP") {
                            (baseStat * setupBonusMultiplier).toInt()
                        } else {
                            baseStat
                        }
                        add(
                            RaceSim.QualifyingEntrant(
                                driverId = rs.getObject("id", UUID::class.java),
                                teamId = rs.getString("current_racing_team_id"),
                                statQualifying = effectiveStat,
                            )
                        )
                    }
                }
            }
        }

        if (entrants.isEmpty()) {
            log.warn("No entrants found for qualifying — skipping")
            return listOf(
                TransitionEventDto("QUALIFYING_SKIPPED", "No drivers eligible to qualify"),
            )
        }

        val rng = Random(masterSeed xor raceId.hashCode().toLong() xor QUALIFYING_SALT)
        val results = RaceSim.simulateQualifying(entrants, rng)

        conn.prepareStatement("DELETE FROM race_results WHERE race_id = ?").use { stmt ->
            stmt.setObject(1, raceId)
            stmt.executeUpdate()
        }
        conn.prepareStatement(
            """
            INSERT INTO race_results
              (race_id, driver_id, team_id, grid_position, status, pole)
            VALUES (?, ?, ?, ?, 'QUALIFIED', ?)
            """.trimIndent()
        ).use { stmt ->
            results.forEach { r ->
                stmt.setObject(1, raceId)
                stmt.setObject(2, r.driverId)
                stmt.setString(3, r.teamId)
                stmt.setInt(4, r.gridPosition)
                stmt.setBoolean(5, r.pole)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

        log.info("Qualifying simulated for race {}: {} entrants", raceId, results.size)
        return listOf(
            TransitionEventDto(
                "QUALIFYING_COMPLETED",
                "${results.size} drivers qualified for race y${state.year} r${state.round}",
            ),
        )
    }

    private fun runRaceHook(conn: Connection, state: GameState): List<TransitionEventDto> {
        val (raceId, masterSeed) = lookupRaceAndSeed(conn, state.year, state.round)
            ?: run {
                log.warn("No race for y{} r{} — skipping race sim", state.year, state.round)
                return emptyList()
            }

        // Pull grid + practice focus (LEFT JOIN). SETUP gives a stat_pace bonus too.
        val entrants = conn.prepareStatement(
            """
            SELECT rr.driver_id, rr.team_id, rr.grid_position,
                   d.stat_pace, d.stat_consistency,
                   pf.focus AS focus
              FROM race_results rr
              JOIN drivers d ON d.id = rr.driver_id
              LEFT JOIN practice_focus pf
                ON pf.driver_id = rr.driver_id AND pf.race_id = rr.race_id
             WHERE rr.race_id = ?
               AND rr.status = 'QUALIFIED'
            """.trimIndent()
        ).use { stmt ->
            stmt.setObject(1, raceId)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val basePace = rs.getInt("stat_pace")
                        val focus = rs.getString("focus")
                        val effectivePace = if (focus == "SETUP") {
                            (basePace * setupBonusMultiplier).toInt()
                        } else {
                            basePace
                        }
                        add(
                            RaceSim.RaceEntrant(
                                driverId = rs.getObject("driver_id", UUID::class.java),
                                teamId = rs.getString("team_id"),
                                gridPosition = rs.getInt("grid_position"),
                                statPace = effectivePace,
                                statConsistency = rs.getInt("stat_consistency"),
                            )
                        )
                    }
                }
            }
        }

        if (entrants.isEmpty()) {
            log.warn("No grid found for race {} — skipping race sim", raceId)
            return listOf(TransitionEventDto("RACE_SKIPPED", "No grid; was qualifying run?"))
        }

        val rng = Random(masterSeed xor raceId.hashCode().toLong() xor RACE_SALT)
        val flEnabled = fastestLapPointEnabled(conn, state.year)
        val results = RaceSim.simulateRace(entrants, rng, fastestLapPointEnabled = flEnabled)

        conn.prepareStatement(
            """
            UPDATE race_results SET
              finishing_position = ?,
              points = ?,
              status = ?,
              fastest_lap = ?,
              dnf_cause = ?
             WHERE race_id = ? AND driver_id = ?
            """.trimIndent()
        ).use { stmt ->
            results.forEach { r ->
                if (r.finishingPosition != null) {
                    stmt.setInt(1, r.finishingPosition)
                } else {
                    stmt.setNull(1, java.sql.Types.INTEGER)
                }
                stmt.setDouble(2, r.points)
                stmt.setString(3, r.status)
                stmt.setBoolean(4, r.fastestLap)
                if (r.dnfCause != null) {
                    stmt.setString(5, r.dnfCause)
                } else {
                    stmt.setNull(5, java.sql.Types.VARCHAR)
                }
                stmt.setObject(6, raceId)
                stmt.setObject(7, r.driverId)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

        val dnfCount = results.count { it.status == "DNF" }
        val finisherCount = results.size - dnfCount
        log.info(
            "Race simulated for {}: {} finishers, {} DNFs (FL bonus enabled: {})",
            raceId, finisherCount, dnfCount, flEnabled,
        )
        val events = mutableListOf(
            TransitionEventDto(
                "RACE_COMPLETED",
                "$finisherCount finishers, $dnfCount DNFs at y${state.year} r${state.round}",
            ),
        )
        results.firstOrNull { it.finishingPosition == 1 }?.let { winner ->
            events += TransitionEventDto("RACE_WINNER", "Driver ${winner.driverId} won")
        }
        return events
    }

    private fun runPostRaceHook(conn: Connection, state: GameState): List<TransitionEventDto> {
        val updated = conn.prepareStatement(
            """
            UPDATE teams t SET season_points = COALESCE(s.total, 0)
              FROM (
                  SELECT rr.team_id, SUM(rr.points) AS total
                    FROM race_results rr
                    JOIN races r ON r.id = rr.race_id
                   WHERE r.season_year = ?
                   GROUP BY rr.team_id
              ) s
             WHERE t.id = s.team_id
            """.trimIndent()
        ).use { stmt ->
            stmt.setInt(1, state.year)
            stmt.executeUpdate()
        }

        log.info("Post-race: rolled season_points for {} teams", updated)
        return listOf(
            TransitionEventDto(
                "STANDINGS_UPDATED",
                "Team standings updated for season ${state.year}",
            ),
        )
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

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

    private companion object {
        const val QUALIFYING_SALT = 0x5111EFA11L
        const val RACE_SALT = 0xACE0FA10L
    }
}
