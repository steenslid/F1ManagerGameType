package f1sim.http.routes

import f1sim.db.Database
import f1sim.http.Envelope
import f1sim.http.getIntOrNull
import f1sim.http.timed
import f1sim.save.SaveSession
import io.javalin.Javalin
import io.javalin.http.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.sql.ResultSet
import java.util.UUID

/**
 * /api/race-results — list with filters.
 *
 * Filters (combine with AND):
 *   ?race={uuid}        — results for one race
 *   ?driver={uuid}      — all results for one driver
 *   ?team={id}          — all results for one team
 *   ?season={year}      — all results in one season
 *
 * Joins drivers and teams in so the response is renderable without a second
 * fetch. Driver and team names denormalized into the row.
 */
class RaceResultsRoutes(private val db: Database) {

    @Serializable
    data class RaceResultDto(
        val raceId: String,
        val seasonYear: Int,
        val round: Int,
        val driverId: String,
        val driverName: String,
        val teamId: String,
        val teamName: String,
        val gridPosition: Int?,
        val finishingPosition: Int?,
        val points: Double,
        val status: String,
        val pole: Boolean,
        val fastestLap: Boolean,
        val dnfCause: String?,
    )

    fun register(app: Javalin) {
        app.get("/api/race-results", ::list)
    }

    private fun list(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()

        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        ctx.queryParam("race")?.let {
            try {
                val id = UUID.fromString(it)
                conditions += "rr.race_id = ?"
                params += id
            } catch (_: IllegalArgumentException) {
                // Invalid UUID — guarantee empty result without failing.
                conditions += "FALSE"
            }
        }
        ctx.queryParam("driver")?.let {
            try {
                val id = UUID.fromString(it)
                conditions += "rr.driver_id = ?"
                params += id
            } catch (_: IllegalArgumentException) {
                conditions += "FALSE"
            }
        }
        ctx.queryParam("team")?.let {
            conditions += "rr.team_id = ?"
            params += it
        }
        ctx.queryParam("season")?.toIntOrNull()?.let {
            conditions += "r.season_year = ?"
            params += it
        }

        val sql = buildString {
            append(SELECT_COLUMNS)
            if (conditions.isNotEmpty()) {
                append(" WHERE ")
                append(conditions.joinToString(" AND "))
            }
            append(
                " ORDER BY r.season_year ASC, r.round ASC, " +
                    "COALESCE(rr.finishing_position, rr.grid_position, 999) ASC"
            )
        }

        val results = db.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { i, p -> stmt.setObject(i + 1, p) }
                stmt.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(mapRow(rs)) }
                }
            }
        }
        Envelope.encode(results, ListSerializer(RaceResultDto.serializer()))
    }

    private fun mapRow(rs: ResultSet): RaceResultDto = RaceResultDto(
        raceId = rs.getObject("race_id", UUID::class.java).toString(),
        seasonYear = rs.getInt("season_year"),
        round = rs.getInt("round"),
        driverId = rs.getObject("driver_id", UUID::class.java).toString(),
        driverName = rs.getString("driver_name"),
        teamId = rs.getString("team_id"),
        teamName = rs.getString("team_name"),
        gridPosition = rs.getIntOrNull("grid_position"),
        finishingPosition = rs.getIntOrNull("finishing_position"),
        points = rs.getDouble("points"),
        status = rs.getString("status"),
        pole = rs.getBoolean("pole"),
        fastestLap = rs.getBoolean("fastest_lap"),
        dnfCause = rs.getString("dnf_cause"),
    )

    private companion object {
        const val SELECT_COLUMNS = """
            SELECT rr.race_id, r.season_year, r.round,
                   rr.driver_id, d.name AS driver_name,
                   rr.team_id, t.name AS team_name,
                   rr.grid_position, rr.finishing_position, rr.points,
                   rr.status, rr.pole, rr.fastest_lap, rr.dnf_cause
              FROM race_results rr
              JOIN races r   ON r.id = rr.race_id
              JOIN drivers d ON d.id = rr.driver_id
              JOIN teams t   ON t.id = rr.team_id
        """
    }
}
