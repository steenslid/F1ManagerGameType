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
 *     4. Contract expirations — non-retired drivers/personnel whose
 *        contract_expires_year <= ending year are released to free-agent
 *        state (team affiliations nulled, contract dates kept as history).
 *
 *   DRIVER_MARKET phase (not handled here; see DriverMarketService):
 *     5. Driver market — multi-round matching between free agents and
 *        open seats, including player offers. Runs in its own phase
 *        between OFF_SEASON and PRE_SEASON.
 *
 *   PRE_SEASON hooks (the new year about to start):
 *     4b. Sponsor renewals — extend lapsed deals at ~same value so teams
 *         keep their income stream going. Stub for a real sponsor market.
 *     6. Sponsor revenue tick — sum active sponsorships, set current_year_income.
 *     7. Operating cost tick — base_operating_cost + academy_investment +
 *        driver salaries + personnel salaries, set current_year_expenses.
 *
 * Remaining steps stubbed: personnel market, sponsor market, junior
 * promotions, regulation reset, board review, calendar generation.
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
        // Contract expirations run after retirements so retirees aren't listed twice.
        // Retirement nulls team affiliations, so an expired-contract retiree fails
        // the "has a team" predicate below and is skipped naturally.
        val expirationEvents = rollContractExpirations(conn, endingSeasonYear)
        // Driver market is no longer run here — it has its own phase
        // (DRIVER_MARKET) between OFF_SEASON and PRE_SEASON. See
        // DriverMarketService.
        val total = ageEvents + retirementEvents + expirationEvents
        log.info(
            "OFF_SEASON for ending year {}: {} aging, {} retirements, {} contract expirations",
            endingSeasonYear, ageEvents, retirementEvents, expirationEvents,
        )
        return total
    }

    /**
     * Called when transitioning INTO PRE_SEASON. The new year is starting;
     * fill in income (sponsor revenue) and expenses (base + salaries).
     */
    fun runPreSeasonHooks(conn: Connection, newSeasonYear: Int): Int {
        val renewalEvents = renewSponsors(conn, newSeasonYear)
        val revenueEvents = applySponsorRevenueTick(conn, newSeasonYear)
        val costEvents = applyOperatingCostsTick(conn, newSeasonYear)
        val total = renewalEvents + revenueEvents + costEvents
        log.info(
            "PRE_SEASON {}: {} sponsor renewals, {} sponsor revenue events, {} operating cost events",
            newSeasonYear, renewalEvents, revenueEvents, costEvents,
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
              academy_team_id = NULL,
              previous_team_id = NULL
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
    // Step 4: Contract expirations
    // ------------------------------------------------------------------

    /**
     * Contracts whose `contract_expires_year` is <= the ending season year
     * have lapsed. Affected drivers / personnel are released to free-agent
     * state by nulling their team affiliations. They aren't retired —
     * retirement runs first and would null both `retired` and the
     * affiliations; survivors of that filter are what we process here.
     *
     * `contract_expires_year` / `contract_expires_round` are deliberately
     * left in place. They serve as a record of when the contract ended.
     * The driver/personnel market (next chunk) will overwrite them on a
     * new signing.
     *
     * No RNG — fully deterministic from the contract year.
     */
    private fun rollContractExpirations(conn: Connection, endingSeasonYear: Int): Int {
        var count = 0
        count += expireDriverContracts(conn, endingSeasonYear)
        count += expirePersonnelContracts(conn, endingSeasonYear)
        return count
    }

    private fun expireDriverContracts(conn: Connection, endingSeasonYear: Int): Int {
        data class Expiry(
            val id: UUID, val name: String,
            val previousTeamId: String?,
            val contractYear: Int,
        )

        // One-cycle loyalty reset: any driver who entered THIS off-season
        // already unsigned (no racing team) had their chance in the prior
        // year's market and either wasn't picked or chose not to sign. The
        // `previous_team_id` they carry is stale — clear it so the upcoming
        // market doesn't keep nudging them toward a team they've effectively
        // moved on from. This runs before the new expiries set fresh
        // previous_team_id values, so newly-released drivers are unaffected.
        val staleCleared = conn.prepareStatement(
            """
            UPDATE drivers SET previous_team_id = NULL
             WHERE NOT retired
               AND current_racing_team_id IS NULL
               AND previous_team_id IS NOT NULL
            """.trimIndent()
        ).use { it.executeUpdate() }
        if (staleCleared > 0) {
            log.info("Cleared stale previous_team_id for {} drivers", staleCleared)
        }

        // Only drivers with some team affiliation at all — pure free agents
        // (no racing / reserve / academy team) have nothing to release.
        val expiries = conn.prepareStatement(
            """
            SELECT id, name, current_racing_team_id, contract_expires_year
              FROM drivers
             WHERE NOT retired
               AND contract_expires_year IS NOT NULL
               AND contract_expires_year <= ?
               AND (
                 current_racing_team_id IS NOT NULL
                 OR reserve_for_team_id IS NOT NULL
                 OR academy_team_id IS NOT NULL
               )
            """.trimIndent()
        ).use { stmt ->
            stmt.setInt(1, endingSeasonYear)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Expiry(
                                id = rs.getObject("id", UUID::class.java),
                                name = rs.getString("name"),
                                previousTeamId = rs.getString("current_racing_team_id"),
                                contractYear = rs.getInt("contract_expires_year"),
                            )
                        )
                    }
                }
            }
        }

        if (expiries.isEmpty()) return 0

        conn.prepareStatement(
            """
            UPDATE drivers SET
              previous_team_id = COALESCE(current_racing_team_id, previous_team_id),
              current_racing_team_id = NULL,
              reserve_for_team_id = NULL,
              academy_team_id = NULL
             WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            expiries.forEach { e ->
                stmt.setObject(1, e.id)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

        logEvents(
            conn, endingSeasonYear,
            expiries.map { e ->
                val from = e.previousTeamId ?: "(no racing team)"
                EventToLog(
                    "CONTRACT_EXPIRED", "DRIVER", e.id.toString(), e.name,
                    "${e.name}: contract expired (${e.contractYear}); released from $from",
                )
            },
        )
        return expiries.size
    }

    private fun expirePersonnelContracts(conn: Connection, endingSeasonYear: Int): Int {
        data class Expiry(
            val id: UUID, val name: String,
            val previousTeamId: String?,
            val previousRole: String?,
            val contractYear: Int,
        )

        val expiries = conn.prepareStatement(
            """
            SELECT id, name, current_team_id, role, contract_expires_year
              FROM personnel
             WHERE NOT retired
               AND contract_expires_year IS NOT NULL
               AND contract_expires_year <= ?
               AND current_team_id IS NOT NULL
            """.trimIndent()
        ).use { stmt ->
            stmt.setInt(1, endingSeasonYear)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Expiry(
                                id = rs.getObject("id", UUID::class.java),
                                name = rs.getString("name"),
                                previousTeamId = rs.getString("current_team_id"),
                                previousRole = rs.getString("role"),
                                contractYear = rs.getInt("contract_expires_year"),
                            )
                        )
                    }
                }
            }
        }

        if (expiries.isEmpty()) return 0

        conn.prepareStatement(
            """
            UPDATE personnel SET
              current_team_id = NULL,
              role = NULL
             WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            expiries.forEach { e ->
                stmt.setObject(1, e.id)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

        logEvents(
            conn, endingSeasonYear,
            expiries.map { e ->
                val from = e.previousTeamId ?: "(no team)"
                val roleNote = if (e.previousRole != null) " as ${e.previousRole}" else ""
                EventToLog(
                    "CONTRACT_EXPIRED", "PERSONNEL", e.id.toString(), e.name,
                    "${e.name}: contract expired (${e.contractYear}); released from $from$roleNote",
                )
            },
        )
        return expiries.size
    }

    // ------------------------------------------------------------------
    // Step 4b: Sponsor renewals (PRE_SEASON — runs before revenue tick)
    // ------------------------------------------------------------------

    /**
     * Renewal stub: any deal whose `end_year` is before the new season year
     * (so it's just expired) gets auto-renewed for [SPONSOR_RENEWAL_TERM_YEARS]
     * years at the same annual value, plus a small ±10% noise. Real sponsor
     * markets (re-evaluation, defection on poor performance, new entrants)
     * are a follow-up; this just keeps the income lights on.
     *
     * Determinism is keyed on the deal id so renewals across saves/replays
     * are stable.
     */
    private fun renewSponsors(conn: Connection, newSeasonYear: Int): Int {
        data class Lapsed(
            val id: Long,
            val teamId: String,
            val teamName: String,
            val sponsorName: String,
            val oldEndYear: Int,
            val oldValue: Long,
        )

        val lapsed = conn.prepareStatement(
            """
            SELECT ts.id, ts.team_id, t.name AS team_name,
                   s.name AS sponsor_name,
                   ts.end_year, ts.annual_value
              FROM team_sponsorships ts
              JOIN teams t   ON t.id = ts.team_id
              JOIN sponsors s ON s.id = ts.sponsor_id
             WHERE ts.end_year < ?
               AND ts.end_year >= ?
            """.trimIndent()
        ).use { stmt ->
            // The second filter constrains us to "just-expired" deals (ended
            // last season) — older lapsed deals stay lapsed. Keeps the
            // renewal step idempotent across replays even though it's not
            // RNG-deterministic per-deal beyond the noise seed.
            stmt.setInt(1, newSeasonYear)
            stmt.setInt(2, newSeasonYear - 1)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Lapsed(
                                id = rs.getLong("id"),
                                teamId = rs.getString("team_id"),
                                teamName = rs.getString("team_name"),
                                sponsorName = rs.getString("sponsor_name"),
                                oldEndYear = rs.getInt("end_year"),
                                oldValue = rs.getLong("annual_value"),
                            )
                        )
                    }
                }
            }
        }

        if (lapsed.isEmpty()) return 0

        // Per-deal RNG so renewals are stable across replays: same deal id +
        // same renewal year = same noise. Reuses the deterministic-seeding
        // pattern from retirement rolls.
        val renewals = lapsed.map { d ->
            val rng = kotlin.random.Random(
                SPONSOR_RENEW_SALT xor d.id xor newSeasonYear.toLong()
            )
            val noise = 0.9 + rng.nextDouble() * 0.2  // [0.9, 1.1)
            val newValue = (d.oldValue * noise).toLong().coerceAtLeast(MIN_RENEWAL_VALUE)
            val newEnd = newSeasonYear + SPONSOR_RENEWAL_TERM_YEARS - 1
            Triple(d, newValue, newEnd)
        }

        conn.prepareStatement(
            """
            UPDATE team_sponsorships SET
              start_year = ?,
              end_year = ?,
              annual_value = ?
             WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            renewals.forEach { (d, newValue, newEnd) ->
                stmt.setInt(1, newSeasonYear)
                stmt.setInt(2, newEnd)
                stmt.setLong(3, newValue)
                stmt.setLong(4, d.id)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

        // Log under SPONSOR_REVENUE — it's the same conceptual bucket and
        // avoids another CHECK update. The message disambiguates.
        logEvents(
            conn, newSeasonYear,
            renewals.map { (d, newValue, newEnd) ->
                EventToLog(
                    "SPONSOR_REVENUE", "TEAM", d.teamId, d.teamName,
                    "${d.teamName}: ${d.sponsorName} renewed " +
                        "($${d.oldValue} → $${newValue}/yr) through $newEnd",
                )
            },
        )

        return renewals.size
    }

    // ------------------------------------------------------------------
    // Step 5: Sponsor revenue tick (PRE_SEASON)
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
    // Step 6: Operating cost tick (PRE_SEASON)
    // ------------------------------------------------------------------

    /**
     * For each F1 team: cost = base_operating_cost + academy_investment +
     * sum(driver salaries) + sum(personnel salaries), where only non-retired
     * entities currently attached to the team count. Applied to
     * current_year_expenses.
     */
    private fun applyOperatingCostsTick(conn: Connection, seasonYear: Int): Int {
        data class TeamCost(
            val teamId: String,
            val teamName: String,
            val base: Long,
            val academy: Long,
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
                   t.academy_investment AS academy_cost,
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
                        val academy = rs.getLong("academy_cost")
                        val driverSalaries = rs.getLong("driver_salaries")
                        val personnelSalaries = rs.getLong("personnel_salaries")
                        val total = base + academy + driverSalaries + personnelSalaries
                        add(
                            TeamCost(
                                teamId = rs.getString("team_id"),
                                teamName = rs.getString("team_name"),
                                base = base,
                                academy = academy,
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
                    "${u.teamName}: base $${u.base} + academy $${u.academy} + " +
                        "drivers $${u.driverSalaries} + " +
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
        const val SPONSOR_RENEW_SALT = 0x53504F4E_524E5731L  // "SPON_RNW1"

        const val SPONSOR_RENEWAL_TERM_YEARS = 2
        const val MIN_RENEWAL_VALUE = 500_000L
    }
}
