package f1sim.game

import f1sim.db.Database
import f1sim.http.NotFoundException
import f1sim.save.SaveSession
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID
import kotlin.random.Random

/**
 * Driver market — multi-round off-season matching between free-agent drivers
 * and teams with open seats. Owns:
 *
 *   1. Market state lifecycle (initialize on entry, advance per round, clean
 *      up on exit). State is a singleton row in `driver_market_state`.
 *   2. Player offer surface: GET available drivers, submit/withdraw an offer
 *      via [submitOffer] / [withdrawOffer]. Offers live in
 *      `driver_market_offers` until consumed by [resolveOneRound].
 *   3. Per-round matching: player offers are processed first (the player's
 *      team competes alongside AI teams via the driver's preference list),
 *      then AI fills its own remaining seats. The player team's seats can
 *      only be filled by player offers — silence = empty seats. AI signings
 *      are additionally gated by team cash reserves (see [canAfford]); a
 *      cash-poor AI team can be priced out and leave its own seats empty.
 *
 * Deterministic: each round seeds its own RNG from
 * `Random(masterSeed XOR MARKET_SALT XOR endingSeasonYear XOR round)`.
 * Replaying a single round of the same save produces identical outcomes.
 *
 * Signings write to `drivers` (team / contract / salary) and emit a
 * `MARKET_SIGNING` row to `off_season_events` so the off-season report
 * picks them up. Player signings note that they came from a player offer.
 */
class DriverMarketService(private val db: Database) {

    private val log = LoggerFactory.getLogger(DriverMarketService::class.java)

    // ------------------------------------------------------------------
    // DTOs (player-facing)
    // ------------------------------------------------------------------

    @Serializable
    data class MarketStateDto(
        val active: Boolean,
        val seasonYear: Int? = null,
        val currentRound: Int? = null,
        val totalRounds: Int? = null,
        val complete: Boolean = false,
        val playerTeamId: String? = null,
        val playerTeamOpenSeats: Int? = null,
    )

    @Serializable
    data class FreeAgentDto(
        val driverId: String,
        val name: String,
        val nationality: String,
        val age: Int,
        val statPace: Int,
        val statQualifying: Int,
        val statConsistency: Int,
        val morale: Int,
        val recommendedSalary: Long,
        val playerHasOffer: Boolean,
    )

    @Serializable
    data class TeamWithSeatsDto(
        val teamId: String,
        val teamName: String,
        val prestige: Int,
        val openSeats: Int,
    )

    @Serializable
    data class AvailableDto(
        val state: MarketStateDto,
        val freeAgents: List<FreeAgentDto>,
        val teamsWithOpenSeats: List<TeamWithSeatsDto>,
    )

    @Serializable
    data class PlayerOfferDto(
        val driverId: String,
        val driverName: String,
        val teamId: String,
        val salary: Long,
        val contractYears: Int,
        val submittedRound: Int,
    )

    @Serializable
    data class SubmitOfferRequest(
        val driverId: String,
        val salary: Long,
        val contractYears: Int = DEFAULT_CONTRACT_YEARS,
    )

    // ------------------------------------------------------------------
    // Player-facing operations (called from routes)
    // ------------------------------------------------------------------

    fun viewAvailable(): AvailableDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn ->
            val state = readState(conn)
            if (!state.active) {
                return@withConnection AvailableDto(
                    state = state,
                    freeAgents = emptyList(),
                    teamsWithOpenSeats = emptyList(),
                )
            }
            AvailableDto(
                state = state,
                freeAgents = readFreeAgentsForView(conn, state.playerTeamId),
                teamsWithOpenSeats = readOpenSeatTeams(conn),
            )
        }
    }

    fun viewPlayerOffers(): List<PlayerOfferDto> {
        SaveSession.requireLoaded()
        return db.withConnection { conn ->
            val playerTeamId = readPlayerTeamId(conn)
                ?: return@withConnection emptyList()
            conn.prepareStatement(
                """
                SELECT o.driver_id, d.name AS driver_name, o.team_id,
                       o.salary, o.contract_years, o.submitted_round
                  FROM driver_market_offers o
                  JOIN drivers d ON d.id = o.driver_id
                 WHERE o.team_id = ?
                 ORDER BY d.name ASC
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, playerTeamId)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                PlayerOfferDto(
                                    driverId = rs.getObject("driver_id", UUID::class.java).toString(),
                                    driverName = rs.getString("driver_name"),
                                    teamId = rs.getString("team_id"),
                                    salary = rs.getLong("salary"),
                                    contractYears = rs.getInt("contract_years"),
                                    submittedRound = rs.getInt("submitted_round"),
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    fun submitOffer(req: SubmitOfferRequest): PlayerOfferDto {
        SaveSession.requireLoaded()
        val driverId = try {
            UUID.fromString(req.driverId)
        } catch (_: IllegalArgumentException) {
            throw NotFoundException("No driver with id ${req.driverId}")
        }
        require(req.salary in MIN_OFFER_SALARY..MAX_OFFER_SALARY) {
            "salary must be between $MIN_OFFER_SALARY and $MAX_OFFER_SALARY"
        }
        require(req.contractYears in MIN_CONTRACT_YEARS..MAX_CONTRACT_YEARS) {
            "contractYears must be between $MIN_CONTRACT_YEARS and $MAX_CONTRACT_YEARS"
        }

        return db.withConnection { conn ->
            val state = readState(conn)
            check(state.active && !state.complete) {
                "Driver market is not currently accepting offers"
            }
            val playerTeamId = state.playerTeamId
                ?: error("No player team selected — cannot submit market offers")
            check((state.playerTeamOpenSeats ?: 0) > 0 || hasOfferFor(conn, driverId, playerTeamId)) {
                "Player team has no open seats and no existing offer to replace"
            }

            // Driver must exist and be a free agent (not on a racing team, not retired).
            val driverName = conn.prepareStatement(
                """
                SELECT name FROM drivers
                 WHERE id = ?
                   AND NOT retired
                   AND current_racing_team_id IS NULL
                """.trimIndent()
            ).use { stmt ->
                stmt.setObject(1, driverId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) throw NotFoundException(
                        "Driver $driverId is not a free agent"
                    )
                    rs.getString("name")
                }
            }

            val currentRound = state.currentRound ?: 0
            val nextRound = currentRound + 1
            conn.prepareStatement(
                """
                INSERT INTO driver_market_offers
                  (driver_id, team_id, salary, contract_years, submitted_round)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (driver_id, team_id) DO UPDATE SET
                  salary = EXCLUDED.salary,
                  contract_years = EXCLUDED.contract_years,
                  submitted_round = EXCLUDED.submitted_round,
                  submitted_at = now()
                """.trimIndent()
            ).use { stmt ->
                stmt.setObject(1, driverId)
                stmt.setString(2, playerTeamId)
                stmt.setLong(3, req.salary)
                stmt.setInt(4, req.contractYears)
                stmt.setInt(5, nextRound)
                stmt.executeUpdate()
            }
            log.info(
                "Player offer: driver={} team={} salary={} years={} for round {}",
                driverId, playerTeamId, req.salary, req.contractYears, nextRound,
            )

            PlayerOfferDto(
                driverId = driverId.toString(),
                driverName = driverName,
                teamId = playerTeamId,
                salary = req.salary,
                contractYears = req.contractYears,
                submittedRound = nextRound,
            )
        }
    }

    fun withdrawOffer(driverIdRaw: String) {
        SaveSession.requireLoaded()
        val driverId = try {
            UUID.fromString(driverIdRaw)
        } catch (_: IllegalArgumentException) {
            throw NotFoundException("No driver with id $driverIdRaw")
        }
        db.withConnection { conn ->
            val playerTeamId = readPlayerTeamId(conn)
                ?: error("No player team selected")
            val deleted = conn.prepareStatement(
                "DELETE FROM driver_market_offers WHERE driver_id = ? AND team_id = ?"
            ).use { stmt ->
                stmt.setObject(1, driverId)
                stmt.setString(2, playerTeamId)
                stmt.executeUpdate()
            }
            if (deleted == 0) throw NotFoundException(
                "No pending offer for driver $driverId"
            )
        }
    }

    // ------------------------------------------------------------------
    // Phase-hook operations (called from GameService transition hooks)
    // ------------------------------------------------------------------

    /**
     * Called on OFF_SEASON -> DRIVER_MARKET transition. Wipes any leftover
     * state from a prior market and initialises a fresh row at round 0.
     * (Round 0 = "open for offers; no rounds resolved yet".)
     */
    fun initializeMarketForOffSeason(conn: Connection, endingSeasonYear: Int) {
        conn.createStatement().use { it.execute("DELETE FROM driver_market_offers") }
        conn.createStatement().use { it.execute("DELETE FROM driver_market_state") }
        conn.prepareStatement(
            """
            INSERT INTO driver_market_state
              (season_year, current_round, total_rounds)
            VALUES (?, 0, ?)
            """.trimIndent()
        ).use { stmt ->
            stmt.setInt(1, endingSeasonYear)
            stmt.setInt(2, DEFAULT_TOTAL_ROUNDS)
            stmt.executeUpdate()
        }
        log.info("Driver market: initialized for off-season ending {}", endingSeasonYear)
    }

    /**
     * Returns true if the market has completed all its rounds and is ready
     * to exit to PRE_SEASON. Called by GameService to decide PhaseMachine's
     * isMarketComplete flag on a DRIVER_MARKET advance.
     */
    fun isMarketComplete(conn: Connection): Boolean {
        return conn.prepareStatement(
            "SELECT current_round, total_rounds FROM driver_market_state"
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                if (!rs.next()) true
                else rs.getInt("current_round") >= rs.getInt("total_rounds")
            }
        }
    }

    /**
     * Runs one market round. Resolves player offers first, then AI matching
     * on the remainder. Increments `driver_market_state.current_round`.
     *
     * Returns the number of signings made this round (for the transition
     * event log).
     */
    fun resolveOneRound(
        conn: Connection,
        endingSeasonYear: Int,
        masterSeed: Long,
    ): Int {
        val stateRow = readStateRow(conn)
            ?: error("No driver market state — was initializeMarketForOffSeason called?")
        if (stateRow.currentRound >= stateRow.totalRounds) {
            log.info("Driver market: already complete; no round run")
            return 0
        }
        val round = stateRow.currentRound + 1
        val rng = Random(masterSeed xor MARKET_SALT xor
            stateRow.seasonYear.toLong() xor round.toLong())

        val freeAgents = readFreeAgentsForMatching(conn).toMutableList()
        val marketTeams = readMatchingTeams(conn).toMutableList()
        val playerTeamId = readPlayerTeamId(conn)
        val offers = readOffersForResolution(conn)

        val signings = mutableListOf<MarketSigning>()

        // Phase 1: process player offers. Driver must exist in the pool;
        // the player team must be in the driver's top-K teams this round.
        if (playerTeamId != null && offers.isNotEmpty()) {
            val playerTeam = marketTeams.firstOrNull { it.id == playerTeamId }
            if (playerTeam != null) {
                val topK = topKForRound(round, marketTeams.size)
                offers.forEach { offer ->
                    if (playerTeam.openSeats <= 0) return@forEach
                    val driver = freeAgents.firstOrNull { it.id == offer.driverId } ?: return@forEach
                    val driversRanking = marketTeams
                        .filter { it.openSeats > 0 }
                        .map { it to driverScore(driver, it, rng) }
                        .sortedWith(
                            compareByDescending<Pair<MarketTeam, Double>> { it.second }
                                .thenBy { it.first.id }
                        )
                        .map { it.first }
                    val mutualOk = driversRanking.take(topK).any { it.id == playerTeamId }
                    if (mutualOk) {
                        signings += MarketSigning(
                            agent = driver,
                            team = playerTeam,
                            salary = offer.salary,
                            expiresYear = endingSeasonYear + offer.contractYears,
                            playerOffer = true,
                        )
                        freeAgents.remove(driver)
                        playerTeam.openSeats -= 1
                    }
                    // If not mutual: leave driver in the pool; AI may sign them
                    // this round, or they may roll forward to a later round.
                }
            }
        }

        // Phase 2: AI matching. The player team is excluded from the team
        // iteration — its remaining seats are the player's responsibility.
        val aiTeams = marketTeams.filter { it.id != playerTeamId && it.openSeats > 0 }
            .sortedWith(
                compareByDescending<MarketTeam> { it.prestige }
                    .thenBy { it.id }
            )

        // Counter-bidding context: which AI team is the closest prestige rival
        // to the player team? That AI team gets a scoring bump on any
        // candidate who has a pending player offer this round. Result: a
        // similar-tier AI team may snatch a contested driver, making the
        // market feel reactive without an explicit bidding mini-game.
        val playerTeamPrestige = playerTeamId
            ?.let { id -> marketTeams.firstOrNull { it.id == id }?.prestige }
        val closestRivalTeamId: String? = if (playerTeamPrestige != null) {
            aiTeams.minByOrNull { kotlin.math.abs(it.prestige - playerTeamPrestige) }?.id
        } else null
        val playerOfferedDriverIds: Set<UUID> = offers.map { it.driverId }.toSet()

        val topK = topKForRound(round, marketTeams.size)
        for (team in aiTeams) {
            while (team.openSeats > 0 && freeAgents.isNotEmpty()) {
                // Score every free agent (preserving the round RNG draw
                // sequence) and pre-compute the salary each would command.
                // computeAiSalary uses its own per-(agent, team, round) RNG,
                // so calling it here doesn't perturb the outer market RNG.
                val scored = freeAgents.map { agent ->
                    val counter = team.id == closestRivalTeamId &&
                        agent.id in playerOfferedDriverIds
                    Scored(
                        agent = agent,
                        teamScore = teamScore(team, agent, rng, counter),
                        salary = computeAiSalary(agent, team, round),
                    )
                }

                // Affordability gate: the team's projected racing-driver
                // salary bill (existing roster + already-committed this market
                // + this new salary) must stay within AI_SALARY_CASH_FRACTION
                // of its cash reserves. Filter to what the team can actually
                // pay, then pick the best by preference. If nothing's
                // affordable the team is tapped out and stops signing.
                val candidate = scored
                    .filter { team.canAfford(it.salary) }
                    .maxWithOrNull(
                        compareBy<Scored> { it.teamScore }
                            .thenBy { it.agent.name }
                    )?.agent ?: break

                val driversRanking = marketTeams
                    .filter { it.openSeats > 0 && it.id != playerTeamId }
                    .map { it to driverScore(candidate, it, rng) }
                    .sortedWith(
                        compareByDescending<Pair<MarketTeam, Double>> { it.second }
                            .thenBy { it.first.id }
                    )
                    .map { it.first }

                val mutualOk = driversRanking.take(topK).any { it.id == team.id }
                if (mutualOk) {
                    val isCounter = team.id == closestRivalTeamId &&
                        candidate.id in playerOfferedDriverIds
                    val salary = computeAiSalary(candidate, team, round)
                    signings += MarketSigning(
                        agent = candidate,
                        team = team,
                        salary = salary,
                        expiresYear = endingSeasonYear + DEFAULT_CONTRACT_YEARS,
                        playerOffer = false,
                        counterBid = isCounter,
                    )
                    freeAgents.remove(candidate)
                    team.openSeats -= 1
                    team.committedSalary += salary
                } else {
                    break
                }
            }
        }

        if (signings.isNotEmpty()) {
            applySignings(conn, signings, endingSeasonYear, round)
        }

        // Always consume the offers (whether they signed or were rejected)
        // and advance the round counter. Player can re-submit fresh offers
        // for the next round.
        conn.createStatement().use { it.execute("DELETE FROM driver_market_offers") }
        conn.prepareStatement(
            "UPDATE driver_market_state SET current_round = ?"
        ).use { stmt ->
            stmt.setInt(1, round)
            stmt.executeUpdate()
        }

        log.info(
            "Driver market round {}/{}: {} signings ({} player)",
            round, stateRow.totalRounds, signings.size,
            signings.count { it.playerOffer },
        )
        return signings.size
    }

    /**
     * Called on DRIVER_MARKET -> PRE_SEASON transition. Clears state so the
     * tables are empty until the next off-season's market initializes.
     */
    fun finalizeMarket(conn: Connection) {
        conn.createStatement().use { it.execute("DELETE FROM driver_market_offers") }
        conn.createStatement().use { it.execute("DELETE FROM driver_market_state") }
        log.info("Driver market: finalized and cleared")
    }

    // ------------------------------------------------------------------
    // Matching internals
    // ------------------------------------------------------------------

    private data class MarketAgent(
        val id: UUID,
        val name: String,
        val statPace: Int,
        val currentAge: Int,
        val peakAge: Int,
        val morale: Int,
        val previousTeamId: String?,
        val marketValueModifier: Double,
        val traitLoyalty: Double,
    )

    private data class MarketTeam(
        val id: String,
        val name: String,
        val prestige: Int,
        val seasonPoints: Int,
        val cashReserves: Long,
        val existingDriverSalary: Long,
        var openSeats: Int,
        var committedSalary: Long = 0,
    )

    private data class MarketSigning(
        val agent: MarketAgent,
        val team: MarketTeam,
        val salary: Long,
        val expiresYear: Int,
        val playerOffer: Boolean,
        val counterBid: Boolean = false,
    )

    private data class StoredOffer(
        val driverId: UUID,
        val salary: Long,
        val contractYears: Int,
    )

    /** A scored, salary-quoted candidate within one AI team's signing loop. */
    private data class Scored(
        val agent: MarketAgent,
        val teamScore: Double,
        val salary: Long,
    )

    private fun readFreeAgentsForMatching(conn: Connection): List<MarketAgent> {
        return conn.prepareStatement(
            """
            SELECT id, name, stat_pace, current_age, trait_peak_age, morale,
                   previous_team_id, trait_market_value_modifier, trait_loyalty
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
                                peakAge = rs.getInt("trait_peak_age"),
                                morale = rs.getInt("morale"),
                                previousTeamId = rs.getString("previous_team_id"),
                                marketValueModifier = rs.getDouble("trait_market_value_modifier"),
                                traitLoyalty = rs.getDouble("trait_loyalty"),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun readMatchingTeams(conn: Connection): List<MarketTeam> {
        return conn.prepareStatement(
            """
            SELECT t.id, t.name, t.prestige, t.season_points, t.cash_reserves,
                   GREATEST(0, $SEATS_PER_TEAM - COALESCE(driver_count.cnt, 0)) AS open_seats,
                   COALESCE(driver_count.sal, 0) AS existing_driver_salary
              FROM teams t
              LEFT JOIN (
                  SELECT current_racing_team_id,
                         COUNT(*) AS cnt,
                         COALESCE(SUM(current_salary), 0) AS sal
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
                                cashReserves = rs.getLong("cash_reserves"),
                                existingDriverSalary = rs.getLong("existing_driver_salary"),
                                openSeats = rs.getInt("open_seats"),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun readOffersForResolution(conn: Connection): List<StoredOffer> {
        return conn.prepareStatement(
            "SELECT driver_id, salary, contract_years FROM driver_market_offers"
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StoredOffer(
                                driverId = rs.getObject("driver_id", UUID::class.java),
                                salary = rs.getLong("salary"),
                                contractYears = rs.getInt("contract_years"),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun applySignings(
        conn: Connection,
        signings: List<MarketSigning>,
        endingSeasonYear: Int,
        round: Int,
    ) {
        conn.prepareStatement(
            """
            UPDATE drivers SET
              current_racing_team_id = ?,
              contract_expires_year = ?,
              contract_expires_round = ?,
              current_salary = ?,
              previous_team_id = NULL
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

        // Reuse the off_season_events log so the off-season report picks up
        // signings made via the new DRIVER_MARKET phase.
        conn.prepareStatement(
            """
            INSERT INTO off_season_events
              (season_year, event_type, subject_kind, subject_id, subject_name, message)
            VALUES (?, 'MARKET_SIGNING', 'DRIVER', ?, ?, ?)
            """.trimIndent()
        ).use { stmt ->
            signings.forEach { s ->
                val origin = when {
                    s.playerOffer -> "player offer"
                    s.counterBid -> "AI counter-bid"
                    else -> "AI matching"
                }
                val msg = "${s.agent.name} → ${s.team.name} (pace ${s.agent.statPace}, age ${s.agent.currentAge}): " +
                    "contract through ${s.expiresYear} at \$${s.salary}/yr [round $round, $origin]"
                stmt.setInt(1, endingSeasonYear)
                stmt.setString(2, s.agent.id.toString())
                stmt.setString(3, s.agent.name)
                stmt.setString(4, msg)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    // Same scoring shape as the AI-only version, kept private here so the
    // engine is self-contained.

    /**
     * Team's preference score for a candidate driver.
     *
     *   skill   : pace, the dominant term
     *   ageOpt  : symmetric penalty around 27 (peak racing years)
     *   morale  : slight bonus for happy drivers
     *   noise   : RNG spice for variety; magnitude tuned to be ~10% of skill
     *   counter : bump applied when this AI team is the closest rival to a
     *             player offer for this driver. Makes head-to-head feel
     *             competitive without overpowering pure skill scouting.
     */
    private fun teamScore(
        team: MarketTeam,
        agent: MarketAgent,
        rng: Random,
        isCounterBidTarget: Boolean = false,
    ): Double {
        val skill = (agent.statPace - 50).toDouble()
        val ageOpt = -kotlin.math.abs(agent.currentAge - 27).toDouble()
        val morale = (agent.morale - 50).toDouble()
        val noise = rng.nextDouble() * 10.0
        val counter = if (isCounterBidTarget) COUNTER_BID_BONUS else 0.0
        // Team-side loyalty: a team gets a bump for keeping a driver it
        // just released. Weighted by the driver's trait_loyalty — keeping
        // a 0.9-loyalty veteran feels valuable (continuity, sponsor fit);
        // keeping a 0.2-loyalty hothead barely registers.
        val loyalty = if (agent.previousTeamId == team.id) {
            TEAM_LOYALTY_BONUS * agent.traitLoyalty
        } else 0.0
        return skill * 0.7 + ageOpt * 0.5 + morale * 0.2 + noise + counter + loyalty
    }

    /**
     * Driver's preference score for a team.
     *
     *   prestige   : the dominant term — drivers chase top teams
     *   performance: recent results (just-ended season's points)
     *   loyalty    : bump when this is the team the driver just came from,
     *                weighted by the driver's trait_loyalty (0..1). A
     *                very-loyal driver feels the full pull of their old
     *                team; a fickle one barely cares.
     *   noise      : RNG spice
     */
    private fun driverScore(agent: MarketAgent, team: MarketTeam, rng: Random): Double {
        val prestige = (team.prestige - 50).toDouble()
        val performance = team.seasonPoints / 20.0
        val loyalty = if (agent.previousTeamId == team.id) {
            LOYALTY_BONUS * agent.traitLoyalty
        } else 0.0
        val noise = rng.nextDouble() * 5.0
        return prestige * 0.6 + performance * 0.3 + loyalty + noise
    }

    /**
     * Affordability gate for AI signings. A team's projected racing-driver
     * salary bill — existing roster + salaries already committed this market
     * + the prospective new salary — must not exceed [AI_SALARY_CASH_FRACTION]
     * of its current cash reserves. A team in the red (negative reserves)
     * can't sign anyone, which can legitimately leave AI seats empty. Player
     * offers are NOT gated here (the player can still overspend within
     * MIN/MAX_OFFER_SALARY) — by design, the player owns their own budget rope.
     */
    private fun MarketTeam.canAfford(salary: Long): Boolean {
        val ceiling = (cashReserves * AI_SALARY_CASH_FRACTION).toLong()
        return existingDriverSalary + committedSalary + salary <= ceiling
    }

    private fun computeAiSalary(agent: MarketAgent, team: MarketTeam, round: Int): Long {
        val base = when {
            agent.statPace >= 90 -> 20_000_000L
            agent.statPace >= 80 -> 8_000_000L
            agent.statPace >= 70 -> 2_000_000L
            else -> 500_000L
        }
        val prestigeFactor = team.prestige / 75.0
        // Per-driver "market value" multiplier. Generational talents
        // (~1.30) command a premium; journeymen (~0.90) sign for less.
        // From driver seed `trait_market_value_modifier`.
        val valueModifier = agent.marketValueModifier
        // Age decay: drivers past their peak get progressively cheaper.
        // 5% off per year past peak, floored at 50% of base. A 41-year-old
        // veteran whose stat_pace is still 88 (because pace decay rolls
        // slowly) doesn't command the same salary as an in-peak 28-year-old
        // at the same pace.
        val yearsPastPeak = (agent.currentAge - agent.peakAge).coerceAtLeast(0)
        val ageFactor = (1.0 - yearsPastPeak * AGE_DECAY_PER_YEAR).coerceAtLeast(MIN_AGE_FACTOR)
        // Per-(agent, team, round) RNG so two equal-prestige rival teams
        // don't quote the same exact number. Seeded locally rather than
        // drawn from the outer market RNG — otherwise this would shift
        // RNG state for subsequent scoring/signing draws and changing
        // the salary calculation could quietly alter signing outcomes.
        val noiseRng = Random(
            MARKET_SALT xor
                agent.id.leastSignificantBits xor
                team.id.hashCode().toLong() xor
                round.toLong()
        )
        val noiseFactor = 1.0 - SALARY_NOISE_PCT + noiseRng.nextDouble() * 2.0 * SALARY_NOISE_PCT
        return (base * prestigeFactor * valueModifier * ageFactor * noiseFactor).toLong()
    }

    /**
     * Top-K cap that loosens each round so leftover seats can fill. Also
     * clamps to the actual team count — if there are fewer teams than the
     * cap suggests, every driver is "in the top K" for any team.
     */
    private fun topKForRound(round: Int, teamCount: Int): Int {
        val raw = when (round) {
            1 -> 3
            2 -> 5
            else -> 10
        }
        return kotlin.math.min(raw, teamCount).coerceAtLeast(1)
    }

    // ------------------------------------------------------------------
    // State + view helpers
    // ------------------------------------------------------------------

    private data class StateRow(
        val seasonYear: Int,
        val currentRound: Int,
        val totalRounds: Int,
    )

    private fun readStateRow(conn: Connection): StateRow? {
        return conn.prepareStatement(
            "SELECT season_year, current_round, total_rounds FROM driver_market_state"
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                if (!rs.next()) null
                else StateRow(
                    seasonYear = rs.getInt("season_year"),
                    currentRound = rs.getInt("current_round"),
                    totalRounds = rs.getInt("total_rounds"),
                )
            }
        }
    }

    private fun readState(conn: Connection): MarketStateDto {
        val row = readStateRow(conn) ?: return MarketStateDto(active = false)
        val playerTeamId = readPlayerTeamId(conn)
        val openSeats = if (playerTeamId != null) {
            countOpenSeats(conn, playerTeamId)
        } else null
        return MarketStateDto(
            active = true,
            seasonYear = row.seasonYear,
            currentRound = row.currentRound,
            totalRounds = row.totalRounds,
            complete = row.currentRound >= row.totalRounds,
            playerTeamId = playerTeamId,
            playerTeamOpenSeats = openSeats,
        )
    }

    private fun readPlayerTeamId(conn: Connection): String? {
        return conn.prepareStatement("SELECT player_team_id FROM game").use { stmt ->
            stmt.executeQuery().use { rs ->
                if (!rs.next()) null else rs.getString("player_team_id")
            }
        }
    }

    private fun countOpenSeats(conn: Connection, teamId: String): Int {
        return conn.prepareStatement(
            """
            SELECT GREATEST(0, $SEATS_PER_TEAM - COUNT(*)) AS open_seats
              FROM drivers
             WHERE NOT retired
               AND current_racing_team_id = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, teamId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getInt("open_seats") else 0
            }
        }
    }

    private fun readFreeAgentsForView(conn: Connection, playerTeamId: String?): List<FreeAgentDto> {
        return conn.prepareStatement(
            """
            SELECT d.id, d.name, d.nationality, d.current_age,
                   d.stat_pace, d.stat_qualifying, d.stat_consistency, d.morale,
                   CASE WHEN o.driver_id IS NOT NULL THEN TRUE ELSE FALSE END AS has_offer
              FROM drivers d
              LEFT JOIN driver_market_offers o
                ON o.driver_id = d.id AND o.team_id = ?
             WHERE NOT d.retired
               AND d.current_racing_team_id IS NULL
               AND d.current_age BETWEEN 18 AND 50
             ORDER BY d.stat_pace DESC, d.name ASC
            """.trimIndent()
        ).use { stmt ->
            // Pass empty string if no player team — LEFT JOIN will never match,
            // has_offer is always FALSE which is correct.
            stmt.setString(1, playerTeamId ?: "")
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val pace = rs.getInt("stat_pace")
                        val recommended = when {
                            pace >= 90 -> 20_000_000L
                            pace >= 80 -> 8_000_000L
                            pace >= 70 -> 2_000_000L
                            else -> 500_000L
                        }
                        add(
                            FreeAgentDto(
                                driverId = rs.getObject("id", UUID::class.java).toString(),
                                name = rs.getString("name"),
                                nationality = rs.getString("nationality"),
                                age = rs.getInt("current_age"),
                                statPace = pace,
                                statQualifying = rs.getInt("stat_qualifying"),
                                statConsistency = rs.getInt("stat_consistency"),
                                morale = rs.getInt("morale"),
                                recommendedSalary = recommended,
                                playerHasOffer = rs.getBoolean("has_offer"),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun readOpenSeatTeams(conn: Connection): List<TeamWithSeatsDto> {
        return conn.prepareStatement(
            """
            SELECT t.id, t.name, t.prestige,
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
             ORDER BY t.prestige DESC, t.name ASC
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val open = rs.getInt("open_seats")
                        if (open > 0) {
                            add(
                                TeamWithSeatsDto(
                                    teamId = rs.getString("id"),
                                    teamName = rs.getString("name"),
                                    prestige = rs.getInt("prestige"),
                                    openSeats = open,
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun hasOfferFor(conn: Connection, driverId: UUID, teamId: String): Boolean {
        return conn.prepareStatement(
            "SELECT 1 FROM driver_market_offers WHERE driver_id = ? AND team_id = ?"
        ).use { stmt ->
            stmt.setObject(1, driverId)
            stmt.setString(2, teamId)
            stmt.executeQuery().use { rs -> rs.next() }
        }
    }

    companion object {
        const val MARKET_SALT = 0x6D61_726B_6574_5341L

        const val DEFAULT_TOTAL_ROUNDS = 3
        const val SEATS_PER_TEAM = 2

        const val DEFAULT_CONTRACT_YEARS = 2
        const val CONTRACT_END_ROUND = 24

        const val MIN_OFFER_SALARY = 500_000L
        const val MAX_OFFER_SALARY = 75_000_000L
        const val MIN_CONTRACT_YEARS = 1
        const val MAX_CONTRACT_YEARS = 5

        /**
         * Counter-bid bump applied to the closest-prestige AI team's score
         * for a driver the player has bid on. Sized to be comparable to the
         * `rng.nextDouble() * 10.0` noise term (slightly larger) so it can
         * flip close calls but doesn't dominate raw skill differences.
         */
        const val COUNTER_BID_BONUS = 15.0

        /**
         * Bonus added to a driver's preference score for the team they were
         * just released from, multiplied by `trait_loyalty` (0..1). Sized
         * slightly larger than driverScore's noise term (5.0) — enough to
         * break ties toward the prior team but small enough that a
         * meaningfully more prestigious rival still wins.
         */
        const val LOYALTY_BONUS = 8.0

        /**
         * Bonus added to a team's preference score for re-signing a driver
         * it just released, multiplied by `trait_loyalty` (0..1). Slightly
         * higher than driver-side because the team-side score has a larger
         * noise term (0..10) and a larger absolute skill range, so the
         * bonus needs more weight to register as a real factor.
         */
        const val TEAM_LOYALTY_BONUS = 10.0

        /**
         * AI salary decay per year past `trait_peak_age`. Applied in
         * `computeAiSalary` as a multiplicative factor. 5% per year matches
         * the feel of veteran-discount contracts without nuking the salary
         * of a still-quick 35-year-old.
         */
        const val AGE_DECAY_PER_YEAR = 0.05

        /**
         * Floor on the age-decay factor — even a 50-year-old whose pace is
         * somehow still high enough to be in the market won't sign for less
         * than half their base bracket. Past 10 years over peak the slope
         * flattens here.
         */
        const val MIN_AGE_FACTOR = 0.50

        /**
         * Half-width of the per-(agent, team, round) AI salary noise band.
         * 0.05 → final salary lands in [base × 0.95, base × 1.05]. Small
         * enough not to upset the brackets, big enough that two equal-
         * prestige rival teams quote noticeably different numbers.
         */
        const val SALARY_NOISE_PCT = 0.05

        /**
         * Cap on the fraction of an AI team's cash reserves that can be tied
         * up in total racing-driver salaries (existing roster + newly signed).
         * Gates AI signings so a cash-poor team can't keep buying drivers it
         * can't pay for. 0.60 leaves headroom for operating costs, personnel,
         * and R&D-to-come. With seeded year-1 cash (~$100M–200M) this rarely
         * bites, but it starts mattering once multi-year deficits (e.g. a
         * back-marker bleeding sponsors) erode reserves. Player offers are
         * exempt. Tuning knob.
         */
        const val AI_SALARY_CASH_FRACTION = 0.60
    }
}
