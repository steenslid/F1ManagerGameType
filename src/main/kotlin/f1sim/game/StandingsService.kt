package f1sim.game

import f1sim.db.Database
import f1sim.save.SaveSession
import kotlinx.serialization.Serializable
import java.sql.Connection

/**
 * Season standings. Aggregated from `race_results` + `sprint_results` on the
 * fly — no separate standings table.
 *
 * Points = SUM(race_results.points) + SUM(sprint_results.sprint_points)
 *   filtered to the requested season.
 *
 * Wins / podiums / poles stay race-only per design doc § Sprint specifics:
 * "Sprint wins/poles tracked separately from race wins/poles in career stats".
 *
 * Returned in points-descending order. Position is 1-based, assigned by sort
 * order; ties broken by driver/team name alphabetically.
 */
class StandingsService(private val db: Database) {

    @Serializable
    data class DriverStandingDto(
        val position: Int,
        val driverId: String,
        val driverName: String,
        val teamId: String,
        val teamName: String,
        val points: Double,
        val wins: Int,
        val podiums: Int,
        val poles: Int,
    )

    @Serializable
    data class TeamStandingDto(
        val position: Int,
        val teamId: String,
        val teamName: String,
        val points: Double,
        val wins: Int,
        val podiums: Int,
    )

    @Serializable
    data class StandingsResponseDto(
        val seasonYear: Int,
        val drivers: List<DriverStandingDto>? = null,
        val teams: List<TeamStandingDto>? = null,
    )

    /**
     * @param type "driver" or "team" or "both" (defaults to "both")
     * @param season year to aggregate over; defaults to current_season_year
     */
    fun standings(type: String, season: Int?): StandingsResponseDto {
        SaveSession.requireLoaded()
        require(type in setOf("driver", "team", "both")) {
            "type must be driver | team | both"
        }

        return db.withConnection { conn ->
            val resolvedSeason = season ?: readCurrentSeason(conn)
            val drivers = if (type != "team") readDriverStandings(conn, resolvedSeason) else null
            val teams = if (type != "driver") readTeamStandings(conn, resolvedSeason) else null
            StandingsResponseDto(
                seasonYear = resolvedSeason,
                drivers = drivers,
                teams = teams,
            )
        }
    }

    private fun readCurrentSeason(conn: Connection): Int {
        conn.prepareStatement("SELECT current_season_year FROM game").use { stmt ->
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "No game row in current save" }
                return rs.getInt("current_season_year")
            }
        }
    }

    private fun readDriverStandings(conn: Connection, season: Int): List<DriverStandingDto> {
        // MAX(sprint.pts) (not SUM) because the sprint subquery returns one row
        // per driver; the join with race_results multiplies it across rows.
        // Using MAX over identical values gives the single per-driver total back.
        val sql = """
            SELECT d.id AS driver_id, d.name AS driver_name,
                   d.current_racing_team_id AS team_id,
                   COALESCE(t.name, d.current_racing_team_id) AS team_name,
                   COALESCE(SUM(rr.points), 0) + COALESCE(MAX(sprint.pts), 0) AS pts,
                   COUNT(*) FILTER (WHERE rr.finishing_position = 1) AS wins,
                   COUNT(*) FILTER (WHERE rr.finishing_position BETWEEN 1 AND 3) AS podiums,
                   COUNT(*) FILTER (WHERE rr.pole) AS poles
              FROM drivers d
              LEFT JOIN teams t ON t.id = d.current_racing_team_id
              LEFT JOIN race_results rr ON rr.driver_id = d.id
              LEFT JOIN races r ON r.id = rr.race_id AND r.season_year = ?
              LEFT JOIN (
                  SELECT sr.driver_id, SUM(sr.sprint_points) AS pts
                    FROM sprint_results sr
                    JOIN races r2 ON r2.id = sr.race_id AND r2.season_year = ?
                   GROUP BY sr.driver_id
              ) sprint ON sprint.driver_id = d.id
             WHERE d.current_racing_team_id IS NOT NULL
               AND NOT d.retired
             GROUP BY d.id, d.name, d.current_racing_team_id, t.name
             ORDER BY pts DESC, d.name ASC
        """.trimIndent()

        return conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, season)
            stmt.setInt(2, season)
            stmt.executeQuery().use { rs ->
                buildList {
                    var pos = 0
                    while (rs.next()) {
                        pos += 1
                        add(
                            DriverStandingDto(
                                position = pos,
                                driverId = rs.getString("driver_id"),
                                driverName = rs.getString("driver_name"),
                                teamId = rs.getString("team_id"),
                                teamName = rs.getString("team_name"),
                                points = rs.getDouble("pts"),
                                wins = rs.getInt("wins"),
                                podiums = rs.getInt("podiums"),
                                poles = rs.getInt("poles"),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun readTeamStandings(conn: Connection, season: Int): List<TeamStandingDto> {
        val sql = """
            SELECT t.id AS team_id, t.name AS team_name,
                   COALESCE(SUM(rr.points), 0) + COALESCE(MAX(sprint.pts), 0) AS pts,
                   COUNT(*) FILTER (WHERE rr.finishing_position = 1) AS wins,
                   COUNT(*) FILTER (WHERE rr.finishing_position BETWEEN 1 AND 3) AS podiums
              FROM teams t
              LEFT JOIN race_results rr ON rr.team_id = t.id
              LEFT JOIN races r ON r.id = rr.race_id AND r.season_year = ?
              LEFT JOIN (
                  SELECT sr.team_id, SUM(sr.sprint_points) AS pts
                    FROM sprint_results sr
                    JOIN races r2 ON r2.id = sr.race_id AND r2.season_year = ?
                   GROUP BY sr.team_id
              ) sprint ON sprint.team_id = t.id
             WHERE t.series = 'F1'
             GROUP BY t.id, t.name
             ORDER BY pts DESC, t.name ASC
        """.trimIndent()

        return conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, season)
            stmt.setInt(2, season)
            stmt.executeQuery().use { rs ->
                buildList {
                    var pos = 0
                    while (rs.next()) {
                        pos += 1
                        add(
                            TeamStandingDto(
                                position = pos,
                                teamId = rs.getString("team_id"),
                                teamName = rs.getString("team_name"),
                                points = rs.getDouble("pts"),
                                wins = rs.getInt("wins"),
                                podiums = rs.getInt("podiums"),
                            )
                        )
                    }
                }
            }
        }
    }
}
