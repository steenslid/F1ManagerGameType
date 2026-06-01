package f1sim.game

import f1sim.db.Database
import f1sim.save.SaveSession
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection

/** Hard cap on the annual R&D budget the player can set per car area. */
private const val MAX_RD_BUDGET_PER_AREA = 200_000_000L

/**
 * Player R&D control, split across three car areas — aerodynamics, chassis,
 * powertrain. The player sets an annual budget per area; each costs money
 * (summed into the PRE_SEASON operating-cost tick) and develops its own rating
 * over seasons (see [OffSeasonService.runCarDevelopment]). Overall
 * `car_performance` (what the sim reads) is the average of the three.
 *
 * Read returns the player's per-area ratings + budgets + cash, plus the whole
 * F1 grid's overall car ratings so the player can see where they stand.
 */
class TeamRdService(private val db: Database) {

    private val log = LoggerFactory.getLogger(TeamRdService::class.java)

    @Serializable
    data class RdStateDto(
        val playerTeamId: String? = null,
        val carPerformance: Int? = null,
        val carAero: Int? = null,
        val carChassis: Int? = null,
        val carPowertrain: Int? = null,
        val rdAero: Long? = null,
        val rdChassis: Long? = null,
        val rdPowertrain: Long? = null,
        val cashReserves: Long? = null,
        val maxPerArea: Long = MAX_RD_BUDGET_PER_AREA,
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
    data class SetRdRequest(
        val aero: Long,
        val chassis: Long,
        val powertrain: Long,
    )

    fun view(): RdStateDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn -> readState(conn) }
    }

    fun setBudget(req: SetRdRequest): RdStateDto {
        SaveSession.requireLoaded()
        listOf("aero" to req.aero, "chassis" to req.chassis, "powertrain" to req.powertrain)
            .forEach { (name, v) ->
                require(v in 0..MAX_RD_BUDGET_PER_AREA) {
                    "$name budget must be between 0 and $MAX_RD_BUDGET_PER_AREA"
                }
            }
        return db.withConnection { conn ->
            val playerTeamId = readPlayerTeamId(conn)
                ?: error("No player team selected — cannot set an R&D budget")
            conn.prepareStatement(
                "UPDATE teams SET rd_aero = ?, rd_chassis = ?, rd_powertrain = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, req.aero)
                stmt.setLong(2, req.chassis)
                stmt.setLong(3, req.powertrain)
                stmt.setString(4, playerTeamId)
                stmt.executeUpdate()
            }
            log.info("R&D budget for {} set: aero={} chassis={} pu={}", playerTeamId, req.aero, req.chassis, req.powertrain)
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

        if (playerTeamId == null) return RdStateDto(grid = grid)

        return conn.prepareStatement(
            """
            SELECT car_performance, car_aero, car_chassis, car_powertrain,
                   rd_aero, rd_chassis, rd_powertrain, cash_reserves
              FROM teams WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, playerTeamId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) RdStateDto(playerTeamId = playerTeamId, grid = grid)
                else RdStateDto(
                    playerTeamId = playerTeamId,
                    carPerformance = rs.getInt("car_performance"),
                    carAero = rs.getInt("car_aero"),
                    carChassis = rs.getInt("car_chassis"),
                    carPowertrain = rs.getInt("car_powertrain"),
                    rdAero = rs.getLong("rd_aero"),
                    rdChassis = rs.getLong("rd_chassis"),
                    rdPowertrain = rs.getLong("rd_powertrain"),
                    cashReserves = rs.getLong("cash_reserves"),
                    grid = grid,
                )
            }
        }
    }

    private fun readPlayerTeamId(conn: Connection): String? {
        return conn.prepareStatement("SELECT player_team_id FROM game").use { stmt ->
            stmt.executeQuery().use { rs -> if (!rs.next()) null else rs.getString("player_team_id") }
        }
    }
}
