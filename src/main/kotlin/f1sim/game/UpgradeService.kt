package f1sim.game

import f1sim.db.Database
import f1sim.http.NotFoundException
import f1sim.save.SaveSession
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * In-season R&D upgrade projects. The player commissions a boost to one car
 * area; it costs cash up front and delivers (applies its gain to that area)
 * a set number of rounds later. Unlike the passive pre-season R&D budget,
 * upgrades are timed development — invest now, have it land for the stretch of
 * the calendar that rewards that area.
 *
 * Player-only, no RNG. Delivery is driven from the PRACTICE transition hook
 * (see GameService) so an upgrade is active for the weekend it lands.
 */
class UpgradeService(private val db: Database) {

    private val log = LoggerFactory.getLogger(UpgradeService::class.java)

    // size -> (gain, cost, rounds to deliver)
    private data class Size(val gain: Int, val cost: Long, val rounds: Int)
    private val sizes = linkedMapOf(
        "SMALL" to Size(2, 12_000_000L, 2),
        "MEDIUM" to Size(4, 30_000_000L, 3),
        "LARGE" to Size(6, 60_000_000L, 5),
    )
    private val areas = setOf("AERO", "CHASSIS", "POWERTRAIN")
    private fun areaColumn(area: String) = when (area) {
        "AERO" -> "car_aero"
        "CHASSIS" -> "car_chassis"
        else -> "car_powertrain"
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    @Serializable
    data class UpgradeStateDto(
        val playerTeamId: String? = null,
        val currentYear: Int = 0,
        val currentRound: Int = 0,
        val totalRounds: Int = 0,
        val cashReserves: Long? = null,
        val carAero: Int? = null,
        val carChassis: Int? = null,
        val carPowertrain: Int? = null,
        val sizes: List<SizeDto> = emptyList(),
        val inProgress: List<ProjectDto> = emptyList(),
    )

    @Serializable
    data class SizeDto(val size: String, val gain: Int, val cost: Long, val rounds: Int)

    @Serializable
    data class ProjectDto(
        val id: Long,
        val area: String,
        val gain: Int,
        val cost: Long,
        val deliverRound: Int,
        val roundsRemaining: Int,
    )

    @Serializable
    data class CommissionRequest(val area: String, val size: String)

    private fun sizeDtos() = sizes.map { (k, v) -> SizeDto(k, v.gain, v.cost, v.rounds) }

    // ------------------------------------------------------------------
    // Player-facing
    // ------------------------------------------------------------------

    fun view(): UpgradeStateDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn -> readState(conn) }
    }

    fun commission(req: CommissionRequest): UpgradeStateDto {
        SaveSession.requireLoaded()
        val area = req.area.uppercase()
        val sizeKey = req.size.uppercase()
        require(area in areas) { "area must be one of $areas" }
        val size = sizes[sizeKey] ?: throw NotFoundException("Unknown upgrade size '${req.size}'")

        return db.withConnection { conn ->
            conn.autoCommit = false
            try {
                val ctx = readGameContext(conn)
                val playerTeamId = ctx.playerTeamId
                    ?: error("No player team selected — cannot commission upgrades")

                val deliverRound = ctx.currentRound + size.rounds
                check(deliverRound <= ctx.totalRounds) {
                    "Not enough rounds left this season for a ${sizeKey.lowercase()} upgrade " +
                        "(needs ${size.rounds}, ${ctx.totalRounds - ctx.currentRound} remain)."
                }
                check(!hasActiveProject(conn, playerTeamId, area)) {
                    "You already have a $area upgrade in development."
                }
                val cash = readCash(conn, playerTeamId)
                check(cash >= size.cost) {
                    "Not enough cash for this upgrade (need $${size.cost}, have $$cash)."
                }

                conn.prepareStatement("UPDATE teams SET cash_reserves = cash_reserves - ? WHERE id = ?").use { stmt ->
                    stmt.setLong(1, size.cost)
                    stmt.setString(2, playerTeamId)
                    stmt.executeUpdate()
                }
                conn.prepareStatement(
                    """
                    INSERT INTO upgrade_projects
                      (team_id, area, gain, cost, commissioned_year, commissioned_round, deliver_round)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, playerTeamId)
                    stmt.setString(2, area)
                    stmt.setInt(3, size.gain)
                    stmt.setLong(4, size.cost)
                    stmt.setInt(5, ctx.currentYear)
                    stmt.setInt(6, ctx.currentRound)
                    stmt.setInt(7, deliverRound)
                    stmt.executeUpdate()
                }
                conn.commit()
                log.info("Upgrade commissioned: {} {} for {} -> deliver round {}", sizeKey, area, playerTeamId, deliverRound)
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }
            readState(conn)
        }
    }

    // ------------------------------------------------------------------
    // Delivery (called from GameService transition hooks, within its txn)
    // ------------------------------------------------------------------

    /**
     * Deliver any of the current season's projects whose deliver round has
     * arrived (used at the PRACTICE transition). Returns human-readable
     * messages for the transition event log.
     */
    fun deliverDueUpgrades(conn: Connection, year: Int, round: Int): List<String> =
        deliver(conn, "commissioned_year = ? AND deliver_round <= ? AND NOT delivered", listOf(year, round))

    /**
     * Deliver every still-pending project regardless of round — a season-end
     * sweep so a late-commissioned upgrade's cash is never wasted.
     */
    fun deliverPendingUpgrades(conn: Connection): List<String> =
        deliver(conn, "NOT delivered", emptyList())

    private data class Pending(val id: Long, val teamId: String, val area: String, val gain: Int)

    private fun deliver(conn: Connection, where: String, params: List<Int>): List<String> {
        val pending = conn.prepareStatement(
            "SELECT id, team_id, area, gain FROM upgrade_projects WHERE $where"
        ).use { stmt ->
            params.forEachIndexed { i, p -> stmt.setInt(i + 1, p) }
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(Pending(rs.getLong("id"), rs.getString("team_id"), rs.getString("area"), rs.getInt("gain")))
                    }
                }
            }
        }
        if (pending.isEmpty()) return emptyList()

        val messages = mutableListOf<String>()
        for (p in pending) {
            val col = areaColumn(p.area)
            conn.prepareStatement("UPDATE teams SET $col = LEAST(100, $col + ?) WHERE id = ?").use { stmt ->
                stmt.setInt(1, p.gain)
                stmt.setString(2, p.teamId)
                stmt.executeUpdate()
            }
            messages += "${p.area} upgrade delivered (+${p.gain})"
        }
        // Mark delivered.
        conn.prepareStatement(
            "UPDATE upgrade_projects SET delivered = TRUE WHERE id = ANY(?)"
        ).use { stmt ->
            val arr = conn.createArrayOf("bigint", pending.map { it.id }.toTypedArray())
            stmt.setArray(1, arr)
            stmt.executeUpdate()
        }
        // Recompute overall car_performance for affected teams.
        val teamIds = pending.map { it.teamId }.distinct()
        conn.prepareStatement(
            """
            UPDATE teams
               SET car_performance = ROUND((car_aero + car_chassis + car_powertrain) / 3.0)::INT
             WHERE id = ANY(?)
            """.trimIndent()
        ).use { stmt ->
            val arr = conn.createArrayOf("text", teamIds.toTypedArray())
            stmt.setArray(1, arr)
            stmt.executeUpdate()
        }
        log.info("Delivered {} upgrade project(s)", pending.size)
        return messages
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    private data class GameContext(
        val playerTeamId: String?, val currentYear: Int, val currentRound: Int, val totalRounds: Int,
    )

    private fun readGameContext(conn: Connection): GameContext {
        val (teamId, year, round) = conn.prepareStatement(
            "SELECT player_team_id, current_season_year, current_round FROM game"
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "No game row" }
                Triple(rs.getString("player_team_id"), rs.getInt("current_season_year"), rs.getInt("current_round"))
            }
        }
        val total = conn.prepareStatement("SELECT COUNT(*) FROM races WHERE season_year = ?").use { stmt ->
            stmt.setInt(1, year)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        return GameContext(teamId, year, round, total)
    }

    private fun readState(conn: Connection): UpgradeStateDto {
        val ctx = readGameContext(conn)
        val base = UpgradeStateDto(
            playerTeamId = ctx.playerTeamId,
            currentYear = ctx.currentYear,
            currentRound = ctx.currentRound,
            totalRounds = ctx.totalRounds,
            sizes = sizeDtos(),
        )
        val playerTeamId = ctx.playerTeamId ?: return base

        val (cash, aero, chassis, powertrain) = conn.prepareStatement(
            "SELECT cash_reserves, car_aero, car_chassis, car_powertrain FROM teams WHERE id = ?"
        ).use { stmt ->
            stmt.setString(1, playerTeamId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return base
                listOf(rs.getLong("cash_reserves"), rs.getLong("car_aero"), rs.getLong("car_chassis"), rs.getLong("car_powertrain"))
            }.let { Quad(it[0], it[1].toInt(), it[2].toInt(), it[3].toInt()) }
        }

        val inProgress = conn.prepareStatement(
            """
            SELECT id, area, gain, cost, deliver_round
              FROM upgrade_projects
             WHERE team_id = ? AND NOT delivered AND commissioned_year = ?
             ORDER BY deliver_round ASC
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, playerTeamId)
            stmt.setInt(2, ctx.currentYear)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val deliverRound = rs.getInt("deliver_round")
                        add(
                            ProjectDto(
                                id = rs.getLong("id"),
                                area = rs.getString("area"),
                                gain = rs.getInt("gain"),
                                cost = rs.getLong("cost"),
                                deliverRound = deliverRound,
                                roundsRemaining = maxOf(0, deliverRound - ctx.currentRound),
                            )
                        )
                    }
                }
            }
        }

        return base.copy(
            cashReserves = cash,
            carAero = aero,
            carChassis = chassis,
            carPowertrain = powertrain,
            inProgress = inProgress,
        )
    }

    private data class Quad(val cash: Long, val aero: Int, val chassis: Int, val powertrain: Int)

    private fun hasActiveProject(conn: Connection, teamId: String, area: String): Boolean {
        return conn.prepareStatement(
            "SELECT 1 FROM upgrade_projects WHERE team_id = ? AND area = ? AND NOT delivered"
        ).use { stmt ->
            stmt.setString(1, teamId)
            stmt.setString(2, area)
            stmt.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun readCash(conn: Connection, teamId: String): Long {
        return conn.prepareStatement("SELECT cash_reserves FROM teams WHERE id = ?").use { stmt ->
            stmt.setString(1, teamId)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getLong("cash_reserves") else 0L }
        }
    }
}
