<script setup>
import { ref } from 'vue'
import NavBar from './components/NavBar.vue'
import SeasonBar from './components/SeasonBar.vue'

// Importing the new, clean panels
import SavesPanel from './panels/SavesPanel.vue'
import DashboardPanel from './panels/DashboardPanel.vue'
import MarketPanel from './panels/MarketPanel.vue'
import TeamsPanel from './panels/TeamsPanel.vue'
import SchedulePanel from "./panels/SchedulePanel.vue";
import RaceWeekendPanel from "./panels/RaceWeekendPanel.vue";
import DriversPanel from "./panels/DriversPanel.vue";

const appState = ref('saves')
const activePanel = ref('Dashboard')

const handleSaveLoaded = () => {
  appState.value = 'game'
  activePanel.value = 'Dashboard'
}

const handleAdvance = () => {
  console.log("Advancing game state...")
}
</script>

<template>
  <div class="new-ui-wrapper">
    <SavesPanel
        v-if="appState === 'saves'"
        @saveLoaded="handleSaveLoaded"
    />

    <div v-else-if="appState === 'game'">
      <NavBar v-model:activePanel="activePanel" />
      <SeasonBar @advance="handleAdvance" />

      <main class="wrap">
        <DashboardPanel v-if="activePanel === 'Dashboard'" />
        <RaceWeekendPanel v-else-if="activePanel === 'Race Weekend'" />
        <MarketPanel v-else-if="activePanel === 'Market' || activePanel === 'Finance'" />
        <TeamsPanel v-else-if="activePanel === 'Teams'" />
        <SchedulePanel v-else-if="activePanel === 'Schedule'" />
        <DriversPanel v-else-if="activePanel === 'Drivers'" />


      <div v-else class="card" style="text-align: center; padding: 40px;">
        <h2>{{ activePanel }}</h2>
        <p class="faint">Component not linked yet.</p>
      </div>
      </main>
    </div>
  </div>
</template>

