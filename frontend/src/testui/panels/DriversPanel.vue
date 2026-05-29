<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../../api.js'

const drivers = ref([])
const teams = ref([])
const loading = ref(false)
const error = ref(null)
const elapsed = ref(null)
const teamFilter = ref('')
const freeAgentOnly = ref(false)

async function refresh() {
  loading.value = true
  error.value = null
  try {
    const filter = {}
    if (teamFilter.value) filter.team = teamFilter.value
    if (freeAgentOnly.value) filter.free_agent = 'true'

    const res = await api.listDrivers(filter)
    drivers.value = res.data
    elapsed.value = res.elapsedMs
  } catch (e) {
    error.value = e.message
    drivers.value = []
  } finally {
    loading.value = false
  }
}

async function loadTeams() {
  try {
    const res = await api.listTeams()
    teams.value = res.data
  } catch {
    // ignore — filter dropdown just shows manual entry
  }
}

function fmtMoney(n) {
  return '$' + new Intl.NumberFormat().format(n)
}

onMounted(async () => {
  await loadTeams()
  await refresh()
})
</script>

<template>
  <div class="panel">
    <h2>Drivers</h2>

    <section>
      <div class="toolbar">
        <button @click="refresh" :disabled="loading">Refresh</button>
        <label style="margin-left: 12px;">
          Team
          <select v-model="teamFilter">
            <option value="">(any)</option>
            <option v-for="t in teams" :key="t.id" :value="t.id">{{ t.name }}</option>
          </select>
        </label>
        <label>
          <input type="checkbox" v-model="freeAgentOnly" />
          Free agents only
        </label>
        <button @click="refresh">Apply</button>
        <span v-if="elapsed !== null" class="timing">{{ elapsed }} ms</span>
      </div>

      <div v-if="error" class="error">{{ error }}</div>
      <div v-else-if="loading" class="loading">Loading…</div>
      <table v-else-if="drivers.length">
        <thead>
          <tr>
            <th>Name</th>
            <th>Nat.</th>
            <th class="numeric">Age</th>
            <th>Team</th>
            <th class="numeric">Pace</th>
            <th class="numeric">Qual</th>
            <th class="numeric">Consist.</th>
            <th class="numeric">Tyres</th>
            <th class="numeric">Wet</th>
            <th class="numeric">Morale</th>
            <th class="numeric">Salary</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in drivers" :key="d.id">
            <td>{{ d.name }}</td>
            <td>{{ d.nationality }}</td>
            <td class="numeric">{{ d.currentAge }}</td>
            <td>
              <span v-if="d.currentRacingTeamId">{{ d.currentRacingTeamId }}</span>
              <span v-else class="muted">—</span>
            </td>
            <td class="numeric">{{ d.stats.pace }}</td>
            <td class="numeric">{{ d.stats.qualifying }}</td>
            <td class="numeric">{{ d.stats.consistency }}</td>
            <td class="numeric">{{ d.stats.tyreManagement }}</td>
            <td class="numeric">{{ d.stats.wetSkill }}</td>
            <td class="numeric">{{ d.morale }}</td>
            <td class="numeric">{{ fmtMoney(d.currentSalary) }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted">No drivers.</p>

      <details v-if="drivers.length">
        <summary>Raw response</summary>
        <pre>{{ JSON.stringify(drivers, null, 2) }}</pre>
      </details>
    </section>
  </div>
</template>
