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
  continueGame: () => request('POST', '/api/game/continue'),
  getTasks: () => request('GET', '/api/game/tasks'),
  selectTeam: (teamId) => request('POST', '/api/game/select-team', { teamId }),
  getCurrentRace: () => request('GET', '/api/game/current-race'),

  // Race weekend
  viewPractice: () => request('GET', '/api/race-weekend/practice'),
  setPracticeFocus: (driverId, focus) =>
    request('POST', '/api/race-weekend/practice', { driverId, focus }),
  viewStrategy: () => request('GET', '/api/race-weekend/strategy'),
  setStrategy: (driverId, archetype) =>
    request('POST', '/api/race-weekend/strategy', { driverId, archetype }),

  // Lineup (mid-season reserve / junior call-up)
  getLineup: () => request('GET', '/api/team/lineup'),
  swapLineup: (outDriverId, inDriverId) =>
    request('POST', '/api/team/lineup/swap', { outDriverId, inDriverId }),

  // Team R&D (per-area car development budgets: { aero, chassis, powertrain })
  getRd: () => request('GET', '/api/team/rd'),
  setRd: (req) => request('POST', '/api/team/rd', req),

  // In-season upgrade projects
  getUpgrades: () => request('GET', '/api/team/upgrades'),
  commissionUpgrade: (area, size) => request('POST', '/api/team/upgrades', { area, size }),

  // Personnel market (hire/fire staff)
  getStaffMarket: () => request('GET', '/api/personnel/market'),
  hireStaff: (personnelId, role) => request('POST', '/api/personnel/market/hire', { personnelId, role }),
  releaseStaff: (personnelId) => request('POST', '/api/personnel/market/release', { personnelId }),

  // Sponsor market
  getSponsorMarket: () => request('GET', '/api/sponsors/market'),
  signSponsor: (req) => request('POST', '/api/sponsors/market/sign', req),
  renewSponsor: (req) => request('POST', '/api/sponsors/market/renew', req),
  cancelSponsor: (dealId) => request('POST', '/api/sponsors/market/cancel', { dealId }),

  // Ladder (feeder grids: 'f2' | 'f3')
  getLadder: (series) => request('GET', `/api/ladder/${series}`),

  // Board objective
  getBoard: () => request('GET', '/api/board'),

  // Standings
  getStandings: (filter) => request('GET', '/api/standings' + qs(filter)),

  // Off-season
  getOffSeasonReport: (filter) => request('GET', '/api/off-season/report' + qs(filter)),

  // Driver market
  getMarketAvailable: () => request('GET', '/api/market/driver/available'),
  getMarketOffers: () => request('GET', '/api/market/driver/offers'),
  submitMarketOffer: (req) => request('POST', '/api/market/driver/offers', req),
  withdrawMarketOffer: (driverId) =>
    request('DELETE', `/api/market/driver/offers/${driverId}`),

  // Resources
  listRaces: (filter) => request('GET', '/api/races' + qs(filter)),
  listRaceResults: (filter) => request('GET', '/api/race-results' + qs(filter)),
  listSprintResults: (filter) => request('GET', '/api/sprint-results' + qs(filter)),
  listTeams: (filter) => request('GET', '/api/teams' + qs(filter)),
  listDrivers: (filter) => request('GET', '/api/drivers' + qs(filter)),
  listPersonnel: (filter) => request('GET', '/api/personnel' + qs(filter)),
  listTracks: () => request('GET', '/api/tracks'),
  listEngineSuppliers: () => request('GET', '/api/engine-suppliers'),
  listPuVersions: (filter) => request('GET', '/api/pu-versions' + qs(filter)),
  listSponsors: (filter) => request('GET', '/api/sponsors' + qs(filter)),
  listTeamSponsorships: (filter) =>
    request('GET', '/api/team-sponsorships' + qs(filter)),
  listTyreCompounds: () => request('GET', '/api/tyre-compounds'),
  listRegulationEras: () => request('GET', '/api/regulation-eras'),
}
