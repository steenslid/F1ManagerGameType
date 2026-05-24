package f1sim.game

import f1sim.db.Database
import f1sim.http.NotFoundException
import f1sim.save.SaveSession
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID

/**
 * Race-weekend decisions: practice focus and strategy.
 *
 * Practice focus: set per (current race, driver) during PRACTICE phase only.
 * Strategy: set per (current race, driver) during QUALIFYING phase only —
 * after the grid is known, before the race runs.
 *
 * Both UPSERT on `(race_id, driver_id)` so the player can change their mind
 * within the allowed phase.
 *
 * The sim hooks in [GameService] LEFT JOIN these tables to apply effects.
 */
class RaceWeekendService(private val db: Database) {

    private val log = LoggerFactory.getLogger(RaceWeekendService::class.java)

    private val validFocusValues = setOf(
        "SETUP", "TYRE_PROGRAM", "RELIABILITY_CHECK", "DEVELOPMENT_FEEDBACK"
    )

    private val validStrategyArchetypes = setOf(
        "M_H", "S_H", "S_M_M", "M_M_H", "S_S_H"
    )

    // ------------------------------------------------------------------
    // Practice focus DTOs
    // ------------------------------------------------------------------

    @Serializable
    data class PracticeViewDto(
        val raceId: String,
        val seasonYear: Int,
        val round: Int,
        val canEdit: Boolean,
        val entries: List<PracticeEntryDto>,
    )

    @Serializable
    data class PracticeEntryDto(
        val driverId: String,
        val driverName: String,
        val teamId: String,
        val teamName: String,
        val focus: String?,
    )

    @Serializable
    data class SetPracticeFocusRequest(
        val driverId: String,
        val focus: String,
    )

    // ------------------------------------------------------------------
    // Strategy DTOs
    // ------------------------------------------------------------------

    @Serializable
    data class StrategyViewDto(
        val raceId: String,
        val seasonYear: Int,
        val round: Int,
        val canEdit: Boolean,
        val entries: List<StrategyEntryDto>,
    )

    @Serializable
    data class StrategyEntryDto(
        val driverId: String,
        val driverName: String,
        val teamId: String,
        val teamName: String,
        val gridPosition: Int?,
        val archetype: String?,
    )

    @Serializable
    data class SetStrategyRequest(
        val driverId: String,
        val archetype: String,
    )

    // ------------------------------------------------------------------
    // Practice focus operations
    // ------------------------------------------------------------------

    fun viewPractice(): PracticeViewDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn -> viewPracticeInTxn(conn) }
    }

    fun setPracticeFocus(req: SetPracticeFocusRequest): PracticeViewDto {
        SaveSession.requireLoaded()
        require(req.focus in validFocusValues) {
            "focus must be one of ${validFocusValues.joinToString()}"
        }
        val driverId = try {
            UUID.fromString(req.driverId)
        } catch (_: IllegalArgumentException) {
            throw NotFoundException("No driver with id ${req.driverId}")
        }

        return db.withConnection { conn ->
            conn.autoCommit = false
            try {
                val (raceId, _, _, phase) = readCurrentRaceContext(conn)
                check(phase == "PRACTICE") {
                    "Practice focus can only be set during PRACTICE phase (was $phase)"
                }
                requireDriverEligible(conn, driverId)

                conn.prepareStatement(
                    """
                    INSERT INTO practice_focus (race_id, driver_id, focus)
                    VALUES (?, ?, ?)
                    ON CONFLICT (race_id, driver_id)
                    DO UPDATE SET focus = EXCLUDED.focus, set_at = now()
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setObject(1, raceId)
                    stmt.setObject(2, driverId)
                    stmt.setString(3, req.focus)
                    stmt.executeUpdate()
                }
                conn.commit()
                log.info("Practice focus set: driver={} focus={} race={}", driverId, req.focus, raceId)
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }

            return@withConnection viewPracticeInTxn(conn)
        }
    }

    private fun viewPracticeInTxn(conn: Connection): PracticeViewDto {
        val (raceId, year, round, phase) = readCurrentRaceContext(conn)
        val canEdit = phase == "PRACTICE"

        val entries = conn.prepareStatement(
            """
            SELECT d.id AS driver_id, d.name AS driver_name,
                   t.id AS team_id, t.name AS team_name,
                   pf.focus AS focus
              FROM drivers d
              JOIN teams t ON t.id = d.current_racing_team_id
              LEFT JOIN practice_focus pf
                ON pf.driver_id = d.id AND pf.race_id = ?
             WHERE NOT d.retired
               AND t.series = 'F1'
             ORDER BY t.name ASC, d.name ASC
            """.trimIndent()
        ).use { stmt ->
            stmt.setObject(1, raceId)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            PracticeEntryDto(
                                driverId = rs.getObject("driver_id", UUID::class.java).toString(),
                                driverName = rs.getString("driver_name"),
                                teamId = rs.getString("team_id"),
                                teamName = rs.getString("team_name"),
                                focus = rs.getString("focus"),
                            )
                        )
                    }
                }
            }
        }

        return PracticeViewDto(
            raceId = raceId.toString(),
            seasonYear = year,
            round = round,
            canEdit = canEdit,
            entries = entries,
        )
    }

    // ------------------------------------------------------------------
    // Strategy operations
    // ------------------------------------------------------------------

    fun viewStrategy(): StrategyViewDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn -> viewStrategyInTxn(conn) }
    }

    fun setStrategy(req: SetStrategyRequest): StrategyViewDto {
        SaveSession.requireLoaded()
        require(req.archetype in validStrategyArchetypes) {
            "archetype must be one of ${validStrategyArchetypes.joinToString()}"
        }
        val driverId = try {
            UUID.fromString(req.driverId)
        } catch (_: IllegalArgumentException) {
            throw NotFoundException("No driver with id ${req.driverId}")
        }

        return db.withConnection { conn ->
            conn.autoCommit = false
            try {
                val (raceId, _, _, phase) = readCurrentRaceContext(conn)
                check(phase == "QUALIFYING") {
                    "Strategy can only be set during QUALIFYING phase (was $phase)"
                }
                requireDriverEligible(conn, driverId)

                conn.prepareStatement(
                    """
                    INSERT INTO race_strategy (race_id, driver_id, archetype)
                    VALUES (?, ?, ?)
                    ON CONFLICT (race_id, driver_id)
                    DO UPDATE SET archetype = EXCLUDED.archetype, set_at = now()
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setObject(1, raceId)
                    stmt.setObject(2, driverId)
                    stmt.setString(3, req.archetype)
                    stmt.executeUpdate()
                }
                conn.commit()
                log.info("Strategy set: driver={} archetype={} race={}", driverId, req.archetype, raceId)
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }

            return@withConnection viewStrategyInTxn(conn)
        }
    }

    private fun viewStrategyInTxn(conn: Connection): StrategyViewDto {
        val (raceId, year, round, phase) = readCurrentRaceContext(conn)
        val canEdit = phase == "QUALIFYING"

        // Joining race_results gets us grid positions when available (after
        // qualifying sim has run). Sort by grid position when present.
        val entries = conn.prepareStatement(
            """
            SELECT d.id AS driver_id, d.name AS driver_name,
                   t.id AS team_id, t.name AS team_name,
                   rr.grid_position AS grid_position,
                   rs.archetype AS archetype
              FROM drivers d
              JOIN teams t ON t.id = d.current_racing_team_id
              LEFT JOIN race_results rr
                ON rr.driver_id = d.id AND rr.race_id = ?
              LEFT JOIN race_strategy rs
                ON rs.driver_id = d.id AND rs.race_id = ?
             WHERE NOT d.retired
               AND t.series = 'F1'
             ORDER BY COALESCE(rr.grid_position, 999) ASC, d.name ASC
            """.trimIndent()
        ).use { stmt ->
            stmt.setObject(1, raceId)
            stmt.setObject(2, raceId)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val gridRaw = rs.getInt("grid_position")
                        val grid = if (rs.wasNull()) null else gridRaw
                        add(
                            StrategyEntryDto(
                                driverId = rs.getObject("driver_id", UUID::class.java).toString(),
                                driverName = rs.getString("driver_name"),
                                teamId = rs.getString("team_id"),
                                teamName = rs.getString("team_name"),
                                gridPosition = grid,
                                archetype = rs.getString("archetype"),
                            )
                        )
                    }
                }
            }
        }

        return StrategyViewDto(
            raceId = raceId.toString(),
            seasonYear = year,
            round = round,
            canEdit = canEdit,
            entries = entries,
        )
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private fun requireDriverEligible(conn: Connection, driverId: UUID) {
        val eligible = conn.prepareStatement(
            """
            SELECT 1
              FROM drivers d
              JOIN teams t ON t.id = d.current_racing_team_id
             WHERE d.id = ?
               AND NOT d.retired
               AND t.series = 'F1'
            """.trimIndent()
        ).use { stmt ->
            stmt.setObject(1, driverId)
            stmt.executeQuery().use { rs -> rs.next() }
        }
        if (!eligible) {
            throw NotFoundException("Driver $driverId is not an active F1 entrant")
        }
    }

    private fun readCurrentRaceContext(conn: Connection): RaceContext {
        return conn.prepareStatement(
            """
            SELECT r.id AS race_id, g.current_season_year, g.current_round, g.current_phase
              FROM game g
              JOIN races r ON r.season_year = g.current_season_year
                          AND r.round = g.current_round
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                if (!rs.next()) {
                    throw NotFoundException("Not in a race weekend")
                }
                RaceContext(
                    raceId = rs.getObject("race_id", UUID::class.java),
                    year = rs.getInt("current_season_year"),
                    round = rs.getInt("current_round"),
                    phase = rs.getString("current_phase"),
                )
            }
        }
    }

    private data class RaceContext(
        val raceId: UUID,
        val year: Int,
        val round: Int,
        val phase: String,
    )
}
