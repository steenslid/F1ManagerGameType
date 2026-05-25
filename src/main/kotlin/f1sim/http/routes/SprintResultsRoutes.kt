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
 * /api/sprint-results — list with filters.
 *
 * Filters (combine with AND):
 *   ?race={uuid}        — sprint results for one race
 *   ?driver={uuid}      — all sprint results for one driver
 *   ?team={id}          — all sprint results for one team
 *   ?season={year}      — all sprint results in one season
 *
 * Only sprint-weekend races have rows here; standard weekends are absent.
 * Mirrors the race-results route's filter set and join shape.
 */
class SprintResultsRoutes(private val db: Database) {

    @Serializable
    data class SprintResultDto(
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
        val dnfCause: String?,
    )

    fun register(app: Javalin) {
        app.get("/api/sprint-results", ::list)
    }

    private fun list(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()

        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        ctx.queryParam("race")?.let {
            try {
                val id = UUID.fromString(it)
                conditions += "sr.race_id = ?"
                params += id
            } catch (_: IllegalArgumentException) {
                conditions += "FALSE"
            }
        }
        ctx.queryParam("driver")?.let {
            try {
                val id = UUID.fromString(it)
                conditions += "sr.driver_id = ?"
                params += id
            } catch (_: IllegalArgumentException) {
                conditions += "FALSE"
            }
        }
        ctx.queryParam("team")?.let {
            conditions += "sr.team_id = ?"
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
                    "COALESCE(sr.finishing_position, sr.grid_position, 999) ASC"
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
        Envelope.encode(results, ListSerializer(SprintResultDto.serializer()))
    }

    private fun mapRow(rs: ResultSet): SprintResultDto = SprintResultDto(
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
        dnfCause = rs.getString("dnf_cause"),
    )

    private companion object {
        const val SELECT_COLUMNS = """
            SELECT sr.race_id, r.season_year, r.round,
                   sr.driver_id, d.name AS driver_name,
                   sr.team_id, t.name AS team_name,
                   sr.grid_position, sr.finishing_position,
                   sr.sprint_points AS points,
                   sr.status, sr.pole, sr.dnf_cause
              FROM sprint_results sr
              JOIN races r   ON r.id = sr.race_id
              JOIN drivers d ON d.id = sr.driver_id
              JOIN teams t   ON t.id = sr.team_id
        """
    }
}
