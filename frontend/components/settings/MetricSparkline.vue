<script setup lang="ts">
// Trend line for an unbounded polled metric (JCLAW-1057).
//
// The counterpart to MetricBar: where a value has no ceiling to be a proportion of,
// Grafana's guidance is a stat with a sparkline rather than a gauge. Hand-rolled SVG
// with theme tokens — the project carries no charting dependency and one polyline does
// not justify introducing the first.
//
// The buffer fills as samples arrive, so a freshly-opened panel has nothing to draw. It
// renders as an empty track of the same height rather than collapsing, because a visual
// that appears a minute after the panel does would shift every card beneath it.

const props = defineProps<{
  /** Oldest to newest. Fewer than two points is not yet a trend. */
  points: number[]
  label: string
}>()

const VIEW_W = 100
const VIEW_H = 16

const path = computed(() => {
  const pts = props.points
  if (pts.length < 2) return ''
  // Auto-scaled: an unbounded series has no ceiling by definition. A flat run divides by
  // a floor of 1 so it sits on the baseline instead of vanishing.
  const span = Math.max(...pts, 1)
  const step = VIEW_W / (pts.length - 1)
  return pts
    .map((v, i) => {
      const y = VIEW_H - Math.min(1, Math.max(0, v / span)) * VIEW_H
      return `${i === 0 ? 'M' : 'L'}${(i * step).toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
})
</script>

<template>
  <svg
    :viewBox="`0 0 ${VIEW_W} ${VIEW_H}`"
    class="w-full h-4"
    preserveAspectRatio="none"
    role="img"
    :aria-label="label"
  >
    <line
      x1="0"
      :y1="VIEW_H - 0.5"
      :x2="VIEW_W"
      :y2="VIEW_H - 0.5"
      stroke="var(--color-border)"
      stroke-width="0.5"
      vector-effect="non-scaling-stroke"
    />
    <path
      v-if="path"
      :d="path"
      fill="none"
      stroke="var(--color-fg-primary)"
      stroke-width="1.5"
      vector-effect="non-scaling-stroke"
      stroke-linejoin="round"
      stroke-linecap="round"
    />
  </svg>
</template>
