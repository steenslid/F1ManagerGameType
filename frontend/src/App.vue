<script setup>
import { ref } from 'vue'
import SavesPanel from './panels/SavesPanel.vue'
import GamePanel from './panels/GamePanel.vue'
import CalendarPanel from './panels/CalendarPanel.vue'
import TeamsPanel from './panels/TeamsPanel.vue'
import DriversPanel from './panels/DriversPanel.vue'
import ReferencePanel from './panels/ReferencePanel.vue'

const tabs = [
  { id: 'saves', label: 'Saves', component: SavesPanel },
  { id: 'game', label: 'Game', component: GamePanel },
  { id: 'calendar', label: 'Calendar', component: CalendarPanel },
  { id: 'teams', label: 'Teams', component: TeamsPanel },
  { id: 'drivers', label: 'Drivers', component: DriversPanel },
  { id: 'reference', label: 'Reference', component: ReferencePanel },
]

const activeTab = ref('saves')
</script>

<template>
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
</template>
