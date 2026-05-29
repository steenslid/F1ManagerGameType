<script setup>
import { ref, onMounted, computed } from 'vue'
import { api } from '../api.js'

const activeTab = ref('drivers') // 'drivers' or 'sponsors'

const freeAgents = ref([])
const pendingOffers = ref([])
const sponsors = ref([])
const budget = ref({ cash: 0, salaryBill: 0 })

const isLoading = ref(true)

const formatMoney = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(val || 0)
const headroom = computed(() => budget.value.cash - budget.value.salaryBill)

onMounted(async () => {
  isLoading.value = true
  try {
    // Fetch market and finances
    const [availRes, offersRes] = await Promise.all([
      api.getMarketAvailable(),
      api.getMarketOffers()
    ])

    budget.value = {
      cash: availRes.data.playerTeamCashReserves || 0,
      salaryBill: availRes.data.playerTeamDriverSalaryBill || 0
    }
    freeAgents.value = availRes.data.freeAgents || []
    pendingOffers.value = offersRes.data || []

    // Fetch sponsors (Assuming your backend has this route based on your old SponsorshipsPanel)
    const sponsorRes = await api.listSponsors()
    sponsors.value = sponsorRes.data || []

  } catch (e) {
    console.error("Failed to load market data", e)
  } finally {
    isLoading.value = false
  }
})
</script>

<template>
  <div class="card">
    <div class="card-header">
      <h2>Contracts & Negotiations</h2>
      <div class="tabs">
        <button :class="{ active: activeTab === 'drivers' }" @click="activeTab = 'drivers'">Driver Market</button>
        <button :class="{ active: activeTab === 'sponsors' }" @click="activeTab = 'sponsors'">Sponsorships</button>
      </div>
    </div>

    <div v-if="isLoading" class="faint p-20">Loading market data...</div>

    <!-- DRIVER MARKET TAB -->
    <div v-else-if="activeTab === 'drivers'" class="market-grid">
      <div class="list-section">
        <table class="data-table">
          <thead><tr><th>Driver</th><th>Age</th><th class="r">Suggested Salary</th><th class="r">Action</th></tr></thead>
          <tbody>
          <tr v-for="driver in freeAgents" :key="driver.id">
            <td class="name">{{ driver.name }}</td>
            <td class="num">{{ driver.currentAge || '--' }}</td>
            <td class="r num">{{ formatMoney(driver.recommendedSalary) }}</td>
            <td class="r"><button class="btn-small">Draft Offer</button></td>
          </tr>
          <tr v-if="!freeAgents.length"><td colspan="4" class="faint text-center">No free agents available.</td></tr>
          </tbody>
        </table>
      </div>

      <div class="side-section">
        <div class="finance-box">
          <h3 class="muted uppercase">Signing Budget</h3>
          <div class="fin-row"><span class="k">Cash Reserves</span><span class="v num">{{ formatMoney(budget.cash) }}</span></div>
          <div class="fin-row"><span class="k">Current Salary Bill</span><span class="v num" style="color: var(--bad)">- {{ formatMoney(budget.salaryBill) }}</span></div>
          <div class="divider"></div>
          <div class="fin-row total"><span class="k">Available Headroom</span><span class="v num" :class="{ 'good': headroom > 0 }">{{ formatMoney(headroom) }}</span></div>
        </div>

        <div class="finance-box" style="margin-top: 20px;">
          <h3 class="muted uppercase">Pending Offers</h3>
          <div class="offer-item" v-for="offer in pendingOffers" :key="offer.driverId">
            <div>{{ offer.driverName || 'Unknown' }}</div>
            <div class="num muted">{{ formatMoney(offer.salaryAmount) }}</div>
          </div>
          <div v-if="!pendingOffers.length" class="faint text-center">No active negotiations.</div>
        </div>
      </div>
    </div>

    <!-- SPONSORSHIPS TAB -->
    <div v-else-if="activeTab === 'sponsors'">
      <table class="data-table">
        <thead><tr><th>Sponsor Name</th><th>Type</th><th class="r">Payout</th><th class="r">Status</th></tr></thead>
        <tbody>
        <tr v-for="sponsor in sponsors" :key="sponsor.id">
          <td class="name">{{ sponsor.name }}</td>
          <td>{{ sponsor.type || 'Primary' }}</td>
          <td class="r num">{{ formatMoney(sponsor.payoutAmount) }}</td>
          <td class="r"><span class="pill">Active</span></td>
        </tr>
        <tr v-if="!sponsors.length"><td colspan="4" class="faint text-center">No sponsors found.</td></tr>
        </tbody>
      </table>
    </div>

  </div>
</template>

<style scoped>
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 20px; }
.card-header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--line); padding-bottom: 16px; margin-bottom: 16px; }
.card-header h2 { margin: 0; font-size: 16px; font-weight: 700; }

.tabs button { background: transparent; color: var(--muted); border: 1px solid var(--line); padding: 6px 12px; font-size: 12px; cursor: pointer; font-weight: 600; }
.tabs button:first-child { border-radius: 6px 0 0 6px; border-right: none; }
.tabs button:last-child { border-radius: 0 6px 6px 0; }
.tabs button.active { background: var(--surface-2); color: var(--fg); border-color: var(--muted); }

.market-grid { display: grid; grid-template-columns: 2fr 1fr; gap: 24px; }
.uppercase { text-transform: uppercase; font-size: 11px; letter-spacing: 1px; margin: 0 0 12px 0;}

/* Data Table (reusable) */
.data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table th { color: var(--faint); text-transform: uppercase; font-size: 10px; text-align: left; padding: 8px; border-bottom: 1px solid var(--line); }
.data-table td { padding: 12px 8px; border-bottom: 1px solid var(--surface-2); }
.data-table th.r, .data-table td.r { text-align: right; }
.name { font-weight: 600; }
.btn-small { background: var(--surface-2); border: 1px solid var(--line); color: var(--fg); padding: 6px 12px; border-radius: 6px; cursor: pointer; }
.btn-small:hover { border-color: var(--muted); }

/* Finance Box */
.finance-box { background: var(--bg); padding: 16px; border-radius: 8px; border: 1px solid var(--line); }
.fin-row { display: flex; justify-content: space-between; margin-bottom: 8px; font-size: 13px; }
.fin-row .k { color: var(--muted); }
.fin-row .v { font-weight: 600; }
.divider { height: 1px; background: var(--line); margin: 12px 0; }
.total .v { font-size: 16px; font-weight: 800; }
.good { color: var(--good); }

.offer-item { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid var(--surface-2); font-size: 13px;}
.pill { background: var(--accent-soft); color: var(--accent); padding: 4px 8px; border-radius: 4px; font-size: 11px; font-weight: 700; }
.p-20 { padding: 20px; text-align: center; }
</style>
