<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api.js'
import { useGame } from '../useGame.js'
import { fmtMoney, fmtMoneyFull } from '../format.js'

const { state: game, myTeam, refreshAll } = useGame()

const activeTab = ref('drivers')
const isLoading = ref(true)
const error = ref(null)

const market = ref({ active: false }) // MarketStateDto
const freeAgents = ref([])
const offers = ref([])

// Sponsor market
const sm = ref(null)            // SponsorMarketDto
const sponsorsLoading = ref(false)
const signForm = ref(null)
const renewForm = ref(null)
const sponsorBusy = ref(false)

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

async function loadSponsorMarket() {
  sponsorsLoading.value = true
  try {
    sm.value = (await api.getSponsorMarket()).data
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    sponsorsLoading.value = false
  }
}

function switchTab(tab) {
  activeTab.value = tab
  if (tab === 'sponsors' && !sm.value) loadSponsorMarket()
}

function startSign(s) {
  const max = Number(s.maxAnnualValue)
  signForm.value = {
    sponsorId: s.sponsorId, name: s.name, canBeTitle: s.canBeTitle,
    min: Number(s.minAnnualValue) || 1_000_000, max, value: max, years: 2, title: false,
  }
  renewForm.value = null
}

function startRenew(d) {
  const max = Number(d.maxAnnualValue)
  renewForm.value = {
    dealId: d.id, name: d.sponsorName, min: 1_000_000, max,
    value: Math.min(Number(d.annualValue), max) || max, years: 2,
  }
  signForm.value = null
}

function closeForms() { signForm.value = null; renewForm.value = null }

async function submitSign() {
  const f = signForm.value
  if (!f) return
  sponsorBusy.value = true
  error.value = null
  try {
    sm.value = (await api.signSponsor({
      sponsorId: f.sponsorId, annualValue: Number(f.value),
      termYears: Number(f.years), title: !!f.title,
    })).data
    signForm.value = null
    await refreshAll()
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    sponsorBusy.value = false
  }
}

async function submitRenew() {
  const f = renewForm.value
  if (!f) return
  sponsorBusy.value = true
  error.value = null
  try {
    sm.value = (await api.renewSponsor({
      dealId: f.dealId, annualValue: Number(f.value), termYears: Number(f.years),
    })).data
    renewForm.value = null
    await refreshAll()
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    sponsorBusy.value = false
  }
}

async function cancelDeal(d) {
  sponsorBusy.value = true
  error.value = null
  try {
    sm.value = (await api.cancelSponsor(d.id)).data
    await refreshAll()
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    sponsorBusy.value = false
  }
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
      <div v-if="!myTeam" class="faint p-20 text-center">Select a team to manage sponsors.</div>
      <div v-else-if="sponsorsLoading" class="faint p-20">Loading sponsors…</div>
      <div v-else-if="sm" class="market-grid">
        <div class="list-section">
          <div class="sec-head">
            <h3 class="muted uppercase">Your sponsors</h3>
            <span class="slots">{{ sm.dealsUsed }}/{{ sm.maxTotalDeals }} slots<span v-if="sm.titleUsed"> · title filled</span></span>
          </div>
          <table class="data-table">
            <thead><tr><th>Sponsor</th><th>Tier</th><th class="r">Value</th><th>Term</th><th class="r"></th></tr></thead>
            <tbody>
              <tr v-for="d in sm.currentDeals" :key="d.id" :class="{ lapsed: d.expired }">
                <td class="name">{{ d.sponsorName }} <span v-if="d.isTitle" class="pill title">Title</span></td>
                <td class="faint">{{ d.tier }}</td>
                <td class="r num">{{ fmtMoney(d.annualValue) }}</td>
                <td class="faint">{{ d.startYear }}–{{ d.endYear }}<span v-if="d.expired" class="exp"> · expired</span></td>
                <td class="r nowrap">
                  <button class="btn-small" @click="startRenew(d)">{{ d.expired ? 'Re-sign' : 'Renew' }}</button>
                  <button class="btn-cancel btn-tiny" :disabled="sponsorBusy" @click="cancelDeal(d)">Drop</button>
                </td>
              </tr>
              <tr v-if="!sm.currentDeals.length"><td colspan="5" class="faint text-center p-20">No sponsor deals — sign one below.</td></tr>
            </tbody>
          </table>

          <h3 class="muted uppercase mt">Available sponsors</h3>
          <table class="data-table">
            <thead><tr><th>Sponsor</th><th>Tier</th><th class="text-center">Prestige</th><th class="r">Will pay up to</th><th class="r"></th></tr></thead>
            <tbody>
              <tr v-for="s in sm.available" :key="s.sponsorId" :class="{ dim: !s.willDeal }">
                <td class="name">{{ s.name }} <span class="faint nat">{{ s.industry }}</span></td>
                <td class="faint">{{ s.tier }}</td>
                <td class="text-center num">{{ s.prestige }}</td>
                <td class="r num"><span v-if="s.willDeal">{{ fmtMoney(s.maxAnnualValue) }}</span><span v-else class="faint">—</span></td>
                <td class="r">
                  <button v-if="s.willDeal" class="btn-small" :disabled="sm.dealsUsed >= sm.maxTotalDeals" @click="startSign(s)">Sign</button>
                  <span v-else class="faint small">Prestige low</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="side-section">
          <!-- Sign form -->
          <div v-if="signForm" class="finance-box offer-form">
            <h3 class="muted uppercase">Sign · {{ signForm.name }}</h3>
            <label>Annual value</label>
            <input type="range" :min="signForm.min" :max="signForm.max" step="500000" v-model.number="signForm.value" />
            <div class="hint">{{ fmtMoneyFull(signForm.value) }} <span class="faint">· max {{ fmtMoney(signForm.max) }}</span></div>
            <label>Term (years)</label>
            <input type="number" v-model.number="signForm.years" min="1" max="5" />
            <label v-if="signForm.canBeTitle && !sm.titleUsed" class="check"><input type="checkbox" v-model="signForm.title" /> Title sponsor</label>
            <div class="offer-actions">
              <button class="btn-cancel" @click="closeForms">Cancel</button>
              <button class="btn" :disabled="sponsorBusy" @click="submitSign">{{ sponsorBusy ? 'Signing…' : 'Sign deal' }}</button>
            </div>
          </div>

          <!-- Renew form -->
          <div v-else-if="renewForm" class="finance-box offer-form">
            <h3 class="muted uppercase">Renew · {{ renewForm.name }}</h3>
            <label>Annual value</label>
            <input type="range" :min="renewForm.min" :max="renewForm.max" step="500000" v-model.number="renewForm.value" />
            <div class="hint">{{ fmtMoneyFull(renewForm.value) }} <span class="faint">· max {{ fmtMoney(renewForm.max) }}</span></div>
            <label>Term (years)</label>
            <input type="number" v-model.number="renewForm.years" min="1" max="5" />
            <div class="offer-actions">
              <button class="btn-cancel" @click="closeForms">Cancel</button>
              <button class="btn" :disabled="sponsorBusy" @click="submitRenew">{{ sponsorBusy ? 'Saving…' : 'Renew deal' }}</button>
            </div>
          </div>

          <div class="finance-box" :style="(signForm || renewForm) ? 'margin-top:16px' : ''">
            <h3 class="muted uppercase">Portfolio</h3>
            <div class="fin-row"><span class="k">Active deals</span><span class="v num">{{ sm.dealsUsed }}/{{ sm.maxTotalDeals }}</span></div>
            <div class="fin-row"><span class="k">Title sponsor</span><span class="v">{{ sm.titleUsed ? 'Yes' : '—' }}</span></div>
            <div class="fin-row"><span class="k">Team prestige</span><span class="v num">{{ sm.teamPrestige }}</span></div>
            <p class="faint small note">New and renewed deals take effect from next pre-season. Your deals no longer auto-renew — manage them here.</p>
          </div>
        </div>
      </div>
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

.sec-head { display: flex; align-items: baseline; justify-content: space-between; }
.slots { font-size: 11px; color: var(--muted); }
.mt { margin-top: 22px; }
.nowrap { white-space: nowrap; }
tr.lapsed td { opacity: .5; }
tr.dim td { opacity: .5; }
.exp { color: var(--warn); }
.check { display: flex; align-items: center; gap: 8px; text-transform: none; letter-spacing: 0; font-size: 12px; color: var(--fg); }
.check input { width: auto; }
.note { margin-top: 10px; line-height: 1.5; }
.offer-form input[type="range"] { width: 100%; accent-color: var(--accent); }
</style>
