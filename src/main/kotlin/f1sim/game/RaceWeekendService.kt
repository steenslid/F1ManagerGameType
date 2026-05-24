package f1sim.game

import f1sim.db.Database
import f1sim.http.NotFoundException
import f1sim.save.SaveSession
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID

/**
 * Race-weekend decisions: practice focus (this round), strategy (later).
 *
 * Practice focus is set per (current race, driver). Reads work in any phase
 * for visibility; writes are only valid while phase = PRACTICE.
 *
 * The qualifying / race sim hooks in [GameService] join against `practice_focus`
 * to apply effects (currently: SETUP gives a small bonus to qualifying / race
 * pace).
 */
class RaceWeekendService(private val db: Database) {

    private val log = LoggerFactory.getLogger(RaceWeekendService::class.java)

    private val validFocusValues = setOf(
        "SETUP", "TYRE_PROGRAM", "RELIABILITY_CHECK", "DEVELOPMENT_FEEDBACK"
    )

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

    /**
     * Returns the practice-focus state for the current race weekend. 404s
     * if we're not in a race weekend.
     */
    fun viewPractice(): PracticeViewDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn ->
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

            PracticeViewDto(
                raceId = raceId.toString(),
                seasonYear = year,
                round = round,
                canEdit = canEdit,
                entries = entries,
            )
        }
    }

    /**
     * Set a driver's practice focus for the current race. Only valid during
     * PRACTICE phase. UPSERT — overwrites any previous selection.
     */
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

                // Driver must exist and be on an F1 racing team.
                val driverEligible = conn.prepareStatement(
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
                if (!driverEligible) {
                    throw NotFoundException("Driver $driverId is not an active F1 entrant")
                }

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

            // Re-fetch full view to return.
            return@withConnection viewPracticeInTxn(conn)
        }
    }

    /** Internal helper used after a write to return the fresh state. */
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

    /**
     * Find the current race's UUID and the current phase. Throws 404 if no
     * race weekend is active.
     */
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
