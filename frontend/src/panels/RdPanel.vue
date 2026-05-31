<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api.js'
import { useGame } from '../useGame.js'
import { fmtMoney, fmtMoneyFull } from '../format.js'

const { refreshAll } = useGame()

const state = ref(null)   // RdStateDto
const draft = ref(0)      // slider value (dollars)
const loading = ref(true)
const saving = ref(false)
const error = ref(null)
const savedAt = ref(0)

onMounted(load)

async function load() {
  loading.value = true
  error.value = null
  try {
    state.value = (await api.getRd()).data
    draft.value = Number(state.value?.rdBudget ?? 0)
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    loading.value = false
  }
}

const maxBudget = computed(() => Number(state.value?.maxBudget ?? 500_000_000))
const hasTeam = computed(() => !!state.value?.playerTeamId)
const dirty = computed(() => Number(draft.value) !== Number(state.value?.rdBudget ?? 0))

// Cars develop toward roughly FLOOR + spend/(spend+REF) * SPAN * tech each
// season. Mirror the backend's saturating shape (REF 60M, floor 45, span ~50,
// tech ~1.0) so the player sees a rough "sustains ~N" hint as they drag.
function sustainsHint(budget) {
  const ref = 60_000_000
  const spendFactor = budget <= 0 ? 0 : budget / (budget + ref)
  return Math.round(45 + spendFactor * 50 * 1.0)
}

const projected = computed(() => sustainsHint(Number(draft.value)))

async function save() {
  if (!dirty.value) return
  saving.value = true
  error.value = null
  try {
    state.value = (await api.setRd(Number(draft.value))).data
    draft.value = Number(state.value?.rdBudget ?? 0)
    savedAt.value = Date.now()
    await refreshAll() // expenses change at the next pre-season tick
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
    <!-- R&D control -->
    <div class="card">
      <h2>Car development</h2>
      <div v-if="!hasTeam" class="faint empty">Select a team to manage its R&amp;D programme.</div>
      <template v-else>
        <div class="headline">
          <div class="metric">
            <span class="mv num" :class="carClass(state.carPerformance)">{{ state.carPerformance }}</span>
            <span class="ml">current car</span>
          </div>
          <div class="arrow">→</div>
          <div class="metric">
            <span class="mv num">{{ projected }}</span>
            <span class="ml">this budget sustains</span>
          </div>
        </div>

        <label class="lbl">Annual R&amp;D budget</label>
        <input
          class="slider" type="range" min="0" :max="maxBudget" step="5000000"
          v-model.number="draft"
        />
        <div class="budget-row">
          <span class="bigmoney num">{{ fmtMoneyFull(draft) }}</span>
          <button class="btn" :disabled="!dirty || saving" @click="save">
            {{ saving ? 'Saving…' : dirty ? 'Set budget' : 'Saved' }}
          </button>
        </div>

        <div class="fin">
          <div class="fin-row"><span class="k">Cash reserves</span><span class="v num">{{ fmtMoney(state.cashReserves) }}</span></div>
          <div class="fin-row"><span class="k">Current R&amp;D spend</span><span class="v num">{{ fmtMoney(state.rdBudget) }}/yr</span></div>
        </div>

        <p class="note faint">
          R&amp;D is charged to your operating costs each season and develops your
          car over time — pour money in to climb the order, or cut back to bank
          cash and slide toward the back of the grid as rivals push on. Changes
          take effect from the next pre-season.
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
</template>

<style scoped>
.page-title { font-size: 18px; margin: 4px 0 18px; font-weight: 700; }
.error-banner { background: var(--accent-soft); border: 1px solid #e1060055; color: #ff7066; padding: 10px 14px; border-radius: 8px; margin-bottom: 14px; font-size: 12px; }
.grid { display: grid; grid-template-columns: 1.3fr 1fr; gap: 16px; align-items: start; }
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 18px; }
.card h2 { font-size: 11px; text-transform: uppercase; letter-spacing: 1px; color: var(--muted); margin: 0 0 14px; font-weight: 700; }
.empty { padding: 10px 0; font-size: 13px; }

.headline { display: flex; align-items: center; gap: 20px; margin-bottom: 18px; }
.metric { display: flex; flex-direction: column; }
.mv { font-size: 34px; font-weight: 800; line-height: 1; }
.mv.good { color: var(--good); }
.mv.elite { color: #ff7066; }
.ml { font-size: 10px; text-transform: uppercase; letter-spacing: .6px; color: var(--faint); margin-top: 4px; }
.arrow { font-size: 22px; color: var(--faint); }

.lbl { display: block; font-size: 11px; text-transform: uppercase; letter-spacing: .5px; color: var(--muted); font-weight: 600; margin-bottom: 8px; }
.slider { width: 100%; accent-color: var(--accent); }
.budget-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 8px 0 16px; }
.bigmoney { font-size: 20px; font-weight: 800; }
.btn { background: var(--accent); color: #fff; border: none; padding: 9px 16px; border-radius: 8px; font-weight: 700; font-size: 13px; cursor: pointer; }
.btn:hover:not(:disabled) { filter: brightness(1.1); }
.btn:disabled { opacity: .55; cursor: not-allowed; }

.fin { border-top: 1px solid var(--line); padding-top: 12px; }
.fin-row { display: flex; justify-content: space-between; margin-bottom: 8px; font-size: 13px; }
.fin-row .k { color: var(--muted); }
.fin-row .v { font-weight: 700; }
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
</style>
