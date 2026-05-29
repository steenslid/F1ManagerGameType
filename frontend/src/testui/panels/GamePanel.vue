<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../../api.js'

const state = ref(null)
const actions = ref(null)
const error = ref(null)
const loading = ref(false)
const advanceError = ref(null)
const transitions = ref([])

const teamsForPick = ref([])
const teamPick = ref('')
const teamPickError = ref(null)

async function refresh() {
  loading.value = true
  error.value = null
  try {
    const [s, a] = await Promise.all([api.getGameState(), api.getActions()])
    state.value = s.data
    actions.value = a.data
    if (state.value && !state.value.playerTeam) {
      await loadTeamsForPick()
    }
  } catch (e) {
    error.value = e.message
    state.value = null
    actions.value = null
  } finally {
    loading.value = false
  }
}

async function loadTeamsForPick() {
  try {
    const res = await api.listTeams({ series: 'F1' })
    teamsForPick.value = res.data
  } catch {
    // ignore — picker may show empty if a save isn't loaded
  }
}

async function selectTeam() {
  if (!teamPick.value) return
  teamPickError.value = null
  try {
    await api.selectTeam(teamPick.value)
    teamPick.value = ''
    await refresh()
  } catch (e) {
    teamPickError.value = e.message
  }
}

async function advance() {
  advanceError.value = null
  try {
    const res = await api.advance()
    transitions.value.unshift({ at: new Date(), ...res.data })
    await refresh()
  } catch (e) {
    advanceError.value = e.message
  }
}

function fmtTime(d) {
  return d.toLocaleTimeString()
}

onMounted(refresh)
</script>

<template>
  <div class="panel">
    <h2>Game state</h2>

    <section>
      <div class="toolbar">
        <button @click="refresh" :disabled="loading">Refresh</button>
      </div>

      <div v-if="error" class="error">{{ error }}</div>
      <div v-else-if="loading" class="loading">Loading…</div>
      <table v-else-if="state">
        <tbody>
          <tr><th>Save</th><td>{{ state.saveName }}</td></tr>
          <tr><th>Manager</th><td>{{ state.managerName }}</td></tr>
          <tr><th>Difficulty</th><td>{{ state.difficulty }}</td></tr>
          <tr>
            <th>Player team</th>
            <td>
              <span v-if="state.playerTeam">{{ state.playerTeam.name }}</span>
              <span v-else class="muted">(none selected)</span>
            </td>
          </tr>
          <tr><th>Year</th><td class="numeric">{{ state.year }}</td></tr>
          <tr><th>Round</th><td class="numeric">{{ state.round }}</td></tr>
          <tr><th>Phase</th><td><strong>{{ state.phase }}</strong></td></tr>
        </tbody>
      </table>
    </section>

    <section v-if="state && !state.playerTeam">
      <h3>Select your team</h3>
      <p class="muted">Pick the F1 team you'll be managing.</p>
      <div v-if="teamPickError" class="error">{{ teamPickError }}</div>
      <select v-model="teamPick">
        <option value="">—</option>
        <option v-for="t in teamsForPick" :key="t.id" :value="t.id">
          {{ t.name }}
        </option>
      </select>
      <button class="primary" @click="selectTeam" :disabled="!teamPick">
        Select team
      </button>
    </section>

    <section v-if="actions">
      <h3>Available actions</h3>
      <div v-if="advanceError" class="error">{{ advanceError }}</div>
      <div v-for="a in actions.actions" :key="a.name" style="margin-bottom: 4px;">
        <button
          v-if="a.name === 'advance'"
          class="primary"
          @click="advance"
        >
          {{ a.label }}
        </button>
        <span class="muted" style="margin-left: 8px;">
          {{ a.method }} {{ a.endpoint }}
        </span>
      </div>
    </section>

    <section v-if="transitions.length">
      <h3>Recent transitions</h3>
      <table>
        <thead>
          <tr>
            <th>Time</th>
            <th>Before</th>
            <th>After</th>
            <th>Events</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(t, i) in transitions" :key="i">
            <td class="muted">{{ fmtTime(t.at) }}</td>
            <td>
              <span class="pill">y{{ t.previous.year }} r{{ t.previous.round }}</span>
              {{ t.previous.phase }}
            </td>
            <td>
              <span class="pill">y{{ t.current.year }} r{{ t.current.round }}</span>
              <strong>{{ t.current.phase }}</strong>
            </td>
            <td class="numeric">{{ t.events.length }}</td>
          </tr>
        </tbody>
      </table>
      <details>
        <summary>Raw event log</summary>
        <pre>{{ JSON.stringify(transitions, null, 2) }}</pre>
      </details>
    </section>
  </div>
</template>
