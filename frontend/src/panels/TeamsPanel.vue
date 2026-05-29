<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api.js'

const teams = ref([])
const isLoading = ref(true)

const formatMoney = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(val || 0)

onMounted(async () => {
  try {
    const res = await api.listTeams()
    teams.value = res.data || []
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
      <h2>Constructors Directory</h2>
    </div>

    <div v-if="isLoading" class="faint p-20">Loading teams...</div>

    <table v-else class="data-table">
      <thead>
      <tr>
        <th>Logo</th>
        <th>Team Name</th>
        <th>Principal</th>
        <th>Engine Supplier</th>
        <th class="r">Cash Reserves</th>
      </tr>
      </thead>
      <tbody>
      <tr v-for="team in teams" :key="team.id">
        <td><div class="crest">{{ team.name.substring(0, 2).toUpperCase() }}</div></td>
        <td class="name">{{ team.name }}</td>
        <td>{{ team.principalName || 'N/A' }}</td>
        <td>{{ team.engineSupplierName || 'Unknown' }}</td>
        <td class="r num">{{ formatMoney(team.cashReserves) }}</td>
      </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 20px; }
.card-header h2 { margin: 0 0 16px; font-size: 16px; font-weight: 700; }
.data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table th { color: var(--faint); text-transform: uppercase; font-size: 10px; text-align: left; padding: 8px; border-bottom: 1px solid var(--line); }
.data-table td { padding: 12px 8px; border-bottom: 1px solid var(--surface-2); vertical-align: middle; }
.data-table th.r, .data-table td.r { text-align: right; }
.name { font-weight: 700; font-size: 14px; }
.crest { width: 28px; height: 28px; border-radius: 4px; background: var(--surface-2); display: grid; place-items: center; font-size: 10px; font-weight: 800; color: var(--muted); border: 1px solid var(--line); }
.p-20 { padding: 20px; text-align: center; }
</style>
