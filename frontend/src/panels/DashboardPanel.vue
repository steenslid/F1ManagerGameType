<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { api } from '../api.js'
import { useGame } from '../useGame.js'
import { fmtMoney, phaseLabel } from '../format.js'

const emit = defineEmits(['navigate'])
const { state, myTeam, hasTeam } = useGame()

const driverStandings = ref([])
const teamStandings = ref([])
const loadingStandings = ref(false)

async function loadStandings() {
  if (!state.overview?.year) return
  loadingStandings.value = true
  try {
    const res = await api.getStandings({ type: 'both', season: state.overview.year })
    driverStandings.value = res.data?.drivers || []
    teamStandings.value = res.data?.teams || []
  } catch {
    driverStandings.value = []
    teamStandings.value = []
  } finally {
    loadingStandings.value = false
  }
}

onMounted(loadStandings)
// Re-pull standings whenever the season or round changes (after an advance).
watch(() => [state.overview?.year, state.overview?.round], loadStandings)

const myTeamId = computed(() => state.overview?.playerTeam?.id || null)

const carPerformance = computed(() => myTeam.value?.carPerformance ?? null)
const f1Teams = computed(() => state.teams.filter((t) => t.series === 'F1'))
const carRank = computed(() => {
  if (carPerformance.value == null) return null
  const sorted = [...f1Teams.value].sort((a, b) => (b.carPerformance ?? 0) - (a.carPerformance ?? 0))
  const idx = sorted.findIndex((t) => t.id === myTeamId.value)
  return idx >= 0 ? idx + 1 : null
})

const finance = computed(() => myTeam.value?.finance || null)
const net = computed(() => {
  if (!finance.value) return 0
  return (finance.value.currentYearIncome || 0) - (finance.value.currentYearExpenses || 0)
})
const incomeBarPct = computed(() => {
  const f = finance.value
  if (!f) return 0
  const inc = f.currentYearIncome || 0
  const exp = f.currentYearExpenses || 0
  const total = inc + exp
  return total > 0 ? Math.round((inc / total) * 100) : 0
})

const attention = computed(() => {
  if (!hasTeam.value) {
    return { msg: 'No team selected', sub: 'Pick a constructor to take control of from the Teams screen.', go: 'Choose a team', target: 'Teams' }
  }
  const p = state.overview?.phase
  const track = state.currentRace?.trackName
  switch (p) {
    case 'OFF_SEASON':
      return { msg: 'Off-season', sub: 'Advance to open the driver market.', go: null }
    case 'DRIVER_MARKET':
      return { msg: 'Driver market is open', sub: 'Submit contract offers to free agents before the rounds resolve.', go: 'Open market', target: 'Market' }
    case 'PRE_SEASON':
      return { msg: 'Pre-season', sub: 'Advance to start the opening round.', go: null }
    case 'PRACTICE':
      return { msg: track ? `Practice open at ${track}` : 'Practice open', sub: 'Set a practice focus for your drivers before qualifying.', go: 'Set focus', target: 'Race Weekend' }
    case 'QUALIFYING':
    case 'SPRINT_QUALIFYING':
      return { msg: 'Qualifying', sub: 'Pick a strategy archetype for each of your drivers.', go: 'Set strategy', target: 'Race Weekend' }
    case 'RACE':
    case 'SPRINT':
      return { msg: 'Lights out', sub: 'Advance to run the session, then review the results.', go: 'Race weekend', target: 'Race Weekend' }
    case 'POST_RACE':
      return { msg: 'Session complete', sub: 'Review the results, then advance to the next round.', go: 'View results', target: 'Race Weekend' }
    case 'BETWEEN_ROUNDS':
      return { msg: 'Between rounds', sub: 'Advance to begin the next race weekend.', go: null }
    case 'END_OF_SEASON':
      return { msg: 'Season complete', sub: 'Advance into the off-season to settle the books.', go: null }
    default:
      return { msg: phaseLabel(p), sub: '', go: null }
  }
})

function posClass(pos) {
  return pos === 1 ? 'p1' : pos === 2 ? 'p2' : pos === 3 ? 'p3' : ''
}
</script>

<template>
  <h1 class="page-title">Dashboard</h1>

  <div class="card attn" :class="{ warn: !hasTeam }">
    <span class="dot"></span>
    <div>
      <div class="msg">{{ attention.msg }}</div>
      <div class="sub">{{ attention.sub }}</div>
    </div>
    <span v-if="attention.go" class="go" @click="emit('navigate', attention.target)">{{ attention.go }} →</span>
  </div>

  <div class="grid">
    <div class="col">
      <!-- Next session -->
      <div class="card">
        <h2>Next session</h2>
        <div v-if="state.currentRace" class="race">
          <div class="flag">{{ phaseLabel(state.overview?.phase) === 'Practice' ? '🏁' : '🏎️' }}</div>
          <div>
            <div class="nm">{{ state.currentRace.trackName }}</div>
            <div class="loc">{{ state.currentRace.trackCountry }} · Round {{ state.currentRace.round }}</div>
            <div class="tags">
              <span v-if="state.currentRace.sessionFormat === 'SPRINT'" class="tag sprint">Sprint weekend</span>
              <span class="tag">{{ phaseLabel(state.overview?.phase) }}</span>
            </div>
          </div>
        </div>
        <div v-else class="faint empty">
          No race weekend in progress — currently {{ phaseLabel(state.overview?.phase) }}.
        </div>
      </div>

      <!-- Drivers' championship -->
      <div class="card">
        <h2>Drivers' championship</h2>
        <div v-if="loadingStandings" class="faint empty">Loading…</div>
        <table v-else-if="driverStandings.length">
          <thead><tr><th style="width:34px">#</th><th>Driver</th><th>Team</th><th class="r">Pts</th></tr></thead>
          <tbody>
            <tr v-for="d in driverStandings" :key="d.driverId" :class="{ me: d.teamId === myTeamId }">
              <td><span class="pos" :class="posClass(d.position)">{{ d.position }}</span></td>
              <td>{{ d.driverName }}</td>
              <td class="faint">{{ d.teamName }}</td>
              <td class="r num">{{ d.points }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="faint empty">No race results yet this season.</div>
      </div>
    </div>

    <div class="col">
      <!-- Constructors -->
      <div class="card">
        <h2>Constructors</h2>
        <div v-if="loadingStandings" class="faint empty">Loading…</div>
        <table v-else-if="teamStandings.length">
          <thead><tr><th style="width:34px">#</th><th>Team</th><th class="r">Pts</th></tr></thead>
          <tbody>
            <tr v-for="t in teamStandings" :key="t.teamId" :class="{ me: t.teamId === myTeamId }">
              <td><span class="pos" :class="posClass(t.position)">{{ t.position }}</span></td>
              <td>{{ t.teamName }}</td>
              <td class="r num">{{ t.points }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="faint empty">No standings yet.</div>
      </div>

      <!-- Finances -->
      <div class="card fin">
        <h2>Finances · {{ state.overview?.year }}</h2>
        <div v-if="finance">
          <div class="row"><span class="big num">{{ fmtMoney(finance.cashReserves) }}</span><span class="lab">cash reserves</span></div>
          <div class="row"><span class="lab">Projected income</span><span class="num pos-v">+{{ fmtMoney(finance.currentYearIncome) }}</span></div>
          <div class="row"><span class="lab">Projected expenses</span><span class="num neg-v">−{{ fmtMoney(finance.currentYearExpenses) }}</span></div>
          <div class="bar"><span :style="{ width: incomeBarPct + '%' }"></span></div>
          <div class="row" style="margin-top:6px"><span class="lab">Net</span>
            <span class="num" :class="net >= 0 ? 'pos-v' : 'neg-v'">{{ net >= 0 ? '+' : '−' }}{{ fmtMoney(Math.abs(net)) }}</span>
          </div>
        </div>
        <div v-else class="faint empty">Select a team to see its finances.</div>
      </div>

      <!-- Car performance -->
      <div class="card car" v-if="carPerformance != null">
        <h2>Car performance</h2>
        <div class="car-row">
          <span class="big num">{{ carPerformance }}</span>
          <span class="lab" v-if="carRank">P{{ carRank }} of {{ f1Teams.length }} on the grid</span>
        </div>
        <div class="bar"><span :style="{ width: carPerformance + '%' }"></span></div>
        <span class="go" @click="emit('navigate', 'R&D')">Develop in R&D →</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-title { font-size: 18px; margin: 4px 0 18px; font-weight: 700; }
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 16px 18px; }
.card h2 { font-size: 11px; text-transform: uppercase; letter-spacing: 1px; color: var(--muted); margin: 0 0 12px; font-weight: 700; }
.empty { padding: 8px 0; font-size: 13px; }

.attn { display: flex; align-items: center; gap: 12px; border-left: 3px solid var(--accent); background: var(--surface-2); margin-bottom: 16px; }
.attn.warn { border-left-color: var(--warn); }
.attn .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 0 4px var(--accent-soft); }
.attn.warn .dot { background: var(--warn); box-shadow: 0 0 0 4px #e0b34122; }
.attn .msg { font-weight: 600; }
.attn .sub { color: var(--muted); font-size: 12px; }
.attn .go { margin-left: auto; font-size: 12px; color: #ff7066; font-weight: 700; cursor: pointer; }
.attn .go:hover { text-decoration: underline; }

.grid { display: grid; grid-template-columns: 1.4fr 1fr; gap: 16px; }
.col { display: flex; flex-direction: column; gap: 16px; }

.race { display: flex; gap: 18px; align-items: center; }
.flag { width: 74px; height: 74px; border-radius: 10px; background: var(--surface-2); display: grid; place-items: center; font-size: 30px; border: 1px solid var(--line); }
.race .nm { font-size: 17px; font-weight: 700; }
.race .loc { color: var(--muted); font-size: 12px; margin-top: 2px; }
.tags { display: flex; gap: 6px; margin-top: 9px; }
.tag { font-size: 10px; letter-spacing: .5px; text-transform: uppercase; padding: 3px 8px; border-radius: 6px; background: var(--surface-2); color: var(--muted); border: 1px solid var(--line); }
.tag.sprint { color: var(--warn); border-color: #e0b34155; }

table { width: 100%; border-collapse: collapse; font-size: 13px; }
th { font-size: 10px; text-transform: uppercase; letter-spacing: .6px; color: var(--faint); text-align: left; padding: 0 8px 8px; font-weight: 600; }
td { padding: 7px 8px; border-top: 1px solid var(--line); }
td.r, th.r { text-align: right; }
tr.me td { background: var(--accent-soft); }
.pos { display: inline-grid; place-items: center; width: 22px; height: 22px; border-radius: 6px; background: var(--surface-2); font-weight: 700; font-size: 12px; }
.pos.p1 { background: #d9a441; color: #1a1305; }
.pos.p2 { background: #9aa3ad; color: #10151a; }
.pos.p3 { background: #b4724a; color: #160d06; }

.fin .row { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 8px; }
.fin .lab { color: var(--muted); font-size: 12px; }
.fin .big { font-size: 22px; font-weight: 800; }
.pos-v { color: var(--good); } .neg-v { color: var(--bad); }
.bar { height: 6px; border-radius: 4px; background: var(--surface-2); overflow: hidden; margin-top: 4px; }
.bar > span { display: block; height: 100%; background: var(--accent); }
.car-row { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 8px; }
.car .big { font-size: 26px; font-weight: 800; }
.car .lab { color: var(--muted); font-size: 12px; }
.car .go { display: inline-block; margin-top: 10px; font-size: 12px; color: #ff7066; font-weight: 700; cursor: pointer; }
.car .go:hover { text-decoration: underline; }
.faint { color: var(--faint); }
</style>
