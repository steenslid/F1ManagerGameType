package f1sim.sim

import java.util.UUID
import kotlin.math.exp
import kotlin.math.max
import kotlin.random.Random

/**
 * Pure race-weekend simulation. No DB, no logging, no side effects.
 *
 * GameService owns orchestration: reads from the DB, builds value types,
 * calls the sim, writes the results back. Functions take everything they
 * need as parameters so they're testable in isolation.
 *
 * v1 models:
 *   Qualifying: stat_qualifying + gaussian noise. Sort, assign grid.
 *   Race:       stat_pace + grid_bonus + consistency-weighted noise.
 *               DNFs roll first, finishers sort by score.
 *               Fastest lap: weighted pick from finishers in top 5.
 *               Points: FIA table; +1 for fastest lap if rules allow
 *               (regulation_eras.fastest_lap_point) and driver is in top 10.
 */
object RaceSim {

    // -- Qualifying ----------------------------------------------------

    private const val QUALIFYING_SIGMA = 8.0

    data class QualifyingEntrant(
        val driverId: UUID,
        val teamId: String,
        val statQualifying: Int,
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

    /** Bonus point awarded for the fastest lap if rules allow + driver in top 10. */
    private const val FASTEST_LAP_BONUS = 1

    private val DNF_CAUSES = listOf(
        "COLLISION", "MECHANICAL", "HYDRAULICS",
        "ENGINE_FAILURE", "BRAKES", "GEARBOX",
    )

    data class RaceEntrant(
        val driverId: UUID,
        val teamId: String,
        val gridPosition: Int,
        val statPace: Int,
        val statConsistency: Int,
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
     * @param fastestLapPointEnabled If true (pre-2025 rules / custom era),
     *   the fastest lap awards +1 to a driver finishing in the top 10.
     *   Under 2025+ FIA regulations this is false and the fastest-lap flag
     *   is recorded as a stat without points.
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
            val gridBonus = gridBonus(e.gridPosition)
            val sigma = RACE_PACE_SIGMA * (50.0 / max(e.statConsistency, 25))
            val noise = rng.gaussian() * sigma
            val score = e.statPace + gridBonus + noise
            e to score
        }.sortedByDescending { (_, score) -> score }

        val finishingOrder = finishersScored.mapIndexed { index, (e, _) -> e to (index + 1) }

        // 3. Fastest lap: weighted pick from top 5 finishers.
        val topFiveFinishers = finishingOrder.take(5).map { it.first }
        val fastestLapDriver: UUID? = if (topFiveFinishers.isNotEmpty()) {
            weightedPick(topFiveFinishers, rng) { it.statPace.toDouble() }.driverId
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
