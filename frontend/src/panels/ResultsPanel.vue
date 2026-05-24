<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api.js'

const state = ref(null)
const currentRace = ref(null)
const currentResults = ref([])
const teams = ref([])
const seasonResults = ref([])

const loading = ref(false)
const error = ref(null)

async function refresh() {
  loading.value = true
  error.value = null
  currentRace.value = null
  currentResults.value = []
  try {
    const s = await api.getGameState()
    state.value = s.data

    // Try to load the current race (404s outside a weekend).
    try {
      const r = await api.getCurrentRace()
      currentRace.value = r.data
      const rr = await api.listRaceResults({ race: r.data.raceId })
      currentResults.value = rr.data
    } catch (e) {
      // Not in a race weekend — just leave currentRace null.
      if (!String(e.message).startsWith('[NOT_FOUND]')) {
        throw e
      }
    }

    // Season-to-date team standings.
    const t = await api.listTeams()
    teams.value = t.data
    const sr = await api.listRaceResults({ season: state.value.year })
    seasonResults.value = sr.data
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

// Combined grid + finishing order for the current race.
// One row per driver, sorted by finishing position when available, else grid.
const currentRows = computed(() => {
  return [...currentResults.value].sort((a, b) => {
    const af = a.finishingPosition ?? 999
    const bf = b.finishingPosition ?? 999
    if (af !== bf) return af - bf
    return (a.gridPosition ?? 999) - (b.gridPosition ?? 999)
  })
})

const teamStandings = computed(() => {
  return [...teams.value]
    .map(t => ({
      id: t.id,
      name: t.name,
      points: t.seasonPoints,
    }))
    .sort((a, b) => b.points - a.points)
})

function fmtPos(n) {
  return n == null ? '—' : n
}

onMounted(refresh)
</script>

<template>
  <div class="panel">
    <h2>Results</h2>

    <section>
      <div class="toolbar">
        <button @click="refresh" :disabled="loading">Refresh</button>
      </div>
      <div v-if="error" class="error">{{ error }}</div>
      <div v-else-if="loading" class="loading">Loading…</div>
    </section>

    <section v-if="state && !loading">
      <h3>Current race</h3>
      <p v-if="!currentRace" class="muted">
        Not in a race weekend (phase {{ state.phase }}, round {{ state.round }}).
      </p>
      <div v-else>
        <p>
          <strong>{{ currentRace.trackName }}</strong>
          <span class="muted"> — {{ currentRace.trackCountry }} —
            y{{ currentRace.seasonYear }} r{{ currentRace.round }} —
          </span>
          <span class="pill">{{ currentRace.sessionFormat }}</span>
        </p>
        <table v-if="currentRows.length">
          <thead>
            <tr>
              <th class="numeric">Finish</th>
              <th class="numeric">Grid</th>
              <th>Driver</th>
              <th>Team</th>
              <th>Status</th>
              <th class="numeric">Points</th>
              <th>Notes</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in currentRows" :key="r.driverId">
              <td class="numeric"><strong>{{ fmtPos(r.finishingPosition) }}</strong></td>
              <td class="numeric muted">{{ fmtPos(r.gridPosition) }}</td>
              <td>{{ r.driverName }}</td>
              <td>{{ r.teamName }}</td>
              <td>
                <span class="pill">{{ r.status }}</span>
              </td>
              <td class="numeric">{{ r.points.toFixed(0) }}</td>
              <td>
                <span v-if="r.pole" class="pill">POLE</span>
                <span v-if="r.fastestLap" class="pill">FL</span>
                <span v-if="r.dnfCause" class="muted">{{ r.dnfCause }}</span>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else class="muted">
          No results yet for this race. Advance to QUALIFYING to generate the grid.
        </p>
      </div>
    </section>

    <section v-if="!loading && teamStandings.length">
      <h3>Team standings — {{ state?.year }}</h3>
      <table>
        <thead>
          <tr>
            <th class="numeric">Pos</th>
            <th>Team</th>
            <th class="numeric">Points</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(t, i) in teamStandings" :key="t.id">
            <td class="numeric">{{ i + 1 }}</td>
            <td>{{ t.name }}</td>
            <td class="numeric"><strong>{{ t.points }}</strong></td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-if="!loading && seasonResults.length">
      <h3>All season results ({{ seasonResults.length }} rows)</h3>
      <details>
        <summary>Raw season result rows</summary>
        <pre>{{ JSON.stringify(seasonResults, null, 2) }}</pre>
      </details>
    </section>
  </div>
</template>
