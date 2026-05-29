<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../../api.js'

const teams = ref([])
const loading = ref(false)
const error = ref(null)
const elapsed = ref(null)
const series = ref('')

async function refresh() {
  loading.value = true
  error.value = null
  try {
    const filter = series.value ? { series: series.value } : null
    const res = await api.listTeams(filter)
    teams.value = res.data
    elapsed.value = res.elapsedMs
  } catch (e) {
    error.value = e.message
    teams.value = []
  } finally {
    loading.value = false
  }
}

function fmtMoney(n) {
  return '$' + new Intl.NumberFormat().format(n)
}

onMounted(refresh)
</script>

<template>
  <div class="panel">
    <h2>Teams</h2>

    <section>
      <div class="toolbar">
        <button @click="refresh" :disabled="loading">Refresh</button>
        <label style="margin-left: 12px;">
          Series
          <select v-model="series">
            <option value="">(all)</option>
            <option>F1</option>
            <option>F2</option>
            <option>F3</option>
          </select>
        </label>
        <button @click="refresh">Apply</button>
        <span v-if="elapsed !== null" class="timing">{{ elapsed }} ms</span>
      </div>

      <div v-if="error" class="error">{{ error }}</div>
      <div v-else-if="loading" class="loading">Loading…</div>
      <table v-else-if="teams.length">
        <thead>
          <tr>
            <th>Name</th>
            <th>Country</th>
            <th>Series</th>
            <th class="numeric">Prestige</th>
            <th class="numeric">Cash</th>
            <th class="numeric">Pit crew</th>
            <th class="numeric">Season pts</th>
            <th>Cap</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in teams" :key="t.id">
            <td>{{ t.name }}</td>
            <td>{{ t.country }}</td>
            <td><span class="pill">{{ t.series }}</span></td>
            <td class="numeric">{{ t.prestige }}</td>
            <td class="numeric">{{ fmtMoney(t.finance.cashReserves) }}</td>
            <td class="numeric">{{ t.pitCrewRating }}</td>
            <td class="numeric">{{ t.seasonPoints }}</td>
            <td>
              <span class="pill">{{ t.finance.capComplianceStatus }}</span>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted">No teams.</p>

      <details v-if="teams.length">
        <summary>Raw response</summary>
        <pre>{{ JSON.stringify(teams, null, 2) }}</pre>
      </details>
    </section>
  </div>
</template>
