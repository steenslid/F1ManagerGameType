<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api.js'

const emit = defineEmits(['saveLoaded'])

const saves = ref([])
const isLoading = ref(true)
const newSaveName = ref('')
const isCreating = ref(false)

onMounted(async () => {
  await fetchSaves()
})

const fetchSaves = async () => {
  isLoading.value = true
  try {
    const res = await api.listSaves()
    saves.value = res.data || []
  } catch (e) {
    console.error("Failed to fetch saves", e)
  } finally {
    isLoading.value = false
  }
}

const createNewGame = async () => {
  if (!newSaveName.value.trim()) return
  isCreating.value = true
  try {
    const res = await api.createSave({ name: newSaveName.value })
    if (res.data && res.data.id) {
      await api.loadSave(res.data.id)
    }
    emit('saveLoaded')
  } catch (e) {
    console.error("Failed to create save", e)
  } finally {
    isCreating.value = false
  }
}

const loadGame = async (saveId) => {
  try {
    await api.loadSave(saveId)
    emit('saveLoaded')
  } catch (e) {
    console.error("Failed to load save", e)
  }
}

const deleteSave = async (saveId) => {
  if (!confirm("Are you sure you want to delete this career?")) return
  try {
    await api.deleteSave(saveId)
    await fetchSaves()
  } catch (e) {
    console.error("Failed to delete save", e)
  }
}
</script>

<template>
  <div class="launcher-wrap">
    <div class="brand-hero">
      <h1>F1<b>SIM</b></h1>
      <p class="subtitle">Managerial Career</p>
    </div>

    <div class="launcher-grid">

      <div class="card create-card">
        <div class="card-header">
          <h2>New Career</h2>
        </div>
        <p class="faint desc">Start a new managerial journey from the beginning of the selected season.</p>

        <div class="input-group">
          <label>Save Name</label>
          <input
              v-model="newSaveName"
              type="text"
              placeholder="e.g. My F1 Dynasty"
              @keyup.enter="createNewGame"
          />
        </div>

        <button
            class="btn block-btn"
            :disabled="!newSaveName.trim() || isCreating"
            @click="createNewGame"
        >
          {{ isCreating ? 'Initializing...' : 'Start New Game' }}
        </button>
      </div>

      <div class="card load-card">
        <div class="card-header">
          <h2>Load Career</h2>
        </div>

        <div v-if="isLoading" class="faint text-center p-20">Loading saves...</div>

        <div v-else-if="saves.length === 0" class="faint text-center p-20">
          No previous saves found.
        </div>

        <div v-else class="saves-list">
          <div class="save-item" v-for="save in saves" :key="save.id">
            <div class="save-info" @click="loadGame(save.id)">
              <div class="save-name">{{ save.name || save.saveName || save.id }}</div>
              <div class="save-meta faint">
                Season {{ save.currentSeasonYear || 2026 }} • Round {{ save.currentRound || 1 }}
              </div>
            </div>
            <div class="save-actions">
              <button class="btn-cancel btn-small" @click="deleteSave(save.id)">Delete</button>
              <button class="btn btn-small" @click="loadGame(save.id)">Load</button>
            </div>
          </div>
        </div>
      </div>

    </div>
  </div>
</template>

<style scoped>
.launcher-wrap {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px;
  background: radial-gradient(circle at 50% 0%, var(--surface-2) 0%, var(--bg) 60%);
}

.brand-hero {
  text-align: center;
  margin-bottom: 40px;
}
.brand-hero h1 {
  font-size: 48px;
  font-weight: 800;
  letter-spacing: 2px;
  color: #fff;
  margin: 0;
}
.brand-hero h1 b { color: var(--accent); }
.subtitle {
  color: var(--muted);
  text-transform: uppercase;
  letter-spacing: 4px;
  font-size: 14px;
  margin-top: 8px;
}

.launcher-grid {
  display: grid;
  grid-template-columns: 1fr 1.5fr;
  gap: 24px;
  width: 100%;
  max-width: 900px;
}

.card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 24px;
  box-shadow: 0 10px 30px rgba(0,0,0,0.5);
}

.card-header h2 {
  font-size: 14px; text-transform: uppercase; letter-spacing: 1px; color: var(--muted); margin: 0 0 16px; font-weight: 700;
}

.desc { margin-bottom: 24px; font-size: 14px; line-height: 1.5; }
.faint { color: var(--faint); }
.text-center { text-align: center; }
.p-20 { padding: 20px; }

/* Form inputs */
.input-group { margin-bottom: 24px; display: flex; flex-direction: column; gap: 8px; }
.input-group label { font-size: 11px; color: var(--muted); text-transform: uppercase; font-weight: 600; }
input { background: var(--bg); border: 1px solid var(--line); color: var(--fg); padding: 14px; border-radius: 8px; font-size: 16px; width: 100%; outline: none; box-sizing: border-box;}
input:focus { border-color: var(--accent); }

/* Buttons */
.btn { background: var(--accent); color: #fff; border: none; padding: 12px 20px; border-radius: 8px; font-weight: 700; cursor: pointer; transition: 0.2s;}
.btn:hover:not(:disabled) { filter: brightness(1.1); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.block-btn { width: 100%; font-size: 15px; }

.btn-small { padding: 8px 14px; font-size: 12px; }
.btn-cancel { background: transparent; color: var(--muted); border: 1px solid var(--line); border-radius: 8px; font-weight: 600; cursor: pointer; }
.btn-cancel:hover { background: var(--surface-2); color: var(--fg); }

/* Save List */
.saves-list { display: flex; flex-direction: column; }
.save-item {
  display: flex; justify-content: space-between; align-items: center;
  padding: 16px; border: 1px solid var(--line); border-radius: 8px; margin-bottom: 12px;
  background: var(--bg); transition: 0.2s;
}
.save-item:hover { border-color: var(--muted); }
.save-info { flex: 1; cursor: pointer; }
.save-name { font-weight: 700; font-size: 16px; margin-bottom: 4px; color: #fff; }
.save-meta { font-size: 12px; }
.save-actions { display: flex; gap: 8px; }
</style>