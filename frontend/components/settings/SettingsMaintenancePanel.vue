<script setup lang="ts">
// Maintenance section (JCLAW-1057): the two controls that take the instance
// down, on one page.
//
// Composition only — each panel keeps its own heading, preflight fetch,
// confirmation dialog and progress tracking, because the two flows differ in
// kind: a restart is down within seconds, while an upgrade downloads a release
// before stopping anything and stays usable for minutes after the ack. Both
// root at `mb-6 space-y-4`, so they separate themselves and this adds no
// spacing of its own.
import SettingsJvmPanel from './SettingsJvmPanel.vue'
import SettingsLogsPanel from './SettingsLogsPanel.vue'
import SettingsUpgradePanel from './SettingsUpgradePanel.vue'
import SettingsRestartPanel from './SettingsRestartPanel.vue'
</script>

<template>
  <div>
    <!-- State first, then the actions that change it: the runtime figures are what
         an operator checks before deciding whether to restart at all. -->
    <SettingsJvmPanel />
    <SettingsLogsPanel />
    <SettingsUpgradePanel />
    <SettingsRestartPanel />
  </div>
</template>
