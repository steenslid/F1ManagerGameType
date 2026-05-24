// Backend client. Hard-coded localhost URL — change if your backend runs
// elsewhere. Every call returns { data, elapsedMs } on success or throws
// an Error with the message "[CODE] message" on failure.

const BASE = 'http://localhost:7777'

async function request(method, path, body) {
  const opts = { method, headers: {} }
  if (body !== undefined) {
    opts.headers['content-type'] = 'application/json'
    opts.body = JSON.stringify(body)
  }
  const res = await fetch(BASE + path, opts)
  let envelope = null
  try {
    envelope = await res.json()
  } catch {
    // non-JSON body
  }
  if (!res.ok) {
    const err = envelope?.errors?.[0]
    throw new Error(err ? `[${err.code}] ${err.message}` : `HTTP ${res.status}`)
  }
  return {
    data: envelope?.data ?? null,
    elapsedMs: envelope?.meta?.elapsed_ms ?? 0,
  }
}

function qs(params) {
  const entries = Object.entries(params ?? {}).filter(
    ([, v]) => v !== null && v !== undefined && v !== ''
  )
  if (entries.length === 0) return ''
  return '?' + entries.map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&')
}

export const api = {
  // Saves
  listSaves: () => request('GET', '/api/saves'),
  createSave: (req) => request('POST', '/api/saves', req),
  loadSave: (id) => request('POST', `/api/saves/${id}/load`),
  deleteSave: (id) => request('DELETE', `/api/saves/${id}`),

  // Game
  getGameState: () => request('GET', '/api/game/state'),
  getActions: () => request('GET', '/api/game/actions'),
  advance: () => request('POST', '/api/game/advance'),
  selectTeam: (teamId) => request('POST', '/api/game/select-team', { teamId }),
  getCurrentRace: () => request('GET', '/api/game/current-race'),

  // Race weekend
  viewPractice: () => request('GET', '/api/race-weekend/practice'),
  setPracticeFocus: (driverId, focus) =>
    request('POST', '/api/race-weekend/practice', { driverId, focus }),

  // Standings
  getStandings: (filter) => request('GET', '/api/standings' + qs(filter)),

  // Resources
  listRaces: (filter) => request('GET', '/api/races' + qs(filter)),
  listRaceResults: (filter) => request('GET', '/api/race-results' + qs(filter)),
  listTeams: (filter) => request('GET', '/api/teams' + qs(filter)),
  listDrivers: (filter) => request('GET', '/api/drivers' + qs(filter)),
  listPersonnel: (filter) => request('GET', '/api/personnel' + qs(filter)),
  listTracks: () => request('GET', '/api/tracks'),
  listEngineSuppliers: () => request('GET', '/api/engine-suppliers'),
  listPuVersions: (filter) => request('GET', '/api/pu-versions' + qs(filter)),
  listSponsors: (filter) => request('GET', '/api/sponsors' + qs(filter)),
  listTyreCompounds: () => request('GET', '/api/tyre-compounds'),
  listRegulationEras: () => request('GET', '/api/regulation-eras'),
}
