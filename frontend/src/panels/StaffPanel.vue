<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api.js'
import { useGame } from '../useGame.js'
import { fmtMoney } from '../format.js'

const { refreshAll } = useGame()

const market = ref(null)
const loading = ref(true)
const error = ref(null)
const busy = ref(false)
const search = ref('')
const hirePick = ref({}) // personnelId -> role chosen in its row select

const SKILLS = [
  { key: 'leadership', label: 'LDR' },
  { key: 'design', label: 'DES' },
  { key: 'strategy', label: 'STR' },
  { key: 'crewManagement', label: 'CRW' },
  { key: 'driverManagement', label: 'DRV' },
]

onMounted(load)

async function load() {
  loading.value = true
  error.value = null
  try {
    market.value = (await api.getStaffMarket()).data
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    loading.value = false
  }
}

const hasTeam = computed(() => !!market.value?.playerTeamId)
const slots = computed(() => market.value?.slots || [])
const vacantRoles = computed(() => slots.value.filter((s) => !s.member))

const filtered = computed(() => {
  const q = search.value.trim().toLowerCase()
  const list = market.value?.available || []
  if (!q) return list
  return list.filter((p) => p.name.toLowerCase().includes(q) || p.nationality.toLowerCase().includes(q))
})

function skillClass(v) {
  if (v >= 85) return 'elite'
  if (v >= 70) return 'good'
  return ''
}

async function hire(p) {
  const role = hirePick.value[p.id] || vacantRoles.value[0]?.role
  if (!role) return
  busy.value = true
  error.value = null
  try {
    market.value = (await api.hireStaff(p.id, role)).data
    await refreshAll()
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    busy.value = false
  }
}

async function release(member) {
  busy.value = true
  error.value = null
  try {
    market.value = (await api.releaseStaff(member.id)).data
    await refreshAll()
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <h1 class="page-title">Staff</h1>
  <div v-if="error" class="error-banner">{{ error }}</div>
  <div v-if="loading" class="faint card empty">Loading…</div>

  <template v-else>
    <!-- Role slots -->
    <div class="card">
      <h2>Department heads</h2>
      <div v-if="!hasTeam" class="faint empty">Select a team to manage its staff.</div>
      <div v-else class="slots">
        <div class="slot" v-for="s in slots" :key="s.role" :class="{ vacant: !s.member }">
          <div class="srole">{{ s.label }}</div>
          <template v-if="s.member">
            <div class="sname">{{ s.member.name }}</div>
            <div class="ssub faint">{{ s.member.nationality }} · {{ s.member.age }}y</div>
            <div class="sskill">
              <span class="skv num" :class="skillClass(s.member.skills[s.skill])">{{ s.member.skills[s.skill] }}</span>
              <span class="skl">primary</span>
            </div>
            <div class="sfoot">
              <span class="faint num">{{ fmtMoney(s.member.salary) }}</span>
              <button class="btn-cancel btn-tiny" :disabled="busy" @click="release(s.member)">Release</button>
            </div>
          </template>
          <template v-else>
            <div class="sname faint">Vacant</div>
            <div class="ssub faint">Hire someone below.</div>
          </template>
        </div>
      </div>
      <p v-if="hasTeam" class="faint note">
        A strong <b>Technical Director</b> speeds car development; a strong
        <b>Race Engineer</b> accelerates your young drivers' growth. Salaries are
        billed to operating costs.
      </p>
    </div>

    <!-- Available free agents -->
    <div class="card" style="margin-top:16px">
      <div class="card-header">
        <h2>Available staff</h2>
        <input v-model="search" type="text" placeholder="Search name or nationality…" class="search-input" />
      </div>
      <table class="data-table">
        <thead>
          <tr>
            <th>Name</th><th>Nat.</th><th class="r">Age</th>
            <th class="text-center" v-for="s in SKILLS" :key="s.key">{{ s.label }}</th>
            <th class="r">Salary</th><th class="r"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in filtered" :key="p.id">
            <td class="name">{{ p.name }}</td>
            <td class="faint">{{ p.nationality }}</td>
            <td class="r num">{{ p.age }}</td>
            <td class="text-center" v-for="s in SKILLS" :key="s.key">
              <span class="skill-badge" :class="skillClass(p.skills[s.key])">{{ p.skills[s.key] }}</span>
            </td>
            <td class="r num faint">{{ fmtMoney(p.salary) }}</td>
            <td class="r nowrap">
              <select v-if="vacantRoles.length" v-model="hirePick[p.id]" class="sel">
                <option v-for="r in vacantRoles" :key="r.role" :value="r.role">{{ r.label }}</option>
              </select>
              <button class="btn-small" :disabled="busy || !vacantRoles.length || !hasTeam" @click="hire(p)">Hire</button>
            </td>
          </tr>
          <tr v-if="!filtered.length"><td colspan="9" class="faint text-center p-20">No free-agent staff.</td></tr>
        </tbody>
      </table>
    </div>
  </template>
</template>

<style scoped>
.page-title { font-size: 18px; margin: 4px 0 18px; font-weight: 700; }
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 18px; }
.card > h2 { font-size: 11px; text-transform: uppercase; letter-spacing: 1px; color: var(--muted); margin: 0 0 14px; font-weight: 700; }
.card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; gap: 12px; }
.card-header h2 { margin: 0; font-size: 11px; text-transform: uppercase; letter-spacing: 1px; color: var(--muted); font-weight: 700; }
.error-banner { background: var(--accent-soft); border: 1px solid #e1060055; color: #ff7066; padding: 10px 14px; border-radius: 8px; margin-bottom: 14px; font-size: 12px; }
.empty { padding: 10px 0; font-size: 13px; }

.slots { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 12px; }
.slot { background: var(--bg); border: 1px solid var(--line); border-radius: 10px; padding: 13px 15px; }
.slot.vacant { border-style: dashed; }
.srole { font-size: 10px; text-transform: uppercase; letter-spacing: .6px; color: var(--faint); font-weight: 700; margin-bottom: 8px; }
.sname { font-weight: 700; font-size: 14px; }
.ssub { font-size: 11px; margin-top: 1px; }
.sskill { display: flex; align-items: baseline; gap: 6px; margin: 10px 0; }
.skv { font-size: 22px; font-weight: 800; }
.skv.good { color: var(--good); } .skv.elite { color: #ff7066; }
.skl { font-size: 9px; text-transform: uppercase; letter-spacing: .5px; color: var(--faint); }
.sfoot { display: flex; align-items: center; justify-content: space-between; border-top: 1px solid var(--line); padding-top: 9px; font-size: 12px; }
.note { font-size: 12px; line-height: 1.5; margin: 14px 0 0; }

.search-input { background: var(--bg); border: 1px solid var(--line); color: var(--fg); padding: 8px 12px; border-radius: 6px; font-size: 13px; outline: none; width: 230px; }
.search-input:focus { border-color: var(--accent); }
.data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table th { color: var(--faint); text-transform: uppercase; font-size: 10px; text-align: left; padding: 8px; border-bottom: 1px solid var(--line); }
.data-table td { padding: 10px 8px; border-bottom: 1px solid var(--surface-2); vertical-align: middle; }
.data-table th.r, .data-table td.r { text-align: right; }
.text-center { text-align: center; }
.name { font-weight: 700; }
.nowrap { white-space: nowrap; }
.skill-badge { display: inline-block; padding: 1px 6px; border-radius: 4px; background: var(--surface-2); border: 1px solid var(--line); font-weight: 700; font-size: 11px; min-width: 22px; }
.skill-badge.elite { background: var(--accent-soft); color: #ff7066; border-color: #e1060055; }
.skill-badge.good { background: rgba(57,211,83,.1); color: var(--good); border-color: #39d35355; }
.sel { background: var(--bg); border: 1px solid var(--line); color: var(--fg); padding: 6px 8px; border-radius: 6px; font-size: 12px; outline: none; margin-right: 6px; }
.btn-small { background: var(--accent); color: #fff; border: none; padding: 6px 14px; border-radius: 6px; cursor: pointer; font-weight: 700; font-size: 12px; }
.btn-small:hover:not(:disabled) { filter: brightness(1.1); }
.btn-small:disabled { opacity: .5; cursor: not-allowed; }
.btn-cancel { background: transparent; color: var(--muted); border: 1px solid var(--line); border-radius: 6px; padding: 5px 10px; cursor: pointer; font-weight: 600; }
.btn-cancel:hover:not(:disabled) { background: var(--surface-2); color: var(--fg); }
.btn-tiny { font-size: 11px; padding: 4px 9px; }
.p-20 { padding: 20px; }
.faint { color: var(--faint); }
.num { font-variant-numeric: tabular-nums; }
</style>
