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
 * /api/engine-suppliers and /api/pu-versions.
 *
 * PU versions are grouped in this file because they only make sense relative
 * to a supplier and are usually viewed together.
 *
 * PU split into nested ice/ers DTOs mirroring the design doc's split.
 */
class PowerUnitRoutes(private val db: Database) {

    @Serializable
    data class EngineSupplierDto(
        val id: String,
        val name: String,
        val country: String,
        val enteredYear: Int,
        val exitedYear: Int?,
        val worksTeamId: String?,
        val isCustomSupplier: Boolean,
    )

    @Serializable
    data class PuVersionDto(
        val id: String,
        val supplierId: String,
        val mkVersion: Int,
        val introducedYear: Int,
        val ice: PuIceDto,
        val ers: PuErsDto,
        val reliability: Int,
        val coolingRequirement: Int,
    )

    @Serializable
    data class PuIceDto(
        val topSpeed: Int,
        val fuelEfficiency: Int,
        val weight: Int,
    )

    @Serializable
    data class PuErsDto(
        val deploymentPower: Int,
        val recoveryRate: Int,
        val deploymentEfficiency: Int,
    )

    fun register(app: Javalin) {
        app.get("/api/engine-suppliers", ::listSuppliers)
        app.get("/api/engine-suppliers/{id}", ::getSupplier)
        app.get("/api/pu-versions", ::listPuVersions)
        app.get("/api/pu-versions/{id}", ::getPuVersion)
    }

    // -- engine_suppliers --

    private fun listSuppliers(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()
        val suppliers = db.withConnection { conn ->
            conn.prepareStatement("$SUPPLIER_COLUMNS ORDER BY name ASC").use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(mapSupplier(rs)) }
                }
            }
        }
        Envelope.encode(suppliers, ListSerializer(EngineSupplierDto.serializer()))
    }

    private fun getSupplier(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()
        val id = ctx.pathParam("id")
        val supplier = db.withConnection { conn ->
            conn.prepareStatement("$SUPPLIER_COLUMNS WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) throw NotFoundException("No engine supplier with id $id")
                    mapSupplier(rs)
                }
            }
        }
        Envelope.encode(supplier, EngineSupplierDto.serializer())
    }

    // -- pu_versions --

    private fun listPuVersions(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()

        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        ctx.queryParam("supplier")?.let {
            conditions += "supplier_id = ?"
            params += it
        }

        val sql = buildString {
            append(PU_COLUMNS)
            if (conditions.isNotEmpty()) {
                append(" WHERE ")
                append(conditions.joinToString(" AND "))
            }
            append(" ORDER BY supplier_id, mk_version DESC")
        }

        val versions = db.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { i, p -> stmt.setObject(i + 1, p) }
                stmt.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(mapPuVersion(rs)) }
                }
            }
        }
        Envelope.encode(versions, ListSerializer(PuVersionDto.serializer()))
    }

    private fun getPuVersion(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()
        val id = try {
            UUID.fromString(ctx.pathParam("id"))
        } catch (_: IllegalArgumentException) {
            throw NotFoundException("No PU version with id ${ctx.pathParam("id")}")
        }
        val pu = db.withConnection { conn ->
            conn.prepareStatement("$PU_COLUMNS WHERE id = ?").use { stmt ->
                stmt.setObject(1, id)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) throw NotFoundException("No PU version with id $id")
                    mapPuVersion(rs)
                }
            }
        }
        Envelope.encode(pu, PuVersionDto.serializer())
    }

    // -- row mappers --

    private fun mapSupplier(rs: ResultSet): EngineSupplierDto = EngineSupplierDto(
        id = rs.getString("id"),
        name = rs.getString("name"),
        country = rs.getString("country"),
        enteredYear = rs.getInt("entered_year"),
        exitedYear = rs.getInt("exited_year").let { if (rs.wasNull()) null else it },
        worksTeamId = rs.getString("works_team_id"),
        isCustomSupplier = rs.getBoolean("is_custom_supplier"),
    )

    private fun mapPuVersion(rs: ResultSet): PuVersionDto = PuVersionDto(
        id = rs.getObject("id", UUID::class.java).toString(),
        supplierId = rs.getString("supplier_id"),
        mkVersion = rs.getInt("mk_version"),
        introducedYear = rs.getInt("introduced_year"),
        ice = PuIceDto(
            topSpeed = rs.getInt("ice_top_speed"),
            fuelEfficiency = rs.getInt("ice_fuel_efficiency"),
            weight = rs.getInt("ice_weight"),
        ),
        ers = PuErsDto(
            deploymentPower = rs.getInt("ers_deployment_power"),
            recoveryRate = rs.getInt("ers_recovery_rate"),
            deploymentEfficiency = rs.getInt("ers_deployment_efficiency"),
        ),
        reliability = rs.getInt("reliability"),
        coolingRequirement = rs.getInt("cooling_requirement"),
    )

    private companion object {
        const val SUPPLIER_COLUMNS = """
            SELECT id, name, country, entered_year, exited_year,
                   works_team_id, is_custom_supplier
              FROM engine_suppliers
        """

        const val PU_COLUMNS = """
            SELECT id, supplier_id, mk_version, introduced_year,
                   ice_top_speed, ice_fuel_efficiency, ice_weight,
                   ers_deployment_power, ers_recovery_rate, ers_deployment_efficiency,
                   reliability, cooling_requirement
              FROM pu_versions
        """
    }
}
