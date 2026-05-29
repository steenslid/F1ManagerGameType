<script setup>
import { ref, onMounted, computed } from 'vue'
import { api } from '../api.js'

const drivers = ref([])
const isLoading = ref(true)
const searchQuery = ref('')

const formatMoney = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(val || 0)

onMounted(async () => {
  try {
    // Assuming your api.js has a method that hits DriverRoutes.kt
    const res = await api.listDrivers()
    drivers.value = res.data || []
  } catch (e) {
    console.error("Failed to load drivers", e)
  } finally {
    isLoading.value = false
  }
})

// Simple client-side search filter
const filteredDrivers = computed(() => {
  if (!searchQuery.value) return drivers.value
  return drivers.value.filter(d =>
      d.name.toLowerCase().includes(searchQuery.value.toLowerCase()) ||
      (d.teamName && d.teamName.toLowerCase().includes(searchQuery.value.toLowerCase()))
  )
})
</script>

<template>
  <div class="card">
    <div class="card-header">
      <h2>Global Driver Database</h2>

      <div class="toolbar">
        <input
            type="text"
            v-model="searchQuery"
            placeholder="Search drivers or teams..."
            class="search-input"
        />
      </div>
    </div>

    <div v-if="isLoading" class="faint p-20 text-center">Loading scouting data...</div>

    <table v-else class="data-table">
      <thead>
      <tr>
        <th>Driver</th>
        <th>Age</th>
        <th>Current Team</th>
        <th class="text-center">Skill Rating</th>
        <th class="r">Estimated Salary</th>
      </tr>
      </thead>
      <tbody>
      <tr v-for="driver in filteredDrivers" :key="driver.id">
        <td class="name">{{ driver.name }}</td>
        <td class="num">{{ driver.currentAge || '--' }}</td>
        <td>
          <span v-if="driver.teamName">{{ driver.teamName }}</span>
          <span v-else class="faint">Free Agent</span>
        </td>
        <td class="text-center num">
          <!-- Mocking a skill rating badge since F1 games heavily rely on overall scores -->
          <span class="rating-badge" :class="{'elite': driver.rating >= 85, 'good': driver.rating >= 75 && driver.rating < 85}">
              {{ driver.rating || 80 }}
            </span>
        </td>
        <td class="r num faint">{{ formatMoney(driver.salary || driver.recommendedSalary) }}</td>
      </tr>
      <tr v-if="!filteredDrivers.length">
        <td colspan="5" class="faint text-center p-20">No drivers match your search.</td>
      </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 20px; }
.card-header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--line); padding-bottom: 16px; margin-bottom: 16px; }
.card-header h2 { margin: 0; font-size: 16px; font-weight: 700; }

.search-input { background: var(--bg); border: 1px solid var(--line); color: var(--fg); padding: 8px 12px; border-radius: 6px; font-size: 13px; outline: none; width: 250px; }
.search-input:focus { border-color: var(--accent); }

.data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table th { color: var(--faint); text-transform: uppercase; font-size: 10px; text-align: left; padding: 8px; border-bottom: 1px solid var(--line); }
.data-table td { padding: 12px 8px; border-bottom: 1px solid var(--surface-2); vertical-align: middle; }
.data-table th.r, .data-table td.r { text-align: right; }
.name { font-weight: 700; font-size: 14px; }

.rating-badge { display: inline-block; padding: 2px 6px; border-radius: 4px; background: var(--surface-2); border: 1px solid var(--line); font-weight: 700; font-size: 12px; }
.rating-badge.elite { background: var(--accent-soft); color: var(--accent); border-color: var(--accent); }
.rating-badge.good { background: rgba(57, 211, 83, 0.1); color: var(--good); border-color: var(--good); }

.text-center { text-align: center; }
.p-20 { padding: 20px; }
</style>
