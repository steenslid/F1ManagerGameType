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
 * /api/teams — list with optional ?series= filter, plus get-by-id.
 *
 * Hidden fields excluded from the response:
 *   ai_aggression, ai_ambition, ai_loyalty, ai_frugality, ai_development_focus,
 *   board_ambition, board_realism
 */
class TeamRoutes(private val db: Database) {

    @Serializable
    data class TeamDto(
        val id: String,
        val name: String,
        val country: String,
        val baseCountry: String,
        val series: String,
        val prestige: Int,
        val isCustomTeam: Boolean,
        val finance: TeamFinanceDto,
        val regulationUnderstanding: Double,
        val pitCrewRating: Int,
        val seasonPoints: Int,
    )

    @Serializable
    data class TeamFinanceDto(
        val cashReserves: Long,
        val currentYearIncome: Long,
        val currentYearExpenses: Long,
        val heritagePayment: Long,
        val baseOperatingCost: Long,
        val academyInvestment: Long,
        val capComplianceStatus: String,
    )

    fun register(app: Javalin) {
        app.get("/api/teams", ::list)
        app.get("/api/teams/{id}", ::getOne)
    }

    private fun list(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()

        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        ctx.queryParam("series")?.let {
            conditions += "series = ?"
            params += it
        }

        val sql = buildString {
            append(SELECT_COLUMNS)
            if (conditions.isNotEmpty()) {
                append(" WHERE ")
                append(conditions.joinToString(" AND "))
            }
            append(" ORDER BY prestige DESC, name ASC")
        }

        val teams = db.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { i, p -> stmt.setObject(i + 1, p) }
                stmt.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(mapRow(rs)) }
                }
            }
        }
        Envelope.encode(teams, ListSerializer(TeamDto.serializer()))
    }

    private fun getOne(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()
        val id = ctx.pathParam("id")
        val team = db.withConnection { conn ->
            conn.prepareStatement("$SELECT_COLUMNS WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) throw NotFoundException("No team with id $id")
                    mapRow(rs)
                }
            }
        }
        Envelope.encode(team, TeamDto.serializer())
    }

    private fun mapRow(rs: ResultSet): TeamDto = TeamDto(
        id = rs.getString("id"),
        name = rs.getString("name"),
        country = rs.getString("country"),
        baseCountry = rs.getString("base_country"),
        series = rs.getString("series"),
        prestige = rs.getInt("prestige"),
        isCustomTeam = rs.getBoolean("is_custom_team"),
        finance = TeamFinanceDto(
            cashReserves = rs.getLong("cash_reserves"),
            currentYearIncome = rs.getLong("current_year_income"),
            currentYearExpenses = rs.getLong("current_year_expenses"),
            heritagePayment = rs.getLong("heritage_payment"),
            baseOperatingCost = rs.getLong("base_operating_cost"),
            academyInvestment = rs.getLong("academy_investment"),
            capComplianceStatus = rs.getString("cap_compliance_status"),
        ),
        regulationUnderstanding = rs.getDouble("regulation_understanding"),
        pitCrewRating = rs.getInt("pit_crew_rating"),
        seasonPoints = rs.getInt("season_points"),
    )

    private companion object {
        const val SELECT_COLUMNS = """
            SELECT id, name, country, base_country, series, prestige, is_custom_team,
                   cash_reserves, current_year_income, current_year_expenses,
                   heritage_payment, base_operating_cost, academy_investment,
                   cap_compliance_status, regulation_understanding, pit_crew_rating,
                   season_points
              FROM teams
        """
    }
}
