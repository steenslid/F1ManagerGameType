<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api.js'
import { fmtMoney } from '../format.js'

const drivers = ref([])
const teamMap = ref({}) // id -> name
const isLoading = ref(true)
const error = ref(null)
const searchQuery = ref('')

onMounted(async () => {
  isLoading.value = true
  error.value = null
  try {
    const [driverRes, teamRes] = await Promise.all([api.listDrivers(), api.listTeams()])
    drivers.value = driverRes.data || []
    const map = {}
    for (const t of teamRes.data || []) map[t.id] = t.name
    teamMap.value = map
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    isLoading.value = false
  }
})

function teamName(d) {
  return d.currentRacingTeamId ? (teamMap.value[d.currentRacingTeamId] || d.currentRacingTeamId) : null
}

const rows = computed(() =>
  drivers.value
    .filter((d) => !d.retired)
    .map((d) => ({ ...d, teamName: teamName(d), rating: d.stats?.pace ?? 0 }))
)

const filtered = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return rows.value
  return rows.value.filter(
    (d) => d.name.toLowerCase().includes(q) || (d.teamName || '').toLowerCase().includes(q)
  )
})

function ratingClass(r) {
  if (r >= 88) return 'elite'
  if (r >= 78) return 'good'
  return ''
}
</script>

<template>
  <div class="card">
    <div class="card-header">
      <h2>Driver Database</h2>
      <input v-model="searchQuery" type="text" placeholder="Search drivers or teams…" class="search-input" />
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="isLoading" class="faint p-20 text-center">Loading drivers…</div>

    <table v-else class="data-table">
      <thead>
        <tr><th>Driver</th><th>Nat.</th><th class="r">Age</th><th>Team</th><th class="text-center">Pace</th><th class="r">Salary</th></tr>
      </thead>
      <tbody>
        <tr v-for="d in filtered" :key="d.id">
          <td class="name">{{ d.name }}</td>
          <td class="faint">{{ d.nationality }}</td>
          <td class="r num">{{ d.currentAge }}</td>
          <td><span v-if="d.teamName">{{ d.teamName }}</span><span v-else class="faint">Free agent</span></td>
          <td class="text-center"><span class="rating-badge" :class="ratingClass(d.rating)">{{ d.rating }}</span></td>
          <td class="r num faint">{{ fmtMoney(d.currentSalary) }}</td>
        </tr>
        <tr v-if="!filtered.length"><td colspan="6" class="faint text-center p-20">No drivers match your search.</td></tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 20px; }
.card-header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--line); padding-bottom: 16px; margin-bottom: 16px; }
.card-header h2 { margin: 0; font-size: 16px; font-weight: 700; }
.error-banner { background: var(--accent-soft); border: 1px solid #e1060055; color: #ff7066; padding: 10px 14px; border-radius: 8px; margin-bottom: 14px; font-size: 12px; }
.search-input { background: var(--bg); border: 1px solid var(--line); color: var(--fg); padding: 8px 12px; border-radius: 6px; font-size: 13px; outline: none; width: 250px; }
.search-input:focus { border-color: var(--accent); }
.data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table th { color: var(--faint); text-transform: uppercase; font-size: 10px; text-align: left; padding: 8px; border-bottom: 1px solid var(--line); }
.data-table td { padding: 12px 8px; border-bottom: 1px solid var(--surface-2); vertical-align: middle; }
.data-table th.r, .data-table td.r { text-align: right; }
.text-center { text-align: center; }
.name { font-weight: 700; font-size: 14px; }
.rating-badge { display: inline-block; padding: 2px 8px; border-radius: 4px; background: var(--surface-2); border: 1px solid var(--line); font-weight: 700; font-size: 12px; }
.rating-badge.elite { background: var(--accent-soft); color: #ff7066; border-color: #e1060055; }
.rating-badge.good { background: rgba(57,211,83,.1); color: var(--good); border-color: #39d35355; }
.p-20 { padding: 20px; }
.faint { color: var(--faint); }
</style>
