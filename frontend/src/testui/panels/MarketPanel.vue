<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../../api.js'

const available = ref(null)
const offers = ref([])
const loading = ref(false)
const error = ref(null)
const submitError = ref(null)

// Local form state for a new/edited offer
const offerForm = ref({
  driverId: '',
  salary: 0,
  contractYears: 2,
})

async function refresh() {
  loading.value = true
  error.value = null
  try {
    const [a, o] = await Promise.all([
      api.getMarketAvailable(),
      api.getMarketOffers(),
    ])
    available.value = a.data
    offers.value = o.data
  } catch (e) {
    error.value = e.message
    available.value = null
    offers.value = []
  } finally {
    loading.value = false
  }
}

async function submitOffer() {
  submitError.value = null
  if (!offerForm.value.driverId) {
    submitError.value = 'Pick a driver first'
    return
  }
  try {
    await api.submitMarketOffer({
      driverId: offerForm.value.driverId,
      salary: Number(offerForm.value.salary),
      contractYears: Number(offerForm.value.contractYears),
    })
    offerForm.value = { driverId: '', salary: 0, contractYears: 2 }
    await refresh()
  } catch (e) {
    submitError.value = e.message
  }
}

async function withdrawOffer(driverId) {
  submitError.value = null
  try {
    await api.withdrawMarketOffer(driverId)
    await refresh()
  } catch (e) {
    submitError.value = e.message
  }
}

function preFill(agent) {
  // Existing offer? Pre-fill with that. Else use the recommendation.
  const existing = offers.value.find((o) => o.driverId === agent.driverId)
  offerForm.value = {
    driverId: agent.driverId,
    salary: existing?.salary ?? agent.recommendedSalary,
    contractYears: existing?.contractYears ?? 2,
  }
}

const state = computed(() => available.value?.state)
const canSubmit = computed(() => {
  const s = state.value
  if (!s) return false
  if (!s.active) return false
  if (s.complete) return false
  if (s.playerTeamId == null) return false
  // Can submit if there's an open seat OR an existing offer to update.
  const hasOpenSeat = (s.playerTeamOpenSeats ?? 0) > 0
  const hasOfferForSelected = offers.value.some(
    (o) => o.driverId === offerForm.value.driverId,
  )
  return hasOpenSeat || hasOfferForSelected
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
    <h2>Driver market</h2>

    <section>
      <div class="toolbar">
        <button @click="refresh" :disabled="loading">Refresh</button>
      </div>
      <div v-if="error" class="error">{{ error }}</div>
      <div v-else-if="loading" class="loading">Loading…</div>
    </section>

    <section v-if="!loading && state">
      <p v-if="!state.active" class="muted">
        Driver market isn't open. Advance to DRIVER_MARKET phase via the
        Game panel (after END_OF_SEASON → OFF_SEASON → DRIVER_MARKET).
      </p>
      <p v-else-if="state.complete">
        <strong>Market complete</strong> for season ending {{ state.seasonYear }}.
        Resolved {{ state.totalRounds }} / {{ state.totalRounds }} rounds.
        Next advance moves to PRE_SEASON.
      </p>
      <p v-else>
        <strong>Market open</strong> — off-season ending {{ state.seasonYear }}.
        <span class="muted">
          Round {{ state.currentRound }} / {{ state.totalRounds }} resolved.
        </span>
        <br />
        <span v-if="state.playerTeamId">
          Your team's open seats:
          <strong>{{ state.playerTeamOpenSeats }}</strong>
        </span>
        <span v-else class="muted">
          No player team selected — submit offers requires selecting your team.
        </span>
      </p>
    </section>

    <section v-if="state?.active && offers.length">
      <h3>Your pending offers ({{ offers.length }})</h3>
      <table>
        <thead>
          <tr>
            <th>Driver</th>
            <th class="numeric">Salary</th>
            <th class="numeric">Years</th>
            <th class="numeric">Round submitted</th>
            <th>Action</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="o in offers" :key="o.driverId">
            <td>{{ o.driverName }}</td>
            <td class="numeric">{{ fmtMoney(o.salary) }}</td>
            <td class="numeric">{{ o.contractYears }}</td>
            <td class="numeric">{{ o.submittedRound }}</td>
            <td>
              <button class="danger" @click="withdrawOffer(o.driverId)">Withdraw</button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-if="state?.active && !state.complete">
      <h3>Submit an offer</h3>
      <p class="muted" v-if="!canSubmit">
        Click a driver below to pre-fill the form. Submission requires
        an open seat or an existing offer to update.
      </p>
      <div v-if="submitError" class="error">{{ submitError }}</div>
      <form @submit.prevent="submitOffer">
        <label>
          Driver ID
          <input v-model="offerForm.driverId" placeholder="(click a row below)" style="width: 280px;" />
        </label>
        <label>
          Salary ($)
          <input v-model="offerForm.salary" type="number" min="100000" step="100000" />
        </label>
        <label>
          Years
          <input v-model="offerForm.contractYears" type="number" min="1" max="5" />
        </label>
        <button type="submit" class="primary" :disabled="!canSubmit">
          Submit / update offer
        </button>
      </form>
    </section>

    <section v-if="state?.active && available?.freeAgents?.length">
      <h3>Free agents ({{ available.freeAgents.length }})</h3>
      <p class="muted">
        Click a row to pre-fill the offer form. Salary suggestion is the
        AI-default for that pace band.
      </p>
      <table>
        <thead>
          <tr>
            <th>Name</th>
            <th>Nat.</th>
            <th class="numeric">Age</th>
            <th class="numeric">Pace</th>
            <th class="numeric">Qual</th>
            <th class="numeric">Consist.</th>
            <th class="numeric">Morale</th>
            <th class="numeric">Suggested salary</th>
            <th>You</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="d in available.freeAgents"
            :key="d.driverId"
            @click="preFill(d)"
            style="cursor: pointer;"
            :class="{ 'has-offer': d.playerHasOffer }"
          >
            <td>{{ d.name }}</td>
            <td>{{ d.nationality }}</td>
            <td class="numeric">{{ d.age }}</td>
            <td class="numeric">{{ d.statPace }}</td>
            <td class="numeric">{{ d.statQualifying }}</td>
            <td class="numeric">{{ d.statConsistency }}</td>
            <td class="numeric">{{ d.morale }}</td>
            <td class="numeric">{{ fmtMoney(d.recommendedSalary) }}</td>
            <td>
              <span v-if="d.playerHasOffer" class="pill">OFFERED</span>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-if="state?.active && available?.teamsWithOpenSeats?.length">
      <h3>Teams with open seats</h3>
      <table>
        <thead>
          <tr>
            <th>Team</th>
            <th class="numeric">Prestige</th>
            <th class="numeric">Open seats</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in available.teamsWithOpenSeats" :key="t.teamId">
            <td>{{ t.teamName }}</td>
            <td class="numeric">{{ t.prestige }}</td>
            <td class="numeric"><strong>{{ t.openSeats }}</strong></td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-if="state?.active">
      <details>
        <summary>Raw market state</summary>
        <pre>{{ JSON.stringify({ state, offers, freeAgents: available?.freeAgents, teams: available?.teamsWithOpenSeats }, null, 2) }}</pre>
      </details>
    </section>
  </div>
</template>

<style scoped>
tr.has-offer {
  background: #eaf2fc;
}
</style>
