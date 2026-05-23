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

/**
 * /api/tracks — list + get-by-id. No filters yet (small dataset).
 * Track demands are grouped into a nested DTO since the frontend will likely
 * render them as a single chart.
 */
class TrackRoutes(private val db: Database) {

    @Serializable
    data class TrackDto(
        val id: String,
        val name: String,
        val country: String,
        val lengthKm: Double,
        val type: String,
        val demands: TrackDemandsDto,
        val pitLaneLossSeconds: Double,
        val overtakeDifficulty: Double,
        val rainProbabilityBaseline: Double,
        val temperatureMinC: Double,
        val temperatureMaxC: Double,
    )

    @Serializable
    data class TrackDemandsDto(
        val topSpeed: Double,
        val acceleration: Double,
        val lowSpeedCornering: Double,
        val mediumSpeedCornering: Double,
        val highSpeedCornering: Double,
        val braking: Double,
        val tyreWear: Double,
        val cooling: Double,
    )

    fun register(app: Javalin) {
        app.get("/api/tracks", ::list)
        app.get("/api/tracks/{id}", ::getOne)
    }

    private fun list(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()
        val tracks = db.withConnection { conn ->
            conn.prepareStatement("$SELECT_COLUMNS ORDER BY name ASC").use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(mapRow(rs)) }
                }
            }
        }
        Envelope.encode(tracks, ListSerializer(TrackDto.serializer()))
    }

    private fun getOne(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()
        val id = ctx.pathParam("id")
        val track = db.withConnection { conn ->
            conn.prepareStatement("$SELECT_COLUMNS WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) throw NotFoundException("No track with id $id")
                    mapRow(rs)
                }
            }
        }
        Envelope.encode(track, TrackDto.serializer())
    }

    private fun mapRow(rs: ResultSet): TrackDto = TrackDto(
        id = rs.getString("id"),
        name = rs.getString("name"),
        country = rs.getString("country"),
        lengthKm = rs.getDouble("length_km"),
        type = rs.getString("type"),
        demands = TrackDemandsDto(
            topSpeed = rs.getDouble("demand_top_speed"),
            acceleration = rs.getDouble("demand_acceleration"),
            lowSpeedCornering = rs.getDouble("demand_low_speed_cornering"),
            mediumSpeedCornering = rs.getDouble("demand_medium_speed_cornering"),
            highSpeedCornering = rs.getDouble("demand_high_speed_cornering"),
            braking = rs.getDouble("demand_braking"),
            tyreWear = rs.getDouble("demand_tyre_wear"),
            cooling = rs.getDouble("demand_cooling"),
        ),
        pitLaneLossSeconds = rs.getDouble("pit_lane_loss_seconds"),
        overtakeDifficulty = rs.getDouble("overtake_difficulty"),
        rainProbabilityBaseline = rs.getDouble("rain_probability_baseline"),
        temperatureMinC = rs.getDouble("temperature_min_c"),
        temperatureMaxC = rs.getDouble("temperature_max_c"),
    )

    private companion object {
        const val SELECT_COLUMNS = """
            SELECT id, name, country, length_km, type,
                   demand_top_speed, demand_acceleration,
                   demand_low_speed_cornering, demand_medium_speed_cornering, demand_high_speed_cornering,
                   demand_braking, demand_tyre_wear, demand_cooling,
                   pit_lane_loss_seconds, overtake_difficulty,
                   rain_probability_baseline, temperature_min_c, temperature_max_c
              FROM tracks
        """
    }
}
