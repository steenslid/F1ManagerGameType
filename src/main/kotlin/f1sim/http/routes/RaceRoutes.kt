package f1sim.http.routes

import f1sim.db.Database
import f1sim.http.Envelope
import f1sim.http.NotFoundException
import f1sim.http.timed
import f1sim.save.SaveSession
import io.javalin.Javalin
import io.javalin.http.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.sql.ResultSet
import java.util.UUID

/**
 * /api/races — the calendar.
 *
 * The track is denormalized into the response (just id/name/country) so the
 * frontend doesn't need a second fetch to render a calendar view. Full track
 * details are still available via /api/tracks/{id}.
 *
 * Filter:
 *   ?season={year} — restrict to a specific season
 */
class RaceRoutes(private val db: Database) {

    @Serializable
    data class RaceDto(
        val id: String,
        val seasonYear: Int,
        val round: Int,
        val sessionFormat: String,
        val track: TrackRefDto,
    )

    @Serializable
    data class TrackRefDto(
        val id: String,
        val name: String,
        val country: String,
        val type: String,
        // Which car area this track rewards most (AERO | CHASSIS | POWERTRAIN),
        // from its demand profile — same blend the race sim uses. Lets the
        // player plan R&D against the upcoming calendar.
        val favoredArea: String,
    )

    fun register(app: Javalin) {
        app.get("/api/races", ::list)
        app.get("/api/races/{id}", ::getOne)
    }

    private fun list(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()

        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
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
            append(" ORDER BY r.season_year ASC, r.round ASC")
        }

        val races = db.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { i, p -> stmt.setObject(i + 1, p) }
                stmt.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(mapRow(rs)) }
                }
            }
        }
        Envelope.encode(races, ListSerializer(RaceDto.serializer()))
    }

    private fun getOne(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()
        val id = try {
            UUID.fromString(ctx.pathParam("id"))
        } catch (_: IllegalArgumentException) {
            throw NotFoundException("No race with id ${ctx.pathParam("id")}")
        }
        val race = db.withConnection { conn ->
            conn.prepareStatement("$SELECT_COLUMNS WHERE r.id = ?").use { stmt ->
                stmt.setObject(1, id)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) throw NotFoundException("No race with id $id")
                    mapRow(rs)
                }
            }
        }
        Envelope.encode(race, RaceDto.serializer())
    }

    private fun mapRow(rs: ResultSet): RaceDto = RaceDto(
        id = rs.getObject("id", UUID::class.java).toString(),
        seasonYear = rs.getInt("season_year"),
        round = rs.getInt("round"),
        sessionFormat = rs.getString("session_format"),
        track = TrackRefDto(
            id = rs.getString("track_id"),
            name = rs.getString("track_name"),
            country = rs.getString("track_country"),
            type = rs.getString("track_type"),
            favoredArea = favoredArea(rs),
        ),
    )

    // Mirror the sim's per-track weighting: powertrain ← top speed + accel,
    // aero ← cornering phases, chassis ← braking + tyre wear. Return the
    // dominant area.
    private fun favoredArea(rs: ResultSet): String {
        val power = rs.getDouble("demand_top_speed") + rs.getDouble("demand_acceleration")
        val aero = rs.getDouble("demand_low_speed_cornering") +
            rs.getDouble("demand_medium_speed_cornering") +
            rs.getDouble("demand_high_speed_cornering")
        val chassis = rs.getDouble("demand_braking") + rs.getDouble("demand_tyre_wear")
        return when (maxOf(power, aero, chassis)) {
            aero -> "AERO"
            power -> "POWERTRAIN"
            else -> "CHASSIS"
        }
    }

    private companion object {
        const val SELECT_COLUMNS = """
            SELECT r.id, r.season_year, r.round, r.session_format,
                   r.track_id, t.name AS track_name, t.country AS track_country,
                   t.type AS track_type,
                   t.demand_top_speed, t.demand_acceleration,
                   t.demand_low_speed_cornering, t.demand_medium_speed_cornering,
                   t.demand_high_speed_cornering, t.demand_braking, t.demand_tyre_wear
              FROM races r
              JOIN tracks t ON t.id = r.track_id
        """
    }
}
