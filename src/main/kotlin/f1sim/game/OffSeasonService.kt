package f1sim.game

import f1sim.db.Database
import f1sim.save.SaveSession
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Off-season pipeline. Per the design doc this has 11 ordered steps; v1
 * implements the first three:
 *
 *   1. Finance settle (END_OF_SEASON) — apply year's income/expenses to
 *      cash_reserves, zero the YTD counters.
 *
 *   2. Aging tick (OFF_SEASON) — every non-retired driver and personnel
 *      gets +1 year. Drivers past their peak age lose stat_pace by
 *      (age - peak_age) * decline_rate, floored at 0.
 *
 *   3. Retirement rolls (OFF_SEASON) — drivers above 32 roll against
 *      trait_retirement_threshold; personnel use an age-based curve.
 *      Retired entities lose team affiliations.
 *
 * Remaining 8 steps (contract expirations, markets, junior promotions,
 * sponsor refresh, regulation reset, board review, season setup, calendar
 * generation) are stubbed.
 *
 * All hooks write to `off_season_events` for a readable history. The whole
 * pipeline runs inside the GameService advance transaction.
 */
class OffSeasonService(private val db: Database) {

    private val log = LoggerFactory.getLogger(OffSeasonService::class.java)

    /**
     * Called from GameService when transitioning INTO END_OF_SEASON.
     * Returns event count for the transition log.
     */
    fun runEndOfSeasonHooks(conn: Connection, seasonYear: Int): Int {
        if (!hasSimulatedRaces(conn, seasonYear)) {
            log.info("Skipping END_OF_SEASON hooks for {} — no races simulated", seasonYear)
            return 0
        }

        val financeEvents = settleFinances(conn, seasonYear)
        log.info("END_OF_SEASON {}: finance settled, {} events", seasonYear, financeEvents)
        return financeEvents
    }

    /**
     * Called from GameService when transitioning INTO OFF_SEASON.
     * `endingSeasonYear` is the year that just ended (i.e. before the +1
     * that the PhaseMachine does in END_OF_SEASON → OFF_SEASON).
     */
    fun runOffSeasonHooks(conn: Connection, endingSeasonYear: Int, masterSeed: Long): Int {
        if (!hasSimulatedRaces(conn, endingSeasonYear)) {
            log.info("Skipping OFF_SEASON hooks for {} — no races simulated", endingSeasonYear)
            return 0
        }

        val ageEvents = applyAgingTick(conn, endingSeasonYear)
        val retirementEvents = rollRetirements(conn, endingSeasonYear, masterSeed)
        val total = ageEvents + retirementEvents
        log.info(
            "OFF_SEASON for ending year {}: {} aging events, {} retirements",
            endingSeasonYear, ageEvents, retirementEvents,
        )
        return total
    }

    // ------------------------------------------------------------------
    // Step 1: Finance settle
    // ------------------------------------------------------------------

    private fun settleFinances(conn: Connection, seasonYear: Int): Int {
        // Read each F1 team's current ledger, log, then apply.
        val updates = conn.prepareStatement(
            """
            SELECT id, name, cash_reserves, current_year_income, current_year_expenses
              FROM teams
             WHERE series = 'F1'
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = rs.getString("id")
                        val name = rs.getString("name")
                        val cash = rs.getLong("cash_reserves")
                        val income = rs.getLong("current_year_income")
                        val expenses = rs.getLong("current_year_expenses")
                        val delta = income - expenses
                        val newCash = cash + delta
                        add(FinanceUpdate(id, name, cash, income, expenses, newCash))
                    }
                }
            }
        }

        if (updates.isEmpty()) return 0

        conn.prepareStatement(
            """
            UPDATE teams SET
              cash_reserves = ?,
              current_year_income = 0,
              current_year_expenses = 0
             WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            updates.forEach { u ->
                stmt.setLong(1, u.newCash)
                stmt.setString(2, u.teamId)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

        // Log one event per team.
        val message = { u: FinanceUpdate ->
            val delta = u.income - u.expenses
            val sign = if (delta >= 0) "+" else ""
            "${u.teamName}: $$sign${delta} (income $${u.income}, expenses $${u.expenses}) " +
                "→ cash $${u.newCash}"
        }
        logEvents(
            conn, seasonYear,
            updates.map {
                EventToLog("FINANCE_SETTLED", "TEAM", it.teamId, it.teamName, message(it))
            },
        )

        return updates.size
    }

    private data class FinanceUpdate(
        val teamId: String,
        val teamName: String,
        val cash: Long,
        val income: Long,
        val expenses: Long,
        val newCash: Long,
    )

    // ------------------------------------------------------------------
    // Step 2: Aging tick
    // ------------------------------------------------------------------

    private fun applyAgingTick(conn: Connection, seasonYear: Int): Int {
        var events = 0
        events += ageDrivers(conn, seasonYear)
        events += agePersonnel(conn, seasonYear)
        return events
    }

    private fun ageDrivers(conn: Connection, seasonYear: Int): Int {
        // Read everyone first so we have the pre-change snapshot for the log.
        data class Row(
            val id: UUID, val name: String,
            val oldAge: Int, val newAge: Int,
            val peakAge: Int, val declineRate: Double,
            val oldPace: Int, val newPace: Int,
        )

        val rows = conn.prepareStatement(
            """
            SELECT id, name, current_age, trait_peak_age, trait_decline_rate, stat_pace
              FROM drivers
             WHERE NOT retired
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = rs.getObject("id", UUID::class.java)
                        val name = rs.getString("name")
                        val oldAge = rs.getInt("current_age")
                        val newAge = oldAge + 1
                        val peakAge = rs.getInt("trait_peak_age")
                        val declineRate = rs.getDouble("trait_decline_rate")
                        val oldPace = rs.getInt("stat_pace")
                        val newPace = if (newAge > peakAge) {
                            val drop = (newAge - peakAge) * declineRate
                            max(0, (oldPace - drop).roundToInt())
                        } else {
                            oldPace
                        }
                        add(Row(id, name, oldAge, newAge, peakAge, declineRate, oldPace, newPace))
                    }
                }
            }
        }

        if (rows.isEmpty()) return 0

        conn.prepareStatement(
            "UPDATE drivers SET current_age = ?, stat_pace = ? WHERE id = ?"
        ).use { stmt ->
            rows.forEach { r ->
                stmt.setInt(1, r.newAge)
                stmt.setInt(2, r.newPace)
                stmt.setObject(3, r.id)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

        // One AGE_TICK event per driver, plus STAT_DRIFT events only for ones
        // whose pace actually moved.
        val events = mutableListOf<EventToLog>()
        rows.forEach { r ->
            events += EventToLog(
                "AGE_TICK", "DRIVER", r.id.toString(), r.name,
                "${r.name}: age ${r.oldAge} → ${r.newAge}",
            )
            if (r.newPace != r.oldPace) {
                events += EventToLog(
                    "STAT_DRIFT", "DRIVER", r.id.toString(), r.name,
                    "${r.name}: stat_pace ${r.oldPace} → ${r.newPace} (past peak ${r.peakAge})",
                )
            }
        }
        logEvents(conn, seasonYear, events)
        return events.size
    }

    private fun agePersonnel(conn: Connection, seasonYear: Int): Int {
        data class Row(
            val id: UUID, val name: String,
            val oldAge: Int, val newAge: Int,
            val peakAge: Int, val declineRate: Double,
            val oldDesign: Int, val newDesign: Int,
        )

        val rows = conn.prepareStatement(
            """
            SELECT id, name, age, trait_peak_age, trait_decline_rate, skill_design
              FROM personnel
             WHERE NOT retired
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = rs.getObject("id", UUID::class.java)
                        val name = rs.getString("name")
                        val oldAge = rs.getInt("age")
                        val newAge = oldAge + 1
                        val peakAge = rs.getInt("trait_peak_age")
                        val declineRate = rs.getDouble("trait_decline_rate")
                        val oldDesign = rs.getInt("skill_design")
                        val newDesign = if (newAge > peakAge) {
                            val drop = (newAge - peakAge) * declineRate
                            max(0, (oldDesign - drop).roundToInt())
                        } else {
                            oldDesign
                        }
                        add(Row(id, name, oldAge, newAge, peakAge, declineRate, oldDesign, newDesign))
                    }
                }
            }
        }

        if (rows.isEmpty()) return 0

        conn.prepareStatement(
            "UPDATE personnel SET age = ?, skill_design = ? WHERE id = ?"
        ).use { stmt ->
            rows.forEach { r ->
                stmt.setInt(1, r.newAge)
                stmt.setInt(2, r.newDesign)
                stmt.setObject(3, r.id)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

        val events = mutableListOf<EventToLog>()
        rows.forEach { r ->
            events += EventToLog(
                "AGE_TICK", "PERSONNEL", r.id.toString(), r.name,
                "${r.name}: age ${r.oldAge} → ${r.newAge}",
            )
            if (r.newDesign != r.oldDesign) {
                events += EventToLog(
                    "STAT_DRIFT", "PERSONNEL", r.id.toString(), r.name,
                    "${r.name}: skill_design ${r.oldDesign} → ${r.newDesign} (past peak ${r.peakAge})",
                )
            }
        }
        logEvents(conn, seasonYear, events)
        return events.size
    }

    // ------------------------------------------------------------------
    // Step 3: Retirement rolls
    // ------------------------------------------------------------------

    private fun rollRetirements(
        conn: Connection,
        seasonYear: Int,
        masterSeed: Long,
    ): Int {
        var count = 0
        count += rollDriverRetirements(conn, seasonYear, masterSeed)
        count += rollPersonnelRetirements(conn, seasonYear, masterSeed)
        return count
    }

    private fun rollDriverRetirements(
        conn: Connection,
        seasonYear: Int,
        masterSeed: Long,
    ): Int {
        data class Candidate(
            val id: UUID, val name: String, val age: Int, val threshold: Double,
            val previousTeamId: String?,
        )

        val candidates = conn.prepareStatement(
            """
            SELECT id, name, current_age, trait_retirement_threshold, current_racing_team_id
              FROM drivers
             WHERE NOT retired
               AND current_age > 32
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Candidate(
                                id = rs.getObject("id", UUID::class.java),
                                name = rs.getString("name"),
                                age = rs.getInt("current_age"),
                                threshold = rs.getDouble("trait_retirement_threshold"),
                                previousTeamId = rs.getString("current_racing_team_id"),
                            )
                        )
                    }
                }
            }
        }

        val retirees = candidates.filter { c ->
            val rng = Random(masterSeed xor RETIRE_SALT xor c.id.hashCode().toLong())
            rng.nextDouble() < c.threshold
        }

        if (retirees.isEmpty()) return 0

        conn.prepareStatement(
            """
            UPDATE drivers SET
              retired = TRUE,
              current_racing_team_id = NULL,
              reserve_for_team_id = NULL,
              academy_team_id = NULL
             WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            retirees.forEach { r ->
                stmt.setObject(1, r.id)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

        logEvents(
            conn, seasonYear,
            retirees.map { r ->
                val from = r.previousTeamId ?: "(no team)"
                EventToLog(
                    "RETIREMENT", "DRIVER", r.id.toString(), r.name,
                    "${r.name} retired at age ${r.age} (was at $from)",
                )
            },
        )
        return retirees.size
    }

    private fun rollPersonnelRetirements(
        conn: Connection,
        seasonYear: Int,
        masterSeed: Long,
    ): Int {
        data class Candidate(
            val id: UUID, val name: String, val age: Int,
            val previousTeamId: String?,
        )

        val candidates = conn.prepareStatement(
            """
            SELECT id, name, age, current_team_id
              FROM personnel
             WHERE NOT retired
               AND age > 55
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Candidate(
                                id = rs.getObject("id", UUID::class.java),
                                name = rs.getString("name"),
                                age = rs.getInt("age"),
                                previousTeamId = rs.getString("current_team_id"),
                            )
                        )
                    }
                }
            }
        }

        val retirees = candidates.filter { c ->
            val rng = Random(masterSeed xor RETIRE_SALT xor c.id.hashCode().toLong())
            val threshold = personnelRetirementThresholdAt(c.age)
            rng.nextDouble() < threshold
        }

        if (retirees.isEmpty()) return 0

        conn.prepareStatement(
            """
            UPDATE personnel SET
              retired = TRUE,
              current_team_id = NULL,
              role = NULL
             WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            retirees.forEach { r ->
                stmt.setObject(1, r.id)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

        logEvents(
            conn, seasonYear,
            retirees.map { r ->
                val from = r.previousTeamId ?: "(no team)"
                EventToLog(
                    "RETIREMENT", "PERSONNEL", r.id.toString(), r.name,
                    "${r.name} retired at age ${r.age} (was at $from)",
                )
            },
        )
        return retirees.size
    }

    /**
     * Personnel retirement curve: 5% at 60, ramping to 100% at 75.
     * Below 60: 0%. Linear interpolation in between.
     */
    private fun personnelRetirementThresholdAt(age: Int): Double {
        if (age < 60) return 0.0
        if (age >= 75) return 1.0
        // 60 -> 0.05, 75 -> 1.0
        val t = (age - 60).toDouble() / (75 - 60)
        return min(1.0, 0.05 + t * 0.95)
    }

    // ------------------------------------------------------------------
    // Event logging helpers
    // ------------------------------------------------------------------

    private data class EventToLog(
        val type: String,
        val subjectKind: String,
        val subjectId: String,
        val subjectName: String,
        val message: String,
    )

    private fun logEvents(conn: Connection, seasonYear: Int, events: List<EventToLog>) {
        if (events.isEmpty()) return
        conn.prepareStatement(
            """
            INSERT INTO off_season_events
              (season_year, event_type, subject_kind, subject_id, subject_name, message)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { stmt ->
            events.forEach { e ->
                stmt.setInt(1, seasonYear)
                stmt.setString(2, e.type)
                stmt.setString(3, e.subjectKind)
                stmt.setString(4, e.subjectId)
                stmt.setString(5, e.subjectName)
                stmt.setString(6, e.message)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    private fun hasSimulatedRaces(conn: Connection, seasonYear: Int): Boolean {
        conn.prepareStatement(
            """
            SELECT 1
              FROM race_results rr
              JOIN races r ON r.id = rr.race_id
             WHERE r.season_year = ?
               AND rr.status = 'FINISHED'
             LIMIT 1
            """.trimIndent()
        ).use { stmt ->
            stmt.setInt(1, seasonYear)
            stmt.executeQuery().use { rs ->
                return rs.next()
            }
        }
    }

    private companion object {
        const val RETIRE_SALT = 0x4E71_4E72_4E73_4E74L
    }
}
