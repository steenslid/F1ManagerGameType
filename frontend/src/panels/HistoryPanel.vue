<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { api } from '../api.js'
import { useGame } from '../useGame.js'
import { fmtMoney } from '../format.js'

const { state } = useGame()

const season = ref(null)
const races = ref([])         // RaceDto[] for round -> track mapping
const results = ref([])       // RaceResultDto[] across the season
const report = ref(null)      // off-season ReportDto
const loading = ref(false)
const error = ref(null)
const expanded = ref(new Set()) // rounds whose full classification is open

const myTeamId = computed(() => state.overview?.playerTeam?.id || null)

const seasons = computed(() => {
  const cur = state.overview?.year
  if (!cur) return []
  const start = cur - 6
  return Array.from({ length: cur - start + 1 }, (_, i) => cur - i)
})

onMounted(() => {
  season.value = state.overview?.year || null
  load()
})
watch(() => [state.overview?.year, state.overview?.round], () => {
  if (!season.value || season.value === state.overview?.year) {
    season.value = state.overview?.year
    load()
  }
})

async function load() {
  if (!season.value) return
  loading.value = true
  error.value = null
  expanded.value = new Set()
  try {
    const [racesRes, resultsRes, reportRes] = await Promise.all([
      api.listRaces({ season: season.value }).catch(() => ({ data: [] })),
      api.listRaceResults({ season: season.value }).catch(() => ({ data: [] })),
      api.getOffSeasonReport({ season: season.value }).catch(() => ({ data: null })),
    ])
    races.value = racesRes.data || []
    results.value = resultsRes.data || []
    report.value = reportRes.data || null
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    loading.value = false
  }
}

function pickSeason(y) {
  season.value = y
  load()
}

const trackByRound = computed(() => {
  const m = {}
  for (const r of races.value) m[r.round] = r.track?.name || `Round ${r.round}`
  return m
})

// Group results by round, sorted by finishing position, each with its podium.
const rounds = computed(() => {
  const byRound = {}
  for (const r of results.value) {
    ;(byRound[r.round] ||= []).push(r)
  }
  return Object.keys(byRound)
    .map(Number)
    .sort((a, b) => a - b)
    .map((round) => {
      const rows = byRound[round].sort(
        (a, b) => (a.finishingPosition ?? 999) - (b.finishingPosition ?? 999)
      )
      return {
        round,
        track: trackByRound.value[round] || `Round ${round}`,
        rows,
        winner: rows.find((x) => x.finishingPosition === 1) || null,
        podium: rows.filter((x) => x.finishingPosition && x.finishingPosition <= 3),
      }
    })
})

function toggle(round) {
  const s = new Set(expanded.value)
  s.has(round) ? s.delete(round) : s.add(round)
  expanded.value = s
}

function posClass(pos) {
  return pos === 1 ? 'p1' : pos === 2 ? 'p2' : pos === 3 ? 'p3' : ''
}

// Surface the meaningful off-season events; drop the per-driver age/drift noise.
const reportEvents = computed(() => {
  if (!report.value?.events) return []
  const SKIP = new Set(['AGE_TICK', 'STAT_DRIFT'])
  return report.value.events.filter((e) => !SKIP.has(e.eventType))
})
const reportCounts = computed(() => {
  const c = report.value?.counts
  if (!c) return []
  return [
    { label: 'Retirements', val: c.retirements },
    { label: 'Contracts expired', val: c.contractExpirations },
    { label: 'Market signings', val: c.marketSignings },
    { label: 'Sponsor payouts', val: c.sponsorRevenue },
  ].filter((x) => x.val != null)
})
function evClass(type) {
  if (type === 'RETIREMENT') return 'retire'
  if (type === 'MARKET_SIGNING') return 'sign'
  if (type === 'CONTRACT_EXPIRED') return 'expire'
  if (type === 'SPONSOR_REVENUE' || type === 'FINANCE_SETTLED' || type === 'OPERATING_COST') return 'money'
  return ''
}
</script>

<template>
  <div class="head">
    <h1 class="page-title">History</h1>
    <select v-if="seasons.length" class="season-select" :value="season" @change="pickSeason(Number($event.target.value))">
      <option v-for="y in seasons" :key="y" :value="y">{{ y }} season</option>
    </select>
  </div>

  <div v-if="error" class="error-banner">{{ error }}</div>
  <div v-if="loading" class="faint card empty">Loading history…</div>

  <div v-else class="grid">
    <!-- Race-by-race results -->
    <div class="col">
      <div class="card">
        <h2>Race results · {{ season }}</h2>
        <div v-if="!rounds.length" class="faint empty">
          No races have been run in {{ season }} yet. Results appear here as the season unfolds.
        </div>
        <div v-else class="rounds">
          <div class="round" v-for="r in rounds" :key="r.round">
            <div class="round-head" @click="toggle(r.round)">
              <span class="rd-badge">R{{ r.round }}</span>
              <span class="rd-track">{{ r.track }}</span>
              <span class="rd-podium">
                <span v-for="p in r.podium" :key="p.driverId" class="pod" :class="posClass(p.finishingPosition)">
                  {{ p.finishingPosition }}. {{ p.driverName }}
                </span>
              </span>
              <span class="rd-toggle">{{ expanded.has(r.round) ? '−' : '+' }}</span>
            </div>
            <table v-if="expanded.has(r.round)" class="data-table">
              <thead><tr><th class="r">Pos</th><th>Driver</th><th>Team</th><th class="r">Grid</th><th class="r">Pts</th><th>Status</th></tr></thead>
              <tbody>
                <tr v-for="row in r.rows" :key="row.driverId" :class="{ me: row.teamId === myTeamId }">
                  <td class="r num">{{ row.finishingPosition || '—' }}</td>
                  <td class="name">{{ row.driverName }}
                    <span v-if="row.pole" class="tag">Pole</span>
                    <span v-if="row.fastestLap" class="tag fl">FL</span>
                  </td>
                  <td class="faint">{{ row.teamName }}</td>
                  <td class="r num faint">{{ row.gridPosition || '—' }}</td>
                  <td class="r num">{{ row.points }}</td>
                  <td><span class="status" :class="(row.status || '').toLowerCase()">{{ row.status }}</span>
                    <span v-if="row.dnfCause" class="faint dnf">{{ row.dnfCause }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>

    <!-- Off-season report -->
    <div class="col">
      <div class="card">
        <h2>Off-season {{ season }}</h2>
        <div v-if="!report || (!reportEvents.length && !reportCounts.length)" class="faint empty">
          No off-season has been processed for {{ season }} yet. Advance through the
          end of the season to settle the books, age the grid and run the market.
        </div>
        <template v-else>
          <div class="counts" v-if="reportCounts.length">
            <div class="count" v-for="c in reportCounts" :key="c.label">
              <span class="cv num">{{ c.val }}</span>
              <span class="cl">{{ c.label }}</span>
            </div>
          </div>
          <div class="events">
            <div class="event" v-for="e in reportEvents" :key="e.id" :class="evClass(e.eventType)">
              <span class="ev-dot"></span>
              <div>
                <div class="ev-msg">{{ e.message }}</div>
                <div class="ev-sub faint">{{ e.subjectName }}</div>
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18px; }
.page-title { font-size: 18px; margin: 4px 0; font-weight: 700; }
.season-select { background: var(--surface); border: 1px solid var(--line); color: var(--fg); padding: 8px 12px; border-radius: 8px; font-size: 13px; outline: none; cursor: pointer; font-weight: 600; }
.season-select:focus { border-color: var(--accent); }
.error-banner { background: var(--accent-soft); border: 1px solid #e1060055; color: #ff7066; padding: 10px 14px; border-radius: 8px; margin-bottom: 14px; font-size: 12px; }

.grid { display: grid; grid-template-columns: 1.5fr 1fr; gap: 16px; align-items: start; }
.col { display: flex; flex-direction: column; gap: 16px; }
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 16px 18px; }
.card h2 { font-size: 11px; text-transform: uppercase; letter-spacing: 1px; color: var(--muted); margin: 0 0 12px; font-weight: 700; }
.empty { padding: 10px 0; font-size: 13px; line-height: 1.5; }

.rounds { display: flex; flex-direction: column; gap: 8px; }
.round { border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }
.round-head { display: flex; align-items: center; gap: 12px; padding: 10px 12px; cursor: pointer; background: var(--bg); }
.round-head:hover { background: var(--surface-2); }
.rd-badge { font-size: 11px; font-weight: 800; color: var(--muted); background: var(--surface-2); border-radius: 5px; padding: 3px 7px; flex: none; }
.rd-track { font-weight: 700; font-size: 13px; min-width: 120px; }
.rd-podium { display: flex; gap: 6px; flex-wrap: wrap; margin-left: auto; }
.pod { font-size: 11px; padding: 2px 7px; border-radius: 5px; background: var(--surface-2); color: var(--muted); }
.pod.p1 { background: #d9a44122; color: #d9a441; }
.rd-toggle { font-size: 16px; color: var(--faint); width: 16px; text-align: center; flex: none; }

.data-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.data-table th { color: var(--faint); text-transform: uppercase; font-size: 9px; text-align: left; padding: 7px 8px; border-bottom: 1px solid var(--line); border-top: 1px solid var(--line); }
.data-table td { padding: 8px; border-bottom: 1px solid var(--surface-2); }
.data-table th.r, .data-table td.r { text-align: right; }
.name { font-weight: 700; }
tr.me td { background: var(--accent-soft); }
.tag { margin-left: 5px; font-size: 9px; text-transform: uppercase; padding: 1px 5px; border-radius: 4px; background: var(--surface-2); color: var(--muted); font-weight: 700; }
.tag.fl { color: #b07cf0; }
.status { font-size: 11px; }
.status.dnf { color: var(--bad); }
.status.finished { color: var(--good); }
.dnf { margin-left: 6px; font-size: 11px; }

.counts { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 14px; }
.count { background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; display: flex; flex-direction: column; }
.cv { font-size: 20px; font-weight: 800; }
.cl { font-size: 10px; text-transform: uppercase; letter-spacing: .5px; color: var(--faint); }

.events { display: flex; flex-direction: column; gap: 2px; max-height: 520px; overflow-y: auto; }
.event { display: flex; gap: 10px; padding: 8px 4px; border-bottom: 1px solid var(--surface-2); }
.ev-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--muted); margin-top: 5px; flex: none; }
.event.retire .ev-dot { background: var(--bad); }
.event.sign .ev-dot { background: var(--good); }
.event.expire .ev-dot { background: var(--warn); }
.event.money .ev-dot { background: #5aa9e6; }
.ev-msg { font-size: 13px; }
.ev-sub { font-size: 11px; margin-top: 1px; }
.faint { color: var(--faint); }
</style>
