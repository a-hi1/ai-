<template>
  <div class="app">
    <RouterView />
  </div>
</template>

<script setup>
import { RouterView } from 'vue-router'
</script>

<style scoped>
.app {
  min-height: 100vh;
  padding: 20px;
  background:
    radial-gradient(circle at top left, rgba(0, 196, 180, 0.16), transparent 28%),
    radial-gradient(circle at right 12%, rgba(255, 77, 77, 0.14), transparent 22%),
    linear-gradient(180deg, #0f172a 0%, #0b1220 48%, #08101c 100%);
}
</style>
