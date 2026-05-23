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
import java.util.UUID

/**
 * /api/drivers — list with filters, plus get-by-id.
 *
 * Filters:
 *   ?team={id}         — drivers whose current_racing_team_id matches
 *   ?free_agent=true   — drivers with no racing/reserve team
 *   ?nationality={c}   — drivers with that nationality
 *
 * Hidden fields excluded from the response:
 *   development_pool, trait_peak_age, trait_decline_rate,
 *   trait_retirement_threshold, trait_loyalty, trait_temperament,
 *   trait_market_value_modifier
 */
class DriverRoutes(private val db: Database) {

    @Serializable
    data class DriverDto(
        val id: String,
        val name: String,
        val nationality: String,
        val currentAge: Int,
        val currentRacingTeamId: String?,
        val reserveForTeamId: String?,
        val academyTeamId: String?,
        val retired: Boolean,
        val currentSalary: Long,
        val contractExpiresYear: Int?,
        val contractExpiresRound: Int?,
        val morale: Int,
        val stats: DriverStatsDto,
    )

    @Serializable
    data class DriverStatsDto(
        val pace: Int,
        val qualifying: Int,
        val overtaking: Int,
        val defending: Int,
        val consistency: Int,
        val tyreManagement: Int,
        val ersDeployment: Int,
        val wetSkill: Int,
        val feedbackQuality: Int,
    )

    fun register(app: Javalin) {
        app.get("/api/drivers", ::list)
        app.get("/api/drivers/{id}", ::getOne)
    }

    private fun list(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()

        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        ctx.queryParam("team")?.let {
            conditions += "current_racing_team_id = ?"
            params += it
        }
        ctx.queryParam("free_agent")?.let {
            if (it.equals("true", ignoreCase = true)) {
                conditions += "current_racing_team_id IS NULL AND reserve_for_team_id IS NULL"
            }
        }
        ctx.queryParam("nationality")?.let {
            conditions += "nationality = ?"
            params += it
        }

        val sql = buildString {
            append(SELECT_COLUMNS)
            if (conditions.isNotEmpty()) {
                append(" WHERE ")
                append(conditions.joinToString(" AND "))
            }
            append(" ORDER BY stat_pace DESC, name ASC")
        }

        val drivers = db.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { i, p -> stmt.setObject(i + 1, p) }
                stmt.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(mapRow(rs)) }
                }
            }
        }
        Envelope.encode(drivers, ListSerializer(DriverDto.serializer()))
    }

    private fun getOne(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()
        val id = try {
            UUID.fromString(ctx.pathParam("id"))
        } catch (_: IllegalArgumentException) {
            throw NotFoundException("No driver with id ${ctx.pathParam("id")}")
        }
        val driver = db.withConnection { conn ->
            conn.prepareStatement("$SELECT_COLUMNS WHERE id = ?").use { stmt ->
                stmt.setObject(1, id)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) throw NotFoundException("No driver with id $id")
                    mapRow(rs)
                }
            }
        }
        Envelope.encode(driver, DriverDto.serializer())
    }

    private fun mapRow(rs: ResultSet): DriverDto = DriverDto(
        id = rs.getObject("id", UUID::class.java).toString(),
        name = rs.getString("name"),
        nationality = rs.getString("nationality"),
        currentAge = rs.getInt("current_age"),
        currentRacingTeamId = rs.getString("current_racing_team_id"),
        reserveForTeamId = rs.getString("reserve_for_team_id"),
        academyTeamId = rs.getString("academy_team_id"),
        retired = rs.getBoolean("retired"),
        currentSalary = rs.getLong("current_salary"),
        contractExpiresYear = rs.getIntOrNull("contract_expires_year"),
        contractExpiresRound = rs.getIntOrNull("contract_expires_round"),
        morale = rs.getInt("morale"),
        stats = DriverStatsDto(
            pace = rs.getInt("stat_pace"),
            qualifying = rs.getInt("stat_qualifying"),
            overtaking = rs.getInt("stat_overtaking"),
            defending = rs.getInt("stat_defending"),
            consistency = rs.getInt("stat_consistency"),
            tyreManagement = rs.getInt("stat_tyre_management"),
            ersDeployment = rs.getInt("stat_ers_deployment"),
            wetSkill = rs.getInt("stat_wet_skill"),
            feedbackQuality = rs.getInt("stat_feedback_quality"),
        ),
    )

    private companion object {
        const val SELECT_COLUMNS = """
            SELECT id, name, nationality, current_age,
                   current_racing_team_id, reserve_for_team_id, academy_team_id, retired,
                   current_salary, contract_expires_year, contract_expires_round,
                   morale, stat_pace, stat_qualifying, stat_overtaking, stat_defending,
                   stat_consistency, stat_tyre_management, stat_ers_deployment,
                   stat_wet_skill, stat_feedback_quality
              FROM drivers
        """
    }
}
