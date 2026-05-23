<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api.js'

const tracks = ref([])
const compounds = ref([])
const eras = ref([])
const suppliers = ref([])
const puVersions = ref([])

const error = ref(null)
const loading = ref(false)

async function refresh() {
  loading.value = true
  error.value = null
  try {
    const [a, b, c, d, e] = await Promise.all([
      api.listTracks(),
      api.listTyreCompounds(),
      api.listRegulationEras(),
      api.listEngineSuppliers(),
      api.listPuVersions(),
    ])
    tracks.value = a.data
    compounds.value = b.data
    eras.value = c.data
    suppliers.value = d.data
    puVersions.value = e.data
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <div class="panel">
    <h2>Reference data</h2>

    <section>
      <div class="toolbar">
        <button @click="refresh" :disabled="loading">Refresh all</button>
      </div>
      <div v-if="error" class="error">{{ error }}</div>
      <div v-else-if="loading" class="loading">Loading…</div>
    </section>

    <section v-if="!error && !loading">
      <h3>Tracks ({{ tracks.length }})</h3>
      <table v-if="tracks.length">
        <thead>
          <tr>
            <th>ID</th>
            <th>Name</th>
            <th>Country</th>
            <th>Type</th>
            <th class="numeric">Length km</th>
            <th class="numeric">Pit loss</th>
            <th class="numeric">Overtake diff.</th>
            <th class="numeric">Rain prob.</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in tracks" :key="t.id">
            <td><code>{{ t.id }}</code></td>
            <td>{{ t.name }}</td>
            <td>{{ t.country }}</td>
            <td><span class="pill">{{ t.type }}</span></td>
            <td class="numeric">{{ t.lengthKm.toFixed(3) }}</td>
            <td class="numeric">{{ t.pitLaneLossSeconds.toFixed(1) }}s</td>
            <td class="numeric">{{ t.overtakeDifficulty.toFixed(2) }}</td>
            <td class="numeric">{{ t.rainProbabilityBaseline.toFixed(2) }}</td>
          </tr>
        </tbody>
      </table>

      <h3>Tyre compounds ({{ compounds.length }})</h3>
      <table v-if="compounds.length">
        <thead>
          <tr>
            <th>ID</th>
            <th>Name</th>
            <th class="numeric">Pace factor</th>
            <th class="numeric">Deg rate</th>
            <th class="numeric">Longevity</th>
            <th>Optimal temp</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in compounds" :key="c.id">
            <td><code>{{ c.id }}</code></td>
            <td>{{ c.name }}</td>
            <td class="numeric">{{ c.basePaceFactor.toFixed(3) }}</td>
            <td class="numeric">{{ c.degradationRate.toFixed(4) }}</td>
            <td class="numeric">{{ c.longevityLaps }} laps</td>
            <td class="numeric">{{ c.optimalTempMinC }}–{{ c.optimalTempMaxC }}°C</td>
          </tr>
        </tbody>
      </table>

      <h3>Regulation eras ({{ eras.length }})</h3>
      <table v-if="eras.length">
        <thead>
          <tr>
            <th>ID</th>
            <th>Name</th>
            <th class="numeric">Start</th>
            <th class="numeric">End</th>
            <th class="numeric">Reset severity</th>
            <th class="numeric">ERS share</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in eras" :key="e.id">
            <td><code>{{ e.id }}</code></td>
            <td>{{ e.name }}</td>
            <td class="numeric">{{ e.startYear }}</td>
            <td class="numeric">{{ e.endYear ?? '—' }}</td>
            <td class="numeric">{{ e.performanceResetSeverity.toFixed(2) }}</td>
            <td class="numeric">{{ e.ersShare.toFixed(2) }}</td>
          </tr>
        </tbody>
      </table>

      <h3>Engine suppliers ({{ suppliers.length }})</h3>
      <table v-if="suppliers.length">
        <thead>
          <tr>
            <th>ID</th>
            <th>Name</th>
            <th>Country</th>
            <th class="numeric">Entered</th>
            <th>Works team</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in suppliers" :key="s.id">
            <td><code>{{ s.id }}</code></td>
            <td>{{ s.name }}</td>
            <td>{{ s.country }}</td>
            <td class="numeric">{{ s.enteredYear }}</td>
            <td>
              <span v-if="s.worksTeamId">{{ s.worksTeamId }}</span>
              <span v-else class="muted">—</span>
            </td>
          </tr>
        </tbody>
      </table>

      <h3>PU versions ({{ puVersions.length }})</h3>
      <table v-if="puVersions.length">
        <thead>
          <tr>
            <th>Supplier</th>
            <th class="numeric">Mk</th>
            <th class="numeric">Year</th>
            <th class="numeric">ICE top</th>
            <th class="numeric">ICE fuel</th>
            <th class="numeric">ERS dep</th>
            <th class="numeric">ERS rec</th>
            <th class="numeric">Reliability</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in puVersions" :key="p.id">
            <td><code>{{ p.supplierId }}</code></td>
            <td class="numeric">{{ p.mkVersion }}</td>
            <td class="numeric">{{ p.introducedYear }}</td>
            <td class="numeric">{{ p.ice.topSpeed }}</td>
            <td class="numeric">{{ p.ice.fuelEfficiency }}</td>
            <td class="numeric">{{ p.ers.deploymentPower }}</td>
            <td class="numeric">{{ p.ers.recoveryRate }}</td>
            <td class="numeric">{{ p.reliability }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>
