<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../../api.js'

const saves = ref([])
const loading = ref(false)
const error = ref(null)
const lastElapsed = ref(null)
const newSave = ref({ saveName: '', managerName: '', difficulty: 'NORMAL' })

async function refresh() {
  loading.value = true
  error.value = null
  try {
    const res = await api.listSaves()
    saves.value = res.data
    lastElapsed.value = res.elapsedMs
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function create() {
  error.value = null
  try {
    await api.createSave({ ...newSave.value })
    newSave.value = { saveName: '', managerName: '', difficulty: 'NORMAL' }
    await refresh()
  } catch (e) {
    error.value = e.message
  }
}

async function load(id) {
  error.value = null
  try {
    await api.loadSave(id)
    await refresh()
  } catch (e) {
    error.value = e.message
  }
}

async function del(id) {
  if (!confirm('Delete this save? Cannot be undone.')) return
  error.value = null
  try {
    await api.deleteSave(id)
    await refresh()
  } catch (e) {
    error.value = e.message
  }
}

function fmt(iso) {
  return new Date(iso).toLocaleString()
}

onMounted(refresh)
</script>

<template>
  <div class="panel">
    <h2>Saves</h2>

    <section>
      <div class="toolbar">
        <button @click="refresh" :disabled="loading">Refresh</button>
        <span v-if="lastElapsed !== null" class="timing">{{ lastElapsed }} ms</span>
      </div>

      <div v-if="error" class="error">{{ error }}</div>
      <div v-else-if="loading" class="loading">Loading…</div>
      <table v-else-if="saves.length">
        <thead>
          <tr>
            <th>Name</th>
            <th>Created</th>
            <th>Last played</th>
            <th>Schema</th>
            <th>ID</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in saves" :key="s.saveId">
            <td>{{ s.saveName }}</td>
            <td>{{ fmt(s.createdAt) }}</td>
            <td>{{ fmt(s.lastPlayedAt) }}</td>
            <td><span class="pill">v{{ s.schemaVersion }}</span></td>
            <td class="muted"><code>{{ s.saveId.slice(0, 8) }}…</code></td>
            <td>
              <button class="primary" @click="load(s.saveId)">Load</button>
              <button class="danger" @click="del(s.saveId)">Delete</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted">No saves yet.</p>

      <details v-if="saves.length">
        <summary>Raw response</summary>
        <pre>{{ JSON.stringify(saves, null, 2) }}</pre>
      </details>
    </section>

    <section>
      <h3>Create new save</h3>
      <form @submit.prevent="create">
        <label>
          Save name
          <input v-model="newSave.saveName" required placeholder="My career" />
        </label>
        <label>
          Manager
          <input v-model="newSave.managerName" required placeholder="Your name" />
        </label>
        <label>
          Difficulty
          <select v-model="newSave.difficulty">
            <option>EASY</option>
            <option>NORMAL</option>
            <option>HARD</option>
            <option>BRUTAL</option>
          </select>
        </label>
        <button type="submit" class="primary">Create</button>
      </form>
    </section>
  </div>
</template>
