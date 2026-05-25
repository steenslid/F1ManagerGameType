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
 * /api/off-season/report — what happened during a season's off-season
 * processing.
 *
 * Query params:
 *   ?season={year}  — default: latest season that has events
 *
 * Returns the raw event log, plus aggregate counts for quick scanning.
 */
class OffSeasonRoutes(private val db: Database) {

    @Serializable
    data class ReportDto(
        val seasonYear: Int?,
        val counts: CountsDto,
        val events: List<EventDto>,
    )

    @Serializable
    data class CountsDto(
        val financeSettled: Int,
        val ageTicks: Int,
        val statDrifts: Int,
        val retirements: Int,
    )

    @Serializable
    data class EventDto(
        val id: Long,
        val eventType: String,
        val subjectKind: String,
        val subjectId: String,
        val subjectName: String,
        val message: String,
    )

    fun register(app: Javalin) {
        app.get("/api/off-season/report", ::report)
    }

    private fun report(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()
        val season = ctx.queryParam("season")?.toIntOrNull()

        val result = db.withConnection { conn ->
            val targetSeason = season ?: resolveLatestSeason(conn)
            if (targetSeason == null) {
                ReportDto(
                    seasonYear = null,
                    counts = CountsDto(0, 0, 0, 0),
                    events = emptyList(),
                )
            } else {
                val events = readEvents(conn, targetSeason)
                val counts = CountsDto(
                    financeSettled = events.count { it.eventType == "FINANCE_SETTLED" },
                    ageTicks = events.count { it.eventType == "AGE_TICK" },
                    statDrifts = events.count { it.eventType == "STAT_DRIFT" },
                    retirements = events.count { it.eventType == "RETIREMENT" },
                )
                ReportDto(
                    seasonYear = targetSeason,
                    counts = counts,
                    events = events,
                )
            }
        }
        Envelope.encode(result, ReportDto.serializer())
    }

    private fun resolveLatestSeason(conn: java.sql.Connection): Int? {
        conn.prepareStatement("SELECT MAX(season_year) FROM off_season_events").use { stmt ->
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                val v = rs.getInt(1)
                return if (rs.wasNull()) null else v
            }
        }
    }

    private fun readEvents(conn: java.sql.Connection, season: Int): List<EventDto> {
        return conn.prepareStatement(
            """
            SELECT id, event_type, subject_kind, subject_id, subject_name, message
              FROM off_season_events
             WHERE season_year = ?
             ORDER BY id ASC
            """.trimIndent()
        ).use { stmt ->
            stmt.setInt(1, season)
            stmt.executeQuery().use { rs ->
                buildList { while (rs.next()) add(mapRow(rs)) }
            }
        }
    }

    private fun mapRow(rs: ResultSet): EventDto = EventDto(
        id = rs.getLong("id"),
        eventType = rs.getString("event_type"),
        subjectKind = rs.getString("subject_kind"),
        subjectId = rs.getString("subject_id"),
        subjectName = rs.getString("subject_name"),
        message = rs.getString("message"),
    )
}
