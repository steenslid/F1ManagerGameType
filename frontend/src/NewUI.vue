<script setup>
import { ref } from 'vue'
import { useGame } from './useGame.js'
import { fmtMoney, phaseLabel, crestText } from './format.js'

import SavesPanel from './panels/SavesPanel.vue'
import TeamSelectPanel from './panels/TeamSelectPanel.vue'
import DashboardPanel from './panels/DashboardPanel.vue'
import RaceWeekendPanel from './panels/RaceWeekendPanel.vue'
import MarketPanel from './panels/MarketPanel.vue'
import TeamsPanel from './panels/TeamsPanel.vue'
import SchedulePanel from './panels/SchedulePanel.vue'
import DriversPanel from './panels/DriversPanel.vue'
import EventFeed from './panels/EventFeed.vue'

const emit = defineEmits(['open-test-ui'])

const {
  state, myTeam, hasTeam, advanceLabel, refreshAll, advance,
  simToNextRace, simToOffSeason,
} = useGame()

const simOpen = ref(false)

async function runSim(which) {
  simOpen.value = false
  if (which === 'race') await simToNextRace()
  else if (which === 'offseason') await simToOffSeason()
}

const appState = ref('saves') // 'saves' | 'select-team' | 'game'
const activePanel = ref('Dashboard')

// Real screens first, then "coming soon" stubs for systems not built yet.
const menuItems = [
  'Dashboard', 'Race Weekend', 'Schedule', 'Drivers', 'Market', 'Teams',
]
const stubItems = ['Staff', 'Academy', 'History']

async function handleSaveLoaded() {
  await refreshAll()
  activePanel.value = 'Dashboard'
  // A fresh save has no player team yet — route into the picker first.
  appState.value = hasTeam.value ? 'game' : 'select-team'
}

function handleTeamSelected() {
  activePanel.value = 'Dashboard'
  appState.value = 'game'
}
</script>

<template>
  <div class="new-ui-wrapper">
    <SavesPanel v-if="appState === 'saves'" @save-loaded="handleSaveLoaded" />

    <TeamSelectPanel v-else-if="appState === 'select-team'" @team-selected="handleTeamSelected" />

    <div v-else>
      <!-- Top nav -->
      <nav class="nav">
        <span class="brand">F1<b>SIM</b></span>
        <a
          v-for="item in menuItems"
          :key="item"
          :class="{ active: activePanel === item }"
          @click="activePanel = item"
        >{{ item }}</a>
        <a
          v-for="item in stubItems"
          :key="item"
          :class="{ active: activePanel === item }"
          @click="activePanel = item"
        >{{ item }}</a>
        <a class="spacer faint">Settings</a>
        <a class="ext" @click="emit('open-test-ui')">Test UI ↗</a>
      </nav>

      <!-- Season-control bar -->
      <div class="topbar">
        <div class="team">
          <div class="crest" :class="{ empty: !hasTeam }">
            {{ hasTeam ? crestText(myTeam?.name) : '?' }}
          </div>
          <div>
            <div class="name">{{ hasTeam ? myTeam?.name : 'No team selected' }}</div>
            <div class="role">
              <template v-if="hasTeam">Team Principal · {{ state.overview?.managerName }}</template>
              <template v-else>
                <a class="link-inline" @click="activePanel = 'Teams'">Take control of a team →</a>
              </template>
            </div>
          </div>
        </div>

        <div class="ctx">
          <div class="item"><span class="k">Season</span><span class="v num">{{ state.overview?.year ?? '—' }}</span></div>
          <div class="item"><span class="k">Round</span><span class="v num">{{ state.overview?.round || '—' }}</span></div>
          <div class="item"><span class="k">Phase</span><span class="phase-pill">{{ phaseLabel(state.overview?.phase) }}</span></div>
          <div class="item"><span class="k">Cash</span><span class="v num">{{ hasTeam ? fmtMoney(myTeam?.finance?.cashReserves) : '—' }}</span></div>
        </div>

        <div class="advance">
          <span v-if="state.error" class="err">{{ state.error }}</span>

          <div class="sim-wrap">
            <button
              class="btn ghost"
              :disabled="state.advancing || state.simming"
              @click="simOpen = !simOpen"
            >{{ state.simming ? 'Simulating…' : 'Sim ▾' }}</button>
            <template v-if="simOpen">
              <div class="sim-backdrop" @click="simOpen = false"></div>
              <div class="sim-menu">
                <button @click="runSim('race')">Sim to next race</button>
                <button @click="runSim('offseason')">Sim to off-season</button>
              </div>
            </template>
          </div>

          <button class="btn" :disabled="state.advancing || state.simming" @click="advance">
            {{ state.advancing ? 'Advancing…' : advanceLabel }}
          </button>
        </div>
      </div>

      <main class="wrap">
        <DashboardPanel v-if="activePanel === 'Dashboard'" @navigate="activePanel = $event" />
        <RaceWeekendPanel v-else-if="activePanel === 'Race Weekend'" />
        <MarketPanel v-else-if="activePanel === 'Market'" />
        <TeamsPanel v-else-if="activePanel === 'Teams'" />
        <SchedulePanel v-else-if="activePanel === 'Schedule'" />
        <DriversPanel v-else-if="activePanel === 'Drivers'" />
        <div v-else class="card placeholder">
          <h2>{{ activePanel }}</h2>
          <p class="faint">This system isn't built yet — coming soon.</p>
        </div>
      </main>

      <EventFeed />
    </div>
  </div>
</template>

<style scoped>
/* Top nav */
.nav {
  display: flex; align-items: center; gap: 2px; padding: 0 18px;
  background: #0b0d10; border-bottom: 1px solid var(--line);
  position: sticky; top: 0; z-index: 6;
}
.brand { font-weight: 800; letter-spacing: 1.5px; color: #fff; margin-right: 18px; font-size: 13px; }
.brand b { color: var(--accent); }
.nav a {
  padding: 14px 12px; font-size: 13px; color: #cdd2d8; font-weight: 600;
  border-bottom: 2px solid transparent; cursor: pointer; white-space: nowrap;
}
.nav a:hover { color: #fff; }
.nav a.active { color: var(--accent); border-bottom-color: var(--accent); }
.nav .spacer { margin-left: auto; }
.nav a.ext { color: var(--muted); }
.nav a.ext:hover { color: #fff; }
.nav a.faint { color: var(--faint); }

/* Season-control bar */
.topbar {
  position: sticky; top: 45px; z-index: 5; display: flex; align-items: center; gap: 20px;
  padding: 10px 20px; background: linear-gradient(180deg, #15181d, #111317);
  border-bottom: 1px solid var(--line);
}
.team { display: flex; align-items: center; gap: 10px; min-width: 230px; }
.crest {
  width: 30px; height: 30px; border-radius: 7px; background: var(--accent);
  display: grid; place-items: center; font-weight: 800; color: #fff; font-size: 13px;
}
.crest.empty { background: var(--surface-2); color: var(--faint); border: 1px solid var(--line); }
.name { font-weight: 700; letter-spacing: .2px; }
.role { font-size: 11px; color: var(--muted); }
.link-inline { color: #ff7066; cursor: pointer; font-weight: 600; }
.link-inline:hover { text-decoration: underline; }
.ctx { display: flex; align-items: center; gap: 22px; flex: 1; }
.item { display: flex; flex-direction: column; line-height: 1.25; }
.k { font-size: 10px; text-transform: uppercase; letter-spacing: .8px; color: var(--faint); }
.v { font-weight: 600; }
.phase-pill {
  padding: 3px 10px; border-radius: 20px; background: var(--accent-soft);
  color: #ff7066; border: 1px solid #e1060055; font-weight: 700; font-size: 11px; letter-spacing: .4px;
}
.advance { margin-left: auto; display: flex; align-items: center; gap: 12px; }
.err { color: var(--bad); font-size: 12px; max-width: 280px; }
.btn {
  background: var(--accent); color: #fff; border: none; padding: 10px 18px; border-radius: 9px;
  font-weight: 700; font-size: 13px; cursor: pointer; letter-spacing: .2px; transition: .2s;
}
.btn:hover:not(:disabled) { filter: brightness(1.1); }
.btn:disabled { opacity: .6; cursor: not-allowed; }
.btn.ghost { background: var(--surface-2); color: var(--fg); border: 1px solid var(--line); }
.btn.ghost:hover:not(:disabled) { filter: none; border-color: var(--muted); }
.sim-wrap { position: relative; }
.sim-backdrop { position: fixed; inset: 0; z-index: 19; }
.sim-menu {
  position: absolute; top: calc(100% + 6px); right: 0; z-index: 20; min-width: 180px;
  background: var(--surface); border: 1px solid var(--line); border-radius: 10px;
  box-shadow: 0 10px 30px rgba(0,0,0,.5); overflow: hidden; padding: 4px;
}
.sim-menu button {
  display: block; width: 100%; text-align: left; background: transparent; border: none;
  color: var(--fg); padding: 9px 12px; border-radius: 6px; cursor: pointer; font-size: 13px; font-weight: 600;
}
.sim-menu button:hover { background: var(--surface-2); }

.placeholder { text-align: center; padding: 48px; }
.placeholder h2 { margin: 0 0 8px; }
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 20px; }
</style>
