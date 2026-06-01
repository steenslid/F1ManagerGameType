<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { api } from '../api.js'
import { useGame } from '../useGame.js'
import { phaseLabel } from '../format.js'

const { state, myTeam, hasTeam, inRaceWeekend, advanceLabel, advance, refreshAll } = useGame()

const loading = ref(false)
const error = ref(null)

const practice = ref(null)   // PracticeViewDto
const strategy = ref(null)   // StrategyViewDto
const results = ref([])      // RaceResultDto[]
const sprintResults = ref([])

// Mid-season lineup (reserve / junior call-up), only between rounds.
const lineup = ref(null)     // LineupDto
const selectedOut = ref(null)
const selectedIn = ref(null)
const swapping = ref(false)
const SOURCE_LABEL = { FREE_AGENT: 'Free agent', RESERVE: 'Reserve', ACADEMY: 'Academy', F2: 'F2', F3: 'F3', JUNIOR: 'Junior' }

const FOCUS_OPTIONS = [
  { value: 'SETUP', label: 'Setup (+pace)' },
  { value: 'TYRE_PROGRAM', label: 'Tyre Program' },
  { value: 'RELIABILITY_CHECK', label: 'Reliability Check' },
  { value: 'DEVELOPMENT_FEEDBACK', label: 'Development Feedback' },
]
const ARCHETYPE_OPTIONS = [
  { value: 'M_H', label: 'Medium → Hard (safe)' },
  { value: 'S_H', label: 'Soft → Hard' },
  { value: 'S_M_M', label: 'Soft → Medium → Medium' },
  { value: 'M_M_H', label: 'Medium → Medium → Hard' },
  { value: 'S_S_H', label: 'Soft → Soft → Hard (aggressive)' },
]

const phase = computed(() => state.overview?.phase)
const myTeamId = computed(() => myTeam.value?.id || null)

const step = computed(() => {
  switch (phase.value) {
    case 'PRACTICE': return 'practice'
    case 'QUALIFYING':
    case 'SPRINT_QUALIFYING': return 'strategy'
    case 'RACE':
    case 'SPRINT':
    case 'POST_RACE': return 'results'
    default: return 'none'
  }
})

// Only the player's own drivers are editable here.
const myPracticeEntries = computed(() =>
    (practice.value?.entries || []).filter((e) => e.teamId === myTeamId.value)
)
const myStrategyEntries = computed(() =>
    (strategy.value?.entries || []).filter((e) => e.teamId === myTeamId.value)
)
const sortedResults = computed(() =>
    [...results.value].sort((a, b) => (a.finishingPosition ?? 999) - (b.finishingPosition ?? 999))
)

async function loadForStep() {
  error.value = null
  practice.value = null
  strategy.value = null
  results.value = []
  sprintResults.value = []
  if (step.value === 'none' || !state.currentRace) return

  loading.value = true
  try {
    if (step.value === 'practice') {
      practice.value = (await api.viewPractice()).data
    } else if (step.value === 'strategy') {
      strategy.value = (await api.viewStrategy()).data
    } else if (step.value === 'results') {
      const raceId = state.currentRace.raceId
      const [rr, sr] = await Promise.all([
        api.listRaceResults({ race: raceId }).catch(() => ({ data: [] })),
        api.listSprintResults({ race: raceId }).catch(() => ({ data: [] })),
      ])
      results.value = rr.data || []
      sprintResults.value = sr.data || []
    }
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    loading.value = false
  }
}

async function loadLineup() {
  if (phase.value !== 'BETWEEN_ROUNDS' || !hasTeam.value) { lineup.value = null; return }
  try {
    lineup.value = (await api.getLineup()).data
  } catch (e) {
    error.value = e.message || String(e)
  }
}

async function doSwap() {
  if (!selectedOut.value || !selectedIn.value) return
  swapping.value = true
  error.value = null
  try {
    lineup.value = (await api.swapLineup(selectedOut.value, selectedIn.value)).data
    selectedOut.value = null
    selectedIn.value = null
    await refreshAll() // roster changed — refresh shared game state
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    swapping.value = false
  }
}

function raceName(id) { return lineup.value?.raceDrivers.find((d) => d.driverId === id)?.name || '' }
function candName(id) { return lineup.value?.callUpCandidates.find((d) => d.driverId === id)?.name || '' }

function reload() { loadForStep(); loadLineup() }

onMounted(reload)
watch(() => [phase.value, state.currentRace?.raceId], reload)

async function saveFocus(entry, focus) {
  error.value = null
  try {
    practice.value = (await api.setPracticeFocus(entry.driverId, focus)).data
  } catch (e) {
    error.value = e.message || String(e)
  }
}
async function saveStrategy(entry, archetype) {
  error.value = null
  try {
    strategy.value = (await api.setStrategy(entry.driverId, archetype)).data
  } catch (e) {
    error.value = e.message || String(e)
  }
}

function tyreClass(status) { return '' }
</script>

<template>
  <div class="card">
    <div class="card-header">
      <div>
        <h2 v-if="state.currentRace">{{ state.currentRace.trackName }}</h2>
        <h2 v-else>Race Weekend</h2>
        <span v-if="state.currentRace" class="faint loc">
          {{ state.currentRace.trackCountry }} · Round {{ state.currentRace.round }}
          <span v-if="state.currentRace.sessionFormat === 'SPRINT'" class="sprint">· Sprint</span>
        </span>
      </div>
      <div class="phase-now">
        <span class="phase-pill">{{ phaseLabel(phase) }}</span>
        <button class="btn" :disabled="state.advancing" @click="advance">
          {{ state.advancing ? 'Advancing…' : advanceLabel }}
        </button>
      </div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <div v-if="phase === 'BETWEEN_ROUNDS'" class="lineup">
      <p class="hint faint">
        Between rounds you can replace one of your race drivers with any driver
        not currently racing in F1 (18+) — a free agent, your reserve, or an
        F2/F3 junior. Handy for bringing in an experienced free agent when a
        rookie isn't working out. The driver you bench becomes your reserve and
        can be recalled later. (Contracted F1 drivers can't be poached
        mid-season — that's the off-season market.)
      </p>
      <div v-if="!hasTeam" class="faint p-20">Select a team to manage your lineup.</div>
      <div v-else-if="!lineup" class="faint p-20">Loading lineup…</div>
      <template v-else>
        <div class="lineup-grid">
          <div class="lineup-col">
            <h3 class="section">Race seats</h3>
            <div
              v-for="d in lineup.raceDrivers" :key="d.driverId"
              class="lu-card" :class="{ sel: selectedOut === d.driverId }"
              @click="selectedOut = d.driverId"
            >
              <div class="lu-id">
                <div class="lu-name">{{ d.name }}</div>
                <div class="lu-sub faint">{{ d.nationality }} · {{ d.age }}y</div>
              </div>
              <div class="lu-stats">
                <span class="lu-stat">PAC <b>{{ d.statPace }}</b></span>
                <span class="lu-stat">QUA <b>{{ d.statQualifying }}</b></span>
              </div>
              <span v-if="selectedOut === d.driverId" class="lu-tag out">Bench</span>
            </div>
            <div v-if="!lineup.raceDrivers.length" class="faint p-20">No contracted race drivers.</div>
          </div>

          <div class="lineup-col">
            <h3 class="section">Available to call up</h3>
            <div
              v-for="d in lineup.callUpCandidates" :key="d.driverId"
              class="lu-card" :class="{ sel: selectedIn === d.driverId }"
              @click="selectedIn = d.driverId"
            >
              <div class="lu-id">
                <div class="lu-name">{{ d.name }} <span class="src">{{ SOURCE_LABEL[d.source] || d.source }}</span></div>
                <div class="lu-sub faint">{{ d.nationality }} · {{ d.age }}y</div>
              </div>
              <div class="lu-stats">
                <span class="lu-stat">PAC <b>{{ d.statPace }}</b></span>
                <span class="lu-stat">QUA <b>{{ d.statQualifying }}</b></span>
              </div>
              <span v-if="selectedIn === d.driverId" class="lu-tag in">Call up</span>
            </div>
            <div v-if="!lineup.callUpCandidates.length" class="faint p-20">
              No free agents, reserves, or F2/F3 juniors are available to call up.
            </div>
          </div>
        </div>

        <div class="lu-actions">
          <span class="faint" v-if="!selectedOut || !selectedIn">Pick a driver to bench and a driver to call up.</span>
          <span v-else class="confirm-text">
            Bench <b>{{ raceName(selectedOut) }}</b> and promote <b>{{ candName(selectedIn) }}</b> into the seat.
          </span>
          <button class="btn" :disabled="!selectedOut || !selectedIn || swapping" @click="doSwap">
            {{ swapping ? 'Swapping…' : 'Confirm swap' }}
          </button>
        </div>
      </template>
    </div>

    <div v-else-if="step === 'none'" class="empty-state">
      <div class="big">No active session</div>
      <p class="faint">It's currently <b>{{ phaseLabel(phase) }}</b>. Use Advance to move toward the next race weekend.</p>
    </div>

    <div v-else-if="loading" class="faint p-20">Loading session…</div>

    <div v-else-if="step === 'practice'">
      <p class="hint faint"><b class="req">Required:</b> set a practice focus for each of your drivers before you can advance to qualifying. <b>Setup</b> grants a small qualifying/pace boost; the others are placeholders for now.</p>
      <div v-if="!myTeamId" class="faint p-20">Select a team first to set focus.</div>
      <div v-else class="driver-cards">
        <div class="driver-card" :class="{ unset: !e.focus }" v-for="e in myPracticeEntries" :key="e.driverId">
          <div class="dc-head">{{ e.driverName }}<span v-if="!e.focus" class="needs">needs focus</span></div>
          <label>Practice Focus</label>
          <select :value="e.focus || ''" :disabled="!practice?.canEdit" @change="saveFocus(e, $event.target.value)">
            <option value="" disabled>Choose a focus…</option>
            <option v-for="o in FOCUS_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
          </select>
        </div>
        <div v-if="!myPracticeEntries.length" class="faint p-20">No drivers contracted to your team.</div>
      </div>
    </div>

    <div v-else-if="step === 'strategy'">
      <p class="hint faint"><b class="req">Required:</b> pick a race strategy for each of your drivers before you can start the race. Grid positions appear once qualifying has been simulated.</p>
      <div v-if="!myTeamId" class="faint p-20">Select a team first to set strategy.</div>
      <div v-else class="driver-cards">
        <div class="driver-card" :class="{ unset: !e.archetype }" v-for="e in myStrategyEntries" :key="e.driverId">
          <div class="dc-head">
            {{ e.driverName }}
            <span v-if="e.gridPosition" class="grid-pill">P{{ e.gridPosition }}</span>
            <span v-if="!e.archetype" class="needs">needs strategy</span>
          </div>
          <label>Strategy</label>
          <select :value="e.archetype || ''" :disabled="!strategy?.canEdit" @change="saveStrategy(e, $event.target.value)">
            <option value="" disabled>Choose a strategy…</option>
            <option v-for="o in ARCHETYPE_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
          </select>
        </div>
        <div v-if="!myStrategyEntries.length" class="faint p-20">No drivers contracted to your team.</div>
      </div>

      <h3 class="section">Provisional grid</h3>
      <table class="data-table">
        <thead><tr><th class="r">Grid</th><th>Driver</th><th>Team</th><th>Strategy</th></tr></thead>
        <tbody>
        <tr v-for="e in strategy?.entries || []" :key="e.driverId" :class="{ me: e.teamId === myTeamId }">
          <td class="r num">{{ e.gridPosition || '—' }}</td>
          <td class="name">{{ e.driverName }}</td>
          <td class="faint">{{ e.teamName }}</td>
          <td class="faint">{{ e.archetype || '—' }}</td>
        </tr>
        </tbody>
      </table>
    </div>

    <div v-else-if="step === 'results'">

      <div v-if="phase === 'SPRINT'" class="empty-state">
        <div class="big">The Sprint has run</div>
        <p class="faint">Click <b>{{ state.advancing ? 'Advancing…' : advanceLabel }}</b> to reveal the sprint results.</p>
      </div>

      <div v-else>
        <div v-if="sprintResults.length">
          <h3 class="section">Sprint result</h3>
          <table class="data-table">
            <thead><tr><th class="r">Pos</th><th>Driver</th><th>Team</th><th class="r">Pts</th><th>Status</th></tr></thead>
            <tbody>
            <tr v-for="r in [...sprintResults].sort((a,b)=>(a.finishingPosition??999)-(b.finishingPosition??999))" :key="r.driverId" :class="{ me: r.teamId === myTeamId }">
              <td class="r num">{{ r.finishingPosition || '—' }}</td>
              <td class="name">{{ r.driverName }}</td>
              <td class="faint">{{ r.teamName }}</td>
              <td class="r num">{{ r.points }}</td>
              <td><span class="status" :class="r.status.toLowerCase()">{{ r.status }}</span></td>
            </tr>
            </tbody>
          </table>
        </div>

        <div v-if="phase === 'RACE'" class="empty-state" :style="sprintResults.length ? 'margin-top: 40px;' : ''">
          <div class="big">The Grand Prix has run</div>
          <p class="faint">Click <b>{{ state.advancing ? 'Advancing…' : advanceLabel }}</b> to reveal the race results.</p>
        </div>

        <div v-else-if="phase === 'POST_RACE'">
          <h3 class="section">Race result</h3>
          <table class="data-table" v-if="sortedResults.length">
            <thead><tr><th class="r">Pos</th><th>Driver</th><th>Team</th><th class="r">Grid</th><th class="r">Pts</th><th>Status</th></tr></thead>
            <tbody>
            <tr v-for="r in sortedResults" :key="r.driverId" :class="{ me: r.teamId === myTeamId }">
              <td class="r num">{{ r.finishingPosition || '—' }}</td>
              <td class="name">{{ r.driverName }}<span v-if="r.pole" class="tag">Pole</span><span v-if="r.fastestLap" class="tag fl">FL</span></td>
              <td class="faint">{{ r.teamName }}</td>
              <td class="r num">{{ r.gridPosition || '—' }}</td>
              <td class="r num">{{ r.points }}</td>
              <td><span class="status" :class="r.status.toLowerCase()">{{ r.status }}</span>
                <span v-if="r.dnfCause" class="faint dnf">{{ r.dnfCause }}</span></td>
            </tr>
            </tbody>
          </table>
          <div v-else class="faint p-20">No race result yet — advance through the {{ phaseLabel(phase) }} phase to run the simulation.</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 20px; }
.card-header { display: flex; justify-content: space-between; align-items: flex-end; border-bottom: 1px solid var(--line); padding-bottom: 16px; margin-bottom: 18px; gap: 16px; }
.card-header h2 { margin: 0 0 4px; font-size: 20px; font-weight: 800; }
.loc { font-size: 13px; }
.sprint { color: var(--warn); }
.phase-now { display: flex; align-items: center; gap: 12px; }
.phase-pill { padding: 4px 10px; border-radius: 20px; background: var(--accent-soft); color: #ff7066; border: 1px solid #e1060055; font-weight: 700; font-size: 11px; }
.btn { background: var(--accent); color: #fff; border: none; padding: 9px 16px; border-radius: 8px; font-weight: 700; font-size: 13px; cursor: pointer; }
.btn:hover:not(:disabled) { filter: brightness(1.1); }
.btn:disabled { opacity: .6; cursor: not-allowed; }
.error-banner { background: var(--accent-soft); border: 1px solid #e1060055; color: #ff7066; padding: 10px 14px; border-radius: 8px; margin-bottom: 14px; font-size: 12px; }

.empty-state { text-align: center; padding: 40px 20px; }
.empty-state .big { font-size: 18px; font-weight: 700; margin-bottom: 8px; }
.hint { font-size: 13px; margin: 0 0 16px; }

.driver-cards { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.driver-card { background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 18px; }
.driver-card.unset { border-color: var(--warn); }
.req { color: var(--warn); }
.needs { margin-left: auto; font-size: 9px; text-transform: uppercase; letter-spacing: .5px; color: var(--warn); border: 1px solid #e0b34155; border-radius: 4px; padding: 2px 6px; }
.dc-head { font-size: 16px; font-weight: 700; margin-bottom: 14px; display: flex; align-items: center; gap: 8px; }
.grid-pill { background: var(--surface-2); color: var(--muted); border-radius: 6px; padding: 2px 8px; font-size: 12px; font-weight: 700; }
.driver-card label { display: block; font-size: 11px; text-transform: uppercase; color: var(--muted); font-weight: 600; margin-bottom: 6px; }
select { width: 100%; background: var(--surface); color: var(--fg); border: 1px solid var(--line); padding: 10px; border-radius: 6px; font-size: 13px; outline: none; cursor: pointer; }
select:focus { border-color: var(--accent); }
select:disabled { opacity: .6; cursor: not-allowed; }

.section { font-size: 11px; text-transform: uppercase; letter-spacing: 1px; color: var(--muted); margin: 24px 0 10px; font-weight: 700; }
.data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table th { color: var(--faint); text-transform: uppercase; font-size: 10px; text-align: left; padding: 8px; border-bottom: 1px solid var(--line); }
.data-table td { padding: 11px 8px; border-bottom: 1px solid var(--surface-2); vertical-align: middle; }
.data-table th.r, .data-table td.r { text-align: right; }
.name { font-weight: 700; }
tr.me td { background: var(--accent-soft); }
.tag { margin-left: 6px; font-size: 9px; text-transform: uppercase; padding: 2px 5px; border-radius: 4px; background: var(--surface-2); color: var(--muted); font-weight: 700; }
.tag.fl { color: #b07cf0; }
.status { font-size: 11px; font-weight: 600; }
.status.dnf { color: var(--bad); }
.status.finished { color: var(--good); }
.dnf { margin-left: 6px; font-size: 11px; }
.p-20 { padding: 20px; text-align: center; }
.faint { color: var(--faint); }

/* Mid-season lineup */
.lineup-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
.lineup-col .section { margin-top: 0; }
.lu-card {
  display: flex; align-items: center; gap: 12px; background: var(--bg);
  border: 1px solid var(--line); border-radius: 9px; padding: 12px 14px;
  margin-bottom: 8px; cursor: pointer; transition: .12s;
}
.lu-card:hover { border-color: var(--muted); }
.lu-card.sel { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent); }
.lu-id { flex: 1; }
.lu-name { font-weight: 700; font-size: 14px; }
.src { font-size: 9px; text-transform: uppercase; letter-spacing: .5px; background: var(--surface-2); color: var(--muted); border: 1px solid var(--line); border-radius: 4px; padding: 1px 6px; margin-left: 6px; vertical-align: middle; }
.lu-sub { font-size: 11px; margin-top: 1px; }
.lu-stats { display: flex; gap: 10px; }
.lu-stat { font-size: 10px; text-transform: uppercase; letter-spacing: .4px; color: var(--faint); }
.lu-stat b { color: var(--fg); font-size: 13px; margin-left: 3px; }
.lu-tag { font-size: 10px; font-weight: 700; text-transform: uppercase; padding: 3px 8px; border-radius: 20px; }
.lu-tag.out { background: #e0b34122; color: var(--warn); border: 1px solid #e0b34155; }
.lu-tag.in { background: var(--accent-soft); color: #ff7066; border: 1px solid #e1060055; }
.lu-actions { display: flex; align-items: center; justify-content: flex-end; gap: 16px; margin-top: 18px; border-top: 1px solid var(--line); padding-top: 16px; }
.confirm-text { font-size: 13px; }
</style>

