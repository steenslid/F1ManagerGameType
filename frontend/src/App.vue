<script setup>
import { ref } from 'vue'
import TestUI from './testui/TestUI.vue'
import NewUI from './NewUI.vue'

const mode = ref('new')
</script>

<template>
  <button
    class="dev-toggle"
    @click="mode = mode === 'test' ? 'new' : 'test'"
  >
    Switch to {{ mode === 'test' ? 'New UI' : 'Test UI' }}
  </button>

  <TestUI v-if="mode === 'test'" />
  <NewUI v-else @open-test-ui="mode = 'test'" />
</template>

<style>
.dev-toggle {
  position: fixed; bottom: 12px; right: 12px; z-index: 9999;
  background: #ff00ff; color: white; border: none; padding: 6px 10px;
  border-radius: 7px; font-weight: bold; font-size: 11px; cursor: pointer;
  box-shadow: 0 4px 10px rgba(0, 0, 0, 0.5);
  opacity: 0.45; transition: opacity 0.15s;
}
.dev-toggle:hover { background: #d000d0; opacity: 1; }
</style>
