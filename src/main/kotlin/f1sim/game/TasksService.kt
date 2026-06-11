package f1sim.game

import f1sim.db.Database
import f1sim.save.SaveSession
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.util.UUID

/**
 * Aggregates everything that currently wants the player's attention into one
 * checklist, so the UI can guide instead of the player hunting across screens.
 *
 * REQUIRED tasks block the advance (the GameService gates); SUGGESTED tasks
 * are profitable but skippable. Each task carries the panel that handles it.
 * Read-only, no RNG.
 */
class TasksService(private val db: Database) {

    @Serializable
    data class TasksDto(
        val playerTeamId: String? = null,
        val phase: String = "",
        val tasks: List<TaskDto> = emptyList(),
    )

    @Serializable
    data class TaskDto(
        val id: String,
        val label: String,
        val detail: String,
        val panel: String,     // RACE_WEEKEND | MARKET | STAFF | RD | DASHBOARD
        val severity: String,  // REQUIRED | SUGGESTED
    )

    fun view(): TasksDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn -> read(conn) }
    }

    private fun read(conn: Connection): TasksDto {
        val ctx = conn.prepareStatement(
            "SELECT player_team_id, current_season_year, current_round, current_phase FROM game"
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "No game row" }
                Quad(
                    teamId = rs.getString("player_team_id"),
                    year = rs.getInt("current_season_year"),
                    round = rs.getInt("current_round"),
                    phase = rs.getString("current_phase"),
                )
            }
        }
        val playerTeamId = ctx.teamId
        val year = ctx.year
        val round = ctx.round
        val phase = ctx.phase

        if (playerTeamId == null) {
            return TasksDto(
                phase = phase,
                tasks = listOf(
                    TaskDto(
                        "select-team", "Choose a constructor",
                        "Take control of a team to start playing.", "TEAMS", "REQUIRED",
                    )
                ),
            )
        }

        val tasks = mutableListOf<TaskDto>()

        // -- Race-weekend gates (these literally block the advance) -------
        if (phase == "PRACTICE" || phase == "QUALIFYING") {
            val raceId = readRaceId(conn, year, round)
            if (raceId != null) {
                val table = if (phase == "PRACTICE") "practice_focus" else "race_strategy"
                val missing = countMissing(conn, raceId, playerTeamId, table)
                if (missing > 0) {
                    tasks += if (phase == "PRACTICE") TaskDto(
                        "practice-focus", "Set practice focus",
                        "$missing driver(s) still need a focus before qualifying.",
                        "RACE_WEEKEND", "REQUIRED",
                    ) else TaskDto(
                        "race-strategy", "Pick race strategies",
                        "$missing driver(s) still need a strategy before the race.",
                        "RACE_WEEKEND", "REQUIRED",
                    )
                }
            }
        }

        // -- Driver market: open seats with no pending offers --------------
        if (phase == "DRIVER_MARKET") {
            val openSeats = scalar(
                conn,
                "SELECT GREATEST(0, 2 - COUNT(*)) FROM drivers WHERE NOT retired AND current_racing_team_id = ?",
                playerTeamId,
            )
            val offers = scalar(
                conn,
                "SELECT COUNT(*) FROM driver_market_offers WHERE team_id = ?",
                playerTeamId,
            )
            if (openSeats > 0 && offers == 0) {
                tasks += TaskDto(
                    "market-offers", "Make driver offers",
                    "$openSeats open seat(s) and no pending offers — the market won't fill them for you.",
                    "MARKET", "REQUIRED",
                )
            }
        }

        // -- Vacant staff slots --------------------------------------------
        val staffCount = scalar(
            conn,
            "SELECT COUNT(DISTINCT role) FROM personnel WHERE NOT retired AND current_team_id = ? AND role IS NOT NULL",
            playerTeamId,
        )
        if (staffCount < 5) {
            tasks += TaskDto(
                "staff-vacancies", "Fill staff vacancies",
                "${5 - staffCount} department-head slot(s) empty — staff speed up car and driver development.",
                "STAFF", "SUGGESTED",
            )
        }

        // -- Sponsor deals expiring this season ------------------------------
        val expiring = conn.prepareStatement(
            "SELECT COUNT(*) FROM team_sponsorships WHERE team_id = ? AND end_year = ?"
        ).use { stmt ->
            stmt.setString(1, playerTeamId)
            stmt.setInt(2, year)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        if (expiring > 0) {
            tasks += TaskDto(
                "sponsors-expiring", "Sponsor deals expiring",
                "$expiring deal(s) end this season and won't auto-renew — renew or replace them.",
                "MARKET", "SUGGESTED",
            )
        }

        // -- No R&D spend at all ---------------------------------------------
        val rdTotal = scalar(
            conn,
            "SELECT (rd_aero + rd_chassis + rd_powertrain) FROM teams WHERE id = ?",
            playerTeamId,
        )
        if (rdTotal == 0) {
            tasks += TaskDto(
                "rd-zero", "Fund R&D",
                "All three R&D budgets are zero — your car will slide toward the back of the grid.",
                "RD", "SUGGESTED",
            )
        }

        // -- Between rounds: lineup window -----------------------------------
        if (phase == "BETWEEN_ROUNDS") {
            tasks += TaskDto(
                "lineup-window", "Lineup change window",
                "You can swap a race driver for a free agent, reserve or F2/F3 junior before the next round.",
                "RACE_WEEKEND", "SUGGESTED",
            )
        }

        return TasksDto(playerTeamId = playerTeamId, phase = phase, tasks = tasks)
    }

    private data class Quad(val teamId: String?, val year: Int, val round: Int, val phase: String)

    private fun readRaceId(conn: Connection, year: Int, round: Int): UUID? {
        if (round <= 0) return null
        return conn.prepareStatement(
            "SELECT id FROM races WHERE season_year = ? AND round = ?"
        ).use { stmt ->
            stmt.setInt(1, year)
            stmt.setInt(2, round)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getObject("id", UUID::class.java) else null }
        }
    }

    /** Count player race drivers missing a row in the given decision table. */
    private fun countMissing(conn: Connection, raceId: UUID, teamId: String, table: String): Int {
        return conn.prepareStatement(
            """
            SELECT COUNT(*)
              FROM drivers d
             WHERE d.current_racing_team_id = ?
               AND NOT d.retired
               AND NOT EXISTS (
                   SELECT 1 FROM $table x WHERE x.race_id = ? AND x.driver_id = d.id
               )
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, teamId)
            stmt.setObject(2, raceId)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private fun scalar(conn: Connection, sql: String, teamId: String): Int {
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, teamId)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }
}
