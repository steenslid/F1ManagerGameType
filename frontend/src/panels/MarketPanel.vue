<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api.js'
import { useGame } from '../useGame.js'
import { fmtMoney, fmtMoneyFull } from '../format.js'

const { state: game, myTeam } = useGame()

const activeTab = ref('drivers')
const isLoading = ref(true)
const error = ref(null)

const market = ref({ active: false }) // MarketStateDto
const freeAgents = ref([])
const offers = ref([])

const sponsorships = ref([])
const sponsorsLoading = ref(false)

// Inline offer form
const offerDriver = ref(null) // the FreeAgentDto being offered to
const offerSalary = ref(0)
const offerYears = ref(2)
const submitting = ref(false)

const headroom = computed(() => {
  const cash = market.value.playerTeamCashReserves || 0
  const bill = market.value.playerTeamDriverSalaryBill || 0
  return cash - bill
})

onMounted(loadMarket)

async function loadMarket() {
  isLoading.value = true
  error.value = null
  try {
    const [availRes, offersRes] = await Promise.all([
      api.getMarketAvailable(),
      api.getMarketOffers(),
    ])
    market.value = availRes.data?.state || { active: false }
    freeAgents.value = availRes.data?.freeAgents || []
    offers.value = offersRes.data || []
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    isLoading.value = false
  }
}

async function loadSponsorships() {
  if (!myTeam.value?.id) { sponsorships.value = []; return }
  sponsorsLoading.value = true
  try {
    const res = await api.listTeamSponsorships({ team: myTeam.value.id })
    sponsorships.value = res.data || []
  } catch {
    sponsorships.value = []
  } finally {
    sponsorsLoading.value = false
  }
}

function switchTab(tab) {
  activeTab.value = tab
  if (tab === 'sponsors' && !sponsorships.value.length) loadSponsorships()
}

function startOffer(driver) {
  offerDriver.value = driver
  offerSalary.value = driver.recommendedSalary || 1_000_000
  offerYears.value = 2
}

function cancelOffer() {
  offerDriver.value = null
}

async function submitOffer() {
  if (!offerDriver.value) return
  submitting.value = true
  error.value = null
  try {
    await api.submitMarketOffer({
      driverId: offerDriver.value.driverId,
      salary: Number(offerSalary.value),
      contractYears: Number(offerYears.value),
    })
    offerDriver.value = null
    await loadMarket()
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    submitting.value = false
  }
}

async function withdraw(offer) {
  error.value = null
  try {
    await api.withdrawMarketOffer(offer.driverId)
    await loadMarket()
  } catch (e) {
    error.value = e.message || String(e)
  }
}
</script>

<template>
  <div class="card">
    <div class="card-header">
      <h2>Contracts &amp; Market</h2>
      <div class="tabs">
        <button :class="{ active: activeTab === 'drivers' }" @click="switchTab('drivers')">Driver Market</button>
        <button :class="{ active: activeTab === 'sponsors' }" @click="switchTab('sponsors')">Sponsorships</button>
      </div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <!-- DRIVER MARKET -->
    <div v-if="activeTab === 'drivers'">
      <div v-if="isLoading" class="faint p-20">Loading market…</div>

      <div v-else-if="!market.active" class="closed">
        <div class="closed-title">The driver market is closed</div>
        <p class="faint">It opens during the off-season (the <b>Driver Market</b> phase), between seasons.
          Advance the game to the off-season to negotiate contracts.</p>
      </div>

      <div v-else class="market-grid">
        <div class="list-section">
          <table class="data-table">
            <thead><tr><th>Free Agent</th><th class="r">Age</th><th class="text-center">Pace</th><th class="r">Suggested</th><th class="r"></th></tr></thead>
            <tbody>
              <tr v-for="d in freeAgents" :key="d.driverId">
                <td class="name">{{ d.name }} <span class="faint nat">{{ d.nationality }}</span></td>
                <td class="r num">{{ d.age }}</td>
                <td class="text-center num">{{ d.statPace }}</td>
                <td class="r num">{{ fmtMoney(d.recommendedSalary) }}</td>
                <td class="r">
                  <span v-if="d.playerHasOffer" class="pill">Offer in</span>
                  <button v-else class="btn-small" @click="startOffer(d)">Offer</button>
                </td>
              </tr>
              <tr v-if="!freeAgents.length">
                <td colspan="5" class="empty-cell">
                  <div class="empty-title">No free agents yet</div>
                  <p class="faint">Every driver is still under contract. Drivers enter the market when their
                    deals expire at season's end — play through a full season and they'll appear here next off-season.</p>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="side-section">
          <!-- Offer form -->
          <div v-if="offerDriver" class="finance-box offer-form">
            <h3 class="muted uppercase">Offer · {{ offerDriver.name }}</h3>
            <label>Salary (per year)</label>
            <input type="number" v-model="offerSalary" min="500000" max="75000000" step="500000" />
            <div class="hint faint">{{ fmtMoneyFull(offerSalary) }}</div>
            <label>Contract length (years)</label>
            <input type="number" v-model="offerYears" min="1" max="5" />
            <div class="offer-actions">
              <button class="btn-cancel" @click="cancelOffer">Cancel</button>
              <button class="btn" :disabled="submitting" @click="submitOffer">{{ submitting ? 'Sending…' : 'Submit offer' }}</button>
            </div>
          </div>

          <!-- Budget -->
          <div class="finance-box">
            <h3 class="muted uppercase">Signing Budget</h3>
            <div class="fin-row"><span class="k">Open seats</span><span class="v num">{{ market.playerTeamOpenSeats ?? 0 }}</span></div>
            <div class="fin-row"><span class="k">Cash reserves</span><span class="v num">{{ fmtMoney(market.playerTeamCashReserves) }}</span></div>
            <div class="fin-row"><span class="k">Driver salary bill</span><span class="v num neg">−{{ fmtMoney(market.playerTeamDriverSalaryBill) }}</span></div>
            <div class="divider"></div>
            <div class="fin-row total"><span class="k">Headroom</span><span class="v num" :class="{ good: headroom > 0, neg: headroom <= 0 }">{{ fmtMoney(headroom) }}</span></div>
          </div>

          <!-- Pending offers -->
          <div class="finance-box" style="margin-top:16px">
            <h3 class="muted uppercase">Pending Offers · Round {{ market.currentRound }}/{{ market.totalRounds }}</h3>
            <div class="offer-item" v-for="o in offers" :key="o.driverId">
              <div>
                <div>{{ o.driverName }}</div>
                <div class="faint small">{{ fmtMoney(o.salary) }} · {{ o.contractYears }}yr</div>
              </div>
              <button class="btn-cancel btn-tiny" @click="withdraw(o)">Withdraw</button>
            </div>
            <div v-if="!offers.length" class="faint text-center small">No active offers.</div>
          </div>
        </div>
      </div>
    </div>

    <!-- SPONSORSHIPS -->
    <div v-else>
      <div v-if="!myTeam" class="faint p-20 text-center">Select a team to view its sponsorship deals.</div>
      <div v-else-if="sponsorsLoading" class="faint p-20">Loading deals…</div>
      <table v-else class="data-table">
        <thead><tr><th>Sponsor</th><th>Tier</th><th class="r">Annual Value</th><th>Term</th><th class="r"></th></tr></thead>
        <tbody>
          <tr v-for="s in sponsorships" :key="s.id">
            <td class="name">{{ s.sponsorName }}</td>
            <td class="faint">{{ s.sponsorTier }}</td>
            <td class="r num">{{ fmtMoney(s.annualValue) }}</td>
            <td class="faint">{{ s.startYear }}–{{ s.endYear }}</td>
            <td class="r"><span v-if="s.isTitle" class="pill title">Title</span></td>
          </tr>
          <tr v-if="!sponsorships.length"><td colspan="5" class="faint text-center p-20">No sponsorship deals.</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 20px; }
.card-header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--line); padding-bottom: 16px; margin-bottom: 16px; }
.card-header h2 { margin: 0; font-size: 16px; font-weight: 700; }
.error-banner { background: var(--accent-soft); border: 1px solid #e1060055; color: #ff7066; padding: 10px 14px; border-radius: 8px; margin-bottom: 14px; font-size: 12px; }
.tabs button { background: transparent; color: var(--muted); border: 1px solid var(--line); padding: 6px 12px; font-size: 12px; cursor: pointer; font-weight: 600; }
.tabs button:first-child { border-radius: 6px 0 0 6px; border-right: none; }
.tabs button:last-child { border-radius: 0 6px 6px 0; }
.tabs button.active { background: var(--accent-soft); color: #ff7066; border-color: #e1060055; }

.closed { text-align: center; padding: 36px 20px; }
.closed-title { font-size: 16px; font-weight: 700; margin-bottom: 8px; }

.market-grid { display: grid; grid-template-columns: 2fr 1fr; gap: 24px; }
.uppercase { text-transform: uppercase; font-size: 11px; letter-spacing: 1px; margin: 0 0 12px; }

.data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table th { color: var(--faint); text-transform: uppercase; font-size: 10px; text-align: left; padding: 8px; border-bottom: 1px solid var(--line); }
.data-table td { padding: 12px 8px; border-bottom: 1px solid var(--surface-2); }
.data-table th.r, .data-table td.r { text-align: right; }
.text-center { text-align: center; }
.name { font-weight: 600; }
.nat { font-weight: 400; margin-left: 4px; font-size: 11px; }
.small { font-size: 11px; }

.btn { background: var(--accent); color: #fff; border: none; padding: 9px 14px; border-radius: 6px; cursor: pointer; font-weight: 700; font-size: 13px; }
.btn:hover:not(:disabled) { filter: brightness(1.1); }
.btn:disabled { opacity: .6; cursor: not-allowed; }
.btn-small { background: var(--surface-2); border: 1px solid var(--line); color: var(--fg); padding: 6px 12px; border-radius: 6px; cursor: pointer; }
.btn-small:hover { border-color: var(--muted); }
.btn-cancel { background: transparent; color: var(--muted); border: 1px solid var(--line); border-radius: 6px; padding: 8px 12px; cursor: pointer; font-weight: 600; }
.btn-cancel:hover { background: var(--surface-2); color: var(--fg); }
.btn-tiny { padding: 4px 8px; font-size: 11px; }

.finance-box { background: var(--bg); padding: 16px; border-radius: 8px; border: 1px solid var(--line); }
.offer-form { margin-bottom: 16px; border-color: var(--accent); }
.offer-form label { display: block; font-size: 11px; text-transform: uppercase; color: var(--muted); font-weight: 600; margin: 10px 0 4px; }
.offer-form input { width: 100%; background: var(--surface); border: 1px solid var(--line); color: var(--fg); padding: 8px 10px; border-radius: 6px; outline: none; box-sizing: border-box; }
.offer-form input:focus { border-color: var(--accent); }
.offer-form .hint { font-size: 11px; margin-top: 4px; }
.offer-actions { display: flex; gap: 8px; margin-top: 14px; justify-content: flex-end; }
.fin-row { display: flex; justify-content: space-between; margin-bottom: 8px; font-size: 13px; }
.fin-row .k { color: var(--muted); }
.fin-row .v { font-weight: 600; }
.divider { height: 1px; background: var(--line); margin: 12px 0; }
.total .v { font-size: 16px; font-weight: 800; }
.good { color: var(--good); } .neg { color: var(--bad); }
.offer-item { display: flex; justify-content: space-between; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--surface-2); font-size: 13px; }
.pill { background: var(--surface-2); color: var(--muted); padding: 3px 8px; border-radius: 10px; font-size: 11px; font-weight: 700; }
.pill.title { background: var(--accent-soft); color: #ff7066; }
.p-20 { padding: 20px; }
.empty-cell { text-align: center; padding: 28px 20px; }
.empty-cell .empty-title { font-weight: 700; font-size: 14px; margin-bottom: 6px; }
.empty-cell p { max-width: 380px; margin: 0 auto; font-size: 12px; line-height: 1.5; }
.faint { color: var(--faint); }
.muted { color: var(--muted); }
</style>
