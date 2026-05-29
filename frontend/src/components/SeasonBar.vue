<script setup>
defineProps({
  teamName: { type: String, default: 'Scuderia Rossa' },
  teamPrincipal: { type: String, default: 'Alex Verma' },
  season: { type: Number, default: 2026 },
  round: { type: String, default: '5 / 10' },
  phase: { type: String, default: 'PRACTICE' },
  cash: { type: String, default: '$214.6M' }
})

const emit = defineEmits(['advance'])
</script>

<template>
  <div class="topbar">
    <div class="team">
      <div class="crest">SR</div>
      <div>
        <div class="name">{{ teamName }}</div>
        <div class="role">Team Principal · {{ teamPrincipal }}</div>
      </div>
    </div>

    <div class="ctx">
      <div class="item"><span class="k">Season</span><span class="v num">{{ season }}</span></div>
      <div class="item"><span class="k">Round</span><span class="v num">{{ round }}</span></div>
      <div class="item"><span class="k">Phase</span><span class="phase-pill">{{ phase }}</span></div>
      <div class="item"><span class="k">Cash</span><span class="v num">{{ cash }}</span></div>
    </div>

    <div class="advance">
      <button class="btn" @click="emit('advance')">Go to Sprint Qualifying →</button>
    </div>
  </div>
</template>

<style scoped>
.topbar {
  position: sticky; top: 45px; z-index: 5; display: flex; align-items: center; gap: 20px;
  padding: 10px 20px; background: linear-gradient(180deg, #15181d, #111317);
  border-bottom: 1px solid var(--line);
}
.team { display: flex; align-items: center; gap: 10px; min-width: 210px; }
.crest {
  width: 30px; height: 30px; border-radius: 7px; background: var(--accent);
  display: grid; place-items: center; font-weight: 800; color: #fff; font-size: 13px;
}
.name { font-weight: 700; letter-spacing: .2px; }
.role { font-size: 11px; color: var(--muted); }
.ctx { display: flex; align-items: center; gap: 22px; flex: 1; }
.item { display: flex; flex-direction: column; line-height: 1.25; }
.k { font-size: 10px; text-transform: uppercase; letter-spacing: .8px; color: var(--faint); }
.v { font-weight: 600; }
.phase-pill {
  padding: 3px 10px; border-radius: 20px; background: var(--accent-soft);
  color: #ff7066; border: 1px solid #e1060055; font-weight: 700; font-size: 12px; letter-spacing: .4px;
}
.advance { margin-left: auto; display: flex; align-items: center; gap: 12px; }
.btn {
  background: var(--accent); color: #fff; border: none; padding: 10px 18px; border-radius: 9px;
  font-weight: 700; font-size: 13px; cursor: pointer; letter-spacing: .2px;
}
.btn:hover { filter: brightness(1.08); }
</style>