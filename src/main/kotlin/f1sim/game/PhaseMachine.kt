package f1sim.game

/**
 * Pure phase-transition logic. Given a state and the season length, returns
 * the state after one advance step.
 *
 * Standard race weekend flow (no sprint yet):
 *   PRE_SEASON -> PRACTICE -> QUALIFYING -> RACE -> POST_RACE
 *   POST_RACE -> BETWEEN_ROUNDS (if round < roundsPerSeason)
 *   POST_RACE -> END_OF_SEASON (if round == roundsPerSeason)
 *   BETWEEN_ROUNDS -> PRACTICE (round + 1)
 *
 * Season boundary:
 *   END_OF_SEASON -> OFF_SEASON (year + 1, round = 0)
 *   OFF_SEASON -> PRE_SEASON (no year change)
 *
 * Sprint sub-flow (currently unreachable; reserved for when sprint detection
 * exists):
 *   PRACTICE -> SPRINT_QUALIFYING -> SPRINT -> QUALIFYING -> RACE
 *
 * Year increments on END_OF_SEASON -> OFF_SEASON. `current_season_year` thus
 * means "the year of the season we're currently in or about to play".
 *
 * Pure: no DB, no logging, no side effects. The caller (GameService) is
 * responsible for supplying roundsPerSeason, which it queries from the
 * `races` table.
 */
object PhaseMachine {

    fun next(state: GameState, roundsPerSeason: Int): GameState = when (state.phase) {
        Phase.OFF_SEASON -> state.copy(phase = Phase.PRE_SEASON)

        Phase.PRE_SEASON -> state.copy(phase = Phase.PRACTICE, round = 1)

        // Standard flow: practice -> qualifying. Sprint detection would
        // branch here to SPRINT_QUALIFYING instead.
        Phase.PRACTICE -> state.copy(phase = Phase.QUALIFYING)

        Phase.QUALIFYING -> state.copy(phase = Phase.RACE)

        Phase.SPRINT_QUALIFYING -> state.copy(phase = Phase.SPRINT)
        Phase.SPRINT -> state.copy(phase = Phase.QUALIFYING)

        Phase.RACE -> state.copy(phase = Phase.POST_RACE)

        Phase.POST_RACE -> if (state.round < roundsPerSeason) {
            state.copy(phase = Phase.BETWEEN_ROUNDS)
        } else {
            state.copy(phase = Phase.END_OF_SEASON)
        }

        Phase.BETWEEN_ROUNDS -> state.copy(phase = Phase.PRACTICE, round = state.round + 1)

        Phase.END_OF_SEASON -> state.copy(
            phase = Phase.OFF_SEASON,
            year = state.year + 1,
            round = 0,
        )
    }
}
