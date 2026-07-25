<script setup lang="ts">
/**
 * Donut breakdown of prompt composition (JCLAW-690 follow-up).
 *
 * Renders one arc per entry, sized by token share, with a legend carrying the
 * exact numbers. Deliberately hand-rolled SVG rather than a chart dependency —
 * the same call the LatencyOverlayChart makes, and a single-series donut is a
 * few `stroke-dasharray` sums.
 *
 * Long tails are collapsed into a single "Other" slice: a prompt has ~20
 * sections and ~30 tool schemas, most of them under 2%, and a 30-slice donut
 * reads as noise. The table view remains the exhaustive one — this is the
 * at-a-glance shape, so `maxSlices` favours legibility over completeness and
 * the collapsed count is always named in the legend.
 */
import { computed } from 'vue'
import type { PromptBreakdownEntry } from '~/types/api'

const props = withDefaults(defineProps<{
  entries: PromptBreakdownEntry[]
  /** Denominator for share %. Omit to normalise within this series, which is
   *  what the prompt breakdown does — its one chart already spans every
   *  contributor, so the arcs should close at exactly 100%. Pass an explicit
   *  total only when a series is a known part of a larger whole. */
  total?: number
  /** Slices drawn before the remainder collapses into "Other". */
  maxSlices?: number
  label: string
}>(), { total: 0, maxSlices: 8 })

// Categorical palette. Chosen to stay distinguishable in both themes and to
// avoid the red reserved for destructive affordances elsewhere in the admin UI.
// Must hold at least `maxSlices` entries: the lookup wraps with `% length`, so a
// shorter palette silently paints two slices of one chart the same colour.
const COLORS = [
  '#2dd4bf', '#60a5fa', '#a78bfa', '#f0abfc',
  '#fbbf24', '#34d399', '#818cf8', '#fb923c',
  '#22d3ee', '#a3e635', '#f472b6', '#94a3b8',
]
const OTHER_COLOR = '#6b7280'

const RADIUS = 60
const STROKE = 26
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

const seriesTotal = computed(() => props.entries.reduce((n, e) => n + e.tokens, 0))
/** Share denominator: explicit total when given, else the series itself. Guarded
 *  against 0 so an all-empty series renders a track rather than dividing by zero. */
const denominator = computed(() => props.total || seriesTotal.value || 1)

const slices = computed(() => {
  const ranked = [...props.entries].filter(e => e.tokens > 0).sort((a, b) => b.tokens - a.tokens)
  const head = ranked.slice(0, props.maxSlices)
  const tail = ranked.slice(props.maxSlices)
  const parts = head.map((e, i) => ({ name: e.name, tokens: e.tokens, color: COLORS[i % COLORS.length]! }))
  if (tail.length) {
    parts.push({
      name: `Other (${tail.length})`,
      tokens: tail.reduce((n, e) => n + e.tokens, 0),
      color: OTHER_COLOR,
    })
  }
  // Walk a running offset so each arc starts where the previous ended.
  let offset = 0
  return parts.map((p) => {
    const fraction = p.tokens / denominator.value
    const arc = { ...p, fraction, dash: fraction * CIRCUMFERENCE, offset: -offset * CIRCUMFERENCE }
    offset += fraction
    return arc
  })
})

const pct = (f: number) => `${(f * 100).toFixed(1)}%`

/** Text alternative for the graphic — the donut is decorative on its own, so the
 *  accessible name carries the same ranking a sighted user reads off the arcs. */
const summary = computed(() =>
  `${props.label}: ${seriesTotal.value.toLocaleString()} tokens. `
  + slices.value.map(s => `${s.name} ${pct(s.fraction)}`).join(', '))
</script>

<template>
  <div class="flex flex-col sm:flex-row items-center gap-6 py-4">
    <svg
      :viewBox="`0 0 ${(RADIUS + STROKE) * 2} ${(RADIUS + STROKE) * 2}`"
      class="w-40 h-40 shrink-0 -rotate-90"
      role="img"
      :aria-label="summary"
    >
      <circle
        :cx="RADIUS + STROKE"
        :cy="RADIUS + STROKE"
        :r="RADIUS"
        fill="none"
        stroke="currentColor"
        class="text-border"
        :stroke-width="STROKE"
      />
      <circle
        v-for="s in slices"
        :key="s.name"
        :cx="RADIUS + STROKE"
        :cy="RADIUS + STROKE"
        :r="RADIUS"
        fill="none"
        :stroke="s.color"
        :stroke-width="STROKE"
        :stroke-dasharray="`${s.dash} ${CIRCUMFERENCE - s.dash}`"
        :stroke-dashoffset="s.offset"
      >
        <title>{{ s.name }} — {{ s.tokens.toLocaleString() }} tokens ({{ pct(s.fraction) }})</title>
      </circle>
    </svg>

    <ul class="flex-1 w-full space-y-1 text-xs">
      <li
        v-for="s in slices"
        :key="s.name"
        class="flex items-center gap-2"
      >
        <span
          class="w-2.5 h-2.5 shrink-0"
          :style="{ backgroundColor: s.color }"
          aria-hidden="true"
        />
        <span class="flex-1 truncate text-fg-primary font-mono">{{ s.name }}</span>
        <span class="tabular-nums text-fg-muted">{{ s.tokens.toLocaleString() }}</span>
        <span class="tabular-nums text-fg-muted w-12 text-right">{{ pct(s.fraction) }}</span>
      </li>
    </ul>
  </div>
</template>
