<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api.js'

const series = ref('f2') // 'f2' | 'f3'
const standings = ref([])
const loading = ref(true)
const error = ref(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    standings.value = (await api.getLadder(series.value)).data || []
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    loading.value = false
  }
}

function pick(s) {
  if (series.value === s) return
  series.value = s
  load()
}

onMounted(load)

const promotionNote = computed(() =>
  series.value === 'f2'
    ? 'At season’s end the F2 champion graduates to F1 free agency — sign them in the driver market, or call one up mid-season from Race Weekend.'
    : 'At season’s end the F3 champion is promoted up to F2, one rung closer to Formula 1.'
)

// F3 ratings sit lower than F2 — shade thresholds accordingly.
function ratingClass(r) {
  const hi = series.value === 'f2' ? 70 : 58
  const mid = series.value === 'f2' ? 64 : 52
  if (r >= hi) return 'good'
  if (r >= mid) return 'mid'
  return ''
}
</script>

<template>
  <div class="head">
    <h1 class="page-title">Driver Ladder</h1>
    <div class="tabs">
      <button :class="{ active: series === 'f2' }" @click="pick('f2')">F2</button>
      <button :class="{ active: series === 'f3' }" @click="pick('f3')">F3</button>
    </div>
  </div>

  <div class="card note">
    <span class="dot"></span>
    <p class="faint">
      The {{ series.toUpperCase() }} feeder grid — there's no round-by-round
      sim yet, so this is a projected order by driver rating. {{ promotionNote }}
    </p>
  </div>

  <div v-if="error" class="error-banner">{{ error }}</div>

  <div class="card">
    <h2>{{ series.toUpperCase() }} standings</h2>
    <div v-if="loading" class="faint empty">Loading…</div>
    <table v-else-if="standings.length">
      <thead>
        <tr>
          <th style="width:34px">#</th><th>Driver</th><th>Team</th><th class="r">Age</th>
          <th class="r">Pace</th><th class="r">Qual</th><th class="r">Cons</th><th class="r">Rating</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="d in standings" :key="d.driverId" :class="{ champ: d.position === 1 }">
          <td><span class="pos" :class="{ p1: d.position === 1 }">{{ d.position }}</span></td>
          <td class="name">{{ d.name }} <span class="faint nat">{{ d.nationality }}</span>
            <span v-if="d.position === 1" class="tag">Promotion pick</span>
          </td>
          <td class="faint">{{ d.teamName }}</td>
          <td class="r num">{{ d.age }}</td>
          <td class="r num faint">{{ d.statPace }}</td>
          <td class="r num faint">{{ d.statQualifying }}</td>
          <td class="r num faint">{{ d.statConsistency }}</td>
          <td class="r"><span class="rating" :class="ratingClass(d.rating)">{{ d.rating }}</span></td>
        </tr>
      </tbody>
    </table>
    <div v-else class="faint empty">No F2 drivers found.</div>
  </div>
</template>

<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18px; }
.page-title { font-size: 18px; margin: 4px 0; font-weight: 700; }
.tabs button { background: transparent; color: var(--muted); border: 1px solid var(--line); padding: 6px 16px; font-size: 12px; cursor: pointer; font-weight: 700; }
.tabs button:first-child { border-radius: 6px 0 0 6px; border-right: none; }
.tabs button:last-child { border-radius: 0 6px 6px 0; }
.tabs button.active { background: var(--accent-soft); color: #ff7066; border-color: #e1060055; }
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 16px 18px; }
.card + .card { margin-top: 16px; }
.card h2 { font-size: 11px; text-transform: uppercase; letter-spacing: 1px; color: var(--muted); margin: 0 0 12px; font-weight: 700; }
.note { display: flex; gap: 12px; align-items: flex-start; border-left: 3px solid var(--accent); background: var(--surface-2); }
.note .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 0 4px var(--accent-soft); margin-top: 5px; flex: none; }
.note p { margin: 0; font-size: 13px; line-height: 1.5; }
.error-banner { background: var(--accent-soft); border: 1px solid #e1060055; color: #ff7066; padding: 10px 14px; border-radius: 8px; margin: 14px 0; font-size: 12px; }
.empty { padding: 8px 0; font-size: 13px; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th { font-size: 10px; text-transform: uppercase; letter-spacing: .6px; color: var(--faint); text-align: left; padding: 0 8px 8px; font-weight: 600; }
td { padding: 9px 8px; border-top: 1px solid var(--line); }
td.r, th.r { text-align: right; }
tr.champ td { background: var(--accent-soft); }
.name { font-weight: 700; }
.nat { font-weight: 400; font-size: 11px; margin-left: 4px; }
.tag { margin-left: 8px; font-size: 9px; text-transform: uppercase; letter-spacing: .5px; background: var(--accent-soft); color: #ff7066; border: 1px solid #e1060055; border-radius: 4px; padding: 1px 6px; }
.pos { display: inline-grid; place-items: center; width: 22px; height: 22px; border-radius: 6px; background: var(--surface-2); font-weight: 700; font-size: 12px; }
.pos.p1 { background: #d9a441; color: #1a1305; }
.rating { display: inline-block; padding: 2px 8px; border-radius: 4px; background: var(--surface-2); border: 1px solid var(--line); font-weight: 700; }
.rating.good { background: rgba(57,211,83,.1); color: var(--good); border-color: #39d35355; }
.rating.mid { background: var(--accent-soft); color: #ff7066; border-color: #e1060055; }
.faint { color: var(--faint); }
.num { font-variant-numeric: tabular-nums; }
</style>
