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
//   POST /api/game/advance -> AdvanceResultDto { previous, current, events:[{type,message}] }

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
  lastEvents: [],       // raw events from the most recent single advance
  feed: [],             // accumulated, de-noised events for the toast/feed UI
  simming: false,       // true while a Continue is running
  tasks: [],            // TaskDto[] — the player's pending checklist
  lastContinue: null,   // most recent ContinueResultDto (reason/panel)
})

const RACE_WEEKEND_PHASES = new Set([
  'PRACTICE', 'QUALIFYING', 'SPRINT_QUALIFYING', 'SPRINT', 'RACE', 'POST_RACE',
])

// Driver/team id -> name, rebuilt on each refresh so we can humanise events
// like "Driver <uuid> won" (the backend logs raw ids for race winners).
let nameMap = {}

const myTeam = computed(() => {
  const id = state.overview?.playerTeam?.id
  if (!id) return null
  return state.teams.find((t) => t.id === id) || null
})

const hasTeam = computed(() => !!state.overview?.playerTeam?.id)
const inRaceWeekend = computed(() => RACE_WEEKEND_PHASES.has(state.overview?.phase))
const advanceLabel = computed(() => state.actions?.[0]?.label || 'Advance →')

// ------------------------------------------------------------------
// Event humanising
// ------------------------------------------------------------------

const UUID_RE = /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi

// Map a raw event type to a feed category (drives icon + tone in the UI).
function categorise(type) {
  if (type.startsWith('RACE') || type.startsWith('SPRINT') || type === 'QUALIFYING_COMPLETED' || type === 'SPRINT_QUALIFYING_COMPLETED') return 'race'
  if (type.startsWith('MARKET')) return 'market'
  if (type === 'FINANCE_SETTLED' || type === 'SPONSOR_REVENUE_APPLIED' || type === 'OPERATING_COST') return 'finance'
  if (type === 'RETIREMENT' || type === 'CONTRACT_EXPIRED' || type === 'OFF_SEASON_PROCESSED' || type === 'AGE_TICK') return 'people'
  return 'info'
}

// Replace any bare UUIDs in a message with the resolved name when we know it.
function humanise(message) {
  if (!message) return ''
  return message.replace(UUID_RE, (id) => nameMap[id.toLowerCase()] || nameMap[id] || 'a driver')
}

// Some events are noise for a player-facing feed (per-driver age ticks, etc.).
// STAT_DRIFT is noise when it's routine decline, but a young driver
// *developing* is news the player wants.
function isNoise(type, message) {
  if (type === 'AGE_TICK' || type === 'MARKET_ROUND_RESOLVED') return true
  if (type === 'STAT_DRIFT') return !/developing/.test(message || '')
  return false
}

function pushEvents(events, ctx) {
  for (const e of events) {
    if (isNoise(e.type, e.message)) continue
    state.feed.unshift({
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      type: e.type,
      cat: categorise(e.type),
      text: humanise(e.message),
      year: ctx?.year,
      round: ctx?.round,
    })
  }
  // Keep the feed bounded.
  if (state.feed.length > 60) state.feed.length = 60
}

function clearFeed() {
  state.feed = []
}

// ------------------------------------------------------------------
// Data loading
// ------------------------------------------------------------------

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

    // Pending-tasks checklist (non-critical — never block the refresh on it).
    api.getTasks()
      .then((res) => { state.tasks = res.data?.tasks || [] })
      .catch(() => { state.tasks = [] })

    // Rebuild the id->name map (teams now; drivers lazily folded in below).
    const map = {}
    for (const t of state.teams) map[t.id] = t.name
    // Fold drivers in without blocking the critical path.
    api.listDrivers().then((res) => {
      for (const d of res.data || []) map[d.id] = d.name
      nameMap = map
    }).catch(() => { nameMap = map })
    nameMap = map

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

// One advance. Returns the AdvanceResultDto data (or null on error).
async function advanceOnce() {
  const res = await api.advance()
  const data = res.data
  state.lastEvents = data?.events || []
  pushEvents(state.lastEvents, data?.current)
  return data
}

async function advance() {
  if (state.advancing || state.simming) return
  state.advancing = true
  state.error = null
  try {
    await advanceOnce()
    await refreshAll()
  } catch (e) {
    state.error = e.message || String(e)
  } finally {
    state.advancing = false
  }
}

// Smart advance: play forward until the game wants the player's attention
// (a gated decision, a result, an open market, the pre-season planning
// window). Returns the backend's ContinueResultDto — `panel` says which
// screen handles the stop, `reason` is the player-facing line.
async function continueFlow() {
  if (state.advancing || state.simming) return null
  state.simming = true
  state.error = null
  try {
    const res = await api.continueGame()
    const data = res.data
    pushEvents(data?.events || [], data?.current)
    state.lastContinue = data
    return data
  } catch (e) {
    state.error = e.message || String(e)
    return null
  } finally {
    await refreshAll()
    state.simming = false
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
    continueFlow,
    clearFeed,
    takeControl,
  }
}
