package f1sim.game

import f1sim.db.Database
import f1sim.save.SaveSession
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection

/** Hard cap on the annual R&D budget the player can set. */
private const val MAX_RD_BUDGET = 500_000_000L

/**
 * Player R&D budget control. The player sets an annual R&D spend for their
 * team; it costs money (folded into the PRE_SEASON operating-cost tick) and
 * develops `car_performance` over seasons (see
 * [OffSeasonService.runCarDevelopment]).
 *
 * Read returns the player's current car + budget + cash alongside the whole
 * F1 grid's car ratings so the player can see where they stand.
 */
class TeamRdService(private val db: Database) {

    private val log = LoggerFactory.getLogger(TeamRdService::class.java)

    @Serializable
    data class RdStateDto(
        val playerTeamId: String? = null,
        val carPerformance: Int? = null,
        val rdBudget: Long? = null,
        val cashReserves: Long? = null,
        val maxBudget: Long = MAX_RD_BUDGET,
        val grid: List<RdGridDto> = emptyList(),
    )

    @Serializable
    data class RdGridDto(
        val teamId: String,
        val teamName: String,
        val carPerformance: Int,
        val isPlayer: Boolean,
    )

    @Serializable
    data class SetRdRequest(val budget: Long)

    fun view(): RdStateDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn -> readState(conn) }
    }

    fun setBudget(req: SetRdRequest): RdStateDto {
        SaveSession.requireLoaded()
        require(req.budget in 0..MAX_RD_BUDGET) {
            "budget must be between 0 and $MAX_RD_BUDGET"
        }
        return db.withConnection { conn ->
            val playerTeamId = readPlayerTeamId(conn)
                ?: error("No player team selected — cannot set an R&D budget")
            conn.prepareStatement("UPDATE teams SET rd_budget = ? WHERE id = ?").use { stmt ->
                stmt.setLong(1, req.budget)
                stmt.setString(2, playerTeamId)
                stmt.executeUpdate()
            }
            log.info("R&D budget for {} set to {}", playerTeamId, req.budget)
            readState(conn)
        }
    }

    private fun readState(conn: Connection): RdStateDto {
        val playerTeamId = readPlayerTeamId(conn)

        val grid = conn.prepareStatement(
            """
            SELECT id, name, car_performance
              FROM teams
             WHERE series = 'F1'
             ORDER BY car_performance DESC, name ASC
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = rs.getString("id")
                        add(
                            RdGridDto(
                                teamId = id,
                                teamName = rs.getString("name"),
                                carPerformance = rs.getInt("car_performance"),
                                isPlayer = id == playerTeamId,
                            )
                        )
                    }
                }
            }
        }

        if (playerTeamId == null) {
            return RdStateDto(grid = grid)
        }

        return conn.prepareStatement(
            "SELECT car_performance, rd_budget, cash_reserves FROM teams WHERE id = ?"
        ).use { stmt ->
            stmt.setString(1, playerTeamId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) {
                    RdStateDto(playerTeamId = playerTeamId, grid = grid)
                } else {
                    RdStateDto(
                        playerTeamId = playerTeamId,
                        carPerformance = rs.getInt("car_performance"),
                        rdBudget = rs.getLong("rd_budget"),
                        cashReserves = rs.getLong("cash_reserves"),
                        grid = grid,
                    )
                }
            }
        }
    }

    private fun readPlayerTeamId(conn: Connection): String? {
        return conn.prepareStatement("SELECT player_team_id FROM game").use { stmt ->
            stmt.executeQuery().use { rs -> if (!rs.next()) null else rs.getString("player_team_id") }
        }
    }
}
