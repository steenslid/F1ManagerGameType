package f1sim.game

/**
 * Pure phase-transition logic. Given a state and external facts, returns the
 * state after one advance step.
 *
 * Standard race weekend flow:
 *   PRE_SEASON -> PRACTICE -> QUALIFYING -> RACE -> POST_RACE
 *
 * Sprint race weekend flow (when isSprintWeekend is true):
 *   PRE_SEASON -> PRACTICE -> SPRINT_QUALIFYING -> SPRINT -> QUALIFYING -> RACE -> POST_RACE
 *
 * After POST_RACE:
 *   -> BETWEEN_ROUNDS (if round < roundsPerSeason)
 *   -> END_OF_SEASON  (if round == roundsPerSeason)
 *   BETWEEN_ROUNDS -> PRACTICE (round + 1)
 *
 * Season boundary:
 *   END_OF_SEASON -> OFF_SEASON (year + 1, round = 0)
 *   OFF_SEASON -> PRE_SEASON
 *
 * `isSprintWeekend` is consulted only at the PRACTICE -> next transition;
 * other phases ignore it. GameService queries the `races` table for the
 * current round's `session_format` and passes the boolean in.
 *
 * Pure: no DB, no logging, no side effects. The caller (GameService) is
 * responsible for supplying both roundsPerSeason and isSprintWeekend.
 */
object PhaseMachine {

    fun next(state: GameState, roundsPerSeason: Int, isSprintWeekend: Boolean): GameState =
        when (state.phase) {
            Phase.OFF_SEASON -> state.copy(phase = Phase.PRE_SEASON)

            Phase.PRE_SEASON -> state.copy(phase = Phase.PRACTICE, round = 1)

            Phase.PRACTICE -> if (isSprintWeekend) {
                state.copy(phase = Phase.SPRINT_QUALIFYING)
            } else {
                state.copy(phase = Phase.QUALIFYING)
            }

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
