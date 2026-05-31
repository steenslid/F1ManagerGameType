<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api.js'
import { useGame } from '../useGame.js'
import { fmtMoney, crestText } from '../format.js'

const { state } = useGame()

const teams = ref([])
const isLoading = ref(true)
const error = ref(null)

onMounted(load)

async function load() {
  isLoading.value = true
  error.value = null
  try {
    // F1 constructors only — F2 feeder teams live on the ladder, not here.
    const res = await api.listTeams({ series: 'F1' })
    teams.value = res.data || []
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    isLoading.value = false
  }
}

function isMine(team) {
  return state.overview?.playerTeam?.id === team.id
}
</script>

<template>
  <div class="card">
    <div class="card-header"><h2>Constructors</h2></div>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="isLoading" class="faint p-20">Loading teams…</div>

    <table v-else class="data-table">
      <thead>
        <tr>
          <th></th><th>Team</th><th>Country</th>
          <th class="r">Prestige</th><th class="r">Car</th><th class="r">Points</th><th class="r">Cash</th><th class="r"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="team in teams" :key="team.id" :class="{ me: isMine(team) }">
          <td><div class="crest">{{ crestText(team.name) }}</div></td>
          <td class="name">{{ team.name }} <span v-if="team.isCustomTeam" class="pill">Custom</span></td>
          <td class="faint">{{ team.country }}</td>
          <td class="r num">{{ team.prestige }}</td>
          <td class="r num">{{ team.carPerformance }}</td>
          <td class="r num">{{ team.seasonPoints }}</td>
          <td class="r num">{{ fmtMoney(team.finance?.cashReserves) }}</td>
          <td class="r"><span v-if="isMine(team)" class="pill mine">Your team</span></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 20px; }
.card-header h2 { margin: 0 0 16px; font-size: 16px; font-weight: 700; }
.error-banner { background: var(--accent-soft); border: 1px solid #e1060055; color: #ff7066; padding: 10px 14px; border-radius: 8px; margin-bottom: 14px; font-size: 12px; }
.data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table th { color: var(--faint); text-transform: uppercase; font-size: 10px; text-align: left; padding: 8px; border-bottom: 1px solid var(--line); }
.data-table td { padding: 12px 8px; border-bottom: 1px solid var(--surface-2); vertical-align: middle; }
.data-table th.r, .data-table td.r { text-align: right; }
.name { font-weight: 700; font-size: 14px; }
.crest { width: 28px; height: 28px; border-radius: 6px; background: var(--surface-2); display: grid; place-items: center; font-size: 10px; font-weight: 800; color: var(--muted); border: 1px solid var(--line); }
tr.me td { background: var(--accent-soft); }
.pill { background: var(--surface-2); color: var(--muted); padding: 2px 7px; border-radius: 10px; font-size: 10px; font-weight: 700; }
.pill.mine { background: var(--accent-soft); color: #ff7066; border: 1px solid #e1060055; }
.p-20 { padding: 20px; text-align: center; }
.faint { color: var(--faint); }
</style>
