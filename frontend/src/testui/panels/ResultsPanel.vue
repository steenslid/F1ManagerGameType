<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../../api.js'

const state = ref(null)
const currentRace = ref(null)
const currentResults = ref([])
const currentSprintResults = ref([])
const standings = ref(null)
const seasonResults = ref([])
const seasonSprintResults = ref([])

const loading = ref(false)
const error = ref(null)

async function refresh() {
  loading.value = true
  error.value = null
  currentRace.value = null
  currentResults.value = []
  currentSprintResults.value = []
  try {
    const s = await api.getGameState()
    state.value = s.data

    try {
      const r = await api.getCurrentRace()
      currentRace.value = r.data
      const [rr, sr] = await Promise.all([
        api.listRaceResults({ race: r.data.raceId }),
        api.listSprintResults({ race: r.data.raceId }),
      ])
      currentResults.value = rr.data
      currentSprintResults.value = sr.data
    } catch (e) {
      if (!String(e.message).startsWith('[NOT_FOUND]')) {
        throw e
      }
    }

    const st = await api.getStandings({ type: 'both', season: state.value.year })
    standings.value = st.data

    const [seasonRaceRes, seasonSprintRes] = await Promise.all([
      api.listRaceResults({ season: state.value.year }),
      api.listSprintResults({ season: state.value.year }),
    ])
    seasonResults.value = seasonRaceRes.data
    seasonSprintResults.value = seasonSprintRes.data
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function sortByFinish(rows) {
  return [...rows].sort((a, b) => {
    const af = a.finishingPosition ?? 999
    const bf = b.finishingPosition ?? 999
    if (af !== bf) return af - bf
    return (a.gridPosition ?? 999) - (b.gridPosition ?? 999)
  })
}

const currentRows = computed(() => sortByFinish(currentResults.value))
const currentSprintRows = computed(() => sortByFinish(currentSprintResults.value))

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

        <div v-if="currentSprintRows.length">
          <h3>Sprint</h3>
          <table>
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
              <tr v-for="r in currentSprintRows" :key="r.driverId">
                <td class="numeric"><strong>{{ fmtPos(r.finishingPosition) }}</strong></td>
                <td class="numeric muted">{{ fmtPos(r.gridPosition) }}</td>
                <td>{{ r.driverName }}</td>
                <td>{{ r.teamName }}</td>
                <td><span class="pill">{{ r.status }}</span></td>
                <td class="numeric">{{ r.points.toFixed(0) }}</td>
                <td>
                  <span v-if="r.pole" class="pill">SPRINT POLE</span>
                  <span v-if="r.dnfCause" class="muted">{{ r.dnfCause }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="currentRows.length">
          <h3 v-if="currentSprintRows.length">Race</h3>
          <table>
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
        </div>

        <p v-if="!currentRows.length && !currentSprintRows.length" class="muted">
          No results yet for this weekend.
          {{
            currentRace.sessionFormat === 'SPRINT'
              ? 'Advance to SPRINT_QUALIFYING to start.'
              : 'Advance to QUALIFYING to generate the grid.'
          }}
        </p>
      </div>
    </section>

    <section v-if="!loading && standings">
      <h3>Driver standings — {{ standings.seasonYear }}</h3>
      <table v-if="standings.drivers?.length">
        <thead>
          <tr>
            <th class="numeric">Pos</th>
            <th>Driver</th>
            <th>Team</th>
            <th class="numeric">Points</th>
            <th class="numeric">Wins</th>
            <th class="numeric">Podiums</th>
            <th class="numeric">Poles</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in standings.drivers" :key="d.driverId">
            <td class="numeric">{{ d.position }}</td>
            <td>{{ d.driverName }}</td>
            <td>{{ d.teamName }}</td>
            <td class="numeric"><strong>{{ d.points.toFixed(0) }}</strong></td>
            <td class="numeric">{{ d.wins }}</td>
            <td class="numeric">{{ d.podiums }}</td>
            <td class="numeric">{{ d.poles }}</td>
          </tr>
        </tbody>
      </table>

      <h3>Team standings — {{ standings.seasonYear }}</h3>
      <table v-if="standings.teams?.length">
        <thead>
          <tr>
            <th class="numeric">Pos</th>
            <th>Team</th>
            <th class="numeric">Points</th>
            <th class="numeric">Wins</th>
            <th class="numeric">Podiums</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in standings.teams" :key="t.teamId">
            <td class="numeric">{{ t.position }}</td>
            <td>{{ t.teamName }}</td>
            <td class="numeric"><strong>{{ t.points.toFixed(0) }}</strong></td>
            <td class="numeric">{{ t.wins }}</td>
            <td class="numeric">{{ t.podiums }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-if="!loading && (seasonResults.length || seasonSprintResults.length)">
      <h3>All season results ({{ seasonResults.length }} race rows, {{ seasonSprintResults.length }} sprint rows)</h3>
      <details>
        <summary>Raw race result rows</summary>
        <pre>{{ JSON.stringify(seasonResults, null, 2) }}</pre>
      </details>
      <details v-if="seasonSprintResults.length">
        <summary>Raw sprint result rows</summary>
        <pre>{{ JSON.stringify(seasonSprintResults, null, 2) }}</pre>
      </details>
    </section>
  </div>
</template>
