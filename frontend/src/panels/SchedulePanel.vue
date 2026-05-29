<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api.js'
import { useGame } from '../useGame.js'

const { state } = useGame()

const races = ref([])
const isLoading = ref(true)
const error = ref(null)

onMounted(async () => {
  isLoading.value = true
  error.value = null
  try {
    const season = state.overview?.year
    const res = await api.listRaces(season ? { season } : undefined)
    races.value = res.data || []
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    isLoading.value = false
  }
})

function status(round) {
  const cur = state.overview?.round || 0
  if (round < cur) return 'done'
  if (round === cur) return 'current'
  return 'upcoming'
}
</script>

<template>
  <div class="card">
    <div class="card-header"><h2>{{ state.overview?.year }} Season Schedule</h2></div>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="isLoading" class="faint p-20">Loading calendar…</div>

    <div v-else class="schedule-grid">
      <div class="race-card" v-for="race in races" :key="race.id" :class="status(race.round)">
        <div class="top">
          <span class="round-badge">Round {{ race.round }}</span>
          <span v-if="race.sessionFormat === 'SPRINT'" class="sprint-badge">Sprint</span>
        </div>
        <div class="race-name">{{ race.track?.name }}</div>
        <div class="race-loc muted">{{ race.track?.country }}</div>
        <div v-if="status(race.round) === 'current'" class="here">In progress →</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 20px; }
.card-header h2 { margin: 0 0 16px; font-size: 16px; font-weight: 700; }
.error-banner { background: var(--accent-soft); border: 1px solid #e1060055; color: #ff7066; padding: 10px 14px; border-radius: 8px; margin-bottom: 14px; font-size: 12px; }
.schedule-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 16px; }
.race-card { background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 16px; display: flex; flex-direction: column; gap: 8px; }
.race-card.done { opacity: .55; }
.race-card.current { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent-soft); }
.top { display: flex; align-items: center; justify-content: space-between; }
.round-badge { background: var(--surface-2); color: var(--fg); font-size: 10px; font-weight: 700; text-transform: uppercase; padding: 4px 8px; border-radius: 4px; }
.sprint-badge { color: var(--warn); border: 1px solid #e0b34155; font-size: 10px; font-weight: 700; text-transform: uppercase; padding: 3px 7px; border-radius: 4px; }
.race-name { font-weight: 700; font-size: 16px; }
.race-loc { font-size: 13px; }
.here { font-size: 11px; color: #ff7066; font-weight: 700; margin-top: 4px; }
.muted { color: var(--muted); }
.faint { color: var(--faint); }
.p-20 { padding: 20px; text-align: center; }
</style>
