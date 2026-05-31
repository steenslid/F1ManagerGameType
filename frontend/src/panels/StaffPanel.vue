<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api.js'
import { useGame } from '../useGame.js'
import { fmtMoney } from '../format.js'

const { state, myTeam } = useGame()

const personnel = ref([])
const teamMap = ref({}) // id -> name
const isLoading = ref(true)
const error = ref(null)

// Directory filters
const roleFilter = ref('')   // '' = all
const search = ref('')

// Role metadata — order drives the "my team" card layout and the legend.
const ROLES = [
  { key: 'PRINCIPAL', label: 'Team Principal', short: 'TP' },
  { key: 'TECHNICAL_DIRECTOR', label: 'Technical Director', short: 'TD' },
  { key: 'CHIEF_STRATEGIST', label: 'Chief Strategist', short: 'CS' },
  { key: 'CREW_CHIEF', label: 'Crew Chief', short: 'CC' },
  { key: 'RACE_ENGINEER', label: 'Race Engineer', short: 'RE' },
]
const roleLabel = (r) => ROLES.find((x) => x.key === r)?.label || r || '—'
const roleShort = (r) => ROLES.find((x) => x.key === r)?.short || '—'

// Each role leans on a primary skill — surface it as the headline number.
const PRIMARY_SKILL = {
  PRINCIPAL: 'leadership',
  TECHNICAL_DIRECTOR: 'design',
  CHIEF_STRATEGIST: 'strategy',
  CREW_CHIEF: 'crewManagement',
  RACE_ENGINEER: 'driverManagement',
}

onMounted(load)

async function load() {
  isLoading.value = true
  error.value = null
  try {
    const [pRes, tRes] = await Promise.all([api.listPersonnel(), api.listTeams()])
    personnel.value = (pRes.data || []).filter((p) => !p.retired)
    const map = {}
    for (const t of tRes.data || []) map[t.id] = t.name
    teamMap.value = map
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    isLoading.value = false
  }
}

const myTeamId = computed(() => myTeam.value?.id || null)
const teamName = (id) => (id ? teamMap.value[id] || id : null)

// Player's staff, ordered by the canonical role list so the column is stable.
const myStaff = computed(() => {
  if (!myTeamId.value) return []
  const mine = personnel.value.filter((p) => p.currentTeamId === myTeamId.value)
  return [...mine].sort(
    (a, b) =>
      ROLES.findIndex((r) => r.key === a.role) - ROLES.findIndex((r) => r.key === b.role)
  )
})

const ratedPrimary = (p) => p.skills?.[PRIMARY_SKILL[p.role]] ?? 0
function skillClass(v) {
  if (v >= 85) return 'elite'
  if (v >= 70) return 'good'
  return ''
}

const filtered = computed(() => {
  const q = search.value.trim().toLowerCase()
  return personnel.value
    .filter((p) => !roleFilter.value || p.role === roleFilter.value)
    .filter((p) => {
      if (!q) return true
      return (
        p.name.toLowerCase().includes(q) ||
        (teamName(p.currentTeamId) || '').toLowerCase().includes(q)
      )
    })
    .map((p) => ({ ...p, teamName: teamName(p.currentTeamId), primary: ratedPrimary(p) }))
    .sort((a, b) => b.primary - a.primary)
})

// The five skill bars shown on each of the player's staff cards.
const SKILL_ROWS = [
  { key: 'leadership', label: 'Leadership' },
  { key: 'design', label: 'Design' },
  { key: 'strategy', label: 'Strategy' },
  { key: 'crewManagement', label: 'Crew Mgmt' },
  { key: 'driverManagement', label: 'Driver Mgmt' },
]
</script>

<template>
  <h1 class="page-title">Staff</h1>

  <div v-if="error" class="error-banner">{{ error }}</div>

  <!-- Player's department heads -->
  <div class="card">
    <h2>Your department heads</h2>
    <div v-if="isLoading" class="faint empty">Loading staff…</div>
    <div v-else-if="!myTeamId" class="faint empty">
      Select a team to manage its personnel.
    </div>
    <div v-else-if="!myStaff.length" class="faint empty">
      No personnel are contracted to your team. They become available once the
      personnel market is built out — for now seats may sit empty.
    </div>
    <div v-else class="staff-grid">
      <div class="staff-card" v-for="p in myStaff" :key="p.id">
        <div class="sc-head">
          <div class="role-badge">{{ roleShort(p.role) }}</div>
          <div>
            <div class="nm">{{ p.name }}</div>
            <div class="role faint">{{ roleLabel(p.role) }} · {{ p.nationality }} · {{ p.age }}y</div>
          </div>
          <div class="ovr" :class="skillClass(ratedPrimary(p))">{{ ratedPrimary(p) }}</div>
        </div>
        <div class="skills">
          <div class="skill" v-for="s in SKILL_ROWS" :key="s.key">
            <span class="sk-lab">{{ s.label }}</span>
            <span class="sk-bar"><span :style="{ width: (p.skills?.[s.key] ?? 0) + '%' }" :class="skillClass(p.skills?.[s.key] ?? 0)"></span></span>
            <span class="sk-val num">{{ p.skills?.[s.key] ?? 0 }}</span>
          </div>
        </div>
        <div class="sc-foot">
          <span class="faint">Salary</span>
          <span class="num">{{ fmtMoney(p.currentSalary) }}</span>
          <span class="faint contract" v-if="p.contractExpiresYear">· exp {{ p.contractExpiresYear }}</span>
        </div>
      </div>
    </div>
  </div>

  <!-- Personnel directory -->
  <div class="card" style="margin-top:16px">
    <div class="card-header">
      <h2>Personnel directory</h2>
      <div class="controls">
        <select v-model="roleFilter" class="select">
          <option value="">All roles</option>
          <option v-for="r in ROLES" :key="r.key" :value="r.key">{{ r.label }}</option>
        </select>
        <input v-model="search" type="text" placeholder="Search name or team…" class="search-input" />
      </div>
    </div>

    <div v-if="isLoading" class="faint empty">Loading…</div>
    <table v-else class="data-table">
      <thead>
        <tr>
          <th>Name</th><th>Role</th><th>Nat.</th><th class="r">Age</th>
          <th>Team</th><th class="r">Rating</th><th class="r">Salary</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="p in filtered" :key="p.id" :class="{ me: p.currentTeamId === myTeamId }">
          <td class="name">{{ p.name }}</td>
          <td class="faint">{{ roleLabel(p.role) }}</td>
          <td class="faint">{{ p.nationality }}</td>
          <td class="r num">{{ p.age }}</td>
          <td><span v-if="p.teamName">{{ p.teamName }}</span><span v-else class="faint">Free agent</span></td>
          <td class="r"><span class="rating-badge" :class="skillClass(p.primary)">{{ p.primary }}</span></td>
          <td class="r num faint">{{ fmtMoney(p.currentSalary) }}</td>
        </tr>
        <tr v-if="!filtered.length"><td colspan="7" class="faint text-center p-20">No personnel match.</td></tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.page-title { font-size: 18px; margin: 4px 0 18px; font-weight: 700; }
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 18px; }
.card > h2 { font-size: 11px; text-transform: uppercase; letter-spacing: 1px; color: var(--muted); margin: 0 0 14px; font-weight: 700; }
.card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; gap: 12px; }
.card-header h2 { margin: 0; font-size: 11px; text-transform: uppercase; letter-spacing: 1px; color: var(--muted); font-weight: 700; }
.controls { display: flex; gap: 8px; }
.error-banner { background: var(--accent-soft); border: 1px solid #e1060055; color: #ff7066; padding: 10px 14px; border-radius: 8px; margin-bottom: 14px; font-size: 12px; }
.empty { padding: 10px 0; font-size: 13px; }

.staff-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 14px; }
.staff-card { background: var(--bg); border: 1px solid var(--line); border-radius: 10px; padding: 14px 16px; }
.sc-head { display: flex; align-items: center; gap: 11px; }
.role-badge { width: 34px; height: 34px; border-radius: 8px; background: var(--surface-2); border: 1px solid var(--line); display: grid; place-items: center; font-weight: 800; font-size: 12px; color: var(--muted); flex: none; }
.sc-head .nm { font-weight: 700; font-size: 14px; }
.sc-head .role { font-size: 11px; margin-top: 1px; }
.ovr { margin-left: auto; font-size: 20px; font-weight: 800; color: var(--fg); }
.ovr.good { color: var(--good); }
.ovr.elite { color: #ff7066; }

.skills { margin: 14px 0 10px; display: flex; flex-direction: column; gap: 6px; }
.skill { display: grid; grid-template-columns: 78px 1fr 26px; align-items: center; gap: 8px; }
.sk-lab { font-size: 10px; text-transform: uppercase; letter-spacing: .4px; color: var(--faint); }
.sk-bar { height: 6px; border-radius: 4px; background: var(--surface-2); overflow: hidden; }
.sk-bar > span { display: block; height: 100%; background: var(--muted); }
.sk-bar > span.good { background: var(--good); }
.sk-bar > span.elite { background: var(--accent); }
.sk-val { font-size: 11px; text-align: right; color: var(--muted); }

.sc-foot { display: flex; align-items: baseline; gap: 6px; font-size: 12px; border-top: 1px solid var(--line); padding-top: 10px; }
.sc-foot .num { font-weight: 700; }
.contract { font-size: 11px; }

.select, .search-input { background: var(--bg); border: 1px solid var(--line); color: var(--fg); padding: 8px 10px; border-radius: 6px; font-size: 13px; outline: none; }
.select:focus, .search-input:focus { border-color: var(--accent); }
.search-input { width: 220px; }

.data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table th { color: var(--faint); text-transform: uppercase; font-size: 10px; text-align: left; padding: 8px; border-bottom: 1px solid var(--line); }
.data-table td { padding: 11px 8px; border-bottom: 1px solid var(--surface-2); vertical-align: middle; }
.data-table th.r, .data-table td.r { text-align: right; }
.text-center { text-align: center; }
.name { font-weight: 700; }
tr.me td { background: var(--accent-soft); }
.rating-badge { display: inline-block; padding: 2px 8px; border-radius: 4px; background: var(--surface-2); border: 1px solid var(--line); font-weight: 700; font-size: 12px; }
.rating-badge.elite { background: var(--accent-soft); color: #ff7066; border-color: #e1060055; }
.rating-badge.good { background: rgba(57,211,83,.1); color: var(--good); border-color: #39d35355; }
.p-20 { padding: 20px; }
.faint { color: var(--faint); }
</style>
