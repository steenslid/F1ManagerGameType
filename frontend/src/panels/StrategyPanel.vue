<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api.js'

const view = ref(null)
const error = ref(null)
const loading = ref(false)
const submitError = ref(null)

// Order matters: most-conservative to most-aggressive.
const archetypeOptions = [
  { value: 'M_H',   label: 'M-H — Medium → Hard (safe, predictable)' },
  { value: 'M_M_H', label: 'M-M-H — three-stop, neutral' },
  { value: 'S_H',   label: 'S-H — Soft → Hard (front-loaded pace)' },
  { value: 'S_M_M', label: 'S-M-M — three-stop, faster soft start' },
  { value: 'S_S_H', label: 'S-S-H — aggressive (max pace, high variance)' },
]

async function refresh() {
  loading.value = true
  error.value = null
  submitError.value = null
  try {
    const res = await api.viewStrategy()
    view.value = res.data
  } catch (e) {
    error.value = e.message
    view.value = null
  } finally {
    loading.value = false
  }
}

async function setStrategy(driverId, archetype) {
  submitError.value = null
  try {
    const res = await api.setStrategy(driverId, archetype)
    view.value = res.data
  } catch (e) {
    submitError.value = e.message
  }
}

onMounted(refresh)
</script>

<template>
  <div class="panel">
    <h2>Strategy</h2>

    <section>
      <div class="toolbar">
        <button @click="refresh" :disabled="loading">Refresh</button>
      </div>
      <div v-if="error" class="error">{{ error }}</div>
      <div v-else-if="loading" class="loading">Loading…</div>
    </section>

    <section v-if="view">
      <p>
        <strong>Race weekend:</strong>
        y{{ view.seasonYear }} r{{ view.round }}
        <span class="muted"> — </span>
        <span v-if="view.canEdit" class="pill">EDITABLE</span>
        <span v-else class="pill">READ-ONLY (not in QUALIFYING phase)</span>
      </p>
      <p class="muted" v-if="!view.canEdit">
        Strategy can only be set during QUALIFYING — after the grid is decided
        and before the race runs.
      </p>

      <div v-if="submitError" class="error">{{ submitError }}</div>

      <table>
        <thead>
          <tr>
            <th class="numeric">Grid</th>
            <th>Driver</th>
            <th>Team</th>
            <th>Current strategy</th>
            <th>Set</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in view.entries" :key="e.driverId">
            <td class="numeric">
              <span v-if="e.gridPosition">{{ e.gridPosition }}</span>
              <span v-else class="muted">—</span>
            </td>
            <td>{{ e.driverName }}</td>
            <td>{{ e.teamName }}</td>
            <td>
              <span v-if="e.archetype" class="pill">{{ e.archetype }}</span>
              <span v-else class="muted">(default M-H)</span>
            </td>
            <td>
              <select
                :disabled="!view.canEdit"
                :value="e.archetype ?? ''"
                @change="(ev) => ev.target.value && setStrategy(e.driverId, ev.target.value)"
              >
                <option value="" disabled>— choose —</option>
                <option v-for="o in archetypeOptions" :key="o.value" :value="o.value">
                  {{ o.label }}
                </option>
              </select>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-if="view">
      <details>
        <summary>Raw response</summary>
        <pre>{{ JSON.stringify(view, null, 2) }}</pre>
      </details>
    </section>
  </div>
</template>
