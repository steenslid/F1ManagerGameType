<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api.js'
import { useGame } from '../useGame.js'
import { fmtMoney, fmtMoneyFull } from '../format.js'

const { refreshAll } = useGame()

const state = ref(null)
const draft = ref({ aero: 0, chassis: 0, powertrain: 0 })
const loading = ref(true)
const saving = ref(false)
const error = ref(null)

const AREAS = [
  { key: 'aero', label: 'Aerodynamics', rating: 'carAero', budget: 'rdAero' },
  { key: 'chassis', label: 'Chassis', rating: 'carChassis', budget: 'rdChassis' },
  { key: 'powertrain', label: 'Powertrain', rating: 'carPowertrain', budget: 'rdPowertrain' },
]

const upg = ref(null)
const upArea = ref('AERO')
const upSize = ref('MEDIUM')
const upBusy = ref(false)

onMounted(() => { load(); loadUpgrades() })

async function loadUpgrades() {
  try { upg.value = (await api.getUpgrades()).data } catch (e) { /* leave as-is */ }
}

async function commission() {
  upBusy.value = true
  error.value = null
  try {
    upg.value = (await api.commissionUpgrade(upArea.value, upSize.value)).data
    await refreshAll()
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    upBusy.value = false
  }
}

async function load() {
  loading.value = true
  error.value = null
  try {
    state.value = (await api.getRd()).data
    draft.value = {
      aero: Number(state.value?.rdAero ?? 0),
      chassis: Number(state.value?.rdChassis ?? 0),
      powertrain: Number(state.value?.rdPowertrain ?? 0),
    }
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    loading.value = false
  }
}

const maxPerArea = computed(() => Number(state.value?.maxPerArea ?? 200_000_000))
const hasTeam = computed(() => !!state.value?.playerTeamId)
const totalSpend = computed(() => Number(draft.value.aero) + Number(draft.value.chassis) + Number(draft.value.powertrain))
const dirty = computed(() =>
  Number(draft.value.aero) !== Number(state.value?.rdAero ?? 0) ||
  Number(draft.value.chassis) !== Number(state.value?.rdChassis ?? 0) ||
  Number(draft.value.powertrain) !== Number(state.value?.rdPowertrain ?? 0)
)

// Mirror the backend's per-area saturating curve (ref 20M, floor 45, span 50).
function sustains(budget) {
  const ref = 20_000_000
  const sf = budget <= 0 ? 0 : budget / (budget + ref)
  return Math.round(45 + sf * 50)
}

async function save() {
  if (!dirty.value) return
  saving.value = true
  error.value = null
  try {
    state.value = (await api.setRd({
      aero: Number(draft.value.aero),
      chassis: Number(draft.value.chassis),
      powertrain: Number(draft.value.powertrain),
    })).data
    draft.value = {
      aero: Number(state.value?.rdAero ?? 0),
      chassis: Number(state.value?.rdChassis ?? 0),
      powertrain: Number(state.value?.rdPowertrain ?? 0),
    }
    await refreshAll()
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    saving.value = false
  }
}

function carClass(v) {
  if (v >= 80) return 'elite'
  if (v >= 68) return 'good'
  return ''
}
</script>

<template>
  <h1 class="page-title">Research &amp; Development</h1>

  <div v-if="error" class="error-banner">{{ error }}</div>
  <div v-if="loading" class="faint card empty">Loading R&amp;D…</div>

  <div v-else class="grid">
    <!-- Per-area development control -->
    <div class="card">
      <h2>Car development</h2>
      <div v-if="!hasTeam" class="faint empty">Select a team to manage its R&amp;D programme.</div>
      <template v-else>
        <div class="overall">
          <span class="ov num" :class="carClass(state.carPerformance)">{{ state.carPerformance }}</span>
          <span class="ovl">overall car · average of the three areas</span>
        </div>

        <div class="area" v-for="a in AREAS" :key="a.key">
          <div class="area-head">
            <span class="al">{{ a.label }}</span>
            <span class="ar num" :class="carClass(state[a.rating])">{{ state[a.rating] }}</span>
          </div>
          <input class="slider" type="range" min="0" :max="maxPerArea" step="2500000" v-model.number="draft[a.key]" />
          <div class="area-foot">
            <span class="num">{{ fmtMoneyFull(draft[a.key]) }}/yr</span>
            <span class="faint">sustains ~{{ sustains(Number(draft[a.key])) }}</span>
          </div>
        </div>

        <div class="totals">
          <div class="fin-row"><span class="k">Total R&amp;D spend</span><span class="v num">{{ fmtMoney(totalSpend) }}/yr</span></div>
          <div class="fin-row"><span class="k">Cash reserves</span><span class="v num">{{ fmtMoney(state.cashReserves) }}</span></div>
          <button class="btn" :disabled="!dirty || saving" @click="save">
            {{ saving ? 'Saving…' : dirty ? 'Set budgets' : 'Saved' }}
          </button>
        </div>
        <p class="note faint">
          Each area develops toward what its budget + your technical capability
          sustains. Concentrate spend to push one strength, or spread it for a
          balanced car. R&amp;D is billed to operating costs; changes take effect
          from the next pre-season.
        </p>
      </template>
    </div>

    <!-- Grid car ratings -->
    <div class="card">
      <h2>Car performance · grid</h2>
      <table v-if="state?.grid?.length">
        <thead><tr><th style="width:34px">#</th><th>Team</th><th class="r">Car</th></tr></thead>
        <tbody>
          <tr v-for="(t, i) in state.grid" :key="t.teamId" :class="{ me: t.isPlayer }">
            <td class="faint num">{{ i + 1 }}</td>
            <td class="name">{{ t.teamName }}<span v-if="t.isPlayer" class="you">You</span></td>
            <td class="r"><span class="car-badge" :class="carClass(t.carPerformance)">{{ t.carPerformance }}</span></td>
          </tr>
        </tbody>
      </table>
      <div v-else class="faint empty">No F1 teams.</div>
    </div>
  </div>

  <!-- In-season upgrade projects -->
  <div v-if="upg && upg.playerTeamId" class="card upg">
    <h2>Upgrade projects</h2>
    <p class="faint note">
      Commission an in-season upgrade to one area — it costs cash up front and
      delivers a few rounds later. Time it for the tracks ahead. Round
      {{ upg.currentRound }} of {{ upg.totalRounds }} · cash {{ fmtMoney(upg.cashReserves) }}.
    </p>

    <div class="up-list" v-if="upg.inProgress.length">
      <div class="up-row" v-for="p in upg.inProgress" :key="p.id">
        <span class="up-area" :class="p.area.toLowerCase()">{{ p.area }}</span>
        <span class="up-gain num">+{{ p.gain }}</span>
        <span class="faint">delivers round {{ p.deliverRound }} · {{ p.roundsRemaining }} to go</span>
        <span class="up-cost num faint">{{ fmtMoney(p.cost) }}</span>
      </div>
    </div>
    <div v-else class="faint empty">No upgrades in development.</div>

    <div class="commission">
      <select v-model="upArea" class="sel">
        <option value="AERO">Aerodynamics</option>
        <option value="CHASSIS">Chassis</option>
        <option value="POWERTRAIN">Powertrain</option>
      </select>
      <select v-model="upSize" class="sel">
        <option v-for="s in upg.sizes" :key="s.size" :value="s.size">
          {{ s.size }} · +{{ s.gain }} · {{ fmtMoney(s.cost) }} · {{ s.rounds }} rd
        </option>
      </select>
      <button class="btn" :disabled="upBusy" @click="commission">
        {{ upBusy ? 'Commissioning…' : 'Commission' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.page-title { font-size: 18px; margin: 4px 0 18px; font-weight: 700; }
.error-banner { background: var(--accent-soft); border: 1px solid #e1060055; color: #ff7066; padding: 10px 14px; border-radius: 8px; margin-bottom: 14px; font-size: 12px; }
.grid { display: grid; grid-template-columns: 1.3fr 1fr; gap: 16px; align-items: start; }
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 18px; }
.card h2 { font-size: 11px; text-transform: uppercase; letter-spacing: 1px; color: var(--muted); margin: 0 0 14px; font-weight: 700; }
.empty { padding: 10px 0; font-size: 13px; }

.overall { display: flex; align-items: baseline; gap: 12px; margin-bottom: 18px; padding-bottom: 14px; border-bottom: 1px solid var(--line); }
.ov { font-size: 34px; font-weight: 800; line-height: 1; }
.ov.good { color: var(--good); } .ov.elite { color: #ff7066; }
.ovl { font-size: 11px; text-transform: uppercase; letter-spacing: .6px; color: var(--faint); }

.area { margin-bottom: 16px; }
.area-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 6px; }
.al { font-size: 13px; font-weight: 700; }
.ar { font-size: 18px; font-weight: 800; }
.ar.good { color: var(--good); } .ar.elite { color: #ff7066; }
.slider { width: 100%; accent-color: var(--accent); }
.area-foot { display: flex; justify-content: space-between; font-size: 11px; margin-top: 2px; }

.totals { border-top: 1px solid var(--line); padding-top: 12px; margin-top: 4px; }
.fin-row { display: flex; justify-content: space-between; margin-bottom: 8px; font-size: 13px; }
.fin-row .k { color: var(--muted); } .fin-row .v { font-weight: 700; }
.btn { width: 100%; margin-top: 6px; background: var(--accent); color: #fff; border: none; padding: 10px; border-radius: 8px; font-weight: 700; font-size: 13px; cursor: pointer; }
.btn:hover:not(:disabled) { filter: brightness(1.1); }
.btn:disabled { opacity: .55; cursor: not-allowed; }
.note { font-size: 12px; line-height: 1.5; margin: 12px 0 0; }

table { width: 100%; border-collapse: collapse; font-size: 13px; }
th { font-size: 10px; text-transform: uppercase; letter-spacing: .6px; color: var(--faint); text-align: left; padding: 0 8px 8px; font-weight: 600; }
td { padding: 9px 8px; border-top: 1px solid var(--line); }
td.r, th.r { text-align: right; }
tr.me td { background: var(--accent-soft); }
.name { font-weight: 700; }
.you { font-size: 9px; text-transform: uppercase; letter-spacing: .5px; background: var(--accent-soft); color: #ff7066; border: 1px solid #e1060055; border-radius: 4px; padding: 1px 6px; margin-left: 8px; }
.car-badge { display: inline-block; padding: 2px 9px; border-radius: 5px; background: var(--surface-2); border: 1px solid var(--line); font-weight: 700; }
.car-badge.good { background: rgba(57,211,83,.1); color: var(--good); border-color: #39d35355; }
.car-badge.elite { background: var(--accent-soft); color: #ff7066; border-color: #e1060055; }
.faint { color: var(--faint); }
.num { font-variant-numeric: tabular-nums; }

.upg { margin-top: 16px; }
.upg .note { font-size: 12px; margin: 0 0 14px; line-height: 1.5; }
.up-list { display: flex; flex-direction: column; gap: 6px; margin-bottom: 14px; }
.up-row { display: flex; align-items: center; gap: 12px; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 9px 12px; font-size: 13px; }
.up-area { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .4px; padding: 3px 8px; border-radius: 5px; border: 1px solid var(--line); }
.up-area.aero { color: #5aa9e6; border-color: #5aa9e655; background: #5aa9e615; }
.up-area.powertrain { color: #e0b341; border-color: #e0b34155; background: #e0b34115; }
.up-area.chassis { color: #2dd4bf; border-color: #2dd4bf55; background: #2dd4bf15; }
.up-gain { font-weight: 800; color: var(--good); }
.up-cost { margin-left: auto; }
.commission { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
.sel { background: var(--bg); border: 1px solid var(--line); color: var(--fg); padding: 9px 10px; border-radius: 8px; font-size: 13px; outline: none; }
.sel:focus { border-color: var(--accent); }
.commission .btn { width: auto; margin: 0; padding: 9px 18px; }
</style>
