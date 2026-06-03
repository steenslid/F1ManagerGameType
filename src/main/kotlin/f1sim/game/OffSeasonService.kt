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
 *     4c. Junior promotion — the F2 champion graduates to F1 free agency so
 *        they join the upcoming driver market pool.
 *
 *   DRIVER_MARKET phase (not handled here; see DriverMarketService):
 *     5. Driver market — multi-round matching between free agents and
 *        open seats, including player offers. Runs in its own phase
 *        between OFF_SEASON and PRE_SEASON.
 *
 *   PRE_SEASON hooks (the new year about to start):
 *     4b. Sponsor renewals — extend lapsed deals at ~same value (scaled by
 *         last-season WCC rank) so teams keep their income stream going.
 *         Sponsors of below-mid-grid teams may defect (walk away, no
 *         renewal) instead. Stub for a real sponsor market.
 *     6. Sponsor revenue tick — sum active sponsorships, set current_year_income.
 *     7. Operating cost tick — base_operating_cost + academy_investment +
 *        driver salaries + personnel salaries, set current_year_expenses.
 *
 * Remaining steps stubbed: personnel market, sponsor market, regulation
 * reset, board review, calendar generation. (Junior promotion is a minimal
 * first slice — single F2 champion, no feeder-grid refill yet.)
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
        // Junior promotion runs last in the OFF_SEASON pipeline, after seats
        // have opened via retirements/expirations. The F2 champion graduates
        // to F1 free agency so they're in the pool when DRIVER_MARKET (the
        // next phase) initializes.
        val promotionEvents = promoteJuniors(conn, endingSeasonYear, masterSeed)
        // Driver market is no longer run here — it has its own phase
        // (DRIVER_MARKET) between OFF_SEASON and PRE_SEASON. See
        // DriverMarketService.
        val total = ageEvents + retirementEvents + expirationEvents + promotionEvents
        log.info(
            "OFF_SEASON for ending year {}: {} aging, {} retirements, {} contract expirations, {} junior promotions",
            endingSeasonYear, ageEvents, retirementEvents, expirationEvents, promotionEvents,
        )
        return total
    }

    /**
     * Called when transitioning INTO PRE_SEASON. The new year is starting;
     * fill in income (sponsor revenue) and expenses (base + salaries).
     */
    fun runPreSeasonHooks(conn: Connection, newSeasonYear: Int): Int {
        val calendarRaces = generateCalendar(conn, newSeasonYear)
        val renewalEvents = renewSponsors(conn, newSeasonYear)
        val developmentEvents = runCarDevelopment(conn, newSeasonYear)
        val revenueEvents = applySponsorRevenueTick(conn, newSeasonYear)
        val costEvents = applyOperatingCostsTick(conn, newSeasonYear)
        val total = renewalEvents + developmentEvents + revenueEvents + costEvents + (if (calendarRaces > 0) 1 else 0)
        log.info(
            "PRE_SEASON {}: {} calendar races, {} sponsor events, {} car development, {} sponsor revenue events, {} operating cost events",
            newSeasonYear, calendarRaces, renewalEvents, developmentEvents, revenueEvents, costEvents,
        )
        return total
    }

    // ------------------------------------------------------------------
    // Step 0: Calendar generation (PRE_SEASON — multi-season play)
    // ------------------------------------------------------------------

    /**
     * Ensure the new season has a calendar. v1 clones the most recent prior
     * season's race set — same tracks, rounds and sprint formats — into the
     * new year with fresh race ids. No-op if a calendar already exists (so the
     * seeded 2026 is untouched). Without this, seasons past the seeded one have
     * no races: the sim skips every weekend and the schedule is empty. Cloning
     * keeps multi-season play, standings and per-track planning all working.
     */
    private fun generateCalendar(conn: Connection, newSeasonYear: Int): Int {
        val exists = conn.prepareStatement(
            "SELECT 1 FROM races WHERE season_year = ? LIMIT 1"
        ).use { stmt ->
            stmt.setInt(1, newSeasonYear)
            stmt.executeQuery().use { it.next() }
        }
        if (exists) return 0

        val prevYear = conn.prepareStatement(
            "SELECT MAX(season_year) AS y FROM races WHERE season_year < ?"
        ).use { stmt ->
            stmt.setInt(1, newSeasonYear)
            stmt.executeQuery().use { rs ->
                if (rs.next()) { val y = rs.getInt("y"); if (rs.wasNull()) null else y } else null
            }
        } ?: return 0

        data class Round(val round: Int, val trackId: String, val format: String)
        val template = conn.prepareStatement(
            "SELECT round, track_id, session_format FROM races WHERE season_year = ? ORDER BY round ASC"
        ).use { stmt ->
            stmt.setInt(1, prevYear)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(Round(rs.getInt("round"), rs.getString("track_id"), rs.getString("session_format")))
                    }
                }
            }
        }
        if (template.isEmpty()) return 0

        conn.prepareStatement(
            "INSERT INTO races (id, season_year, round, track_id, session_format) VALUES (?, ?, ?, ?, ?)"
        ).use { stmt ->
            template.forEach { t ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setInt(2, newSeasonYear)
                stmt.setInt(3, t.round)
                stmt.setString(4, t.trackId)
                stmt.setString(5, t.format)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        log.info("Generated {}-round calendar for {} (cloned from {})", template.size, newSeasonYear, prevYear)
        return template.size
    }

    // ------------------------------------------------------------------
    // Step 4d: Car development (PRE_SEASON — R&D spend evolves the car)
    // ------------------------------------------------------------------

    /**
     * Each F1 team develops three car areas — aero, chassis, powertrain —
     * independently, each drifting toward the level its own R&D spend +
     * technical capability sustains. Overall `car_performance` (what the sim
     * reads) is the average of the three. Self-balancing with inertia:
     *
     *   spendFactor = rd_area / (rd_area + REF_SPEND)            (0..1, saturating)
     *   tech        = TECH_BASE + TECH_SWING * regulation_understanding
     *   target      = FLOOR_TARGET + spendFactor * SPAN * tech   (clamped MIN..MAX)
     *   new_area    = area + ADJUST_RATE * (target - area)
     *
     * Pour money into an area to climb it over a few seasons; starve it and it
     * slides toward the back as rivals develop. Deterministic (no RNG). Seeded
     * per-area budgets keep the AI grid stable; the player tunes their own via
     * the R&D endpoint, trading cash for performance area by area.
     */
    private fun runCarDevelopment(conn: Connection, newSeasonYear: Int): Int {
        data class Row(
            val teamId: String,
            val teamName: String,
            val oldCar: Int,
            val aero: Int,
            val chassis: Int,
            val powertrain: Int,
            val rdAero: Long,
            val rdChassis: Long,
            val rdPowertrain: Long,
            val regUnderstanding: Double,
        )

        val rows = conn.prepareStatement(
            """
            SELECT id, name, car_performance, car_aero, car_chassis, car_powertrain,
                   rd_aero, rd_chassis, rd_powertrain, regulation_understanding
              FROM teams
             WHERE series = 'F1'
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Row(
                                teamId = rs.getString("id"),
                                teamName = rs.getString("name"),
                                oldCar = rs.getInt("car_performance"),
                                aero = rs.getInt("car_aero"),
                                chassis = rs.getInt("car_chassis"),
                                powertrain = rs.getInt("car_powertrain"),
                                rdAero = rs.getLong("rd_aero"),
                                rdChassis = rs.getLong("rd_chassis"),
                                rdPowertrain = rs.getLong("rd_powertrain"),
                                regUnderstanding = rs.getDouble("regulation_understanding"),
                            )
                        )
                    }
                }
            }
        }
        if (rows.isEmpty()) return 0

        // Develop one area toward what its budget + tech sustains.
        fun develop(current: Int, budget: Long, tech: Double): Int {
            val spend = budget.toDouble()
            val spendFactor = if (spend <= 0.0) 0.0 else spend / (spend + CAR_DEV_REF_SPEND)
            val target = (CAR_DEV_FLOOR_TARGET + spendFactor * CAR_DEV_SPAN * tech)
                .coerceIn(CAR_DEV_MIN.toDouble(), CAR_DEV_MAX.toDouble())
            return (current + CAR_DEV_ADJUST_RATE * (target - current))
                .roundToInt()
                .coerceIn(CAR_DEV_MIN, CAR_DEV_MAX)
        }

        data class Update(
            val teamId: String, val teamName: String,
            val oldCar: Int, val newCar: Int,
            val aero: Int, val chassis: Int, val powertrain: Int,
        )

        val updates = rows.mapNotNull { r ->
            val tech = CAR_DEV_TECH_BASE + CAR_DEV_TECH_SWING * r.regUnderstanding
            val na = develop(r.aero, r.rdAero, tech)
            val nc = develop(r.chassis, r.rdChassis, tech)
            val np = develop(r.powertrain, r.rdPowertrain, tech)
            val newCar = ((na + nc + np) / 3.0).roundToInt().coerceIn(CAR_DEV_MIN, CAR_DEV_MAX)
            if (na == r.aero && nc == r.chassis && np == r.powertrain) null
            else Update(r.teamId, r.teamName, r.oldCar, newCar, na, nc, np)
        }
        if (updates.isEmpty()) return 0

        conn.prepareStatement(
            """
            UPDATE teams SET
              car_aero = ?, car_chassis = ?, car_powertrain = ?, car_performance = ?
             WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            updates.forEach { u ->
                stmt.setInt(1, u.aero)
                stmt.setInt(2, u.chassis)
                stmt.setInt(3, u.powertrain)
                stmt.setInt(4, u.newCar)
                stmt.setString(5, u.teamId)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

        logEvents(
            conn, newSeasonYear,
            updates.map { u ->
                val arrow = if (u.newCar >= u.oldCar) "↑" else "↓"
                EventToLog(
                    "CAR_DEVELOPMENT", "TEAM", u.teamId, u.teamName,
                    "${u.teamName}: car ${u.oldCar} $arrow ${u.newCar} " +
                        "(aero ${u.aero}, chassis ${u.chassis}, PU ${u.powertrain})",
                )
            },
        )
        log.info("Car development {}: {} teams changed", newSeasonYear, updates.size)
        return updates.size
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
            val oldAge: Int, val newAge: Int, val peakAge: Int,
            val oldPace: Int, val newPace: Int,
            val oldQuali: Int, val newQuali: Int,
            val newDevPool: Int,
            val growing: Boolean,
        )

        val rows = conn.prepareStatement(
            """
            SELECT id, name, current_age, trait_peak_age, trait_decline_rate,
                   stat_pace, stat_qualifying, development_pool
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
                        val oldQuali = rs.getInt("stat_qualifying")
                        val devPool = rs.getInt("development_pool")

                        val pastPeak = newAge > peakAge
                        // A driver still at/under peak with a development budget
                        // improves; how much depends on how much potential is
                        // left. Burns the pool, so growth tapers off over time.
                        val growth = if (!pastPeak && devPool > 0) {
                            when {
                                devPool >= 150 -> 2
                                devPool >= 50 -> 1
                                else -> 0
                            }
                        } else 0

                        val newPace = when {
                            pastPeak -> max(0, (oldPace - (newAge - peakAge) * declineRate).roundToInt())
                            growth > 0 -> minOf(oldPace + growth, maxOf(oldPace, DRIVER_GROWTH_CAP))
                            else -> oldPace
                        }
                        val newQuali = if (growth > 0) {
                            minOf(oldQuali + growth, maxOf(oldQuali, DRIVER_GROWTH_CAP))
                        } else oldQuali
                        val newDevPool = if (growth > 0) max(0, devPool - DRIVER_GROWTH_POOL_BURN) else devPool

                        add(Row(id, name, oldAge, newAge, peakAge, oldPace, newPace, oldQuali, newQuali, newDevPool, growth > 0))
                    }
                }
            }
        }

        if (rows.isEmpty()) return 0

        conn.prepareStatement(
            "UPDATE drivers SET current_age = ?, stat_pace = ?, stat_qualifying = ?, development_pool = ? WHERE id = ?"
        ).use { stmt ->
            rows.forEach { r ->
                stmt.setInt(1, r.newAge)
                stmt.setInt(2, r.newPace)
                stmt.setInt(3, r.newQuali)
                stmt.setInt(4, r.newDevPool)
                stmt.setObject(5, r.id)
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
            if (r.newPace != r.oldPace || r.newQuali != r.oldQuali) {
                val note = if (r.growing) "developing" else "past peak ${r.peakAge}"
                events += EventToLog(
                    "STAT_DRIFT", "DRIVER", r.id.toString(), r.name,
                    "${r.name}: pace ${r.oldPace} → ${r.newPace}, quali ${r.oldQuali} → ${r.newQuali} ($note)",
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
    // Step 4c: Junior promotion (OFF_SEASON — the F3 → F2 → F1 ladder)
    // ------------------------------------------------------------------

    /**
     * Settle the feeder-series titles and move the champions up the ladder:
     *   - F2 champion → F1 free agency (enters the upcoming DRIVER_MARKET pool).
     *   - F3 champion → F2 (slots into the F2 seat the graduate just vacated).
     *
     * The feeders aren't run round-by-round; each title is decided here by a
     * deterministic weighted race-craft score + small seeded noise (so the
     * crown isn't always the best prospect on paper). Champions get a small
     * graduation stat bump. F2 and F3 use distinct salts ([JUNIOR_PROMO_SALT],
     * [JUNIOR_PROMO_SALT_F3]) so their draws are independent and the F2 result
     * is unchanged from the single-tier version. Runs after retirements +
     * contract expirations. Returns the number of drivers promoted (0–2).
     */
    private fun promoteJuniors(conn: Connection, endingSeasonYear: Int, masterSeed: Long): Int {
        var count = 0

        // F2 champion → F1 free agency. Must be 18+ so they're valid for the
        // F1 driver market (which gates on age 18–50).
        pickChampion(conn, "F2", masterSeed, endingSeasonYear, JUNIOR_PROMO_SALT, minAge = 18)?.let { champ ->
            graduateToF1(conn, champ, endingSeasonYear)
            count++
        }

        // F3 champion → F2 (no age floor; they're just moving up a feeder),
        // slotting into whichever F2 team is now lightest (typically the seat
        // the F2 graduate just vacated).
        pickChampion(conn, "F3", masterSeed, endingSeasonYear, JUNIOR_PROMO_SALT_F3, minAge = 0)?.let { champ ->
            val f2Team = pickLightestTeam(conn, "F2")
            if (f2Team != null) {
                promoteToF2(conn, champ, f2Team, endingSeasonYear)
                count++
            }
        }

        if (count > 0) {
            log.info("Junior promotion for ending year {}: {} drivers moved up the ladder", endingSeasonYear, count)
        }
        return count
    }

    private data class Champion(
        val id: UUID, val name: String, val teamName: String,
        val pace: Int, val qualifying: Int,
    )

    /** Settle a feeder series' title deterministically and return its champion. */
    private fun pickChampion(
        conn: Connection,
        series: String,
        masterSeed: Long,
        endingSeasonYear: Int,
        salt: Long,
        minAge: Int,
    ): Champion? {
        data class Entry(
            val id: UUID, val name: String, val teamName: String,
            val pace: Int, val qualifying: Int, val consistency: Int,
        )
        val entries = conn.prepareStatement(
            """
            SELECT d.id, d.name, t.name AS team_name,
                   d.stat_pace, d.stat_qualifying, d.stat_consistency
              FROM drivers d
              JOIN teams t ON t.id = d.current_racing_team_id
             WHERE NOT d.retired
               AND t.series = ?
               AND d.current_age >= ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, series)
            stmt.setInt(2, minAge)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Entry(
                                id = rs.getObject("id", UUID::class.java),
                                name = rs.getString("name"),
                                teamName = rs.getString("team_name"),
                                pace = rs.getInt("stat_pace"),
                                qualifying = rs.getInt("stat_qualifying"),
                                consistency = rs.getInt("stat_consistency"),
                            )
                        )
                    }
                }
            }
        }
        if (entries.isEmpty()) return null

        // Sort by id first so per-driver noise draws happen in a stable order
        // (replays of the same save produce the same champion).
        val rng = Random(masterSeed xor salt xor endingSeasonYear.toLong())
        val champ = entries
            .sortedBy { it.id.toString() }
            .map { e ->
                val score = e.pace * 0.45 + e.qualifying * 0.30 +
                    e.consistency * 0.25 + rng.nextDouble() * JUNIOR_TITLE_NOISE
                e to score
            }
            .maxByOrNull { it.second }!!
            .first
        return Champion(champ.id, champ.name, champ.teamName, champ.pace, champ.qualifying)
    }

    /** The team in [series] with the fewest non-retired drivers (id tiebreak). */
    private fun pickLightestTeam(conn: Connection, series: String): Pair<String, String>? {
        return conn.prepareStatement(
            """
            SELECT t.id, t.name
              FROM teams t
              LEFT JOIN (
                  SELECT current_racing_team_id, COUNT(*) AS cnt
                    FROM drivers
                   WHERE NOT retired AND current_racing_team_id IS NOT NULL
                   GROUP BY current_racing_team_id
              ) dc ON dc.current_racing_team_id = t.id
             WHERE t.series = ?
             ORDER BY COALESCE(dc.cnt, 0) ASC, t.id ASC
             LIMIT 1
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, series)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) null else rs.getString("id") to rs.getString("name")
            }
        }
    }

    private fun graduateToF1(conn: Connection, champ: Champion, endingSeasonYear: Int) {
        val boostedPace = (champ.pace + JUNIOR_GRADUATION_BOOST).coerceAtMost(100)
        val boostedQuali = (champ.qualifying + JUNIOR_GRADUATION_BOOST).coerceAtMost(100)
        conn.prepareStatement(
            """
            UPDATE drivers SET
              current_racing_team_id = NULL,
              reserve_for_team_id = NULL,
              academy_team_id = NULL,
              previous_team_id = NULL,
              contract_expires_year = NULL,
              contract_expires_round = NULL,
              current_salary = 0,
              stat_pace = ?,
              stat_qualifying = ?,
              morale = GREATEST(morale, 75)
             WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setInt(1, boostedPace)
            stmt.setInt(2, boostedQuali)
            stmt.setObject(3, champ.id)
            stmt.executeUpdate()
        }
        logEvents(
            conn, endingSeasonYear,
            listOf(
                EventToLog(
                    "JUNIOR_PROMOTION", "DRIVER", champ.id.toString(), champ.name,
                    "${champ.name} won the F2 title with ${champ.teamName} and graduates " +
                        "to F1 as a free agent (pace ${champ.pace} → $boostedPace)",
                )
            ),
        )
    }

    private fun promoteToF2(
        conn: Connection,
        champ: Champion,
        f2Team: Pair<String, String>,
        endingSeasonYear: Int,
    ) {
        val boostedPace = (champ.pace + JUNIOR_GRADUATION_BOOST).coerceAtMost(100)
        val boostedQuali = (champ.qualifying + JUNIOR_GRADUATION_BOOST).coerceAtMost(100)
        conn.prepareStatement(
            """
            UPDATE drivers SET
              current_racing_team_id = ?,
              reserve_for_team_id = NULL,
              academy_team_id = NULL,
              previous_team_id = NULL,
              contract_expires_year = NULL,
              contract_expires_round = NULL,
              current_salary = 0,
              stat_pace = ?,
              stat_qualifying = ?,
              morale = GREATEST(morale, 75)
             WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, f2Team.first)
            stmt.setInt(2, boostedPace)
            stmt.setInt(3, boostedQuali)
            stmt.setObject(4, champ.id)
            stmt.executeUpdate()
        }
        logEvents(
            conn, endingSeasonYear,
            listOf(
                EventToLog(
                    "JUNIOR_PROMOTION", "DRIVER", champ.id.toString(), champ.name,
                    "${champ.name} won the F3 title with ${champ.teamName} and was promoted " +
                        "to F2 with ${f2Team.second} (pace ${champ.pace} → $boostedPace)",
                )
            ),
        )
    }

    // ------------------------------------------------------------------
    // Step 4b: Sponsor renewals (PRE_SEASON — runs before revenue tick)
    // ------------------------------------------------------------------

    /**
     * Renewal stub with defection: any deal whose `end_year` is before the
     * new season year (so it's just expired) is reconsidered.
     *
     * Most lapsed deals auto-renew for [SPONSOR_RENEWAL_TERM_YEARS] years at
     * the old annual value scaled by `(1 + perfMod) * noise`, where `perfMod`
     * is the last-season WCC performance modifier (±[SPONSOR_PERF_MAX_PCT])
     * and `noise` is a small ±10% wobble.
     *
     * Sponsors of teams that finished below mid-grid (negative `perfMod`)
     * may instead **defect** — walk away with no renewal. The per-deal
     * defection probability scales with how far below mid-grid the team
     * finished and with the sponsor's own `performance_sensitivity`:
     *
     *   defectProb = (shortfall / SPONSOR_PERF_MAX_PCT)
     *              * SPONSOR_DEFECTION_MAX_PROB
     *              * sponsor.performance_sensitivity
     *
     * where `shortfall = max(0, -perfMod)`. A title sponsor on a dead-last
     * team that's highly performance-sensitive faces the full risk; a
     * results-indifferent minor sponsor barely flinches. Defecting deals are
     * left lapsed (end_year untouched) and skipped by the revenue tick, so
     * the team loses that income stream.
     *
     * Determinism is keyed on the deal id so renewals/defections across
     * saves/replays are stable. The renewal-noise draw happens first and is
     * unchanged from the renewal-only version, so a deal that does NOT defect
     * renews at exactly the same value as before this feature landed; only
     * the (negative-perfMod) deals that now walk away change behaviour.
     */
    private fun renewSponsors(conn: Connection, newSeasonYear: Int): Int {
        data class Lapsed(
            val id: Long,
            val teamId: String,
            val teamName: String,
            val sponsorName: String,
            val performanceSensitivity: Double,
            val oldEndYear: Int,
            val oldValue: Long,
        )

        // The player owns their own sponsor renewals (via SponsorMarketService),
        // so auto-renewal skips the player team. `IS DISTINCT FROM` makes the
        // filter a no-op when no player team is selected (player_team_id NULL).
        val playerTeamId = conn.prepareStatement("SELECT player_team_id FROM game").use { stmt ->
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("player_team_id") else null }
        }

        val lapsed = conn.prepareStatement(
            """
            SELECT ts.id, ts.team_id, t.name AS team_name,
                   s.name AS sponsor_name,
                   s.performance_sensitivity AS performance_sensitivity,
                   ts.end_year, ts.annual_value
              FROM team_sponsorships ts
              JOIN teams t   ON t.id = ts.team_id
              JOIN sponsors s ON s.id = ts.sponsor_id
             WHERE ts.end_year < ?
               AND ts.end_year >= ?
               AND ts.team_id IS DISTINCT FROM ?
            """.trimIndent()
        ).use { stmt ->
            // The second filter constrains us to "just-expired" deals (ended
            // last season) — older lapsed deals stay lapsed. Keeps the
            // renewal step idempotent across replays even though it's not
            // RNG-deterministic per-deal beyond the noise seed. A deal that
            // defected last off-season keeps its old end_year and so falls
            // out of this window next year — it won't be reconsidered.
            stmt.setInt(1, newSeasonYear)
            stmt.setInt(2, newSeasonYear - 1)
            stmt.setString(3, playerTeamId)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Lapsed(
                                id = rs.getLong("id"),
                                teamId = rs.getString("team_id"),
                                teamName = rs.getString("team_name"),
                                sponsorName = rs.getString("sponsor_name"),
                                performanceSensitivity = rs.getDouble("performance_sensitivity"),
                                oldEndYear = rs.getInt("end_year"),
                                oldValue = rs.getLong("annual_value"),
                            )
                        )
                    }
                }
            }
        }

        if (lapsed.isEmpty()) return 0

        // Performance modifier: sponsors renew at a premium for teams that
        // finished high in the WCC last season, at a discount for teams
        // that finished low. Maps top-ranked → +SPONSOR_PERF_MAX_PCT,
        // bottom-ranked → -SPONSOR_PERF_MAX_PCT, linearly interpolated.
        // Applied multiplicatively on top of the existing ±10% noise, and
        // also drives the defection probability below.
        val perfModByTeam = readWccPerformanceModifiers(conn, newSeasonYear - 1)

        data class Renewal(val deal: Lapsed, val newValue: Long, val newEnd: Int)
        data class Defection(val deal: Lapsed, val defectProb: Double)

        val renewals = mutableListOf<Renewal>()
        val defections = mutableListOf<Defection>()

        // Per-deal RNG so outcomes are stable across replays: same deal id +
        // same renewal year = same draws. The noise draw is taken FIRST so
        // non-defecting deals renew at the exact value they did before
        // defection existed; the defection roll is a second draw from the
        // same per-deal RNG, taken only for at-risk (negative-perfMod) deals.
        lapsed.forEach { d ->
            val rng = kotlin.random.Random(
                SPONSOR_RENEW_SALT xor d.id xor newSeasonYear.toLong()
            )
            val noise = 0.9 + rng.nextDouble() * 0.2  // [0.9, 1.1)
            val perfMod = perfModByTeam[d.teamId] ?: 0.0

            // Defection risk only for below-mid-grid teams (negative perfMod).
            val shortfall = (-perfMod).coerceAtLeast(0.0)
            val defectProb = if (shortfall > 0.0) {
                ((shortfall / SPONSOR_PERF_MAX_PCT) *
                    SPONSOR_DEFECTION_MAX_PROB *
                    d.performanceSensitivity)
                    .coerceIn(0.0, 1.0)
            } else 0.0
            val defects = defectProb > 0.0 && rng.nextDouble() < defectProb

            if (defects) {
                defections += Defection(d, defectProb)
            } else {
                val scale = (1.0 + perfMod) * noise
                val newValue = (d.oldValue * scale).toLong().coerceAtLeast(MIN_RENEWAL_VALUE)
                val newEnd = newSeasonYear + SPONSOR_RENEWAL_TERM_YEARS - 1
                renewals += Renewal(d, newValue, newEnd)
            }
        }

        if (renewals.isNotEmpty()) {
            conn.prepareStatement(
                """
                UPDATE team_sponsorships SET
                  start_year = ?,
                  end_year = ?,
                  annual_value = ?
                 WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                renewals.forEach { r ->
                    stmt.setInt(1, newSeasonYear)
                    stmt.setInt(2, r.newEnd)
                    stmt.setLong(3, r.newValue)
                    stmt.setLong(4, r.deal.id)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }

        // Log under SPONSOR_REVENUE — it's the same conceptual bucket and
        // avoids another CHECK update. The message disambiguates renewal
        // vs defection.
        val events = mutableListOf<EventToLog>()
        renewals.forEach { r ->
            val d = r.deal
            val perfMod = perfModByTeam[d.teamId] ?: 0.0
            val perfTag = when {
                perfMod > 0.0 -> " [+${(perfMod * 100).toInt()}% WCC perf]"
                perfMod < 0.0 -> " [${(perfMod * 100).toInt()}% WCC perf]"
                else -> ""
            }
            events += EventToLog(
                "SPONSOR_REVENUE", "TEAM", d.teamId, d.teamName,
                "${d.teamName}: ${d.sponsorName} renewed " +
                    "($${d.oldValue} → $${r.newValue}/yr) through ${r.newEnd}$perfTag",
            )
        }
        defections.forEach { def ->
            val d = def.deal
            val pct = (def.defectProb * 100).toInt()
            events += EventToLog(
                "SPONSOR_REVENUE", "TEAM", d.teamId, d.teamName,
                "${d.teamName}: ${d.sponsorName} declined to renew and walked away " +
                    "after a poor WCC season ($${d.oldValue}/yr deal lost; defection chance ${pct}%)",
            )
        }
        logEvents(conn, newSeasonYear, events)

        log.info(
            "Sponsor renewals for {}: {} renewed, {} defected",
            newSeasonYear, renewals.size, defections.size,
        )
        return renewals.size + defections.size
    }

    /**
     * Build a per-team WCC performance modifier map for the given season.
     * Returns `Map<teamId, modifier>` where modifier is in
     * `[-SPONSOR_PERF_MAX_PCT, +SPONSOR_PERF_MAX_PCT]`, top-ranked positive,
     * bottom-ranked negative, linear interpolation in between.
     *
     * Aggregates points from both `race_results.points` and
     * `sprint_results.sprint_points` for the given `season_year`. Teams
     * with zero entries get rank = last (and thus the maximum penalty);
     * teams not in the F1 series are excluded entirely so they don't skew
     * the rank distribution.
     *
     * Single-team edge case: with only one F1 team there's no rank gradient,
     * so the modifier is 0.0.
     */
    private fun readWccPerformanceModifiers(
        conn: Connection,
        seasonYear: Int,
    ): Map<String, Double> {
        data class TeamPoints(val teamId: String, val points: Double)

        val teamPoints = conn.prepareStatement(
            """
            SELECT t.id AS team_id,
                   COALESCE(rr.pts, 0) + COALESCE(sr.pts, 0) AS wcc_points
              FROM teams t
              LEFT JOIN (
                  SELECT rr.team_id, SUM(rr.points)::double precision AS pts
                    FROM race_results rr
                    JOIN races r ON r.id = rr.race_id
                   WHERE r.season_year = ?
                   GROUP BY rr.team_id
              ) rr ON rr.team_id = t.id
              LEFT JOIN (
                  SELECT sr.team_id, SUM(sr.sprint_points)::double precision AS pts
                    FROM sprint_results sr
                    JOIN races r ON r.id = sr.race_id
                   WHERE r.season_year = ?
                   GROUP BY sr.team_id
              ) sr ON sr.team_id = t.id
             WHERE t.series = 'F1'
            """.trimIndent()
        ).use { stmt ->
            stmt.setInt(1, seasonYear)
            stmt.setInt(2, seasonYear)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(TeamPoints(rs.getString("team_id"), rs.getDouble("wcc_points")))
                    }
                }
            }
        }

        if (teamPoints.size <= 1) return emptyMap()

        // Sort high → low, then map index to modifier:
        //   idx 0          → +SPONSOR_PERF_MAX_PCT
        //   idx (N-1)      → -SPONSOR_PERF_MAX_PCT
        // Ties get whatever order Postgres/the JVM gave us — fine, since
        // ties are vanishingly rare across a full season and the modifier
        // difference between adjacent ranks is small anyway.
        val ranked = teamPoints.sortedByDescending { it.points }
        val lastIndex = (ranked.size - 1).toDouble()
        return ranked.withIndex().associate { (idx, tp) ->
            val t = idx.toDouble() / lastIndex          // 0.0 .. 1.0
            val mod = SPONSOR_PERF_MAX_PCT - 2.0 * SPONSOR_PERF_MAX_PCT * t
            tp.teamId to mod
        }
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
            val rd: Long,
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
                   (t.rd_aero + t.rd_chassis + t.rd_powertrain) AS rd_cost,
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
                        val rd = rs.getLong("rd_cost")
                        val driverSalaries = rs.getLong("driver_salaries")
                        val personnelSalaries = rs.getLong("personnel_salaries")
                        val total = base + academy + rd + driverSalaries + personnelSalaries
                        add(
                            TeamCost(
                                teamId = rs.getString("team_id"),
                                teamName = rs.getString("team_name"),
                                base = base,
                                academy = academy,
                                rd = rd,
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
                    "${u.teamName}: base $${u.base} + academy $${u.academy} + R&D $${u.rd} + " +
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
        const val JUNIOR_PROMO_SALT = 0x4A554E494F523231L    // "JUNIOR21"
        const val JUNIOR_PROMO_SALT_F3 = 0x4A554E494F523333L // "JUNIOR33"

        /**
         * Half-width-ish of the seeded noise added to each junior's F2 title
         * score. Sized so the championship usually goes to one of the stronger
         * prospects but can occasionally spring a surprise — the on-paper best
         * isn't guaranteed the crown.
         */
        const val JUNIOR_TITLE_NOISE = 8.0

        /**
         * Stat bump (pace + qualifying) applied when an F2 champion graduates
         * to F1. Small — a rookie should arrive raw and develop, not debut as
         * a front-runner — but enough to lift the best juniors into the
         * back-marker-rookie band where an F1 team might gamble on them.
         */
        const val JUNIOR_GRADUATION_BOOST = 3

        /**
         * Young-driver development: at/under peak age with development_pool
         * left, pace + qualifying climb toward [DRIVER_GROWTH_CAP], burning
         * [DRIVER_GROWTH_POOL_BURN] of the pool each season so growth tapers.
         * Cap is below the elite ceiling so seeded stars stay special; growth
         * never reduces an already-higher stat.
         */
        const val DRIVER_GROWTH_CAP = 92
        const val DRIVER_GROWTH_POOL_BURN = 70

        const val SPONSOR_RENEWAL_TERM_YEARS = 2
        const val MIN_RENEWAL_VALUE = 500_000L

        /**
         * Half-width of the sponsor renewal performance modifier band.
         * 0.15 → top WCC team renews at +15% of base, bottom-of-grid at
         * -15%, linearly interpolated. Stacks multiplicatively with the
         * existing ±10% noise: best case ~+26%, worst case ~-23%.
         */
        const val SPONSOR_PERF_MAX_PCT = 0.15

        /**
         * Maximum per-deal probability that a sponsor walks away instead of
         * renewing. Reached only when the team finished dead last
         * (perfMod = -SPONSOR_PERF_MAX_PCT, so shortfall ratio = 1.0) AND the
         * sponsor is maximally performance-sensitive (1.0). Scales linearly
         * down to 0 at mid-grid (perfMod >= 0). With the seeded sponsor pool
         * (performance_sensitivity ~0.30..0.80) a dead-last team faces roughly
         * a 12–32% walk-away chance per just-expired deal — a real sting for
         * back-markers without gutting their income every off-season. Tuning
         * knob; raise for harsher consequences.
         */
        const val SPONSOR_DEFECTION_MAX_PROB = 0.40

        // --- Car development (PRE_SEASON R&D tick) ---------------------
        /** Per-area R&D spend at which spendFactor hits 0.5 (saturating curve). */
        const val CAR_DEV_REF_SPEND = 20_000_000.0
        /** tech = BASE + SWING * regulation_understanding → 0.7..1.3. */
        const val CAR_DEV_TECH_BASE = 0.7
        const val CAR_DEV_TECH_SWING = 0.6
        /** Car level a zero-spend team trends toward (back-marker). */
        const val CAR_DEV_FLOOR_TARGET = 45.0
        /** How far full spend + top tech can lift the target above the floor. */
        const val CAR_DEV_SPAN = 50.0
        /** Fraction of the gap to target closed each season (inertia). */
        const val CAR_DEV_ADJUST_RATE = 0.4
        /** Clamp range for car_performance under development. */
        const val CAR_DEV_MIN = 40
        const val CAR_DEV_MAX = 95
    }
}
