<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api.js'

const emit = defineEmits(['advance'])

const teamName = ref('Loading Team...')
const teamPrincipal = ref('Manager')
const season = ref(2026)
const round = ref(1)
const phase = ref('PRE_SEASON')
const cash = ref(0)
const teamCrest = ref('F1')

// Format cash into a clean "$214.6M" format for the top bar
const formatCash = (val) => {
  if (val === undefined || val === null) return '$0'
  if (val >= 1000000) return `$${(val / 1000000).toFixed(1)}M`
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(val)
}

const loadContext = async () => {
  try {
    const stateRes = await api.getGameState()
    const state = stateRes.data || {}

    season.value = state.currentSeasonYear || 2026
    round.value = state.currentRound || 1
    phase.value = state.phase || 'PRE_SEASON'
    teamPrincipal.value = state.managerName || 'Player'

    if (state.playerTeamId) {
      const teamsRes = await api.listTeams()
      const myTeam = (teamsRes.data || []).find(t => t.id === state.playerTeamId)
      if (myTeam) {
        teamName.value = myTeam.name
        cash.value = myTeam.cashReserves || 0
        teamCrest.value = myTeam.name.substring(0, 2).toUpperCase()
      }
    }
  } catch (e) {
    console.error("SeasonBar failed to load game state", e)
  }
}

onMounted(loadContext)
</script>

<template>
  <div class="topbar">
    <div class="team">
      <div class="crest">{{ teamCrest }}</div>
      <div>
        <div class="name">{{ teamName }}</div>
        <div class="role">Team Principal · {{ teamPrincipal }}</div>
      </div>
    </div>

    <div class="ctx">
      <div class="item"><span class="k">Season</span><span class="v num">{{ season }}</span></div>
      <div class="item"><span class="k">Round</span><span class="v num">{{ round }}</span></div>
      <div class="item"><span class="k">Phase</span><span class="phase-pill">{{ phase }}</span></div>
      <div class="item"><span class="k">Cash</span><span class="v num">{{ formatCash(cash) }}</span></div>
    </div>

    <div class="advance">
      <button class="btn" @click="emit('advance')">Advance Schedule →</button>
    </div>
  </div>
</template>

<style scoped>
.topbar {
  position: sticky; top: 45px; z-index: 5; display: flex; align-items: center; gap: 20px;
  padding: 10px 20px; background: linear-gradient(180deg, #15181d, #111317);
  border-bottom: 1px solid var(--line);
}
.team { display: flex; align-items: center; gap: 10px; min-width: 210px; }
.crest {
  width: 30px; height: 30px; border-radius: 7px; background: var(--accent);
  display: grid; place-items: center; font-weight: 800; color: #fff; font-size: 13px;
}
.name { font-weight: 700; letter-spacing: .2px; }
.role { font-size: 11px; color: var(--muted); }
.ctx { display: flex; align-items: center; gap: 22px; flex: 1; }
.item { display: flex; flex-direction: column; line-height: 1.25; }
.k { font-size: 10px; text-transform: uppercase; letter-spacing: .8px; color: var(--faint); }
.v { font-weight: 600; }
.phase-pill {
  padding: 3px 10px; border-radius: 20px; background: var(--accent-soft);
  color: #ff7066; border: 1px solid #e1060055; font-weight: 700; font-size: 11px; letter-spacing: .4px;
}
.advance { margin-left: auto; display: flex; align-items: center; gap: 12px; }
.btn {
  background: var(--accent); color: #fff; border: none; padding: 10px 18px; border-radius: 9px;
  font-weight: 700; font-size: 13px; cursor: pointer; letter-spacing: .2px; transition: 0.2s;
}
.btn:hover { filter: brightness(1.1); }
</style>
