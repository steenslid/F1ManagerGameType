<script setup>
defineProps({
  activePanel: String
})
const emit = defineEmits(['update:activePanel', 'openTestUi'])

// Removed 'Standings' since it's centralized on the Dashboard
const menuItems = [
  'Dashboard', 'Race Weekend', 'Schedule', 'Drivers',
  'Finance', 'Market', 'Staff', 'Academy', 'Teams', 'History'
]
</script>


<template>
  <nav class="nav">
    <span class="brand">F1<b>SIM</b></span>

    <a
        v-for="item in menuItems"
        :key="item"
        :class="{ active: activePanel === item }"
        @click="emit('update:activePanel', item)"
    >
      {{ item }}
    </a>

    <a class="spacer">Settings</a>
    <a class="ext" @click="emit('openTestUi')">Test UI ↗</a>
    <a class="faint">Feedback</a>
  </nav>
</template>

<style scoped>
.nav {
  display: flex; align-items: center; gap: 2px; padding: 0 18px;
  background: #0b0d10; border-bottom: 1px solid var(--line);
  position: sticky; top: 0; z-index: 6;
}
.brand { font-weight: 800; letter-spacing: 1.5px; color: #fff; margin-right: 18px; font-size: 13px; }
.brand b { color: var(--accent); }
a {
  padding: 14px 12px; font-size: 13px; color: #cdd2d8; font-weight: 600;
  border-bottom: 2px solid transparent; cursor: pointer; white-space: nowrap;
}
a:hover { color: #fff; }
a.active { color: var(--accent); border-bottom-color: var(--accent); }
.spacer { margin-left: auto; }
a.ext { color: var(--muted); }
a.ext:hover { color: #fff; }
a.faint { color: var(--faint); }
</style>