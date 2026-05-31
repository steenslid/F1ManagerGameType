<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { api } from '../api.js'
import { useGame } from '../useGame.js'

const { state } = useGame()

const season = ref(null)
const drivers = ref([])
const teams = ref([])
const loading = ref(false)
const error = ref(null)

const myTeamId = computed(() => state.overview?.playerTeam?.id || null)

// Seasons we can browse: every year from the game's first up to the current one.
// We don't have a dedicated "seasons" endpoint, so derive from the current year.
const seasons = computed(() => {
  const cur = state.overview?.year
  if (!cur) return []
  const start = cur - 6 // show a small history window; empty seasons just read as blank
  return Array.from({ length: cur - start + 1 }, (_, i) => cur - i)
})

onMounted(() => {
  season.value = state.overview?.year || null
  load()
})
watch(() => [state.overview?.year, state.overview?.round], () => {
  // Follow the live season unless the user has deliberately picked an older one.
  if (!season.value || season.value === state.overview?.year) {
    season.value = state.overview?.year
  }
  load()
})

async function load() {
  if (!season.value) return
  loading.value = true
  error.value = null
  try {
    const res = await api.getStandings({ type: 'both', season: season.value })
    drivers.value = res.data?.drivers || []
    teams.value = res.data?.teams || []
  } catch (e) {
    error.value = e.message || String(e)
    drivers.value = []
    teams.value = []
  } finally {
    loading.value = false
  }
}

function pickSeason(y) {
  season.value = y
  load()
}

function posClass(pos) {
  return pos === 1 ? 'p1' : pos === 2 ? 'p2' : pos === 3 ? 'p3' : ''
}
const fmtPts = (p) => (Number.isInteger(p) ? p : (Number(p) || 0).toFixed(1))
</script>

<template>
  <div class="head">
    <h1 class="page-title">Standings</h1>
    <select v-if="seasons.length" class="season-select" :value="season" @change="pickSeason(Number($event.target.value))">
      <option v-for="y in seasons" :key="y" :value="y">{{ y }} season</option>
    </select>
  </div>

  <div v-if="error" class="error-banner">{{ error }}</div>

  <div class="grid">
    <!-- Drivers -->
    <div class="card">
      <h2>Drivers' championship</h2>
      <div v-if="loading" class="faint empty">Loading…</div>
      <table v-else-if="drivers.length">
        <thead>
          <tr>
            <th style="width:34px">#</th><th>Driver</th><th>Team</th>
            <th class="r">Win</th><th class="r">Pod</th><th class="r">Pole</th><th class="r">Pts</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in drivers" :key="d.driverId" :class="{ me: d.teamId === myTeamId }">
            <td><span class="pos" :class="posClass(d.position)">{{ d.position }}</span></td>
            <td class="name">{{ d.driverName }}</td>
            <td class="faint">{{ d.teamName }}</td>
            <td class="r num faint">{{ d.wins }}</td>
            <td class="r num faint">{{ d.podiums }}</td>
            <td class="r num faint">{{ d.poles }}</td>
            <td class="r num pts">{{ fmtPts(d.points) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="faint empty">No race results recorded for {{ season }} yet.</div>
    </div>

    <!-- Constructors -->
    <div class="card">
      <h2>Constructors' championship</h2>
      <div v-if="loading" class="faint empty">Loading…</div>
      <table v-else-if="teams.length">
        <thead>
          <tr>
            <th style="width:34px">#</th><th>Team</th>
            <th class="r">Win</th><th class="r">Pod</th><th class="r">Pts</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in teams" :key="t.teamId" :class="{ me: t.teamId === myTeamId }">
            <td><span class="pos" :class="posClass(t.position)">{{ t.position }}</span></td>
            <td class="name">{{ t.teamName }}</td>
            <td class="r num faint">{{ t.wins }}</td>
            <td class="r num faint">{{ t.podiums }}</td>
            <td class="r num pts">{{ fmtPts(t.points) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="faint empty">No standings for {{ season }} yet.</div>
    </div>
  </div>
</template>

<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18px; }
.page-title { font-size: 18px; margin: 4px 0; font-weight: 700; }
.season-select { background: var(--surface); border: 1px solid var(--line); color: var(--fg); padding: 8px 12px; border-radius: 8px; font-size: 13px; outline: none; cursor: pointer; font-weight: 600; }
.season-select:focus { border-color: var(--accent); }
.error-banner { background: var(--accent-soft); border: 1px solid #e1060055; color: #ff7066; padding: 10px 14px; border-radius: 8px; margin-bottom: 14px; font-size: 12px; }

.grid { display: grid; grid-template-columns: 1.5fr 1fr; gap: 16px; align-items: start; }
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 16px 18px; }
.card h2 { font-size: 11px; text-transform: uppercase; letter-spacing: 1px; color: var(--muted); margin: 0 0 12px; font-weight: 700; }
.empty { padding: 8px 0; font-size: 13px; }

table { width: 100%; border-collapse: collapse; font-size: 13px; }
th { font-size: 10px; text-transform: uppercase; letter-spacing: .6px; color: var(--faint); text-align: left; padding: 0 8px 8px; font-weight: 600; }
td { padding: 9px 8px; border-top: 1px solid var(--line); }
td.r, th.r { text-align: right; }
tr.me td { background: var(--accent-soft); }
.name { font-weight: 700; }
.pts { font-weight: 800; }
.pos { display: inline-grid; place-items: center; width: 22px; height: 22px; border-radius: 6px; background: var(--surface-2); font-weight: 700; font-size: 12px; }
.pos.p1 { background: #d9a441; color: #1a1305; }
.pos.p2 { background: #9aa3ad; color: #10151a; }
.pos.p3 { background: #b4724a; color: #160d06; }
.faint { color: var(--faint); }
</style>
