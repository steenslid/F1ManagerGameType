<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api.js'

const races = ref([])
const isLoading = ref(true)

onMounted(async () => {
  try {
    const res = await api.listRaces() // Or whatever your route is for the calendar
    races.value = res.data || []
  } catch (e) {
    console.error(e)
  } finally {
    isLoading.value = false
  }
})
</script>

<template>
  <div class="card">
    <div class="card-header">
      <h2>Season Schedule</h2>
    </div>

    <div v-if="isLoading" class="faint p-20">Loading calendar...</div>

    <div v-else class="schedule-grid">
      <div class="race-card" v-for="(race, index) in races" :key="race.id">
        <div class="round-badge">Round {{ index + 1 }}</div>
        <div class="race-info">
          <div class="race-name">{{ race.name }}</div>
          <div class="race-loc muted">{{ race.location || 'Track' }}</div>
        </div>
        <div class="race-laps num faint">{{ race.laps || 50 }} Laps</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 20px; }
.card-header h2 { margin: 0 0 16px; font-size: 16px; font-weight: 700; }
.schedule-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 16px; }
.race-card { background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 16px; display: flex; flex-direction: column; gap: 12px; }
.round-badge { align-self: flex-start; background: var(--surface-2); color: var(--fg); font-size: 10px; font-weight: 700; text-transform: uppercase; padding: 4px 8px; border-radius: 4px; }
.race-name { font-weight: 700; font-size: 16px; }
.race-loc { font-size: 13px; margin-top: 2px;}
.race-laps { font-size: 12px; border-top: 1px solid var(--surface-2); padding-top: 12px; margin-top: auto; }
.p-20 { padding: 20px; text-align: center; }
</style>
