<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../../api.js'

const state = ref(null)
const deals = ref([])
const teams = ref([])
const yearInput = ref('')
const error = ref(null)
const loading = ref(false)

async function refresh() {
  loading.value = true
  error.value = null
  try {
    const stateRes = await api.getGameState()
    state.value = stateRes.data
    if (!yearInput.value) yearInput.value = String(state.value.year)

    const year = Number(yearInput.value) || state.value.year
    const [d, t] = await Promise.all([
      api.listTeamSponsorships({ active_in: year }),
      api.listTeams({ series: 'F1' }),
    ])
    deals.value = d.data
    teams.value = t.data
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

// Group deals by team for display.
const dealsByTeam = computed(() => {
  const groups = {}
  for (const t of teams.value) groups[t.id] = { team: t, deals: [] }
  for (const d of deals.value) {
    if (groups[d.teamId]) groups[d.teamId].deals.push(d)
  }
  // Sort teams by total deal value desc.
  return Object.values(groups)
    .map(g => ({
      ...g,
      total: g.deals.reduce((sum, d) => sum + d.annualValue, 0),
    }))
    .sort((a, b) => b.total - a.total)
})

function fmtMoney(n) {
  if (n >= 1_000_000) return `$${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `$${(n / 1_000).toFixed(0)}k`
  return `$${n}`
}

onMounted(refresh)
</script>

<template>
  <div class="panel">
    <h2>Sponsorships</h2>

    <section>
      <div class="toolbar">
        <label>
          Active in year:
          <input
            v-model="yearInput"
            type="number"
            placeholder="current"
            style="width: 80px;"
          />
        </label>
        <button @click="refresh" :disabled="loading">Refresh</button>
      </div>
      <div v-if="error" class="error">{{ error }}</div>
      <div v-else-if="loading" class="loading">Loading…</div>
    </section>

    <section v-if="!loading && dealsByTeam.length">
      <div v-for="group in dealsByTeam" :key="group.team.id" style="margin-bottom: 20px;">
        <h3>
          {{ group.team.name }}
          <span class="muted">
            — {{ group.deals.length }} active,
            total <strong>{{ fmtMoney(group.total) }}</strong>/yr
          </span>
        </h3>
        <table v-if="group.deals.length">
          <thead>
            <tr>
              <th>Sponsor</th>
              <th>Tier</th>
              <th class="numeric">Annual</th>
              <th class="numeric">Start</th>
              <th class="numeric">End</th>
              <th>Notes</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in group.deals" :key="d.id">
              <td>{{ d.sponsorName }}</td>
              <td><span class="pill">{{ d.sponsorTier }}</span></td>
              <td class="numeric"><strong>{{ fmtMoney(d.annualValue) }}</strong></td>
              <td class="numeric">{{ d.startYear }}</td>
              <td class="numeric">{{ d.endYear }}</td>
              <td>
                <span v-if="d.isTitle" class="pill">TITLE</span>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else class="muted">No active deals.</p>
      </div>
    </section>

    <section v-if="!loading">
      <details>
        <summary>Raw deal rows ({{ deals.length }})</summary>
        <pre>{{ JSON.stringify(deals, null, 2) }}</pre>
      </details>
    </section>
  </div>
</template>
