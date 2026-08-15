<script setup lang="ts">
// Maintenance section (JCLAW-1057): the operator actions that change this
// instance, ordered by how much they disrupt it — a password reset costs
// nothing, an upgrade downloads a release and ends in a restart, a restart cuts
// everything in flight.
//
// Composition only. Each panel keeps its own heading, preflight fetch and
// confirmation, and all three root at `mb-6 space-y-4`, so they separate
// themselves and this adds no spacing of its own.
//
// Runtime and log figures deliberately live elsewhere: JVM state sits with the
// dispatcher tuning it informs (Performance) and log disk usage with the levels
// that produce it (Logging).
import SettingsPasswordPanel from './SettingsPasswordPanel.vue'
import SettingsUpgradePanel from './SettingsUpgradePanel.vue'
import SettingsRestartPanel from './SettingsRestartPanel.vue'
</script>

<template>
  <div>
    <SettingsPasswordPanel />
    <SettingsUpgradePanel />
    <SettingsRestartPanel />
  </div>
</template>
