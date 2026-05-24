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
 * /api/sponsors — list with filters, plus get-by-id.
 *
 * Filters:
 *   ?tier={TIER}        — TITLE | PRIMARY | SECONDARY | MINOR
 *   ?industry={text}    — exact match
 */
class SponsorRoutes(private val db: Database) {

    @Serializable
    data class SponsorDto(
        val id: String,
        val name: String,
        val country: String,
        val tier: String,
        val industry: String,
        val prestige: Int,
        val preferences: SponsorPreferencesDto,
        val budget: SponsorBudgetDto,
    )

    @Serializable
    data class SponsorPreferencesDto(
        val performanceSensitivity: Double,
        val riskTolerance: Double,
        val prestigePreference: Double,
    )

    @Serializable
    data class SponsorBudgetDto(
        val min: Long,
        val max: Long,
    )

    fun register(app: Javalin) {
        app.get("/api/sponsors", ::list)
        app.get("/api/sponsors/{id}", ::getOne)
    }

    private fun list(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()

        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        ctx.queryParam("tier")?.let {
            conditions += "tier = ?"
            params += it
        }
        ctx.queryParam("industry")?.let {
            conditions += "industry = ?"
            params += it
        }

        // Tier is ordered TITLE > PRIMARY > SECONDARY > MINOR by budget, not alphabetically.
        val sql = buildString {
            append(SELECT_COLUMNS)
            if (conditions.isNotEmpty()) {
                append(" WHERE ")
                append(conditions.joinToString(" AND "))
            }
            append(" ORDER BY budget_max DESC, name ASC")
        }

        val sponsors = db.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { i, p -> stmt.setObject(i + 1, p) }
                stmt.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(mapRow(rs)) }
                }
            }
        }
        Envelope.encode(sponsors, ListSerializer(SponsorDto.serializer()))
    }

    private fun getOne(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()
        val id = ctx.pathParam("id")
        val sponsor = db.withConnection { conn ->
            conn.prepareStatement("$SELECT_COLUMNS WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) throw NotFoundException("No sponsor with id $id")
                    mapRow(rs)
                }
            }
        }
        Envelope.encode(sponsor, SponsorDto.serializer())
    }

    private fun mapRow(rs: ResultSet): SponsorDto = SponsorDto(
        id = rs.getString("id"),
        name = rs.getString("name"),
        country = rs.getString("country"),
        tier = rs.getString("tier"),
        industry = rs.getString("industry"),
        prestige = rs.getInt("prestige"),
        preferences = SponsorPreferencesDto(
            performanceSensitivity = rs.getDouble("performance_sensitivity"),
            riskTolerance = rs.getDouble("risk_tolerance"),
            prestigePreference = rs.getDouble("prestige_preference"),
        ),
        budget = SponsorBudgetDto(
            min = rs.getLong("budget_min"),
            max = rs.getLong("budget_max"),
        ),
    )

    private companion object {
        const val SELECT_COLUMNS = """
            SELECT id, name, country, tier, industry, prestige,
                   performance_sensitivity, risk_tolerance, prestige_preference,
                   budget_min, budget_max
              FROM sponsors
        """
    }
}
