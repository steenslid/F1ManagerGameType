package f1sim.http.routes

import f1sim.db.Database
import f1sim.http.Envelope
import f1sim.http.timed
import f1sim.save.SaveSession
import io.javalin.Javalin
import io.javalin.http.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID
import kotlin.math.roundToInt

/**
 * /api/ladder/f2 — the F2 feeder grid, ranked.
 *
 * There's no round-by-round F2 sim yet, so the "table" is a projected order:
 * each junior's composite rating (the same weighted race-craft blend the
 * off-season uses to settle the F2 title). The top entry is the driver most
 * likely to be promoted to F1 free agency at season's end.
 */
class LadderRoutes(private val db: Database) {

    @Serializable
    data class F2StandingDto(
        val position: Int,
        val driverId: String,
        val name: String,
        val nationality: String,
        val age: Int,
        val teamId: String?,
        val teamName: String?,
        val statPace: Int,
        val statQualifying: Int,
        val statConsistency: Int,
        val rating: Int,
    )

    fun register(app: Javalin) {
        app.get("/api/ladder/f2", ::f2)
    }

    private fun f2(ctx: Context) = ctx.timed {
        SaveSession.requireLoaded()
        val rows = db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT d.id, d.name, d.nationality, d.current_age,
                       d.current_racing_team_id, t.name AS team_name,
                       d.stat_pace, d.stat_qualifying, d.stat_consistency
                  FROM drivers d
                  JOIN teams t ON t.id = d.current_racing_team_id
                 WHERE t.series = 'F2'
                   AND NOT d.retired
                """.trimIndent()
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val pace = rs.getInt("stat_pace")
                            val quali = rs.getInt("stat_qualifying")
                            val consistency = rs.getInt("stat_consistency")
                            val rating = (pace * 0.45 + quali * 0.30 + consistency * 0.25).roundToInt()
                            add(
                                Tmp(
                                    driverId = rs.getObject("id", UUID::class.java).toString(),
                                    name = rs.getString("name"),
                                    nationality = rs.getString("nationality"),
                                    age = rs.getInt("current_age"),
                                    teamId = rs.getString("current_racing_team_id"),
                                    teamName = rs.getString("team_name"),
                                    statPace = pace,
                                    statQualifying = quali,
                                    statConsistency = consistency,
                                    rating = rating,
                                )
                            )
                        }
                    }
                }
            }
        }

        val standings = rows
            .sortedWith(compareByDescending<Tmp> { it.rating }.thenBy { it.name })
            .mapIndexed { i, r ->
                F2StandingDto(
                    position = i + 1,
                    driverId = r.driverId,
                    name = r.name,
                    nationality = r.nationality,
                    age = r.age,
                    teamId = r.teamId,
                    teamName = r.teamName,
                    statPace = r.statPace,
                    statQualifying = r.statQualifying,
                    statConsistency = r.statConsistency,
                    rating = r.rating,
                )
            }
        Envelope.encode(standings, ListSerializer(F2StandingDto.serializer()))
    }

    private data class Tmp(
        val driverId: String,
        val name: String,
        val nationality: String,
        val age: Int,
        val teamId: String?,
        val teamName: String?,
        val statPace: Int,
        val statQualifying: Int,
        val statConsistency: Int,
        val rating: Int,
    )
}
