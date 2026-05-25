package f1sim.sim

import java.util.UUID
import kotlin.math.exp
import kotlin.math.max
import kotlin.random.Random

/**
 * Pure race-weekend simulation. No DB, no logging, no side effects.
 *
 * Stat fields are Double — multipliers (practice SETUP, strategy bonuses,
 * future car performance modifiers) can move them by fractional amounts
 * without losing the change to integer truncation at the boundary. DB
 * stays Int; GameService casts when building entrants.
 *
 * v1 models:
 *   Qualifying: stat_qualifying + gaussian noise. Sort, assign grid.
 *               Reused for sprint qualifying — same mechanics, separate caller
 *               and salt give a different grid.
 *   Race:       stat_pace + grid_bonus + strategy_pace_bonus
 *               + noise scaled by (consistency, strategy variance).
 *               DNFs roll first, finishers sort by score.
 *               Fastest lap: weighted pick from finishers in top 5.
 *               Points: FIA table; +1 for fastest lap when rules allow
 *               and driver is in top 10.
 *   Sprint:     like Race but no strategy effects (no mandatory stops),
 *               no fastest lap, halved DNF probability (shorter race),
 *               points 8-7-6-5-4-3-2-1 for top 8.
 */
object RaceSim {

    // -- Qualifying ----------------------------------------------------

    private const val QUALIFYING_SIGMA = 8.0

    data class QualifyingEntrant(
        val driverId: UUID,
        val teamId: String,
        val statQualifying: Double,
    )

    data class QualifyingResult(
        val driverId: UUID,
        val teamId: String,
        val gridPosition: Int,
        val pole: Boolean,
    )

    fun simulateQualifying(
        entrants: List<QualifyingEntrant>,
        rng: Random,
    ): List<QualifyingResult> {
        require(entrants.isNotEmpty()) { "qualifying needs at least one entrant" }

        val scored = entrants.map { e ->
            val noise = rng.gaussian() * QUALIFYING_SIGMA
            e to (e.statQualifying + noise)
        }.sortedByDescending { (_, score) -> score }

        return scored.mapIndexed { index, (entrant, _) ->
            val grid = index + 1
            QualifyingResult(
                driverId = entrant.driverId,
                teamId = entrant.teamId,
                gridPosition = grid,
                pole = grid == 1,
            )
        }
    }

    // -- Race ----------------------------------------------------------

    private const val DNF_BASE_PROB = 0.04
    private const val RACE_PACE_SIGMA = 6.0

    /** FIA points: 25,18,15,12,10,8,6,4,2,1 for top 10. */
    private val POINTS_BY_FINISHING_POSITION = listOf(25, 18, 15, 12, 10, 8, 6, 4, 2, 1)

    private const val FASTEST_LAP_BONUS = 1

    private val DNF_CAUSES = listOf(
        "COLLISION", "MECHANICAL", "HYDRAULICS",
        "ENGINE_FAILURE", "BRAKES", "GEARBOX",
    )

    /**
     * Strategy archetype → race effects. paceBonus added to score directly.
     * sigmaMultiplier scales the noise term: <1 = more consistent (predictable),
     * >1 = more variance (bigger swings).
     *
     * Magnitudes deliberately small. Strategy is a tiebreaker the player
     * controls; not a primary decider.
     *
     * Loose interpretation:
     *   M_H   — Medium then Hard. Safe baseline. Slight consistency edge.
     *   S_H   — Soft then Hard. Front-loaded pace, mild variance.
     *   S_M_M — Soft then two Mediums. Three-stopper. Higher variance.
     *   M_M_H — Medium-Medium-Hard. Three-stopper, neutral.
     *   S_S_H — Soft-Soft-Hard. Aggressive. Highest pace bonus, highest variance.
     */
    private data class StrategyEffect(val paceBonus: Double, val sigmaMultiplier: Double)

    private val strategyEffects: Map<String, StrategyEffect> = mapOf(
        "M_H"   to StrategyEffect(paceBonus =  0.0, sigmaMultiplier = 0.9),
        "S_H"   to StrategyEffect(paceBonus =  1.0, sigmaMultiplier = 1.0),
        "S_M_M" to StrategyEffect(paceBonus =  0.5, sigmaMultiplier = 1.1),
        "M_M_H" to StrategyEffect(paceBonus =  0.0, sigmaMultiplier = 1.0),
        "S_S_H" to StrategyEffect(paceBonus =  2.0, sigmaMultiplier = 1.2),
    )

    private val defaultStrategyEffect = StrategyEffect(paceBonus = 0.0, sigmaMultiplier = 1.0)

    data class RaceEntrant(
        val driverId: UUID,
        val teamId: String,
        val gridPosition: Int,
        val statPace: Double,
        val statConsistency: Int,
        val strategyArchetype: String?,
    )

    data class RaceResult(
        val driverId: UUID,
        val teamId: String,
        val finishingPosition: Int?,
        val points: Double,
        val status: String,
        val fastestLap: Boolean,
        val dnfCause: String?,
    )

    /**
     * @param fastestLapPointEnabled If true, fastest lap awards +1 to a
     *   driver finishing in the top 10.
     */
    fun simulateRace(
        entrants: List<RaceEntrant>,
        rng: Random,
        fastestLapPointEnabled: Boolean,
    ): List<RaceResult> {
        require(entrants.isNotEmpty()) { "race needs at least one entrant" }

        // 1. DNF roll per driver.
        val (finishers, dnfs) = entrants.partition { e ->
            val consistencyFactor = (50.0 / max(e.statConsistency, 25))
            val dnfProb = DNF_BASE_PROB * consistencyFactor
            rng.nextDouble() >= dnfProb
        }

        // 2. Score finishers and sort to a finishing order.
        val finishersScored = finishers.map { e ->
            val strategy = strategyEffects[e.strategyArchetype] ?: defaultStrategyEffect
            val gridBonus = gridBonus(e.gridPosition)
            val baseSigma = RACE_PACE_SIGMA * (50.0 / max(e.statConsistency, 25))
            val sigma = baseSigma * strategy.sigmaMultiplier
            val noise = rng.gaussian() * sigma
            val score = e.statPace + gridBonus + strategy.paceBonus + noise
            e to score
        }.sortedByDescending { (_, score) -> score }

        val finishingOrder = finishersScored.mapIndexed { index, (e, _) -> e to (index + 1) }

        // 3. Fastest lap: weighted pick from top 5 finishers.
        val topFiveFinishers = finishingOrder.take(5).map { it.first }
        val fastestLapDriver: UUID? = if (topFiveFinishers.isNotEmpty()) {
            weightedPick(topFiveFinishers, rng) { it.statPace }.driverId
        } else null

        // 4. Assemble results.
        val finisherResults = finishingOrder.map { (e, pos) ->
            val basePoints = if (pos <= POINTS_BY_FINISHING_POSITION.size) {
                POINTS_BY_FINISHING_POSITION[pos - 1].toDouble()
            } else 0.0
            val flBonus = if (
                fastestLapPointEnabled &&
                e.driverId == fastestLapDriver &&
                pos <= 10
            ) {
                FASTEST_LAP_BONUS.toDouble()
            } else 0.0
            RaceResult(
                driverId = e.driverId,
                teamId = e.teamId,
                finishingPosition = pos,
                points = basePoints + flBonus,
                status = "FINISHED",
                fastestLap = e.driverId == fastestLapDriver,
                dnfCause = null,
            )
        }

        val dnfResults = dnfs.map { e ->
            RaceResult(
                driverId = e.driverId,
                teamId = e.teamId,
                finishingPosition = null,
                points = 0.0,
                status = "DNF",
                fastestLap = false,
                dnfCause = DNF_CAUSES[rng.nextInt(DNF_CAUSES.size)],
            )
        }

        return finisherResults + dnfResults
    }

    // -- Sprint --------------------------------------------------------

    /** Halved compared to a full race — sprints are ~100km, ~1/3 of a GP. */
    private const val SPRINT_DNF_BASE_PROB = 0.02

    /** Sprint points: 8-7-6-5-4-3-2-1 for top 8. */
    private val SPRINT_POINTS = listOf(8, 7, 6, 5, 4, 3, 2, 1)

    data class SprintEntrant(
        val driverId: UUID,
        val teamId: String,
        val gridPosition: Int,
        val statPace: Double,
        val statConsistency: Int,
    )

    data class SprintResult(
        val driverId: UUID,
        val teamId: String,
        val finishingPosition: Int?,
        val points: Double,
        val status: String,
        val dnfCause: String?,
    )

    /**
     * Sprint race. Differences from [simulateRace]:
     *   - DNF probability halved (shorter race, less time for things to break)
     *   - No strategy archetype effects (sprints have no mandatory stops)
     *   - No fastest-lap mechanic
     *   - Points table is 8-7-6-5-4-3-2-1 top 8
     *
     * Same grid bonus, same gaussian noise scaled by consistency.
     */
    fun simulateSprint(
        entrants: List<SprintEntrant>,
        rng: Random,
    ): List<SprintResult> {
        require(entrants.isNotEmpty()) { "sprint needs at least one entrant" }

        // 1. DNF roll, halved base prob.
        val (finishers, dnfs) = entrants.partition { e ->
            val consistencyFactor = (50.0 / max(e.statConsistency, 25))
            val dnfProb = SPRINT_DNF_BASE_PROB * consistencyFactor
            rng.nextDouble() >= dnfProb
        }

        // 2. Score finishers — same as race but no strategy term.
        val finishersScored = finishers.map { e ->
            val gridBonus = gridBonus(e.gridPosition)
            val sigma = RACE_PACE_SIGMA * (50.0 / max(e.statConsistency, 25))
            val noise = rng.gaussian() * sigma
            val score = e.statPace + gridBonus + noise
            e to score
        }.sortedByDescending { (_, score) -> score }

        val finishingOrder = finishersScored.mapIndexed { index, (e, _) -> e to (index + 1) }

        // 3. Assemble results — no fastest lap, sprint points table.
        val finisherResults = finishingOrder.map { (e, pos) ->
            val points = if (pos <= SPRINT_POINTS.size) {
                SPRINT_POINTS[pos - 1].toDouble()
            } else 0.0
            SprintResult(
                driverId = e.driverId,
                teamId = e.teamId,
                finishingPosition = pos,
                points = points,
                status = "FINISHED",
                dnfCause = null,
            )
        }

        val dnfResults = dnfs.map { e ->
            SprintResult(
                driverId = e.driverId,
                teamId = e.teamId,
                finishingPosition = null,
                points = 0.0,
                status = "DNF",
                dnfCause = DNF_CAUSES[rng.nextInt(DNF_CAUSES.size)],
            )
        }

        return finisherResults + dnfResults
    }

    // -- Shared helpers ------------------------------------------------

    private fun gridBonus(gridPosition: Int): Double {
        if (gridPosition < 1) return 0.0
        return 6.0 * exp(-0.25 * (gridPosition - 1))
    }

    private fun <T> weightedPick(items: List<T>, rng: Random, weight: (T) -> Double): T {
        val total = items.sumOf(weight)
        if (total <= 0.0) return items[rng.nextInt(items.size)]
        var pick = rng.nextDouble() * total
        for (item in items) {
            pick -= weight(item)
            if (pick <= 0.0) return item
        }
        return items.last()
    }
}

private fun Random.gaussian(): Double {
    var u1: Double
    do { u1 = nextDouble() } while (u1 == 0.0)
    val u2 = nextDouble()
    return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) *
        kotlin.math.cos(2.0 * kotlin.math.PI * u2)
}
