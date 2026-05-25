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
 *     5. Driver market — AI-driven multi-round matching fills open F1
 *        racing seats from the free-agent pool. Two-year contracts at
 *        pace-banded salaries scaled by team prestige.
 *
 *   PRE_SEASON hooks (the new year about to start):
 *     6. Sponsor revenue tick — sum active sponsorships, set current_year_income.
 *     7. Operating cost tick — base_operating_cost + driver salaries +
 *        personnel salaries, set current_year_expenses.
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
        // Driver market runs last so the free-agent pool reflects everyone
        // released this off-season by contract expiration. (Retirees are
        // filtered out — they have `retired = true`.)
        val marketEvents = runDriverMarket(conn, endingSeasonYear, masterSeed)
        val total = ageEvents + retirementEvents + expirationEvents + marketEvents
        log.info(
            "OFF_SEASON for ending year {}: {} aging, {} retirements, {} contract expirations, {} market signings",
            endingSeasonYear, ageEvents, retirementEvents, expirationEvents, marketEvents,
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
    // Step 5: Driver market
    // ------------------------------------------------------------------

    /**
     * AI-driven driver market. Free-agent drivers are matched to open F1
     * racing seats over a few rounds of two-sided preference matching:
     *
     *   - Teams iterate in prestige order each round.
     *   - For each open seat the team scores all unsigned free agents
     *     and identifies its top pick.
     *   - The driver scores the open teams and ranks them; a signing
     *     happens iff this team is in the driver's top-K for the round
     *     (K = 3, 5, 10 across rounds 1-3). This produces the "top of
     *     market signs first" feel from the design doc: top teams get
     *     their first choice in round 1; mid teams settle later; bottom
     *     teams fill remaining seats in round 3.
     *
     * No player input in v1 — entirely AI-driven. Player offers come in
     * a follow-up slice that will use the same matching engine but with
     * player offers seeded in alongside AI proposals.
     *
     * Contracts are 2 years; salary = pace-banded base × team prestige
     * factor (prestige / 75.0). All scoring uses a single deterministic
     * RNG seeded from masterSeed XOR MARKET_SALT XOR endingSeasonYear so
     * the same save + year always produces identical signings.
     *
     * Open known-issues (logged in remaining.md):
     *   - No AI personality modulation (ai_aggression, ai_frugality unused)
     *   - No affordability check vs cash_reserves
     *   - No previous-team loyalty bonus
     *   - Leftover empty seats stay empty (junior promotions not yet built)
     */
    private fun runDriverMarket(
        conn: Connection,
        endingSeasonYear: Int,
        masterSeed: Long,
    ): Int {
        val rng = Random(masterSeed xor MARKET_SALT xor endingSeasonYear.toLong())

        val freeAgents = readMarketFreeAgents(conn).toMutableList()
        val marketTeams = readMarketTeams(conn).toMutableList()

        if (freeAgents.isEmpty() || marketTeams.none { it.openSeats > 0 }) {
            log.info(
                "Driver market: nothing to do (free agents: {}, teams with open seats: {})",
                freeAgents.size, marketTeams.count { it.openSeats > 0 },
            )
            return 0
        }

        val signings = resolveMarketRounds(freeAgents, marketTeams, rng, endingSeasonYear)
        if (signings.isEmpty()) {
            log.info("Driver market: 0 signings after {} rounds", MARKET_ROUNDS)
            return 0
        }

        applyMarketSignings(conn, signings, endingSeasonYear)
        return signings.size
    }

    private data class MarketAgent(
        val id: UUID,
        val name: String,
        val statPace: Int,
        val currentAge: Int,
        val morale: Int,
    )

    private data class MarketTeam(
        val id: String,
        val name: String,
        val prestige: Int,
        val seasonPoints: Int,
        var openSeats: Int,
    )

    private data class MarketSigning(
        val agent: MarketAgent,
        val team: MarketTeam,
        val salary: Long,
        val expiresYear: Int,
    )

    private fun readMarketFreeAgents(conn: Connection): List<MarketAgent> {
        // Drivers without a racing team and not retired. Age guard keeps the
        // pool sensible (rules out anything weird that crept into the data).
        return conn.prepareStatement(
            """
            SELECT id, name, stat_pace, current_age, morale
              FROM drivers
             WHERE NOT retired
               AND current_racing_team_id IS NULL
               AND current_age BETWEEN 18 AND 50
             ORDER BY name ASC
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            MarketAgent(
                                id = rs.getObject("id", UUID::class.java),
                                name = rs.getString("name"),
                                statPace = rs.getInt("stat_pace"),
                                currentAge = rs.getInt("current_age"),
                                morale = rs.getInt("morale"),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun readMarketTeams(conn: Connection): List<MarketTeam> {
        // open_seats = SEATS_PER_TEAM (2) - current non-retired racing drivers.
        // Negative results (over-stuffed) are clamped to 0; shouldn't happen
        // unless something else has gone wrong but be defensive.
        return conn.prepareStatement(
            """
            SELECT t.id, t.name, t.prestige, t.season_points,
                   GREATEST(0, $SEATS_PER_TEAM - COALESCE(driver_count.cnt, 0)) AS open_seats
              FROM teams t
              LEFT JOIN (
                  SELECT current_racing_team_id, COUNT(*) AS cnt
                    FROM drivers
                   WHERE NOT retired
                     AND current_racing_team_id IS NOT NULL
                   GROUP BY current_racing_team_id
              ) driver_count ON driver_count.current_racing_team_id = t.id
             WHERE t.series = 'F1'
             ORDER BY t.id ASC
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            MarketTeam(
                                id = rs.getString("id"),
                                name = rs.getString("name"),
                                prestige = rs.getInt("prestige"),
                                seasonPoints = rs.getInt("season_points"),
                                openSeats = rs.getInt("open_seats"),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun resolveMarketRounds(
        freeAgents: MutableList<MarketAgent>,
        marketTeams: MutableList<MarketTeam>,
        rng: Random,
        endingSeasonYear: Int,
    ): List<MarketSigning> {
        val signings = mutableListOf<MarketSigning>()

        for (round in 1..MARKET_ROUNDS) {
            val topKForMutual = topKForRound(round)
            val teamsWithSeats = marketTeams
                .filter { it.openSeats > 0 }
                .sortedWith(
                    compareByDescending<MarketTeam> { it.prestige }
                        .thenBy { it.id }
                )

            if (teamsWithSeats.isEmpty() || freeAgents.isEmpty()) break

            for (team in teamsWithSeats) {
                // Try to fill this team's open seats this round.
                while (team.openSeats > 0 && freeAgents.isNotEmpty()) {
                    // Team's top pick from current free-agent pool.
                    val candidate = freeAgents
                        .map { it to teamScore(team, it, rng) }
                        .maxWithOrNull(
                            compareBy<Pair<MarketAgent, Double>> { it.second }
                                .thenBy { it.first.name }  // stable tiebreak
                        )?.first ?: break

                    // Candidate's preference ranking over currently-open teams.
                    val candidateRanking = marketTeams
                        .filter { it.openSeats > 0 }
                        .map { it to driverScore(candidate, it, rng) }
                        .sortedWith(
                            compareByDescending<Pair<MarketTeam, Double>> { it.second }
                                .thenBy { it.first.id }
                        )
                        .map { it.first }

                    val mutualOk = candidateRanking
                        .take(topKForMutual)
                        .any { it.id == team.id }

                    if (mutualOk) {
                        signings += MarketSigning(
                            agent = candidate,
                            team = team,
                            salary = computeSignedSalary(candidate, team),
                            expiresYear = endingSeasonYear + CONTRACT_LENGTH_YEARS,
                        )
                        freeAgents.remove(candidate)
                        team.openSeats -= 1
                    } else {
                        // Top candidate doesn't want this team this round.
                        // Yield to the next team in prestige order — this team's
                        // remaining seats wait until a later round when its
                        // standards (or the driver pool) widen.
                        break
                    }
                }
            }
        }

        return signings
    }

    private fun applyMarketSignings(
        conn: Connection,
        signings: List<MarketSigning>,
        endingSeasonYear: Int,
    ) {
        conn.prepareStatement(
            """
            UPDATE drivers SET
              current_racing_team_id = ?,
              contract_expires_year = ?,
              contract_expires_round = ?,
              current_salary = ?
             WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            signings.forEach { s ->
                stmt.setString(1, s.team.id)
                stmt.setInt(2, s.expiresYear)
                stmt.setInt(3, CONTRACT_END_ROUND)
                stmt.setLong(4, s.salary)
                stmt.setObject(5, s.agent.id)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

        logEvents(
            conn, endingSeasonYear,
            signings.map { s ->
                EventToLog(
                    "MARKET_SIGNING", "DRIVER", s.agent.id.toString(), s.agent.name,
                    "${s.agent.name} → ${s.team.name} (pace ${s.agent.statPace}, age ${s.agent.currentAge}): " +
                        "${CONTRACT_LENGTH_YEARS}yr contract through ${s.expiresYear} at \$${s.salary}/yr",
                )
            },
        )

        log.info(
            "Driver market: {} signings applied (off-season ending {})",
            signings.size, endingSeasonYear,
        )
    }

    /**
     * Team's preference score for a candidate driver.
     *
     *   skill   : pace, the dominant term
     *   ageOpt  : symmetric penalty around 27 (peak racing years)
     *   morale  : slight bonus for happy drivers
     *   noise   : RNG spice for variety; magnitude tuned to be ~10% of skill
     */
    private fun teamScore(team: MarketTeam, agent: MarketAgent, rng: Random): Double {
        val skill = (agent.statPace - 50).toDouble()
        val ageOpt = -kotlin.math.abs(agent.currentAge - 27).toDouble()
        val morale = (agent.morale - 50).toDouble()
        val noise = rng.nextDouble() * 10.0
        // Reference team — unused for v1 but keeps the signature future-proof
        // for prestige-modulated scouting (e.g. top teams have wider scouting).
        @Suppress("UNUSED_PARAMETER") team
        return skill * 0.7 + ageOpt * 0.5 + morale * 0.2 + noise
    }

    /**
     * Driver's preference score for a team.
     *
     *   prestige   : the dominant term — drivers chase top teams
     *   performance: recent results (just-ended season's points)
     *   noise      : RNG spice
     */
    private fun driverScore(agent: MarketAgent, team: MarketTeam, rng: Random): Double {
        @Suppress("UNUSED_PARAMETER") agent  // reserved for loyalty / nationality matching
        val prestige = (team.prestige - 50).toDouble()
        // 20-pts-per-unit normalizer: P1-grade ~500 pts → +25; P5 ~50 pts → +2.5.
        val performance = team.seasonPoints / 20.0
        val noise = rng.nextDouble() * 5.0
        return prestige * 0.6 + performance * 0.3 + noise
    }

    private fun computeSignedSalary(agent: MarketAgent, team: MarketTeam): Long {
        // Same banding as computeDerivedSeedValues, scaled by team prestige.
        // Top-team factor (~1.27 at prestige 95) vs bottom-team (~0.53 at 40)
        // gives a 2.4× spread for the same driver depending on team — drives
        // the prestige-pulls-talent dynamic.
        val base = when {
            agent.statPace >= 90 -> 20_000_000L
            agent.statPace >= 80 -> 8_000_000L
            agent.statPace >= 70 -> 2_000_000L
            else -> 500_000L
        }
        val prestigeFactor = team.prestige / 75.0
        return (base * prestigeFactor).toLong()
    }

    private fun topKForRound(round: Int): Int = when (round) {
        1 -> 3
        2 -> 5
        else -> 10
    }

    // ------------------------------------------------------------------
    // Step 6: Sponsor revenue tick (PRE_SEASON)
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
    // Step 7: Operating cost tick (PRE_SEASON)
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
        const val MARKET_SALT = 0x6D61_726B_6574_5341L  // "mrketSA" — distinct from RETIRE_SALT

        const val MARKET_ROUNDS = 3
        const val SEATS_PER_TEAM = 2
        const val CONTRACT_LENGTH_YEARS = 2
        const val CONTRACT_END_ROUND = 24
    }
}
