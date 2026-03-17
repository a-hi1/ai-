<template>
  <MonitorConsoleView mode="detail" :route-service-id="routeServiceId" />
</template>

<script setup lang="ts">
import MonitorConsoleView from '../components/MonitorConsoleView.vue'

defineProps<{
  routeServiceId?: string
}>()
</script>