<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api.js'

const nextRace = ref({})
const constructorStandings = ref([])
const driverStandings = ref([])
const teamFinances = ref({ cash: 0, income: 0, expenses: 0 })
const playerTeamId = ref(null)
const isLoading = ref(true)

const formatMoney = (val) => {
  if (val === undefined || val === null) return '$0'
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(val)
}

onMounted(async () => {
  try {
    isLoading.value = true

    // Fetch all required dashboard context in parallel
    const [stateRes, raceRes, standingsRes] = await Promise.all([
      api.getGameState(),
      api.getCurrentRace(),
      api.getStandings()
    ])

    playerTeamId.value = stateRes.data?.playerTeamId
    nextRace.value = raceRes.data || {}

    // Parse Standings
    if (standingsRes.data) {
      if (Array.isArray(standingsRes.data)) {
        driverStandings.value = standingsRes.data.slice(0, 10)
      } else {
        driverStandings.value = (standingsRes.data.drivers || []).slice(0, 10)
        constructorStandings.value = standingsRes.data.teams || []
      }
    }

    // Fetch the player's specific team accurately from the list
    if (playerTeamId.value) {
      const teamRes = await api.listTeams()
      const myTeam = (teamRes.data || []).find(t => t.id === playerTeamId.value)

      if (myTeam) {
        teamFinances.value = {
          cash: myTeam.cashReserves || 0,
          income: myTeam.currentYearIncome || 0,
          expenses: myTeam.currentYearExpenses || 0
        }
      }
    }
  } catch (e) {
    console.error("Failed to load dashboard data", e)
  } finally {
    isLoading.value = false
  }
})
</script>

<template>
  <div v-if="isLoading" class="faint" style="padding: 20px; text-align: center;">Loading dashboard...</div>
  <div v-else class="dashboard-grid">

    <div class="card next-race">
      <div class="card-header">
        <h2>Next Race</h2>
        <span class="badge" v-if="nextRace.round">Round {{ nextRace.round }}</span>
      </div>
      <div class="race-hero">
        <div class="race-title">{{ nextRace.name || 'TBD - Pre Season' }}</div>
        <div class="race-sub">{{ nextRace.location || 'Awaiting Calendar' }}</div>
      </div>
      <div class="race-stats" v-if="nextRace.laps || nextRace.name">
        <div class="stat"><span class="k">Laps</span><span class="v num">{{ nextRace.laps || '--' }}</span></div>
        <div class="stat"><span class="k">Weather</span><span class="v">{{ nextRace.weather || 'Unknown' }}</span></div>
        <div class="stat"><span class="k">Track Temp</span><span class="v num">{{ nextRace.trackTemp || '--' }}</span></div>
      </div>
    </div>

    <div class="card status">
      <div class="card-header">
        <h2>Team Finances</h2>
      </div>
      <div class="status-list">
        <div class="status-item">
          <div class="k">Available Cash</div>
          <div class="v num good">{{ formatMoney(teamFinances.cash) }}</div>
        </div>
        <div class="status-item">
          <div class="k">Projected Income</div>
          <div class="v num">{{ formatMoney(teamFinances.income) }}</div>
        </div>
        <div class="status-item">
          <div class="k">Projected Expenses</div>
          <div class="v num" style="color: var(--bad);">- {{ formatMoney(teamFinances.expenses) }}</div>
        </div>
      </div>
    </div>

    <div class="card standings">
      <div class="card-header">
        <h2>Constructors</h2>
        <a class="view-all">View All</a>
      </div>
      <table class="standings-table">
        <tbody>
        <tr v-for="(team, index) in constructorStandings" :key="team.id" :class="{ 'is-player': team.id === playerTeamId }">
          <td class="pos num">{{ index + 1 }}</td>
          <td class="indicator"><div class="color-bar" style="background: var(--surface-2)"></div></td>
          <td class="name">{{ team.name }}</td>
          <td class="pts num">{{ team.points || team.seasonPoints || 0 }} <span class="faint">PTS</span></td>
        </tr>
        <tr v-if="!constructorStandings.length">
          <td class="faint text-center p-20" colspan="4">No constructors standings data yet. (New Season)</td>
        </tr>
        </tbody>
      </table>
    </div>

    <div class="card standings">
      <div class="card-header">
        <h2>Drivers</h2>
        <a class="view-all">View All</a>
      </div>
      <table class="standings-table">
        <tbody>
        <tr v-for="(driver, index) in driverStandings" :key="driver.id" :class="{ 'is-player': driver.teamId === playerTeamId }">
          <td class="pos num">{{ index + 1 }}</td>
          <td class="name">
            {{ driver.name }}
            <div class="team-sub">{{ driver.teamName || 'Free Agent' }}</div>
          </td>
          <td class="pts num">{{ driver.points || driver.seasonPoints || 0 }} <span class="faint">PTS</span></td>
        </tr>
        <tr v-if="!driverStandings.length">
          <td class="faint text-center p-20" colspan="3">No driver standings data yet. (New Season)</td>
        </tr>
        </tbody>
      </table>
    </div>

  </div>
</template>

<style scoped>
.dashboard-grid {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 20px;
}

.card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 20px;
  display: flex;
  flex-direction: column;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.card-header h2 {
  margin: 0;
  font-size: 14px;
  text-transform: uppercase;
  letter-spacing: 1px;
  color: var(--muted);
  font-weight: 600;
}

.badge { background: var(--surface-2); padding: 4px 8px; border-radius: 4px; font-size: 11px; font-weight: 700; color: var(--fg); }
.view-all { font-size: 12px; color: var(--muted); cursor: pointer; }
.view-all:hover { color: var(--fg); }

/* Next Race */
.next-race { grid-column: 1 / 2; }
.race-hero { background: linear-gradient(135deg, var(--surface-2) 0%, var(--surface) 100%); padding: 30px 20px; border-radius: 8px; margin-bottom: 16px; border: 1px solid var(--line); }
.race-title { font-size: 28px; font-weight: 800; margin-bottom: 4px; }
.race-sub { color: var(--muted); font-size: 14px; }
.race-stats { display: flex; gap: 30px; }
.stat { display: flex; flex-direction: column; }
.stat .k { font-size: 11px; color: var(--faint); text-transform: uppercase; margin-bottom: 4px; }
.stat .v { font-size: 16px; font-weight: 600; }

/* Status List */
.status { grid-column: 2 / 3; }
.status-list { display: flex; flex-direction: column; gap: 16px; }
.status-item { padding-bottom: 16px; border-bottom: 1px solid var(--line); }
.status-item:last-child { border-bottom: none; padding-bottom: 0; }
.status-item .k { font-size: 12px; color: var(--muted); margin-bottom: 4px; }
.status-item .v { font-size: 18px; font-weight: 600; }
.good { color: var(--good); }

/* Standings */
.standings { grid-column: span 1; }
.standings-table { width: 100%; border-collapse: collapse; }
.standings-table tr { border-bottom: 1px solid var(--surface-2); }
.standings-table tr:last-child { border-bottom: none; }
.standings-table td { padding: 12px 8px; font-size: 14px; }
.standings-table .pos { width: 30px; color: var(--muted); font-weight: 600; text-align: center; }
.standings-table .indicator { width: 10px; padding: 0; }
.color-bar { width: 4px; height: 16px; border-radius: 2px; }
.standings-table .name { font-weight: 600; }
.standings-table .team-sub { font-size: 11px; color: var(--muted); font-weight: 400; margin-top: 2px; }
.standings-table .pts { text-align: right; font-weight: 700; }
.standings-table .faint { color: var(--faint); font-size: 11px; font-weight: 500; }
.is-player td { background: var(--surface-2); }
.is-player .name { color: var(--accent); }
.p-20 { padding: 20px; }
</style>
