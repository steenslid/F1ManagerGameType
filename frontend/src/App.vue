<script setup>
import { ref } from 'vue'
import TestUI from './testui/TestUI.vue'
import NewUI from './NewUI.vue'

// Set default to 'test' so your app boots up exactly how it used to
const mode = ref('test')
</script>

<template>
  <button
      class="dev-toggle"
      @click="mode = mode === 'test' ? 'new' : 'test'"
  >
    Switch to {{ mode === 'test' ? 'New UI' : 'Test UI' }}
  </button>

  <TestUI v-if="mode === 'test'" />
  <NewUI v-else />
</template>

<style>
/* Global style for the dev toggle button */
.dev-toggle {
  position: fixed;
  bottom: 20px;
  right: 20px;
  z-index: 9999;
  background: #ff00ff;
  color: white;
  border: none;
  padding: 10px 15px;
  border-radius: 8px;
  font-weight: bold;
  cursor: pointer;
  box-shadow: 0 4px 10px rgba(0,0,0,0.5);
}
.dev-toggle:hover { background: #d000d0; }
</style>


