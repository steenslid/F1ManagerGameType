<script setup>
import { ref } from 'vue'
import SavesPanel from './panels/SavesPanel.vue'
import GamePanel from './panels/GamePanel.vue'
import CalendarPanel from './panels/CalendarPanel.vue'
import TeamsPanel from './panels/TeamsPanel.vue'
import SponsorshipsPanel from './panels/SponsorshipsPanel.vue'
import DriversPanel from './panels/DriversPanel.vue'
import PracticePanel from './panels/PracticePanel.vue'
import StrategyPanel from './panels/StrategyPanel.vue'
import ResultsPanel from './panels/ResultsPanel.vue'
import MarketPanel from './panels/MarketPanel.vue'
import OffSeasonPanel from './panels/OffSeasonPanel.vue'
import ReferencePanel from './panels/ReferencePanel.vue'

const tabs = [
  { id: 'saves', label: 'Saves', component: SavesPanel },
  { id: 'game', label: 'Game', component: GamePanel },
  { id: 'calendar', label: 'Calendar', component: CalendarPanel },
  { id: 'teams', label: 'Teams', component: TeamsPanel },
  { id: 'sponsorships', label: 'Sponsorships', component: SponsorshipsPanel },
  { id: 'drivers', label: 'Drivers', component: DriversPanel },
  { id: 'practice', label: 'Practice', component: PracticePanel },
  { id: 'strategy', label: 'Strategy', component: StrategyPanel },
  { id: 'results', label: 'Results', component: ResultsPanel },
  { id: 'market', label: 'Market', component: MarketPanel },
  { id: 'offseason', label: 'Off-Season', component: OffSeasonPanel },
  { id: 'reference', label: 'Reference', component: ReferencePanel },
]

const activeTab = ref('saves')
</script>

<template>
  <div class="test-ui-wrapper">
    <header>
      <h1>F1 Sim — test UI</h1>
      <div class="subtitle">Talks to backend at localhost:7777</div>
    </header>

    <nav class="tabs">
      <button
        v-for="t in tabs"
        :key="t.id"
        :class="{ active: activeTab === t.id }"
        @click="activeTab = t.id"
      >
        {{ t.label }}
      </button>
    </nav>

    <main>
      <component :is="tabs.find(t => t.id === activeTab).component" />
    </main>
</div>
</template>
