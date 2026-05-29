<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api.js'
import { useGame } from '../useGame.js'
import { fmtMoney, crestText } from '../format.js'

const emit = defineEmits(['team-selected'])
const { state, takeControl } = useGame()

const teams = ref([])
const driversByTeam = ref({}) // teamId -> [driver, ...]
const isLoading = ref(true)
const error = ref(null)

const selectedId = ref(null)
const confirming = ref(false)

onMounted(load)

async function load() {
  isLoading.value = true
  error.value = null
  try {
    const [teamRes, driverRes] = await Promise.all([api.listTeams(), api.listDrivers()])
    teams.value = (teamRes.data || []).filter((t) => t.series === 'F1')
    const map = {}
    for (const d of driverRes.data || []) {
      if (d.retired || !d.currentRacingTeamId) continue
      ;(map[d.currentRacingTeamId] ||= []).push(d)
    }
    // Strongest driver first within each team.
    for (const id in map) map[id].sort((a, b) => (b.stats?.pace ?? 0) - (a.stats?.pace ?? 0))
    driversByTeam.value = map
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    isLoading.value = false
  }
}

const selectedTeam = computed(() => teams.value.find((t) => t.id === selectedId.value) || null)

// A light, readable tier label off prestige so the pick feels meaningful.
function tier(prestige) {
  if (prestige >= 90) return { label: 'Front-runner', cls: 'top' }
  if (prestige >= 78) return { label: 'Midfield contender', cls: 'mid' }
  return { label: 'Underdog', cls: 'low' }
}

async function confirm() {
  if (!selectedId.value) return
  confirming.value = true
  error.value = null
  try {
    await takeControl(selectedId.value)
    if (state.error) throw new Error(state.error)
    emit('team-selected')
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    confirming.value = false
  }
}
</script>

<template>
  <div class="select-wrap">
    <header class="head">
      <h1>Choose your constructor</h1>
      <p class="faint">
        Pick the team you'll manage as
        <b>{{ state.overview?.managerName || 'Principal' }}</b>
        in {{ state.overview?.year }}. You can't change this later, so choose your challenge.
      </p>
    </header>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="isLoading" class="faint loading">Loading teams…</div>

    <div v-else class="team-grid">
      <button
        v-for="t in teams"
        :key="t.id"
        class="team-card"
        :class="{ selected: selectedId === t.id }"
        @click="selectedId = t.id"
      >
        <div class="tc-top">
          <div class="crest">{{ crestText(t.name) }}</div>
          <div class="tc-id">
            <div class="tc-name">{{ t.name }}</div>
            <div class="tc-country faint">{{ t.country }}</div>
          </div>
          <span class="tier" :class="tier(t.prestige).cls">{{ tier(t.prestige).label }}</span>
        </div>

        <div class="tc-stats">
          <div><span class="k">Prestige</span><span class="v num">{{ t.prestige }}</span></div>
          <div><span class="k">Cash</span><span class="v num">{{ fmtMoney(t.finance?.cashReserves) }}</span></div>
          <div><span class="k">Pit crew</span><span class="v num">{{ t.pitCrewRating }}</span></div>
        </div>

        <div class="tc-drivers">
          <span class="k">Drivers</span>
          <div class="lineup">
            <span v-for="d in (driversByTeam[t.id] || [])" :key="d.id" class="chip">
              {{ d.name }} <b class="num">{{ d.stats?.pace ?? '?' }}</b>
            </span>
            <span v-if="!(driversByTeam[t.id] || []).length" class="faint">No contracted drivers</span>
          </div>
        </div>
      </button>
    </div>

    <footer class="foot" v-if="!isLoading">
      <div class="foot-info">
        <template v-if="selectedTeam">
          Taking control of <b>{{ selectedTeam.name }}</b>
        </template>
        <template v-else class="faint">Select a team to continue</template>
      </div>
      <button class="btn" :disabled="!selectedId || confirming" @click="confirm">
        {{ confirming ? 'Confirming…' : 'Take control →' }}
      </button>
    </footer>
  </div>
</template>

<style scoped>
.select-wrap { max-width: 1100px; margin: 0 auto; padding: 40px 20px 120px; }
.head { text-align: center; margin-bottom: 28px; }
.head h1 { font-size: 28px; font-weight: 800; margin: 0 0 8px; }
.head p { font-size: 14px; max-width: 560px; margin: 0 auto; line-height: 1.5; }
.error-banner { background: var(--accent-soft); border: 1px solid #e1060055; color: #ff7066; padding: 10px 16px; border-radius: 8px; margin-bottom: 18px; font-size: 12px; }
.loading { text-align: center; padding: 40px; }

.team-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 16px; }
.team-card {
  text-align: left; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius);
  padding: 18px; cursor: pointer; transition: .15s; color: var(--fg); font: inherit;
}
.team-card:hover { border-color: var(--muted); transform: translateY(-2px); }
.team-card.selected { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent), 0 10px 30px rgba(225,6,0,.15); }

.tc-top { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.crest { width: 42px; height: 42px; border-radius: 9px; background: var(--accent); display: grid; place-items: center; font-weight: 800; color: #fff; font-size: 15px; }
.tc-id { flex: 1; }
.tc-name { font-weight: 800; font-size: 16px; }
.tc-country { font-size: 12px; }
.tier { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .5px; padding: 3px 8px; border-radius: 20px; }
.tier.top { background: #d9a44122; color: #e0b341; border: 1px solid #e0b34155; }
.tier.mid { background: var(--accent-soft); color: #ff7066; border: 1px solid #e1060055; }
.tier.low { background: var(--surface-2); color: var(--muted); border: 1px solid var(--line); }

.tc-stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; padding: 12px 0; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); margin-bottom: 12px; }
.tc-stats > div { display: flex; flex-direction: column; gap: 2px; }
.k { font-size: 10px; text-transform: uppercase; letter-spacing: .6px; color: var(--faint); }
.v { font-weight: 700; font-size: 15px; }

.tc-drivers .k { display: block; margin-bottom: 6px; }
.lineup { display: flex; flex-wrap: wrap; gap: 6px; }
.chip { background: var(--surface-2); border: 1px solid var(--line); border-radius: 6px; padding: 4px 9px; font-size: 12px; }
.chip b { color: var(--accent); margin-left: 4px; }

.foot {
  position: fixed; bottom: 0; left: 0; right: 0; z-index: 10;
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 16px 32px; background: rgba(13,15,18,.92); backdrop-filter: blur(8px);
  border-top: 1px solid var(--line);
}
/* Keep the confirm button clear of the fixed pink dev toggle (bottom-right). */
.foot { padding-right: 210px; }
.foot-info { font-size: 14px; }
.btn { background: var(--accent); color: #fff; border: none; padding: 12px 24px; border-radius: 9px; font-weight: 700; font-size: 14px; cursor: pointer; transition: .2s; }
.btn:hover:not(:disabled) { filter: brightness(1.1); }
.btn:disabled { opacity: .5; cursor: not-allowed; }
.faint { color: var(--faint); }
</style>
