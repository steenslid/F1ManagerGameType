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
 * /api/personnel — list with filters, plus get-by-id.
 *
 * Filters:
 *   ?team={id}        — personnel currently at that team
 *   ?role={ROLE}      — PRINCIPAL | TECHNICAL_DIRECTOR | CHIEF_STRATEGIST | CREW_CHIEF | RACE_ENGINEER
 *   ?free_agent=true  — current_team_id is null
 *
 * Hidden fields excluded from the response:
 *   development_pool, trait_peak_age, trait_decline_rate, trait_loyalty
 */
class PersonnelRoutes(private val db: Database) {

    @Serializable
    data class PersonnelDto(
        val id: String,
        val name: String,
        val nationality: String,
        val age: Int,
        val currentTeamId: String?,
        val role: String?,
        val currentSalary: Long,
        val contractExpiresYear: Int?,
        val contractExpiresRound: Int?,
        val retired: Boolean,
        val skills: PersonnelSkillsDto,
    )

    @Serializable
    data class PersonnelSkillsDto(
        val leadership: Int,
        val design: Int,
        val strategy: Int,
        val crewManagement: Int,
        val driverManagement: Int,
    )

    fun register(app: Javalin) {
        app.get("/api/personnel", ::list)
        app.get("/api/personnel/{id}", ::getOne)
    }

    private fun list(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()

        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        ctx.queryParam("team")?.let {
            conditions += "current_team_id = ?"
            params += it
        }
        ctx.queryParam("role")?.let {
            conditions += "role = ?"
            params += it
        }
        ctx.queryParam("free_agent")?.let {
            if (it.equals("true", ignoreCase = true)) {
                conditions += "current_team_id IS NULL"
            }
        }

        val sql = buildString {
            append(SELECT_COLUMNS)
            if (conditions.isNotEmpty()) {
                append(" WHERE ")
                append(conditions.joinToString(" AND "))
            }
            append(" ORDER BY role NULLS LAST, name ASC")
        }

        val personnel = db.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { i, p -> stmt.setObject(i + 1, p) }
                stmt.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(mapRow(rs)) }
                }
            }
        }
        Envelope.encode(personnel, ListSerializer(PersonnelDto.serializer()))
    }

    private fun getOne(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()
        val id = try {
            UUID.fromString(ctx.pathParam("id"))
        } catch (_: IllegalArgumentException) {
            throw NotFoundException("No personnel with id ${ctx.pathParam("id")}")
        }
        val person = db.withConnection { conn ->
            conn.prepareStatement("$SELECT_COLUMNS WHERE id = ?").use { stmt ->
                stmt.setObject(1, id)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) throw NotFoundException("No personnel with id $id")
                    mapRow(rs)
                }
            }
        }
        Envelope.encode(person, PersonnelDto.serializer())
    }

    private fun mapRow(rs: ResultSet): PersonnelDto = PersonnelDto(
        id = rs.getObject("id", UUID::class.java).toString(),
        name = rs.getString("name"),
        nationality = rs.getString("nationality"),
        age = rs.getInt("age"),
        currentTeamId = rs.getString("current_team_id"),
        role = rs.getString("role"),
        currentSalary = rs.getLong("current_salary"),
        contractExpiresYear = rs.getIntOrNull("contract_expires_year"),
        contractExpiresRound = rs.getIntOrNull("contract_expires_round"),
        retired = rs.getBoolean("retired"),
        skills = PersonnelSkillsDto(
            leadership = rs.getInt("skill_leadership"),
            design = rs.getInt("skill_design"),
            strategy = rs.getInt("skill_strategy"),
            crewManagement = rs.getInt("skill_crew_management"),
            driverManagement = rs.getInt("skill_driver_management"),
        ),
    )

    private companion object {
        const val SELECT_COLUMNS = """
            SELECT id, name, nationality, age,
                   current_team_id, role, current_salary,
                   contract_expires_year, contract_expires_round, retired,
                   skill_leadership, skill_design, skill_strategy,
                   skill_crew_management, skill_driver_management
              FROM personnel
        """
    }
}
