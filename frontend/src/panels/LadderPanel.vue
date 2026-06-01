<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api.js'

const standings = ref([])
const loading = ref(true)
const error = ref(null)

onMounted(async () => {
  loading.value = true
  error.value = null
  try {
    standings.value = (await api.getF2Ladder()).data || []
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    loading.value = false
  }
})

function ratingClass(r) {
  if (r >= 70) return 'good'
  if (r >= 64) return 'mid'
  return ''
}
</script>

<template>
  <h1 class="page-title">Driver Ladder · F2</h1>

  <div class="card note">
    <span class="dot"></span>
    <p class="faint">
      The F2 feeder grid. There's no round-by-round F2 sim yet, so this is a
      projected order by driver rating. At season's end the F2 champion
      <b>graduates to F1 free agency</b> — sign them in the driver market, or
      call one up mid-season from Race Weekend when a seat opens.
    </p>
  </div>

  <div v-if="error" class="error-banner">{{ error }}</div>

  <div class="card">
    <h2>F2 standings</h2>
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
.page-title { font-size: 18px; margin: 4px 0 18px; font-weight: 700; }
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
