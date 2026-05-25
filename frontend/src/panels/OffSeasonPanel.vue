<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api.js'

const report = ref(null)
const seasonInput = ref('')
const error = ref(null)
const loading = ref(false)

async function refresh() {
  loading.value = true
  error.value = null
  try {
    const filter = seasonInput.value ? { season: seasonInput.value } : {}
    const res = await api.getOffSeasonReport(filter)
    report.value = res.data
  } catch (e) {
    error.value = e.message
    report.value = null
  } finally {
    loading.value = false
  }
}

const groupedEvents = computed(() => {
  if (!report.value?.events) return {}
  const groups = {
    FINANCE_SETTLED: [],
    AGE_TICK: [],
    STAT_DRIFT: [],
    RETIREMENT: [],
    CONTRACT_EXPIRED: [],
    MARKET_SIGNING: [],
    SPONSOR_REVENUE: [],
    OPERATING_COST: [],
  }
  for (const e of report.value.events) {
    if (groups[e.eventType]) groups[e.eventType].push(e)
  }
  return groups
})

onMounted(refresh)
</script>

<template>
  <div class="panel">
    <h2>Off-season report</h2>

    <section>
      <div class="toolbar">
        <label>
          Season:
          <input
            v-model="seasonInput"
            type="number"
            placeholder="latest"
            style="width: 80px;"
          />
        </label>
        <button @click="refresh" :disabled="loading">Load</button>
      </div>
      <div v-if="error" class="error">{{ error }}</div>
      <div v-else-if="loading" class="loading">Loading…</div>
    </section>

    <section v-if="report && !loading">
      <p v-if="report.seasonYear == null" class="muted">
        No off-season events recorded yet. Complete a season and advance
        through END_OF_SEASON → OFF_SEASON to generate the report.
      </p>
      <div v-else>
        <p>
          <strong>Season {{ report.seasonYear }}</strong> —
          <span class="pill">{{ report.counts.financeSettled }} finance</span>
          <span class="pill">{{ report.counts.ageTicks }} aging</span>
          <span class="pill">{{ report.counts.statDrifts }} stat drift</span>
          <span class="pill">{{ report.counts.retirements }} retired</span>
          <span class="pill">{{ report.counts.contractExpirations }} contracts expired</span>
          <span class="pill">{{ report.counts.marketSignings }} signings</span>
          <span class="pill">{{ report.counts.sponsorRevenue }} sponsor</span>
          <span class="pill">{{ report.counts.operatingCosts }} ops cost</span>
        </p>

        <h3 v-if="groupedEvents.RETIREMENT.length">Retirements</h3>
        <table v-if="groupedEvents.RETIREMENT.length">
          <thead>
            <tr><th>Subject</th><th>Kind</th><th>Detail</th></tr>
          </thead>
          <tbody>
            <tr v-for="e in groupedEvents.RETIREMENT" :key="e.id">
              <td>{{ e.subjectName }}</td>
              <td><span class="pill">{{ e.subjectKind }}</span></td>
              <td>{{ e.message }}</td>
            </tr>
          </tbody>
        </table>

        <h3 v-if="groupedEvents.CONTRACT_EXPIRED.length">Contract expirations</h3>
        <table v-if="groupedEvents.CONTRACT_EXPIRED.length">
          <thead>
            <tr><th>Subject</th><th>Kind</th><th>Detail</th></tr>
          </thead>
          <tbody>
            <tr v-for="e in groupedEvents.CONTRACT_EXPIRED" :key="e.id">
              <td>{{ e.subjectName }}</td>
              <td><span class="pill">{{ e.subjectKind }}</span></td>
              <td>{{ e.message }}</td>
            </tr>
          </tbody>
        </table>

        <h3 v-if="groupedEvents.MARKET_SIGNING.length">Driver market signings</h3>
        <table v-if="groupedEvents.MARKET_SIGNING.length">
          <thead>
            <tr><th>Driver</th><th>Detail</th></tr>
          </thead>
          <tbody>
            <tr v-for="e in groupedEvents.MARKET_SIGNING" :key="e.id">
              <td>{{ e.subjectName }}</td>
              <td>{{ e.message }}</td>
            </tr>
          </tbody>
        </table>

        <h3 v-if="groupedEvents.STAT_DRIFT.length">Stat drift</h3>
        <table v-if="groupedEvents.STAT_DRIFT.length">
          <thead>
            <tr><th>Subject</th><th>Kind</th><th>Detail</th></tr>
          </thead>
          <tbody>
            <tr v-for="e in groupedEvents.STAT_DRIFT" :key="e.id">
              <td>{{ e.subjectName }}</td>
              <td><span class="pill">{{ e.subjectKind }}</span></td>
              <td>{{ e.message }}</td>
            </tr>
          </tbody>
        </table>

        <h3 v-if="groupedEvents.SPONSOR_REVENUE.length">Sponsor revenue (income)</h3>
        <table v-if="groupedEvents.SPONSOR_REVENUE.length">
          <thead>
            <tr><th>Team</th><th>Detail</th></tr>
          </thead>
          <tbody>
            <tr v-for="e in groupedEvents.SPONSOR_REVENUE" :key="e.id">
              <td>{{ e.subjectName }}</td>
              <td>{{ e.message }}</td>
            </tr>
          </tbody>
        </table>

        <h3 v-if="groupedEvents.OPERATING_COST.length">Operating costs (expenses)</h3>
        <table v-if="groupedEvents.OPERATING_COST.length">
          <thead>
            <tr><th>Team</th><th>Detail</th></tr>
          </thead>
          <tbody>
            <tr v-for="e in groupedEvents.OPERATING_COST" :key="e.id">
              <td>{{ e.subjectName }}</td>
              <td>{{ e.message }}</td>
            </tr>
          </tbody>
        </table>

        <h3 v-if="groupedEvents.FINANCE_SETTLED.length">Finance settlement</h3>
        <table v-if="groupedEvents.FINANCE_SETTLED.length">
          <thead>
            <tr><th>Team</th><th>Detail</th></tr>
          </thead>
          <tbody>
            <tr v-for="e in groupedEvents.FINANCE_SETTLED" :key="e.id">
              <td>{{ e.subjectName }}</td>
              <td>{{ e.message }}</td>
            </tr>
          </tbody>
        </table>

        <details>
          <summary>Aging tick log ({{ groupedEvents.AGE_TICK.length }} rows)</summary>
          <table>
            <thead>
              <tr><th>Subject</th><th>Kind</th><th>Detail</th></tr>
            </thead>
            <tbody>
              <tr v-for="e in groupedEvents.AGE_TICK" :key="e.id">
                <td>{{ e.subjectName }}</td>
                <td>{{ e.subjectKind }}</td>
                <td>{{ e.message }}</td>
              </tr>
            </tbody>
          </table>
        </details>
      </div>
    </section>
  </div>
</template>
