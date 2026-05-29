<script setup>
import { computed } from 'vue'
import { useGame } from '../useGame.js'

const { state, clearFeed } = useGame()

// Show the most recent handful as a toast stack.
const recent = computed(() => state.feed.slice(0, 5))

const ICON = {
  race: '🏁',
  market: '✍️',
  finance: '💰',
  people: '👤',
  info: '•',
}
</script>

<template>
  <div class="feed" v-if="recent.length">
    <div class="feed-head">
      <span class="ttl">Latest</span>
      <button class="clr" @click="clearFeed">Dismiss</button>
    </div>
    <transition-group name="toast" tag="div" class="toast-list">
      <div v-for="e in recent" :key="e.id" class="toast" :class="e.cat">
        <span class="ico">{{ ICON[e.cat] || '•' }}</span>
        <div class="body">
          <div class="txt">{{ e.text }}</div>
          <div class="meta" v-if="e.year">{{ e.year }}<span v-if="e.round"> · R{{ e.round }}</span></div>
        </div>
      </div>
    </transition-group>
  </div>
</template>

<style scoped>
.feed {
  position: fixed; right: 20px; bottom: 64px; z-index: 50; width: 320px;
  display: flex; flex-direction: column; gap: 8px;
}
.feed-head { display: flex; align-items: center; justify-content: space-between; padding: 0 4px; }
.ttl { font-size: 10px; text-transform: uppercase; letter-spacing: 1px; color: var(--faint); font-weight: 700; }
.clr { background: transparent; border: none; color: var(--muted); font-size: 11px; cursor: pointer; font-weight: 600; }
.clr:hover { color: var(--fg); }
.toast-list { display: flex; flex-direction: column; gap: 8px; }
.toast {
  display: flex; gap: 10px; align-items: flex-start;
  background: var(--surface); border: 1px solid var(--line); border-left: 3px solid var(--muted);
  border-radius: 10px; padding: 10px 12px; box-shadow: 0 6px 18px rgba(0,0,0,.35);
}
.toast.race { border-left-color: var(--accent); }
.toast.market { border-left-color: #b07cf0; }
.toast.finance { border-left-color: var(--good); }
.toast.people { border-left-color: var(--warn); }
.ico { font-size: 14px; line-height: 1.4; }
.body { flex: 1; min-width: 0; }
.txt { font-size: 12.5px; line-height: 1.35; }
.meta { font-size: 10px; color: var(--faint); margin-top: 3px; font-variant-numeric: tabular-nums; }

.toast-enter-active, .toast-leave-active { transition: all .25s ease; }
.toast-enter-from { opacity: 0; transform: translateX(20px); }
.toast-leave-to { opacity: 0; transform: translateX(20px); }
.toast-move { transition: transform .25s ease; }
</style>
