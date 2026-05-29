<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../../api.js'

const view = ref(null)
const error = ref(null)
const loading = ref(false)
const submitError = ref(null)

const focusOptions = [
  { value: 'SETUP', label: 'Setup (+1% qualy / pace this race)' },
  { value: 'TYRE_PROGRAM', label: 'Tyre program (no effect yet)' },
  { value: 'RELIABILITY_CHECK', label: 'Reliability check (no effect yet)' },
  { value: 'DEVELOPMENT_FEEDBACK', label: 'Development feedback (no effect yet)' },
]

async function refresh() {
  loading.value = true
  error.value = null
  submitError.value = null
  try {
    const res = await api.viewPractice()
    view.value = res.data
  } catch (e) {
    error.value = e.message
    view.value = null
  } finally {
    loading.value = false
  }
}

async function setFocus(driverId, focus) {
  submitError.value = null
  try {
    const res = await api.setPracticeFocus(driverId, focus)
    view.value = res.data
  } catch (e) {
    submitError.value = e.message
  }
}

onMounted(refresh)
</script>

<template>
  <div class="panel">
    <h2>Practice focus</h2>

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
        <span v-else class="pill">READ-ONLY (not in PRACTICE phase)</span>
      </p>
      <p class="muted" v-if="!view.canEdit">
        Practice focus can only be changed during the PRACTICE phase. Advance
        the game to a PRACTICE phase before editing.
      </p>

      <div v-if="submitError" class="error">{{ submitError }}</div>

      <table>
        <thead>
          <tr>
            <th>Driver</th>
            <th>Team</th>
            <th>Current focus</th>
            <th>Set</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in view.entries" :key="e.driverId">
            <td>{{ e.driverName }}</td>
            <td>{{ e.teamName }}</td>
            <td>
              <span v-if="e.focus" class="pill">{{ e.focus }}</span>
              <span v-else class="muted">(none)</span>
            </td>
            <td>
              <select
                :disabled="!view.canEdit"
                :value="e.focus ?? ''"
                @change="(ev) => ev.target.value && setFocus(e.driverId, ev.target.value)"
              >
                <option value="" disabled>— choose —</option>
                <option v-for="o in focusOptions" :key="o.value" :value="o.value">
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
