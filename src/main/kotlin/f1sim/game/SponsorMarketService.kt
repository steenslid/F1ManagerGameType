package f1sim.game

import f1sim.db.Database
import f1sim.http.NotFoundException
import f1sim.save.SaveSession
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Player-facing sponsor market: choose and sign new sponsors, renew or cancel
 * existing deals. Replaces auto-renewal *for the player only* — the player's
 * lapsed deals are skipped by [OffSeasonService.renewSponsors] so the player
 * owns their portfolio; AI teams still auto-renew.
 *
 * A sponsor will deal with a team only if the team's prestige clears the
 * sponsor's standard (derived from `prestige_preference`), and the value it
 * will pay scales with how comfortably the team clears that bar, bounded by
 * the sponsor's `budget_min`..`budget_max`. The player can ask for anything up
 * to that ceiling.
 *
 * Slots: at most [MAX_TOTAL_DEALS] active deals and one title deal at a time.
 * New/renewed deals take financial effect from the next PRE_SEASON revenue
 * tick (income is set at season boundaries).
 *
 * No RNG — acceptance is a deterministic function of prestige + budget.
 */
class SponsorMarketService(private val db: Database) {

    private val log = LoggerFactory.getLogger(SponsorMarketService::class.java)

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    @Serializable
    data class SponsorMarketDto(
        val playerTeamId: String? = null,
        val teamPrestige: Int? = null,
        val seasonYear: Int,
        val maxTotalDeals: Int = MAX_TOTAL_DEALS,
        val dealsUsed: Int = 0,
        val titleUsed: Boolean = false,
        val currentDeals: List<DealDto> = emptyList(),
        val available: List<AvailableSponsorDto> = emptyList(),
    )

    @Serializable
    data class DealDto(
        val id: Long,
        val sponsorId: String,
        val sponsorName: String,
        val tier: String,
        val annualValue: Long,
        val startYear: Int,
        val endYear: Int,
        val isTitle: Boolean,
        val expired: Boolean,
        // What this sponsor would now pay the team — caps a renewal offer.
        val maxAnnualValue: Long = 0,
    )

    @Serializable
    data class AvailableSponsorDto(
        val sponsorId: String,
        val name: String,
        val tier: String,
        val industry: String,
        val prestige: Int,
        val willDeal: Boolean,
        val minAnnualValue: Long,
        val maxAnnualValue: Long,
        val canBeTitle: Boolean,
    )

    @Serializable
    data class SignRequest(
        val sponsorId: String,
        val annualValue: Long,
        val termYears: Int = DEFAULT_TERM_YEARS,
        val title: Boolean = false,
    )

    @Serializable
    data class RenewRequest(
        val dealId: Long,
        val annualValue: Long,
        val termYears: Int = DEFAULT_TERM_YEARS,
    )

    @Serializable
    data class CancelRequest(val dealId: Long)

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    fun view(): SponsorMarketDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn -> readMarket(conn) }
    }

    fun sign(req: SignRequest): SponsorMarketDto {
        SaveSession.requireLoaded()
        require(req.termYears in MIN_TERM_YEARS..MAX_TERM_YEARS) {
            "termYears must be between $MIN_TERM_YEARS and $MAX_TERM_YEARS"
        }
        return db.withConnection { conn ->
            conn.autoCommit = false
            try {
                val ctx = requirePlayerContext(conn)
                val sponsor = readSponsor(conn, req.sponsorId)
                    ?: throw NotFoundException("No sponsor with id ${req.sponsorId}")

                // No duplicate active deal with the same sponsor.
                check(!hasActiveDealWith(conn, ctx.teamId, sponsor.id, ctx.seasonYear)) {
                    "${sponsor.name} already sponsors your team"
                }
                val (dealsUsed, titleUsed) = readSlotUsage(conn, ctx.teamId, ctx.seasonYear)
                check(dealsUsed < MAX_TOTAL_DEALS) {
                    "All $MAX_TOTAL_DEALS sponsorship slots are full — cancel a deal first"
                }
                if (req.title) {
                    check(!titleUsed) { "You already have a title sponsor" }
                    check(sponsor.tier == "TITLE" || sponsor.tier == "PRIMARY") {
                        "${sponsor.name} (${sponsor.tier}) isn't big enough to be a title sponsor"
                    }
                }

                val offer = evaluate(sponsor, ctx.prestige)
                check(offer.willDeal) {
                    "${sponsor.name} won't back a team of this prestige"
                }
                require(req.annualValue in MIN_DEAL_VALUE..offer.maxValue) {
                    "${sponsor.name} will pay between $MIN_DEAL_VALUE and ${offer.maxValue} per year"
                }

                conn.prepareStatement(
                    """
                    INSERT INTO team_sponsorships
                      (team_id, sponsor_id, start_year, end_year, annual_value, is_title)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, ctx.teamId)
                    stmt.setString(2, sponsor.id)
                    stmt.setInt(3, ctx.seasonYear)
                    stmt.setInt(4, ctx.seasonYear + req.termYears - 1)
                    stmt.setLong(5, req.annualValue)
                    stmt.setBoolean(6, req.title)
                    stmt.executeUpdate()
                }
                conn.commit()
                log.info("Sponsor signed: {} -> {} at {}/yr", sponsor.id, ctx.teamId, req.annualValue)
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }
            readMarket(conn)
        }
    }

    fun renew(req: RenewRequest): SponsorMarketDto {
        SaveSession.requireLoaded()
        require(req.termYears in MIN_TERM_YEARS..MAX_TERM_YEARS) {
            "termYears must be between $MIN_TERM_YEARS and $MAX_TERM_YEARS"
        }
        return db.withConnection { conn ->
            conn.autoCommit = false
            try {
                val ctx = requirePlayerContext(conn)
                val deal = readPlayerDeal(conn, req.dealId, ctx.teamId)
                    ?: throw NotFoundException("No sponsorship deal ${req.dealId} on your team")
                val sponsor = readSponsor(conn, deal.sponsorId)
                    ?: throw NotFoundException("Sponsor ${deal.sponsorId} no longer exists")

                val offer = evaluate(sponsor, ctx.prestige)
                check(offer.willDeal) {
                    "${sponsor.name} no longer wants to back a team of this prestige"
                }
                require(req.annualValue in MIN_DEAL_VALUE..offer.maxValue) {
                    "${sponsor.name} will renew at between $MIN_DEAL_VALUE and ${offer.maxValue} per year"
                }

                conn.prepareStatement(
                    """
                    UPDATE team_sponsorships
                       SET start_year = ?, end_year = ?, annual_value = ?
                     WHERE id = ?
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setInt(1, ctx.seasonYear)
                    stmt.setInt(2, ctx.seasonYear + req.termYears - 1)
                    stmt.setLong(3, req.annualValue)
                    stmt.setLong(4, req.dealId)
                    stmt.executeUpdate()
                }
                conn.commit()
                log.info("Sponsor renewed: deal {} at {}/yr through {}", req.dealId, req.annualValue, ctx.seasonYear + req.termYears - 1)
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }
            readMarket(conn)
        }
    }

    fun cancel(req: CancelRequest): SponsorMarketDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn ->
            val ctx = requirePlayerContext(conn)
            val deleted = conn.prepareStatement(
                "DELETE FROM team_sponsorships WHERE id = ? AND team_id = ?"
            ).use { stmt ->
                stmt.setLong(1, req.dealId)
                stmt.setString(2, ctx.teamId)
                stmt.executeUpdate()
            }
            if (deleted == 0) throw NotFoundException("No sponsorship deal ${req.dealId} on your team")
            readMarket(conn)
        }
    }

    // ------------------------------------------------------------------
    // Acceptance model
    // ------------------------------------------------------------------

    private data class Sponsor(
        val id: String,
        val name: String,
        val tier: String,
        val industry: String,
        val prestige: Int,
        val prestigePreference: Double,
        val budgetMin: Long,
        val budgetMax: Long,
    )

    private data class Offer(val willDeal: Boolean, val maxValue: Long)

    /**
     * What this sponsor will pay this team. A sponsor sets a prestige bar from
     * its `prestige_preference`; a team that clears it comfortably can command
     * the sponsor's full budget, one that scrapes in gets near the floor, one
     * below the bar (minus tolerance) is turned away.
     */
    private fun evaluate(sponsor: Sponsor, teamPrestige: Int): Offer {
        val reqPrestige = SPONSOR_REQ_BASE + sponsor.prestigePreference * SPONSOR_REQ_SPAN
        val willDeal = teamPrestige >= reqPrestige - SPONSOR_FIT_TOLERANCE
        if (!willDeal) return Offer(willDeal = false, maxValue = 0L)
        val fit = ((teamPrestige - (reqPrestige - SPONSOR_FIT_TOLERANCE)) / SPONSOR_FIT_TOLERANCE)
            .coerceIn(0.0, 1.0)
        val maxValue = (sponsor.budgetMin + (sponsor.budgetMax - sponsor.budgetMin) * fit).toLong()
        return Offer(willDeal = true, maxValue = maxValue)
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    private data class PlayerContext(val teamId: String, val prestige: Int, val seasonYear: Int)

    private fun requirePlayerContext(conn: Connection): PlayerContext =
        readPlayerContext(conn) ?: error("No player team selected — cannot manage sponsors")

    private fun readPlayerContext(conn: Connection): PlayerContext? {
        return conn.prepareStatement(
            """
            SELECT g.current_season_year, g.player_team_id, t.prestige
              FROM game g
              LEFT JOIN teams t ON t.id = g.player_team_id
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                val teamId = rs.getString("player_team_id") ?: return null
                PlayerContext(
                    teamId = teamId,
                    prestige = rs.getInt("prestige"),
                    seasonYear = rs.getInt("current_season_year"),
                )
            }
        }
    }

    private fun readMarket(conn: Connection): SponsorMarketDto {
        val ctx = readPlayerContext(conn)
        val seasonYear = ctx?.seasonYear ?: readSeasonYear(conn)
        if (ctx == null) return SponsorMarketDto(seasonYear = seasonYear)

        val deals = readPlayerDeals(conn, ctx.teamId, seasonYear, ctx.prestige)
        val activeSponsorIds = deals.filter { !it.expired }.map { it.sponsorId }.toSet()
        val dealsUsed = deals.count { !it.expired }
        val titleUsed = deals.any { !it.expired && it.isTitle }

        val available = readAllSponsors(conn)
            .filter { it.id !in activeSponsorIds }
            .map { s ->
                val offer = evaluate(s, ctx.prestige)
                AvailableSponsorDto(
                    sponsorId = s.id,
                    name = s.name,
                    tier = s.tier,
                    industry = s.industry,
                    prestige = s.prestige,
                    willDeal = offer.willDeal,
                    minAnnualValue = if (offer.willDeal) MIN_DEAL_VALUE.coerceAtMost(offer.maxValue) else 0L,
                    maxAnnualValue = offer.maxValue,
                    canBeTitle = s.tier == "TITLE" || s.tier == "PRIMARY",
                )
            }
            .sortedWith(compareByDescending<AvailableSponsorDto> { it.willDeal }.thenByDescending { it.maxAnnualValue })

        return SponsorMarketDto(
            playerTeamId = ctx.teamId,
            teamPrestige = ctx.prestige,
            seasonYear = seasonYear,
            dealsUsed = dealsUsed,
            titleUsed = titleUsed,
            currentDeals = deals,
            available = available,
        )
    }

    private fun readPlayerDeals(conn: Connection, teamId: String, seasonYear: Int, teamPrestige: Int): List<DealDto> {
        return conn.prepareStatement(
            """
            SELECT ts.id, ts.sponsor_id, s.name AS sponsor_name, s.tier AS tier,
                   s.prestige_preference, s.budget_min, s.budget_max,
                   ts.start_year, ts.end_year, ts.annual_value, ts.is_title
              FROM team_sponsorships ts
              JOIN sponsors s ON s.id = ts.sponsor_id
             WHERE ts.team_id = ?
             ORDER BY ts.is_title DESC, ts.annual_value DESC
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, teamId)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val endYear = rs.getInt("end_year")
                        val sponsor = Sponsor(
                            id = rs.getString("sponsor_id"),
                            name = rs.getString("sponsor_name"),
                            tier = rs.getString("tier"),
                            industry = "",
                            prestige = 0,
                            prestigePreference = rs.getDouble("prestige_preference"),
                            budgetMin = rs.getLong("budget_min"),
                            budgetMax = rs.getLong("budget_max"),
                        )
                        add(
                            DealDto(
                                id = rs.getLong("id"),
                                sponsorId = rs.getString("sponsor_id"),
                                sponsorName = rs.getString("sponsor_name"),
                                tier = rs.getString("tier"),
                                annualValue = rs.getLong("annual_value"),
                                startYear = rs.getInt("start_year"),
                                endYear = endYear,
                                isTitle = rs.getBoolean("is_title"),
                                expired = endYear < seasonYear,
                                maxAnnualValue = evaluate(sponsor, teamPrestige).maxValue,
                            )
                        )
                    }
                }
            }
        }
    }

    private fun readPlayerDeal(conn: Connection, dealId: Long, teamId: String): DealDto? {
        return conn.prepareStatement(
            """
            SELECT ts.id, ts.sponsor_id, s.name AS sponsor_name, s.tier AS tier,
                   ts.start_year, ts.end_year, ts.annual_value, ts.is_title
              FROM team_sponsorships ts
              JOIN sponsors s ON s.id = ts.sponsor_id
             WHERE ts.id = ? AND ts.team_id = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setLong(1, dealId)
            stmt.setString(2, teamId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) null
                else DealDto(
                    id = rs.getLong("id"),
                    sponsorId = rs.getString("sponsor_id"),
                    sponsorName = rs.getString("sponsor_name"),
                    tier = rs.getString("tier"),
                    annualValue = rs.getLong("annual_value"),
                    startYear = rs.getInt("start_year"),
                    endYear = rs.getInt("end_year"),
                    isTitle = rs.getBoolean("is_title"),
                    expired = false,
                )
            }
        }
    }

    private fun readAllSponsors(conn: Connection): List<Sponsor> {
        return conn.prepareStatement(
            """
            SELECT id, name, tier, industry, prestige, prestige_preference,
                   budget_min, budget_max
              FROM sponsors
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(mapSponsor(rs))
                }
            }
        }
    }

    private fun readSponsor(conn: Connection, sponsorId: String): Sponsor? {
        return conn.prepareStatement(
            """
            SELECT id, name, tier, industry, prestige, prestige_preference,
                   budget_min, budget_max
              FROM sponsors WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, sponsorId)
            stmt.executeQuery().use { rs -> if (!rs.next()) null else mapSponsor(rs) }
        }
    }

    private fun mapSponsor(rs: java.sql.ResultSet) = Sponsor(
        id = rs.getString("id"),
        name = rs.getString("name"),
        tier = rs.getString("tier"),
        industry = rs.getString("industry"),
        prestige = rs.getInt("prestige"),
        prestigePreference = rs.getDouble("prestige_preference"),
        budgetMin = rs.getLong("budget_min"),
        budgetMax = rs.getLong("budget_max"),
    )

    private fun hasActiveDealWith(conn: Connection, teamId: String, sponsorId: String, seasonYear: Int): Boolean {
        return conn.prepareStatement(
            """
            SELECT 1 FROM team_sponsorships
             WHERE team_id = ? AND sponsor_id = ? AND end_year >= ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, teamId)
            stmt.setString(2, sponsorId)
            stmt.setInt(3, seasonYear)
            stmt.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun readSlotUsage(conn: Connection, teamId: String, seasonYear: Int): Pair<Int, Boolean> {
        return conn.prepareStatement(
            """
            SELECT COUNT(*) AS cnt,
                   COALESCE(BOOL_OR(is_title), FALSE) AS title_used
              FROM team_sponsorships
             WHERE team_id = ? AND end_year >= ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, teamId)
            stmt.setInt(2, seasonYear)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) 0 to false
                else rs.getInt("cnt") to rs.getBoolean("title_used")
            }
        }
    }

    private fun readSeasonYear(conn: Connection): Int {
        return conn.prepareStatement("SELECT current_season_year FROM game").use { stmt ->
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private companion object {
        const val MAX_TOTAL_DEALS = 5
        const val DEFAULT_TERM_YEARS = 2
        const val MIN_TERM_YEARS = 1
        const val MAX_TERM_YEARS = 5
        const val MIN_DEAL_VALUE = 1_000_000L

        // Prestige bar a sponsor sets from its prestige_preference (0..1):
        //   reqPrestige = BASE + preference * SPAN   (0.30 -> ~46, 0.95 -> ~82)
        // A team within FIT_TOLERANCE below the bar can still deal, at a
        // discount; further below, the sponsor walks.
        const val SPONSOR_REQ_BASE = 30.0
        const val SPONSOR_REQ_SPAN = 55.0
        const val SPONSOR_FIT_TOLERANCE = 12.0
    }
}
