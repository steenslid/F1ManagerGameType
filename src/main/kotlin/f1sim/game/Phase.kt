package f1sim.game

/**
 * The set of phases the game can be in. The `current_phase` column of the
 * `game` table is a TEXT with a CHECK constraint accepting exactly these names.
 *
 * RACE_WEEKEND from the design doc is flattened into its sub-phases here
 * (PRACTICE, QUALIFYING, ..., POST_RACE) so the DB needs to store only one
 * string and transitions stay as a single `when`.
 *
 * SPRINT_QUALIFYING and SPRINT are entered for races where the calendar's
 * session_format is SPRINT. Standard weekends skip them.
 *
 * DRIVER_MARKET runs between OFF_SEASON and PRE_SEASON. Each advance from
 * DRIVER_MARKET runs one matching round, until `total_rounds` (default 3)
 * have completed — then it exits to PRE_SEASON. The player can submit
 * offers via /api/market/driver/offer between rounds.
 */
enum class Phase {
    OFF_SEASON,
    DRIVER_MARKET,
    PRE_SEASON,
    PRACTICE,
    QUALIFYING,
    SPRINT_QUALIFYING,
    SPRINT,
    RACE,
    POST_RACE,
    BETWEEN_ROUNDS,
    END_OF_SEASON,
}

/**
 * The minimum state needed for phase transitions. Stored in the `game` table
 * across (current_season_year, current_round, current_phase). Other state on
 * the game row (manager name, difficulty, etc.) is metadata, not used by the
 * machine.
 */
data class GameState(
    val year: Int,
    val round: Int,
    val phase: Phase,
)
