<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api.js'

const activeTab = ref('hub') // 'hub', 'strategy', 'results'
const isLoading = ref(true)

// Mock/State Data
const track = ref({})
const currentPhase = ref('PRACTICE_1')
const schedule = ref([
  { id: 'PRACTICE_1', name: 'Free Practice 1', status: 'COMPLETED' },
  { id: 'PRACTICE_2', name: 'Free Practice 2', status: 'COMPLETED' },
  { id: 'PRACTICE_3', name: 'Free Practice 3', status: 'COMPLETED' },
  { id: 'QUALIFYING', name: 'Qualifying', status: 'ACTIVE' },
  { id: 'RACE', name: 'Race', status: 'PENDING' }
])

const driversStrategy = ref([
  { id: 1, name: 'Driver 1', tyre: 'Soft', pace: 'Push' },
  { id: 2, name: 'Driver 2', tyre: 'Medium', pace: 'Balanced' }
])

const recentResults = ref([])

onMounted(async () => {
  isLoading.value = true
  try {
    // Replace with your actual API endpoints
    const [raceRes, stateRes, resultsRes] = await Promise.all([
      api.getCurrentRace(),
      api.getGameState(),
      // api.getRaceResults() // Uncomment when ready
      Promise.resolve({ data: [] }) // Mocking results for now
    ])

    track.value = raceRes.data || { name: 'Autodromo Nazionale Monza', location: 'Monza, Italy', laps: 53 }
    currentPhase.value = stateRes.data?.phase || 'QUALIFYING'

    // Mock Results Population
    recentResults.value = [
      { pos: 1, driver: 'Max Verstappen', team: 'Red Bull Racing', gap: 'Leader', tyre: 'S' },
      { pos: 2, driver: 'Charles Leclerc', team: 'Scuderia Ferrari', gap: '+0.142s', tyre: 'S' },
      { pos: 3, driver: 'Your Driver 1', team: 'Scuderia Rossa', gap: '+0.311s', tyre: 'S' },
      { pos: 4, driver: 'Lando Norris', team: 'McLaren', gap: '+0.450s', tyre: 'M' }
    ]

  } catch (e) {
    console.error("Failed to load race weekend", e)
  } finally {
    isLoading.value = false
  }
})

const simulateSession = async () => {
  console.log("Simulating session with strategy:", driversStrategy.value)
  // await api.post('/api/game/advance')
  // Fetch new state and switch to results tab
  activeTab.value = 'results'
}
</script>

<template>
  <div class="card wrapper">
    <!-- INTERNAL HEADER & TABS -->
    <div class="card-header">
      <div class="header-titles">
        <h2>{{ track.name || 'Race Weekend' }}</h2>
        <span class="faint loc">{{ track.location }}</span>
      </div>

      <div class="tabs">
        <button :class="{ active: activeTab === 'hub' }" @click="activeTab = 'hub'">Weekend Hub</button>
        <button :class="{ active: activeTab === 'strategy' }" @click="activeTab = 'strategy'">Setup & Strategy</button>
        <button :class="{ active: activeTab === 'results' }" @click="activeTab = 'results'">Session Results</button>
      </div>
    </div>

    <div v-if="isLoading" class="faint p-20 text-center">Loading garage...</div>

    <!-- TAB 1: WEEKEND HUB -->
    <div v-else-if="activeTab === 'hub'" class="hub-grid">
      <!-- Left: Track Info & Simulate Action -->
      <div class="action-panel">
        <div class="track-hero">
          <div class="track-details">
            <div class="stat-group">
              <span class="k">Laps</span>
              <span class="v num">{{ track.laps || 50 }}</span>
            </div>
            <div class="stat-group">
              <span class="k">Track Temp</span>
              <span class="v num">31°C</span>
            </div>
            <div class="stat-group">
              <span class="k">Weather</span>
              <span class="v">Partly Cloudy</span>
            </div>
          </div>
        </div>

        <div class="sim-box">
          <h3>Ready for {{ schedule.find(s => s.status === 'ACTIVE')?.name || 'Next Session' }}</h3>
          <p class="faint">Ensure your strategy is set before heading out on track.</p>
          <div class="sim-actions">
            <button class="btn-cancel" @click="activeTab = 'strategy'">Review Strategy</button>
            <button class="btn" @click="simulateSession">Simulate Session →</button>
          </div>
        </div>
      </div>

      <!-- Right: Weekend Schedule Timeline -->
      <div class="schedule-panel">
        <h3 class="muted uppercase">Weekend Schedule</h3>
        <div class="timeline">
          <div
              v-for="session in schedule"
              :key="session.id"
              class="timeline-item"
              :class="{ 'is-active': session.status === 'ACTIVE', 'is-done': session.status === 'COMPLETED' }"
          >
            <div class="node"></div>
            <div class="session-info">
              <div class="session-name">{{ session.name }}</div>
              <div class="session-status">{{ session.status }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- TAB 2: STRATEGY & SETUP -->
    <div v-else-if="activeTab === 'strategy'" class="strategy-grid">
      <div class="driver-card" v-for="driver in driversStrategy" :key="driver.id">
        <div class="driver-header">
          <div class="crest">{{ driver.name.substring(0,2).toUpperCase() }}</div>
          <div class="name">{{ driver.name }}</div>
        </div>

        <div class="settings-group">
          <label>Tyre Compound</label>
          <select v-model="driver.tyre">
            <option value="Soft">Soft (Red)</option>
            <option value="Medium">Medium (Yellow)</option>
            <option value="Hard">Hard (White)</option>
            <option value="Intermediate">Intermediate (Green)</option>
            <option value="Wet">Wet (Blue)</option>
          </select>
        </div>

        <div class="settings-group">
          <label>Driving Pace</label>
          <select v-model="driver.pace">
            <option value="Conserve">Conserve (Save Tyres)</option>
            <option value="Balanced">Balanced</option>
            <option value="Push">Push</option>
            <option value="Attack">Attack (High Wear)</option>
          </select>
        </div>
      </div>
    </div>

    <!-- TAB 3: SESSION RESULTS -->
    <div v-else-if="activeTab === 'results'">
      <table class="data-table">
        <thead>
        <tr>
          <th class="r" style="width: 50px;">Pos</th>
          <th>Driver</th>
          <th>Team</th>
          <th class="r">Gap / Time</th>
          <th class="text-center" style="width: 80px;">Tyre</th>
        </tr>
        </thead>
        <tbody>
        <tr v-for="res in recentResults" :key="res.driver" :class="{ 'is-player': res.team === 'Scuderia Rossa' }">
          <td class="r num">{{ res.pos }}</td>
          <td class="name">{{ res.driver }}</td>
          <td class="faint">{{ res.team }}</td>
          <td class="r num">{{ res.gap }}</td>
          <td class="text-center"><span class="tyre-pill" :class="res.tyre.toLowerCase()">{{ res.tyre }}</span></td>
        </tr>
        <tr v-if="!recentResults.length">
          <td colspan="5" class="faint text-center p-20">No results available for this session yet.</td>
        </tr>
        </tbody>
      </table>
    </div>

  </div>
</template>

<style scoped>
.wrapper { min-height: 500px; display: flex; flex-direction: column; }
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 20px; }

/* Header & Tabs */
.card-header { display: flex; justify-content: space-between; align-items: flex-end; border-bottom: 1px solid var(--line); padding-bottom: 16px; margin-bottom: 20px; }
.header-titles h2 { margin: 0 0 4px 0; font-size: 20px; font-weight: 800; }
.header-titles .loc { font-size: 13px; }

.tabs { display: flex; }
.tabs button { background: transparent; color: var(--muted); border: 1px solid var(--line); padding: 8px 16px; font-size: 13px; cursor: pointer; font-weight: 600; transition: 0.2s; }
.tabs button:first-child { border-radius: 6px 0 0 6px; border-right: none; }
.tabs button:nth-child(2) { border-right: none; }
.tabs button:last-child { border-radius: 0 6px 6px 0; }
.tabs button:hover:not(.active) { background: var(--surface-2); color: var(--fg); }
.tabs button.active { background: var(--accent-soft); color: var(--accent); border-color: var(--accent); position: relative; z-index: 1; }

.uppercase { text-transform: uppercase; font-size: 11px; letter-spacing: 1px; margin: 0 0 16px 0;}

/* Hub Grid */
.hub-grid { display: grid; grid-template-columns: 2fr 1fr; gap: 24px; flex: 1; }
.action-panel { display: flex; flex-direction: column; gap: 20px; }
.track-hero { background: linear-gradient(135deg, var(--surface-2), var(--bg)); border: 1px solid var(--line); border-radius: 8px; padding: 24px; min-height: 150px; display: flex; align-items: flex-end; }
.track-details { display: flex; gap: 32px; width: 100%; }
.stat-group { display: flex; flex-direction: column; gap: 4px; }
.stat-group .k { font-size: 11px; color: var(--faint); text-transform: uppercase; font-weight: 600; }
.stat-group .v { font-size: 18px; font-weight: 700; }

.sim-box { background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 24px; text-align: center; }
.sim-box h3 { margin: 0 0 8px 0; font-size: 18px; }
.sim-actions { display: flex; justify-content: center; gap: 12px; margin-top: 20px; }

.btn { background: var(--accent); color: #fff; border: none; padding: 10px 20px; border-radius: 6px; font-weight: 700; cursor: pointer; }
.btn:hover { filter: brightness(1.1); }
.btn-cancel { background: transparent; color: var(--fg); border: 1px solid var(--line); padding: 10px 20px; border-radius: 6px; font-weight: 600; cursor: pointer; }
.btn-cancel:hover { background: var(--surface-2); }

/* Schedule Timeline */
.schedule-panel { background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 20px; }
.timeline { display: flex; flex-direction: column; gap: 0; }
.timeline-item { display: flex; gap: 16px; padding: 12px 0; position: relative; opacity: 0.5; }
.timeline-item::before { content: ''; position: absolute; left: 5px; top: 24px; bottom: -12px; width: 2px; background: var(--line); }
.timeline-item:last-child::before { display: none; }
.timeline-item.is-done { opacity: 0.8; }
.timeline-item.is-active { opacity: 1; }

.node { width: 12px; height: 12px; border-radius: 50%; background: var(--surface-2); border: 2px solid var(--line); position: relative; z-index: 2; margin-top: 4px; }
.is-done .node { background: var(--muted); border-color: var(--muted); }
.is-active .node { background: var(--accent); border-color: var(--accent); box-shadow: 0 0 0 4px var(--accent-soft); }

.session-name { font-weight: 700; font-size: 14px; }
.session-status { font-size: 11px; font-weight: 600; margin-top: 4px; text-transform: uppercase; }
.is-active .session-status { color: var(--accent); }

/* Strategy Grid */
.strategy-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
.driver-card { background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 24px; }
.driver-header { display: flex; align-items: center; gap: 12px; margin-bottom: 24px; padding-bottom: 16px; border-bottom: 1px solid var(--line); }
.driver-header .crest { width: 32px; height: 32px; background: var(--surface-2); border-radius: 6px; display: grid; place-items: center; font-weight: 800; font-size: 12px; color: var(--muted); }
.driver-header .name { font-size: 18px; font-weight: 700; }

.settings-group { display: flex; flex-direction: column; gap: 8px; margin-bottom: 20px; }
.settings-group label { font-size: 11px; text-transform: uppercase; color: var(--muted); font-weight: 600; }
select { background: var(--surface); color: var(--fg); border: 1px solid var(--line); padding: 12px; border-radius: 6px; font-size: 14px; outline: none; cursor: pointer; }
select:focus { border-color: var(--accent); }

/* Data Table */
.data-table { width: 100%; border-collapse: collapse; font-size: 14px; }
.data-table th { color: var(--faint); text-transform: uppercase; font-size: 11px; text-align: left; padding: 12px 8px; border-bottom: 1px solid var(--line); }
.data-table td { padding: 12px 8px; border-bottom: 1px solid var(--surface-2); vertical-align: middle; }
.data-table th.r, .data-table td.r { text-align: right; }
.data-table .name { font-weight: 700; }
.is-player td { background: var(--surface-2); }
.is-player .name { color: var(--accent); }

.tyre-pill { display: inline-block; width: 24px; height: 24px; line-height: 24px; text-align: center; border-radius: 50%; font-size: 12px; font-weight: 800; color: #000; }
.tyre-pill.s { background: #ff3b30; color: #fff; }
.tyre-pill.m { background: #ffcc00; }
.tyre-pill.h { background: #ffffff; }
.tyre-pill.i { background: #34c759; color: #fff; }
.tyre-pill.w { background: #007aff; color: #fff; }
</style>
