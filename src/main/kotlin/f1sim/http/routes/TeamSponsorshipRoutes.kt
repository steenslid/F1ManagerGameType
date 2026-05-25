package f1sim.http.routes

import f1sim.db.Database
import f1sim.http.Envelope
import f1sim.http.timed
import f1sim.save.SaveSession
import io.javalin.Javalin
import io.javalin.http.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.sql.ResultSet

/**
 * /api/team-sponsorships — list sponsorship contracts.
 *
 * Query params:
 *   ?team={id}        — only deals for this team
 *   ?sponsor={id}     — only deals with this sponsor
 *   ?active_in={year} — only deals active in the given year (inclusive)
 *
 * Joins team and sponsor for the display name. Sorted by team, then by
 * is_title desc (title first), then annual_value desc.
 */
class TeamSponsorshipRoutes(private val db: Database) {

    @Serializable
    data class TeamSponsorshipDto(
        val id: Long,
        val teamId: String,
        val teamName: String,
        val sponsorId: String,
        val sponsorName: String,
        val sponsorTier: String,
        val startYear: Int,
        val endYear: Int,
        val annualValue: Long,
        val isTitle: Boolean,
    )

    fun register(app: Javalin) {
        app.get("/api/team-sponsorships", ::list)
    }

    private fun list(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()

        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        ctx.queryParam("team")?.let {
            conditions += "ts.team_id = ?"
            params += it
        }
        ctx.queryParam("sponsor")?.let {
            conditions += "ts.sponsor_id = ?"
            params += it
        }
        ctx.queryParam("active_in")?.toIntOrNull()?.let {
            conditions += "ts.start_year <= ? AND ts.end_year >= ?"
            params += it
            params += it
        }

        val sql = buildString {
            append(SELECT_COLUMNS)
            if (conditions.isNotEmpty()) {
                append(" WHERE ")
                append(conditions.joinToString(" AND "))
            }
            append(" ORDER BY t.name ASC, ts.is_title DESC, ts.annual_value DESC")
        }

        val deals = db.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { i, p -> stmt.setObject(i + 1, p) }
                stmt.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(mapRow(rs)) }
                }
            }
        }
        Envelope.encode(deals, ListSerializer(TeamSponsorshipDto.serializer()))
    }

    private fun mapRow(rs: ResultSet): TeamSponsorshipDto = TeamSponsorshipDto(
        id = rs.getLong("id"),
        teamId = rs.getString("team_id"),
        teamName = rs.getString("team_name"),
        sponsorId = rs.getString("sponsor_id"),
        sponsorName = rs.getString("sponsor_name"),
        sponsorTier = rs.getString("sponsor_tier"),
        startYear = rs.getInt("start_year"),
        endYear = rs.getInt("end_year"),
        annualValue = rs.getLong("annual_value"),
        isTitle = rs.getBoolean("is_title"),
    )

    private companion object {
        const val SELECT_COLUMNS = """
            SELECT ts.id, ts.team_id, t.name AS team_name,
                   ts.sponsor_id, s.name AS sponsor_name, s.tier AS sponsor_tier,
                   ts.start_year, ts.end_year, ts.annual_value, ts.is_title
              FROM team_sponsorships ts
              JOIN teams t   ON t.id = ts.team_id
              JOIN sponsors s ON s.id = ts.sponsor_id
        """
    }
}
