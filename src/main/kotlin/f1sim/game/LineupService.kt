package f1sim.game

import f1sim.db.Database
import f1sim.http.NotFoundException
import f1sim.save.SaveSession
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID

/**
 * Mid-season driver lineup management — the reserve / junior call-up.
 *
 * Between race weekends (the BETWEEN_ROUNDS phase) the player can swap one of
 * their two race drivers out for a non-F1 driver: a reserve/academy driver
 * tied to the team, or a junior currently racing in F2/F3. Mirrors real-world
 * mid-season substitutions where a team promotes a junior into a seat.
 *
 * Deliberately narrow, per the design decision:
 *   - Only allowed during BETWEEN_ROUNDS.
 *   - The incoming driver must NOT already be racing in F1 (no poaching a
 *     rival's race driver mid-season — that's the off-season market's job) and
 *     must be at least 18.
 *   - The outgoing race driver is demoted to the team's reserve
 *     (`reserve_for_team_id`), so they keep their contract and can be recalled.
 *
 * No RNG — this is a direct player action. Runs in one transaction.
 */
class LineupService(private val db: Database) {

    private val log = LoggerFactory.getLogger(LineupService::class.java)

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    @Serializable
    data class LineupDto(
        val phase: String,
        val canSwap: Boolean,
        val playerTeamId: String? = null,
        val seasonYear: Int,
        val raceDrivers: List<LineupDriverDto>,
        val callUpCandidates: List<LineupDriverDto>,
    )

    @Serializable
    data class LineupDriverDto(
        val driverId: String,
        val name: String,
        val nationality: String,
        val age: Int,
        val statPace: Int,
        val statQualifying: Int,
        // RACE | RESERVE | ACADEMY | F2 | F3 | JUNIOR
        val source: String,
    )

    @Serializable
    data class SwapRequest(
        val outDriverId: String,
        val inDriverId: String,
    )

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    fun viewLineup(): LineupDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn -> readLineup(conn) }
    }

    private fun readLineup(conn: Connection): LineupDto {
        val ctx = readGameContext(conn)
        val playerTeamId = ctx.playerTeamId
        val canSwap = ctx.phase == BETWEEN_ROUNDS_PHASE && playerTeamId != null
        if (playerTeamId == null) {
            return LineupDto(
                phase = ctx.phase,
                canSwap = false,
                playerTeamId = null,
                seasonYear = ctx.year,
                raceDrivers = emptyList(),
                callUpCandidates = emptyList(),
            )
        }
        return LineupDto(
            phase = ctx.phase,
            canSwap = canSwap,
            playerTeamId = playerTeamId,
            seasonYear = ctx.year,
            raceDrivers = readRaceDrivers(conn, playerTeamId),
            callUpCandidates = readCallUpCandidates(conn, playerTeamId),
        )
    }

    private fun readRaceDrivers(conn: Connection, playerTeamId: String): List<LineupDriverDto> {
        return conn.prepareStatement(
            """
            SELECT d.id, d.name, d.nationality, d.current_age,
                   d.stat_pace, d.stat_qualifying
              FROM drivers d
              JOIN teams t ON t.id = d.current_racing_team_id
             WHERE NOT d.retired
               AND d.current_racing_team_id = ?
               AND t.series = 'F1'
             ORDER BY d.stat_pace DESC, d.name ASC
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, playerTeamId)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(mapDriver(rs, "RACE"))
                }
            }
        }
    }

    private fun readCallUpCandidates(conn: Connection, playerTeamId: String): List<LineupDriverDto> {
        return conn.prepareStatement(
            """
            SELECT d.id, d.name, d.nationality, d.current_age,
                   d.stat_pace, d.stat_qualifying,
                   t.series AS team_series,
                   d.reserve_for_team_id, d.academy_team_id
              FROM drivers d
              LEFT JOIN teams t ON t.id = d.current_racing_team_id
             WHERE NOT d.retired
               AND d.current_age >= $MIN_CALLUP_AGE
               AND (d.current_racing_team_id IS NULL OR t.series IN ('F2','F3'))
             ORDER BY d.stat_pace DESC, d.name ASC
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val series = rs.getString("team_series")
                        val reserveTeam = rs.getString("reserve_for_team_id")
                        val academyTeam = rs.getString("academy_team_id")
                        val source = when {
                            reserveTeam == playerTeamId -> "RESERVE"
                            academyTeam == playerTeamId -> "ACADEMY"
                            series == "F2" -> "F2"
                            series == "F3" -> "F3"
                            series == null -> "FREE_AGENT" // no current racing team
                            else -> "JUNIOR"
                        }
                        add(mapDriver(rs, source))
                    }
                }
            }
        }
    }

    private fun mapDriver(rs: java.sql.ResultSet, source: String) = LineupDriverDto(
        driverId = rs.getObject("id", UUID::class.java).toString(),
        name = rs.getString("name"),
        nationality = rs.getString("nationality"),
        age = rs.getInt("current_age"),
        statPace = rs.getInt("stat_pace"),
        statQualifying = rs.getInt("stat_qualifying"),
        source = source,
    )

    // ------------------------------------------------------------------
    // Swap
    // ------------------------------------------------------------------

    fun swap(req: SwapRequest): LineupDto {
        SaveSession.requireLoaded()
        val outId = parseId(req.outDriverId)
        val inId = parseId(req.inDriverId)
        require(outId != inId) { "Cannot swap a driver with themselves" }

        return db.withConnection { conn ->
            conn.autoCommit = false
            try {
                val ctx = readGameContext(conn)
                check(ctx.phase == BETWEEN_ROUNDS_PHASE) {
                    "Lineup changes are only allowed between rounds (was ${ctx.phase})"
                }
                val playerTeamId = ctx.playerTeamId
                    ?: error("No player team selected — cannot change the lineup")

                requireIsPlayerRaceDriver(conn, outId, playerTeamId)
                requireIsCallUpEligible(conn, inId)

                // Incoming junior/reserve takes the race seat on a fresh deal.
                conn.prepareStatement(
                    """
                    UPDATE drivers SET
                      current_racing_team_id = ?,
                      reserve_for_team_id = NULL,
                      academy_team_id = NULL,
                      previous_team_id = NULL,
                      contract_expires_year = ?,
                      contract_expires_round = ?
                     WHERE id = ?
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, playerTeamId)
                    stmt.setInt(2, ctx.year + CALLUP_CONTRACT_YEARS)
                    stmt.setInt(3, CONTRACT_END_ROUND)
                    stmt.setObject(4, inId)
                    stmt.executeUpdate()
                }

                // Outgoing race driver is demoted to reserve — keeps their
                // contract/salary so they can be recalled later.
                conn.prepareStatement(
                    """
                    UPDATE drivers SET
                      current_racing_team_id = NULL,
                      reserve_for_team_id = ?
                     WHERE id = ?
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, playerTeamId)
                    stmt.setObject(2, outId)
                    stmt.executeUpdate()
                }

                conn.commit()
                log.info(
                    "Lineup swap on team {}: {} -> reserve, {} -> race seat",
                    playerTeamId, outId, inId,
                )
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }
            return@withConnection readLineup(conn)
        }
    }

    // ------------------------------------------------------------------
    // Validation helpers
    // ------------------------------------------------------------------

    private fun requireIsPlayerRaceDriver(conn: Connection, driverId: UUID, playerTeamId: String) {
        val ok = conn.prepareStatement(
            """
            SELECT 1
              FROM drivers d
              JOIN teams t ON t.id = d.current_racing_team_id
             WHERE d.id = ?
               AND NOT d.retired
               AND d.current_racing_team_id = ?
               AND t.series = 'F1'
            """.trimIndent()
        ).use { stmt ->
            stmt.setObject(1, driverId)
            stmt.setString(2, playerTeamId)
            stmt.executeQuery().use { rs -> rs.next() }
        }
        if (!ok) throw NotFoundException("Driver $driverId is not one of your race drivers")
    }

    private fun requireIsCallUpEligible(conn: Connection, driverId: UUID) {
        val ok = conn.prepareStatement(
            """
            SELECT 1
              FROM drivers d
              LEFT JOIN teams t ON t.id = d.current_racing_team_id
             WHERE d.id = ?
               AND NOT d.retired
               AND d.current_age >= $MIN_CALLUP_AGE
               AND (d.current_racing_team_id IS NULL OR t.series IN ('F2','F3'))
            """.trimIndent()
        ).use { stmt ->
            stmt.setObject(1, driverId)
            stmt.executeQuery().use { rs -> rs.next() }
        }
        if (!ok) throw NotFoundException(
            "Driver $driverId can't be called up — must be a free agent, reserve/academy, " +
                "or an F2/F3 driver aged $MIN_CALLUP_AGE+ (a contracted F1 race driver can't be poached mid-season)"
        )
    }

    private fun parseId(raw: String): UUID = try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        throw NotFoundException("No driver with id $raw")
    }

    private data class GameContext(
        val year: Int,
        val round: Int,
        val phase: String,
        val playerTeamId: String?,
    )

    private fun readGameContext(conn: Connection): GameContext {
        return conn.prepareStatement(
            "SELECT current_season_year, current_round, current_phase, player_team_id FROM game"
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "No game row in current save" }
                GameContext(
                    year = rs.getInt("current_season_year"),
                    round = rs.getInt("current_round"),
                    phase = rs.getString("current_phase"),
                    playerTeamId = rs.getString("player_team_id"),
                )
            }
        }
    }

    private companion object {
        const val BETWEEN_ROUNDS_PHASE = "BETWEEN_ROUNDS"
        const val MIN_CALLUP_AGE = 18
        const val CALLUP_CONTRACT_YEARS = 2
        const val CONTRACT_END_ROUND = 24
    }
}
