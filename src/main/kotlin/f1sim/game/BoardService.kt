package f1sim.game

import f1sim.db.Database
import f1sim.save.SaveSession
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection
import kotlin.math.roundToInt

/**
 * Board objective: the constructors'-championship finish the board expects of
 * the player's team this season, plus how they're tracking against it. The
 * target is derived from where the team sits on prestige among F1 teams,
 * nudged by the board's ambition (an ambitious board expects you to punch
 * above your prestige; a realistic one is happy with par). Read-only, no RNG —
 * gives the player a concrete goal to plan the season around.
 */
class BoardService(private val db: Database) {

    private val log = LoggerFactory.getLogger(BoardService::class.java)

    @Serializable
    data class BoardDto(
        val playerTeamId: String? = null,
        val targetPosition: Int? = null,
        val currentPosition: Int? = null,
        val fieldSize: Int = 0,
        val status: String = "NONE", // NONE | AHEAD | ON_TARGET | BEHIND
        val summary: String = "",
    )

    fun view(): BoardDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn -> read(conn) }
    }

    private data class TeamRow(val id: String, val prestige: Int, val points: Int, val boardAmbition: Double)

    private fun read(conn: Connection): BoardDto {
        val playerTeamId = conn.prepareStatement("SELECT player_team_id FROM game").use { stmt ->
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("player_team_id") else null }
        } ?: return BoardDto()

        val teams = conn.prepareStatement(
            """
            SELECT id, prestige, season_points, board_ambition
              FROM teams
             WHERE series = 'F1'
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            TeamRow(
                                id = rs.getString("id"),
                                prestige = rs.getInt("prestige"),
                                points = rs.getInt("season_points"),
                                boardAmbition = rs.getDouble("board_ambition"),
                            )
                        )
                    }
                }
            }
        }
        val n = teams.size
        val player = teams.firstOrNull { it.id == playerTeamId } ?: return BoardDto(playerTeamId = playerTeamId)

        // Prestige rank (1 = most prestigious), tiebreak by id for stability.
        val byPrestige = teams.sortedWith(compareByDescending<TeamRow> { it.prestige }.thenBy { it.id })
        val prestigeRank = byPrestige.indexOfFirst { it.id == playerTeamId } + 1

        // Ambition pulls the target above (or below) prestige par.
        val target = (prestigeRank - (player.boardAmbition - 0.5) * 2.0 * AMBITION_SWING)
            .roundToInt().coerceIn(1, n)

        // Current WCC position from season points (tiebreak by prestige then id).
        val byPoints = teams.sortedWith(
            compareByDescending<TeamRow> { it.points }
                .thenByDescending { it.prestige }
                .thenBy { it.id }
        )
        val current = byPoints.indexOfFirst { it.id == playerTeamId } + 1

        val status = when {
            current < target -> "AHEAD"
            current == target -> "ON_TARGET"
            else -> "BEHIND"
        }
        val summary = when (status) {
            "AHEAD" -> "Ahead of the board's P$target target — currently P$current."
            "ON_TARGET" -> "Right on the board's P$target target."
            else -> "The board expects P$target; you're P$current. Time to make up ground."
        }
        return BoardDto(
            playerTeamId = playerTeamId,
            targetPosition = target,
            currentPosition = current,
            fieldSize = n,
            status = status,
            summary = summary,
        )
    }

    private companion object {
        /** How far board ambition can shift the target off prestige par (places). */
        const val AMBITION_SWING = 3.0
    }
}
