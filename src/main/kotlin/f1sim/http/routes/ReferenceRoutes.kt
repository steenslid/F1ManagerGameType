package f1sim.http.routes

import f1sim.db.Database
import f1sim.http.Envelope
import f1sim.http.NotFoundException
import f1sim.http.getIntOrNull
import f1sim.http.timed
import f1sim.save.SaveSession
import io.javalin.Javalin
import io.javalin.http.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.sql.ResultSet

/**
 * Reference data routes. Combined into one file because both are small
 * lookup tables with no filters and identical access patterns.
 *
 * /api/tyre-compounds
 * /api/regulation-eras
 */
class ReferenceRoutes(private val db: Database) {

    @Serializable
    data class TyreCompoundDto(
        val id: String,
        val name: String,
        val basePaceFactor: Double,
        val degradationRate: Double,
        val longevityLaps: Int,
        val optimalTempMinC: Double,
        val optimalTempMaxC: Double,
    )

    @Serializable
    data class RegulationEraDto(
        val id: String,
        val name: String,
        val startYear: Int,
        val endYear: Int?,
        val performanceResetSeverity: Double,
        val ersShare: Double,
        val fastestLapPoint: Boolean,
    )

    fun register(app: Javalin) {
        app.get("/api/tyre-compounds", ::listCompounds)
        app.get("/api/tyre-compounds/{id}", ::getCompound)
        app.get("/api/regulation-eras", ::listEras)
        app.get("/api/regulation-eras/{id}", ::getEra)
    }

    private fun listCompounds(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()
        val compounds = db.withConnection { conn ->
            conn.prepareStatement("$COMPOUND_COLUMNS ORDER BY base_pace_factor ASC").use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(mapCompound(rs)) }
                }
            }
        }
        Envelope.encode(compounds, ListSerializer(TyreCompoundDto.serializer()))
    }

    private fun getCompound(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()
        val id = ctx.pathParam("id")
        val compound = db.withConnection { conn ->
            conn.prepareStatement("$COMPOUND_COLUMNS WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) throw NotFoundException("No tyre compound with id $id")
                    mapCompound(rs)
                }
            }
        }
        Envelope.encode(compound, TyreCompoundDto.serializer())
    }

    private fun listEras(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()
        val eras = db.withConnection { conn ->
            conn.prepareStatement("$ERA_COLUMNS ORDER BY start_year DESC").use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(mapEra(rs)) }
                }
            }
        }
        Envelope.encode(eras, ListSerializer(RegulationEraDto.serializer()))
    }

    private fun getEra(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()
        val id = ctx.pathParam("id")
        val era = db.withConnection { conn ->
            conn.prepareStatement("$ERA_COLUMNS WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) throw NotFoundException("No regulation era with id $id")
                    mapEra(rs)
                }
            }
        }
        Envelope.encode(era, RegulationEraDto.serializer())
    }

    private fun mapCompound(rs: ResultSet): TyreCompoundDto = TyreCompoundDto(
        id = rs.getString("id"),
        name = rs.getString("name"),
        basePaceFactor = rs.getDouble("base_pace_factor"),
        degradationRate = rs.getDouble("degradation_rate"),
        longevityLaps = rs.getInt("longevity_laps"),
        optimalTempMinC = rs.getDouble("optimal_temp_min_c"),
        optimalTempMaxC = rs.getDouble("optimal_temp_max_c"),
    )

    private fun mapEra(rs: ResultSet): RegulationEraDto = RegulationEraDto(
        id = rs.getString("id"),
        name = rs.getString("name"),
        startYear = rs.getInt("start_year"),
        endYear = rs.getIntOrNull("end_year"),
        performanceResetSeverity = rs.getDouble("performance_reset_severity"),
        ersShare = rs.getDouble("ers_share"),
        fastestLapPoint = rs.getBoolean("fastest_lap_point"),
    )

    private companion object {
        const val COMPOUND_COLUMNS = """
            SELECT id, name, base_pace_factor, degradation_rate, longevity_laps,
                   optimal_temp_min_c, optimal_temp_max_c
              FROM tyre_compounds
        """

        const val ERA_COLUMNS = """
            SELECT id, name, start_year, end_year,
                   performance_reset_severity, ers_share,
                   fastest_lap_point
              FROM regulation_eras
        """
    }
}
