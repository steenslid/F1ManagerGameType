// Shared game state for the new UI. One reactive store, imported by the shell
// and every panel, so that a single `advance()` refreshes the whole UI instead
// of each panel fetching in isolation.
//
// Field shapes mirror the backend DTOs exactly (camelCase — the HTTP envelope
// serializer has no snake_case naming strategy):
//   GET /api/game/state    -> GameOverviewDto { saveId, saveName, managerName,
//                              difficulty, year, round, phase, playerTeam?{id,name} }
//   GET /api/game/actions  -> ActionsDto { phase, actions:[{name,label,method,endpoint}] }
//   GET /api/teams         -> TeamDto[] { id, name, ..., finance{cashReserves,...},
//                              seasonPoints }
//   GET /api/game/current-race -> CurrentRaceDto (404 when round < 1)

import { reactive, computed } from 'vue'
import { api } from './api.js'

const state = reactive({
  loaded: false,
  loading: false,
  advancing: false,
  error: null,
  overview: null,       // GameOverviewDto
  actions: [],          // ActionDto[]
  teams: [],            // TeamDto[]
  currentRace: null,    // CurrentRaceDto | null (null outside a race weekend)
  lastEvents: [],       // events from the most recent advance
})

const RACE_WEEKEND_PHASES = new Set([
  'PRACTICE', 'QUALIFYING', 'SPRINT_QUALIFYING', 'SPRINT', 'RACE', 'POST_RACE',
])

const myTeam = computed(() => {
  const id = state.overview?.playerTeam?.id
  if (!id) return null
  return state.teams.find((t) => t.id === id) || null
})

const hasTeam = computed(() => !!state.overview?.playerTeam?.id)

const inRaceWeekend = computed(() => RACE_WEEKEND_PHASES.has(state.overview?.phase))

// The advance button label is phase-driven from the backend's actions list.
const advanceLabel = computed(() => state.actions?.[0]?.label || 'Advance →')

async function refreshAll() {
  state.loading = true
  state.error = null
  try {
    const [ov, acts, teams] = await Promise.all([
      api.getGameState(),
      api.getActions(),
      api.listTeams(),
    ])
    state.overview = ov.data
    state.actions = acts.data?.actions || []
    state.teams = teams.data || []

    if ((ov.data?.round ?? 0) >= 1) {
      try {
        const cr = await api.getCurrentRace()
        state.currentRace = cr.data
      } catch {
        state.currentRace = null // not in a race weekend right now
      }
    } else {
      state.currentRace = null
    }
    state.loaded = true
  } catch (e) {
    state.error = e.message || String(e)
  } finally {
    state.loading = false
  }
}

async function advance() {
  if (state.advancing) return
  state.advancing = true
  state.error = null
  try {
    const res = await api.advance()
    state.lastEvents = res.data?.events || []
    await refreshAll()
  } catch (e) {
    state.error = e.message || String(e)
  } finally {
    state.advancing = false
  }
}

async function takeControl(teamId) {
  state.error = null
  try {
    await api.selectTeam(teamId)
    await refreshAll()
  } catch (e) {
    state.error = e.message || String(e)
  }
}

export function useGame() {
  return {
    state,
    myTeam,
    hasTeam,
    inRaceWeekend,
    advanceLabel,
    refreshAll,
    advance,
    takeControl,
  }
}
