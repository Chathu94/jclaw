<script setup lang="ts">
// Proportional fill for a bounded metric (JCLAW-1057).
//
// Only for values that are genuinely a percentage of a whole — PatternFly's test for
// when a bar is the right shape, and Grafana's bar gauge carries the same requirement of
// "predefined boundaries". A metric with no ceiling gets a sparkline or a plain figure
// instead; drawing it as a bar invents a denominator and the bar then means nothing.
//
// Hand-rolled SVG-free markup with theme tokens, matching LatencyOverlayChart's
// no-charting-dependency approach.

const props = defineProps<{
  /** Drawn left to right, each a share of `total`. */
  segments: { label: string, value: number, class: string }[]
  /** The bound. Null or non-positive means there is nothing to be a proportion of. */
  total: number | null
}>()

const parts = computed(() => {
  const total = props.total
  if (!total || total <= 0) return []
  let offset = 0
  const out: { label: string, pct: number, offset: number, class: string }[] = []
  for (const s of props.segments) {
    if (s.value <= 0) continue
    // Clamped so a sample that outruns its bound cannot overflow the track. This hides
    // nothing: the figures beside the bar are the source of truth, and a bar is only
    // offered where the two genuinely nest.
    const pct = Math.min(100 - offset, (s.value / total) * 100)
    if (pct <= 0) continue
    // A floor of 2px keeps a real-but-tiny share visible. 352 MB of 48 GB is 0.7% —
    // honest, but sub-pixel, and an invisible bar reads as a rendering fault rather
    // than as "this uses almost nothing". The figure beside it carries the precision.
    out.push({ label: s.label, pct, offset, class: s.class })
    offset += pct
  }
  return out
})
</script>

<template>
  <div
    class="relative h-1 w-full rounded-full bg-muted overflow-hidden"
    role="img"
    :aria-label="segments.map(s => s.label).join(', ')"
  >
    <div
      v-for="p in parts"
      :key="p.label"
      class="absolute inset-y-0"
      :class="p.class"
      :style="{ left: `${p.offset}%`, width: `max(2px, ${p.pct}%)` }"
      :title="`${p.label}: ${p.pct.toFixed(1)}%`"
    />
  </div>
</template>
