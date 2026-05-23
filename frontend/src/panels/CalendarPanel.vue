<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api.js'

const races = ref([])
const loading = ref(false)
const error = ref(null)
const elapsed = ref(null)
const season = ref('')

async function refresh() {
  loading.value = true
  error.value = null
  try {
    const filter = season.value ? { season: season.value } : null
    const res = await api.listRaces(filter)
    races.value = res.data
    elapsed.value = res.elapsedMs
  } catch (e) {
    error.value = e.message
    races.value = []
  } finally {
    loading.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <div class="panel">
    <h2>Calendar</h2>

    <section>
      <div class="toolbar">
        <button @click="refresh" :disabled="loading">Refresh</button>
        <label style="margin-left: 12px;">
          Season
          <input v-model="season" placeholder="e.g. 2026" style="width: 80px;" />
        </label>
        <button @click="refresh">Apply</button>
        <span v-if="elapsed !== null" class="timing">{{ elapsed }} ms</span>
      </div>

      <div v-if="error" class="error">{{ error }}</div>
      <div v-else-if="loading" class="loading">Loading…</div>
      <table v-else-if="races.length">
        <thead>
          <tr>
            <th>Round</th>
            <th>Season</th>
            <th>Track</th>
            <th>Country</th>
            <th>Format</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in races" :key="r.id">
            <td class="numeric">{{ r.round }}</td>
            <td class="numeric">{{ r.seasonYear }}</td>
            <td>{{ r.track.name }}</td>
            <td>{{ r.track.country }}</td>
            <td>
              <span class="pill">{{ r.sessionFormat }}</span>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted">No races.</p>

      <details v-if="races.length">
        <summary>Raw response</summary>
        <pre>{{ JSON.stringify(races, null, 2) }}</pre>
      </details>
    </section>
  </div>
</template>
