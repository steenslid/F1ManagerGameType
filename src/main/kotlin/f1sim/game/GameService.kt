package f1sim.game

import f1sim.db.Database
import f1sim.http.NotFoundException
import f1sim.save.SaveSession
import f1sim.sim.RaceSim
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Game-state orchestration.
 *
 * Race-weekend hooks (qualifying, race, sprint qualifying, sprint, post-race)
 * live here directly. Year-level hooks (END_OF_SEASON finance settle,
 * OFF_SEASON aging/retirement/expiration, PRE_SEASON sponsor revenue) are
 * delegated to [OffSeasonService]. Driver market hooks (initialize, run a
 * round, finalize) delegate to [DriverMarketService] — driver market lives
 * in its own phase between OFF_SEASON and PRE_SEASON.
 */
class GameService(
    private val db: Database,
    private val offSeasonService: OffSeasonService,
    private val driverMarketService: DriverMarketService,
) {

    private val log = LoggerFactory.getLogger(GameService::class.java)

    private val fallbackRoundsPerSeason = 24

    // Practice-focus trade-offs, folded into the sim at the GameService
    // boundary. Each focus is a real choice — a one-lap (qualifying) vs
    // race-day (pace + reliability) tilt. consistencyDelta shifts BOTH the
    // race sim's variance and its DNF roll (both derive from stat_consistency),
    // so a reliability setup genuinely lowers DNF risk at a cost elsewhere.
    private data class FocusEffect(val qualiMult: Double, val paceMult: Double, val consistencyDelta: Int)

    private val focusEffects: Map<String, FocusEffect> = mapOf(
        // Balanced: mild all-round gain, no downside — the safe pick.
        "SETUP" to FocusEffect(qualiMult = 1.015, paceMult = 1.015, consistencyDelta = 0),
        // Race trim: better race pace + steadier, weaker over one lap.
        "TYRE_PROGRAM" to FocusEffect(qualiMult = 0.980, paceMult = 1.020, consistencyDelta = 8),
        // Reliability: much safer (low DNF), but gives up outright speed.
        "RELIABILITY_CHECK" to FocusEffect(qualiMult = 0.985, paceMult = 0.985, consistencyDelta = 14),
        // Qualifying trim: strong grid slot, but a riskier, twitchier race.
        "DEVELOPMENT_FEEDBACK" to FocusEffect(qualiMult = 1.030, paceMult = 1.000, consistencyDelta = -10),
    )
    private val neutralFocus = FocusEffect(1.0, 1.0, 0)
    private fun focusEffect(focus: String?): FocusEffect = focusEffects[focus] ?: neutralFocus

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
                requireRaceWeekendDecisions(conn, before)
                val roundsThisSeason = countRoundsInSeason(conn, before.year)
                val isSprintWeekend = isSprintRound(conn, before.year, before.round)

                // For DRIVER_MARKET -> next decision: run the hook FIRST
                // (it advances the round counter), then check completion
                // via DriverMarketService. This is the only phase where a
                // hook needs to influence the transition itself.
                val (after, events) = if (before.phase == Phase.DRIVER_MARKET) {
                    val roundEvents = runDriverMarketHook(conn, before)
                    val complete = driverMarketService.isMarketComplete(conn)
                    val next = PhaseMachine.next(
                        before, roundsThisSeason, isSprintWeekend,
                        isMarketComplete = complete,
                    )
                    val transitionEvents = if (next.phase != Phase.DRIVER_MARKET) {
                        roundEvents + runTransitionHooks(conn, before, next)
                    } else {
                        roundEvents
                    }
                    next to transitionEvents
                } else {
                    val next = PhaseMachine.next(
                        before, roundsThisSeason, isSprintWeekend,
                        isMarketComplete = false,
                    )
                    next to runTransitionHooks(conn, before, next)
                }

                log.info(
                    "Transition: {} y{} r{} -> {} y{} r{} (season has {} rounds; sprint? {})",
                    before.phase, before.year, before.round,
                    after.phase, after.year, after.round,
                    roundsThisSeason, isSprintWeekend,
                )
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

    private fun readMasterSeed(conn: Connection): Long {
        conn.prepareStatement("SELECT master_rng_seed FROM game").use { stmt ->
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "No game row" }
                return rs.getLong("master_rng_seed")
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
    // Pre-advance gates — force the player to make race-weekend decisions
    // ------------------------------------------------------------------

    /**
     * The player can't skip the parts of a race weekend that are theirs to set:
     * a practice focus for every one of their race drivers before leaving
     * PRACTICE, and a race strategy before leaving QUALIFYING. AI teams and
     * empty seats are unaffected. No player team selected → no gate.
     *
     * Throws IllegalStateException (mapped to 400 BAD_STATE) so the UI surfaces
     * the message on the advance button.
     */
    private fun requireRaceWeekendDecisions(conn: Connection, state: GameState) {
        val playerTeamId = readPlayerTeamIdOrNull(conn) ?: return
        val raceId = lookupRaceAndSeed(conn, state.year, state.round)?.first ?: return
        when (state.phase) {
            Phase.PRACTICE -> {
                val missing = countMissingDecisions(conn, raceId, playerTeamId, "practice_focus")
                check(missing == 0) {
                    "Set a practice focus for all your drivers before advancing " +
                        "($missing still to set)."
                }
            }
            Phase.QUALIFYING -> {
                val missing = countMissingDecisions(conn, raceId, playerTeamId, "race_strategy")
                check(missing == 0) {
                    "Pick a race strategy for all your drivers before advancing " +
                        "($missing still to set)."
                }
            }
            else -> {}
        }
    }

    /**
     * Count the player's active F1 race drivers with no row in the given
     * race-weekend table for this race. The table name is a fixed internal
     * constant ("practice_focus" | "race_strategy"), never user input.
     */
    private fun countMissingDecisions(
        conn: Connection,
        raceId: UUID,
        playerTeamId: String,
        table: String,
    ): Int {
        return conn.prepareStatement(
            """
            SELECT COUNT(*)
              FROM drivers d
              JOIN teams t ON t.id = d.current_racing_team_id
             WHERE d.current_racing_team_id = ?
               AND t.series = 'F1'
               AND NOT d.retired
               AND NOT EXISTS (
                   SELECT 1 FROM $table x
                    WHERE x.race_id = ? AND x.driver_id = d.id
               )
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, playerTeamId)
            stmt.setObject(2, raceId)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private fun readPlayerTeamIdOrNull(conn: Connection): String? {
        return conn.prepareStatement("SELECT player_team_id FROM game").use { stmt ->
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("player_team_id") else null }
        }
    }

    // ------------------------------------------------------------------
    // Transition hooks
    // ------------------------------------------------------------------

    /**
     * Runs on every transition EXCEPT the DRIVER_MARKET self-loop case,
     * which is handled inline in [advance] (the hook must run before the
     * machine knows whether to stay in the phase or exit).
     */
    private fun runTransitionHooks(
        conn: Connection,
        from: GameState,
        to: GameState,
    ): List<TransitionEventDto> {
        return when (to.phase) {
            Phase.PRE_SEASON -> runPreSeasonHook(conn, to, from)
            Phase.PRACTICE -> emptyList()
            Phase.QUALIFYING -> runQualifyingHook(conn, to)
            Phase.SPRINT_QUALIFYING -> runSprintQualifyingHook(conn, to)
            Phase.SPRINT -> runSprintHook(conn, to)
            Phase.RACE -> runRaceHook(conn, to)
            Phase.POST_RACE -> runPostRaceHook(conn, to)
            Phase.BETWEEN_ROUNDS -> emptyList()
            Phase.END_OF_SEASON -> runEndOfSeasonHook(conn, from)
            Phase.OFF_SEASON -> runOffSeasonHook(conn, from)
            // Entering DRIVER_MARKET from OFF_SEASON: initialize.
            // (DRIVER_MARKET self-loop is handled in advance(); we won't be
            // called for that case.)
            Phase.DRIVER_MARKET -> runEnterDriverMarketHook(conn, from)
        }
    }

    private fun runQualifyingHook(conn: Connection, state: GameState): List<TransitionEventDto> {
        val (raceId, masterSeed) = lookupRaceAndSeed(conn, state.year, state.round)
            ?: run {
                log.warn("No race for y{} r{} — skipping qualifying", state.year, state.round)
                return emptyList()
            }

        val entrants = readQualifyingEntrants(conn, raceId, readCarWeights(conn, raceId))

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

        val weights = readCarWeights(conn, raceId)
        val entrants = conn.prepareStatement(
            """
            SELECT rr.driver_id, rr.team_id, rr.grid_position,
                   d.stat_pace, d.stat_consistency,
                   t.car_aero, t.car_chassis, t.car_powertrain,
                   pf.focus AS focus,
                   rs.archetype AS strategy_archetype
              FROM race_results rr
              JOIN drivers d ON d.id = rr.driver_id
              JOIN teams t ON t.id = rr.team_id
              LEFT JOIN practice_focus pf
                ON pf.driver_id = rr.driver_id AND pf.race_id = rr.race_id
              LEFT JOIN race_strategy rs
                ON rs.driver_id = rr.driver_id AND rs.race_id = rr.race_id
             WHERE rr.race_id = ?
               AND rr.status = 'QUALIFIED'
            """.trimIndent()
        ).use { stmt ->
            stmt.setObject(1, raceId)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val basePace = rs.getInt("stat_pace").toDouble()
                        val fx = focusEffect(rs.getString("focus"))
                        val car = weightedCar(rs.getInt("car_aero"), rs.getInt("car_chassis"), rs.getInt("car_powertrain"), weights)
                        val effectivePace = basePace * fx.paceMult + carTerm(car)
                        val effectiveConsistency =
                            (rs.getInt("stat_consistency") + fx.consistencyDelta).coerceIn(1, 100)
                        add(
                            RaceSim.RaceEntrant(
                                driverId = rs.getObject("driver_id", UUID::class.java),
                                teamId = rs.getString("team_id"),
                                gridPosition = rs.getInt("grid_position"),
                                statPace = effectivePace,
                                statConsistency = effectiveConsistency,
                                strategyArchetype = rs.getString("strategy_archetype"),
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

    // -- Sprint hooks -------------------------------------------------

    private fun runSprintQualifyingHook(conn: Connection, state: GameState): List<TransitionEventDto> {
        val (raceId, masterSeed) = lookupRaceAndSeed(conn, state.year, state.round)
            ?: run {
                log.warn("No race for y{} r{} — skipping sprint qualifying", state.year, state.round)
                return emptyList()
            }

        val entrants = readQualifyingEntrants(conn, raceId, readCarWeights(conn, raceId))

        if (entrants.isEmpty()) {
            log.warn("No entrants found for sprint qualifying — skipping")
            return listOf(
                TransitionEventDto("SPRINT_QUALIFYING_SKIPPED", "No drivers eligible"),
            )
        }

        val rng = Random(masterSeed xor raceId.hashCode().toLong() xor SPRINT_QUALIFYING_SALT)
        val results = RaceSim.simulateQualifying(entrants, rng)

        conn.prepareStatement("DELETE FROM sprint_results WHERE race_id = ?").use { stmt ->
            stmt.setObject(1, raceId)
            stmt.executeUpdate()
        }
        conn.prepareStatement(
            """
            INSERT INTO sprint_results
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

        log.info("Sprint qualifying simulated for race {}: {} entrants", raceId, results.size)
        return listOf(
            TransitionEventDto(
                "SPRINT_QUALIFYING_COMPLETED",
                "${results.size} drivers qualified for sprint y${state.year} r${state.round}",
            ),
        )
    }

    private fun runSprintHook(conn: Connection, state: GameState): List<TransitionEventDto> {
        val (raceId, masterSeed) = lookupRaceAndSeed(conn, state.year, state.round)
            ?: run {
                log.warn("No race for y{} r{} — skipping sprint sim", state.year, state.round)
                return emptyList()
            }

        val weights = readCarWeights(conn, raceId)
        val entrants = conn.prepareStatement(
            """
            SELECT sr.driver_id, sr.team_id, sr.grid_position,
                   d.stat_pace, d.stat_consistency,
                   t.car_aero, t.car_chassis, t.car_powertrain
              FROM sprint_results sr
              JOIN drivers d ON d.id = sr.driver_id
              JOIN teams t ON t.id = sr.team_id
             WHERE sr.race_id = ?
               AND sr.status = 'QUALIFIED'
            """.trimIndent()
        ).use { stmt ->
            stmt.setObject(1, raceId)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            RaceSim.SprintEntrant(
                                driverId = rs.getObject("driver_id", UUID::class.java),
                                teamId = rs.getString("team_id"),
                                gridPosition = rs.getInt("grid_position"),
                                statPace = rs.getInt("stat_pace").toDouble() +
                                    carTerm(weightedCar(rs.getInt("car_aero"), rs.getInt("car_chassis"), rs.getInt("car_powertrain"), weights)),
                                statConsistency = rs.getInt("stat_consistency"),
                            )
                        )
                    }
                }
            }
        }

        if (entrants.isEmpty()) {
            log.warn("No grid found for sprint {} — skipping sprint sim", raceId)
            return listOf(TransitionEventDto("SPRINT_SKIPPED", "No grid; was sprint qualifying run?"))
        }

        val rng = Random(masterSeed xor raceId.hashCode().toLong() xor SPRINT_SALT)
        val results = RaceSim.simulateSprint(entrants, rng)

        conn.prepareStatement(
            """
            UPDATE sprint_results SET
              finishing_position = ?,
              sprint_points = ?,
              status = ?,
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
                if (r.dnfCause != null) {
                    stmt.setString(4, r.dnfCause)
                } else {
                    stmt.setNull(4, java.sql.Types.VARCHAR)
                }
                stmt.setObject(5, raceId)
                stmt.setObject(6, r.driverId)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

        val dnfCount = results.count { it.status == "DNF" }
        val finisherCount = results.size - dnfCount
        log.info("Sprint simulated for {}: {} finishers, {} DNFs", raceId, finisherCount, dnfCount)
        val events = mutableListOf(
            TransitionEventDto(
                "SPRINT_COMPLETED",
                "$finisherCount finishers, $dnfCount DNFs at sprint y${state.year} r${state.round}",
            ),
        )
        results.firstOrNull { it.finishingPosition == 1 }?.let { winner ->
            events += TransitionEventDto("SPRINT_WINNER", "Driver ${winner.driverId} won sprint")
        }
        return events
    }

    private fun readQualifyingEntrants(
        conn: Connection,
        raceId: UUID,
        weights: CarWeights,
    ): List<RaceSim.QualifyingEntrant> {
        return conn.prepareStatement(
            """
            SELECT d.id, d.current_racing_team_id, d.stat_qualifying,
                   t.car_aero, t.car_chassis, t.car_powertrain,
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
                        val baseStat = rs.getInt("stat_qualifying").toDouble()
                        val fx = focusEffect(rs.getString("focus"))
                        val car = weightedCar(rs.getInt("car_aero"), rs.getInt("car_chassis"), rs.getInt("car_powertrain"), weights)
                        val effectiveStat = baseStat * fx.qualiMult + carTerm(car)
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
    }

    private fun runPostRaceHook(conn: Connection, state: GameState): List<TransitionEventDto> {
        val updated = conn.prepareStatement(
            """
            UPDATE teams t SET season_points = COALESCE(s.total, 0)
              FROM (
                  SELECT team_id, SUM(points) AS total FROM (
                      SELECT rr.team_id, rr.points
                        FROM race_results rr
                        JOIN races r ON r.id = rr.race_id
                       WHERE r.season_year = ?
                      UNION ALL
                      SELECT sr.team_id, sr.sprint_points AS points
                        FROM sprint_results sr
                        JOIN races r ON r.id = sr.race_id
                       WHERE r.season_year = ?
                  ) AS combined
                  GROUP BY team_id
              ) s
             WHERE t.id = s.team_id
            """.trimIndent()
        ).use { stmt ->
            stmt.setInt(1, state.year)
            stmt.setInt(2, state.year)
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

    private fun runEndOfSeasonHook(conn: Connection, from: GameState): List<TransitionEventDto> {
        val count = offSeasonService.runEndOfSeasonHooks(conn, from.year)
        return if (count > 0) {
            listOf(
                TransitionEventDto(
                    "FINANCE_SETTLED",
                    "End-of-season finances settled for $count teams",
                ),
            )
        } else emptyList()
    }

    private fun runOffSeasonHook(conn: Connection, from: GameState): List<TransitionEventDto> {
        val masterSeed = readMasterSeed(conn)
        val count = offSeasonService.runOffSeasonHooks(conn, from.year, masterSeed)
        return if (count > 0) {
            listOf(
                TransitionEventDto(
                    "OFF_SEASON_PROCESSED",
                    "$count off-season events (aging, retirements, contract expirations)",
                ),
            )
        } else emptyList()
    }

    /**
     * Fires on OFF_SEASON -> DRIVER_MARKET. The market gets initialized
     * with the ending season year, ready for the player to submit offers.
     * `from.year` is the year that just ended (PhaseMachine increments
     * year on END_OF_SEASON -> OFF_SEASON, so this `from` is post-bump;
     * the "ending season" we ran finance/aging for is `from.year - 1`).
     *
     * Wait — re-checking PhaseMachine: END_OF_SEASON -> OFF_SEASON
     * increments year. So `from.year` here (OFF_SEASON's year) is the
     * NEW year. The ending season was `from.year - 1`. Pass that for
     * consistency with the off-season events log.
     */
    private fun runEnterDriverMarketHook(
        conn: Connection,
        from: GameState,
    ): List<TransitionEventDto> {
        val endingSeasonYear = from.year - 1
        driverMarketService.initializeMarketForOffSeason(conn, endingSeasonYear)
        return listOf(
            TransitionEventDto(
                "MARKET_INITIALIZED",
                "Driver market open for off-season ending $endingSeasonYear",
            ),
        )
    }

    /**
     * Fires on each advance while in DRIVER_MARKET. Resolves one round of
     * matching and returns events. The decision whether this is the LAST
     * round (and we should transition to PRE_SEASON) is taken in [advance]
     * after this hook returns, via [DriverMarketService.isMarketComplete].
     */
    private fun runDriverMarketHook(
        conn: Connection,
        from: GameState,
    ): List<TransitionEventDto> {
        val endingSeasonYear = from.year - 1
        val masterSeed = readMasterSeed(conn)
        val signings = driverMarketService.resolveOneRound(
            conn, endingSeasonYear, masterSeed,
        )
        return listOf(
            TransitionEventDto(
                "MARKET_ROUND_RESOLVED",
                "$signings signings this round",
            ),
        )
    }

    private fun runPreSeasonHook(
        conn: Connection,
        to: GameState,
        from: GameState,
    ): List<TransitionEventDto> {
        // If we're coming from DRIVER_MARKET, finalize the market state
        // before running PRE_SEASON's sponsor / cost ticks.
        if (from.phase == Phase.DRIVER_MARKET) {
            driverMarketService.finalizeMarket(conn)
        }
        val count = offSeasonService.runPreSeasonHooks(conn, to.year)
        return if (count > 0) {
            listOf(
                TransitionEventDto(
                    "SPONSOR_REVENUE_APPLIED",
                    "Sponsor revenue applied to $count teams for season ${to.year}",
                ),
            )
        } else emptyList()
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    private fun advanceLabel(from: Phase): String = when (from) {
        Phase.OFF_SEASON -> "Open driver market"
        Phase.DRIVER_MARKET -> "Resolve next market round"
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

    /**
     * Car-performance contribution to a driver's qualifying/race pace, folded
     * into the stat at the GameService boundary (RaceSim stays pure and stat-
     * driven). Only the *relative* term between cars affects finishing order —
     * the baseline cancels in sorting — but it's kept for readable numbers.
     * With seeded cars ~58..82 and weight 0.4 the top car is worth ~+10 pace
     * over the slowest: a real edge, comparable to a strong grid slot, without
     * eclipsing driver skill (pace spread ~19).
     */
    private fun carTerm(carPerformance: Int): Double =
        (carPerformance - CAR_PERF_BASELINE) * CAR_PERF_WEIGHT

    /**
     * Per-track blend of the three car areas into one effective rating. A
     * track's demands decide how much each area matters: powertrain for top
     * speed + acceleration, aero for the cornering phases, chassis for braking
     * + tyre wear. So a team's parts profile plays to (or against) each venue —
     * making where you spend R&D a strategic call against your calendar.
     */
    private data class CarWeights(val aero: Double, val chassis: Double, val powertrain: Double)

    private fun weightedCar(aero: Int, chassis: Int, powertrain: Int, w: CarWeights): Int =
        (aero * w.aero + chassis * w.chassis + powertrain * w.powertrain).roundToInt()

    /** Build normalised area weights from the given race's track demands. */
    private fun readCarWeights(conn: Connection, raceId: UUID): CarWeights {
        return conn.prepareStatement(
            """
            SELECT tk.demand_top_speed, tk.demand_acceleration,
                   tk.demand_low_speed_cornering, tk.demand_medium_speed_cornering,
                   tk.demand_high_speed_cornering, tk.demand_braking, tk.demand_tyre_wear
              FROM races r
              JOIN tracks tk ON tk.id = r.track_id
             WHERE r.id = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setObject(1, raceId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return CarWeights(1.0 / 3, 1.0 / 3, 1.0 / 3)
                val power = rs.getDouble("demand_top_speed") + rs.getDouble("demand_acceleration")
                val aero = rs.getDouble("demand_low_speed_cornering") +
                    rs.getDouble("demand_medium_speed_cornering") +
                    rs.getDouble("demand_high_speed_cornering")
                val chassis = rs.getDouble("demand_braking") + rs.getDouble("demand_tyre_wear")
                val total = power + aero + chassis
                if (total <= 0.0) CarWeights(1.0 / 3, 1.0 / 3, 1.0 / 3)
                else CarWeights(aero = aero / total, chassis = chassis / total, powertrain = power / total)
            }
        }
    }

    private companion object {
        const val QUALIFYING_SALT = 0x5111EFA11L
        const val RACE_SALT = 0xACE0FA10L
        const val SPRINT_QUALIFYING_SALT = 0x5111EFB22L
        const val SPRINT_SALT = 0xACE0FB21L

        const val CAR_PERF_BASELINE = 65
        const val CAR_PERF_WEIGHT = 0.4
    }
}
