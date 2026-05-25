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
 * Off-season / year-flip pipeline. Per the design doc this has 11 ordered
 * steps; v1 implements:
 *
 *   END_OF_SEASON hooks:
 *     1. Finance settle — apply year's income/expenses to cash_reserves,
 *        zero the YTD counters.
 *
 *   OFF_SEASON hooks (the year just ended):
 *     2. Aging tick — drivers and personnel +1 year, pace drift past peak.
 *     3. Retirement rolls.
 *
 *   PRE_SEASON hooks (the new year about to start):
 *     4. Sponsor revenue tick — sum active sponsorships, set current_year_income.
 *     5. Operating cost tick — base_operating_cost + driver salaries +
 *        personnel salaries, set current_year_expenses.
 *
 * Remaining steps stubbed: contract expirations, markets (driver/personnel/
 * sponsor), junior promotions, regulation reset, board review, calendar
 * generation.
 *
 * All hooks write to `off_season_events` for the report. The whole pipeline
 * runs inside the GameService advance transaction.
 */
class OffSeasonService(private val db: Database) {

    private val log = LoggerFactory.getLogger(OffSeasonService::class.java)

    fun runEndOfSeasonHooks(conn: Connection, seasonYear: Int): Int {
        if (!hasSimulatedRaces(conn, seasonYear)) {
            log.info("Skipping END_OF_SEASON hooks for {} — no races simulated", seasonYear)
            return 0
        }

        val financeEvents = settleFinances(conn, seasonYear)
        log.info("END_OF_SEASON {}: finance settled, {} events", seasonYear, financeEvents)
        return financeEvents
    }

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

    /**
     * Called when transitioning INTO PRE_SEASON. The new year is starting;
     * fill in income (sponsor revenue) and expenses (base + salaries).
     */
    fun runPreSeasonHooks(conn: Connection, newSeasonYear: Int): Int {
        val revenueEvents = applySponsorRevenueTick(conn, newSeasonYear)
        val costEvents = applyOperatingCostsTick(conn, newSeasonYear)
        val total = revenueEvents + costEvents
        log.info(
            "PRE_SEASON {}: {} sponsor revenue events, {} operating cost events",
            newSeasonYear, revenueEvents, costEvents,
        )
        return total
    }

    // ------------------------------------------------------------------
    // Step 1: Finance settle
    // ------------------------------------------------------------------

    private fun settleFinances(conn: Connection, seasonYear: Int): Int {
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

        logEvents(
            conn, seasonYear,
            updates.map { u ->
                val delta = u.income - u.expenses
                val sign = if (delta >= 0) "+" else ""
                val msg = "${u.teamName}: $sign$$delta (income $${u.income}, expenses $${u.expenses}) " +
                    "→ cash $${u.newCash}"
                EventToLog("FINANCE_SETTLED", "TEAM", u.teamId, u.teamName, msg)
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

    private fun personnelRetirementThresholdAt(age: Int): Double {
        if (age < 60) return 0.0
        if (age >= 75) return 1.0
        val t = (age - 60).toDouble() / (75 - 60)
        return min(1.0, 0.05 + t * 0.95)
    }

    // ------------------------------------------------------------------
    // Step 4: Sponsor revenue tick (PRE_SEASON)
    // ------------------------------------------------------------------

    private fun applySponsorRevenueTick(conn: Connection, seasonYear: Int): Int {
        data class TeamRevenue(
            val teamId: String,
            val teamName: String,
            val dealCount: Int,
            val totalRevenue: Long,
            val oldIncome: Long,
            val newIncome: Long,
        )

        val rows = conn.prepareStatement(
            """
            SELECT t.id AS team_id, t.name AS team_name,
                   t.current_year_income AS old_income,
                   COUNT(ts.id) AS deal_count,
                   COALESCE(SUM(ts.annual_value), 0) AS total_revenue
              FROM teams t
              LEFT JOIN team_sponsorships ts
                ON ts.team_id = t.id
               AND ts.start_year <= ?
               AND ts.end_year >= ?
             WHERE t.series = 'F1'
             GROUP BY t.id, t.name, t.current_year_income
            """.trimIndent()
        ).use { stmt ->
            stmt.setInt(1, seasonYear)
            stmt.setInt(2, seasonYear)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val oldIncome = rs.getLong("old_income")
                        val revenue = rs.getLong("total_revenue")
                        add(
                            TeamRevenue(
                                teamId = rs.getString("team_id"),
                                teamName = rs.getString("team_name"),
                                dealCount = rs.getInt("deal_count"),
                                totalRevenue = revenue,
                                oldIncome = oldIncome,
                                newIncome = oldIncome + revenue,
                            )
                        )
                    }
                }
            }
        }

        val updates = rows.filter { it.totalRevenue > 0 }
        if (updates.isEmpty()) return 0

        conn.prepareStatement(
            "UPDATE teams SET current_year_income = ? WHERE id = ?"
        ).use { stmt ->
            updates.forEach { u ->
                stmt.setLong(1, u.newIncome)
                stmt.setString(2, u.teamId)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

        logEvents(
            conn, seasonYear,
            updates.map { u ->
                EventToLog(
                    "SPONSOR_REVENUE", "TEAM", u.teamId, u.teamName,
                    "${u.teamName}: ${u.dealCount} active deals worth $${u.totalRevenue} " +
                        "→ current_year_income $${u.newIncome}",
                )
            },
        )

        return updates.size
    }

    // ------------------------------------------------------------------
    // Step 5: Operating cost tick (PRE_SEASON)
    // ------------------------------------------------------------------

    /**
     * For each F1 team: cost = base_operating_cost + sum(driver salaries) +
     * sum(personnel salaries), where only non-retired entities currently
     * attached to the team count. Applied to current_year_expenses.
     */
    private fun applyOperatingCostsTick(conn: Connection, seasonYear: Int): Int {
        data class TeamCost(
            val teamId: String,
            val teamName: String,
            val base: Long,
            val driverSalaries: Long,
            val personnelSalaries: Long,
            val total: Long,
            val oldExpenses: Long,
            val newExpenses: Long,
        )

        val rows = conn.prepareStatement(
            """
            SELECT t.id AS team_id, t.name AS team_name,
                   t.current_year_expenses AS old_expenses,
                   t.base_operating_cost AS base_cost,
                   COALESCE(driver_sum.salaries, 0) AS driver_salaries,
                   COALESCE(personnel_sum.salaries, 0) AS personnel_salaries
              FROM teams t
              LEFT JOIN (
                  SELECT current_racing_team_id AS team_id, SUM(current_salary) AS salaries
                    FROM drivers
                   WHERE NOT retired
                     AND current_racing_team_id IS NOT NULL
                   GROUP BY current_racing_team_id
              ) driver_sum ON driver_sum.team_id = t.id
              LEFT JOIN (
                  SELECT current_team_id AS team_id, SUM(current_salary) AS salaries
                    FROM personnel
                   WHERE NOT retired
                     AND current_team_id IS NOT NULL
                   GROUP BY current_team_id
              ) personnel_sum ON personnel_sum.team_id = t.id
             WHERE t.series = 'F1'
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val oldExpenses = rs.getLong("old_expenses")
                        val base = rs.getLong("base_cost")
                        val driverSalaries = rs.getLong("driver_salaries")
                        val personnelSalaries = rs.getLong("personnel_salaries")
                        val total = base + driverSalaries + personnelSalaries
                        add(
                            TeamCost(
                                teamId = rs.getString("team_id"),
                                teamName = rs.getString("team_name"),
                                base = base,
                                driverSalaries = driverSalaries,
                                personnelSalaries = personnelSalaries,
                                total = total,
                                oldExpenses = oldExpenses,
                                newExpenses = oldExpenses + total,
                            )
                        )
                    }
                }
            }
        }

        val updates = rows.filter { it.total > 0 }
        if (updates.isEmpty()) return 0

        conn.prepareStatement(
            "UPDATE teams SET current_year_expenses = ? WHERE id = ?"
        ).use { stmt ->
            updates.forEach { u ->
                stmt.setLong(1, u.newExpenses)
                stmt.setString(2, u.teamId)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

        logEvents(
            conn, seasonYear,
            updates.map { u ->
                EventToLog(
                    "OPERATING_COST", "TEAM", u.teamId, u.teamName,
                    "${u.teamName}: base $${u.base} + drivers $${u.driverSalaries} + " +
                        "personnel $${u.personnelSalaries} = $${u.total} " +
                        "→ current_year_expenses $${u.newExpenses}",
                )
            },
        )

        return updates.size
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
