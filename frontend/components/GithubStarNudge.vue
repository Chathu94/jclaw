<script setup lang="ts">
const props = defineProps<{
  /**
   * Hold the nudge back — the layout passes the guided-tour intro dialog's
   * open state. On a fresh install that modal auto-opens on first load, and a
   * hand-drawn arrow pointing at the header from behind a dimmed overlay is
   * the worst possible first impression. While suppressed the nudge doesn't
   * render and doesn't burn its once-ever flag, so it appears on the next load
   * instead.
   */
  suppressed?: boolean
}>()

const SEEN_KEY = 'jclaw-star-nudge-seen'
// Long enough for the layout's `loadTourStatus()` call (a local endpoint,
// single-digit ms in practice) to have resolved, so `suppressed` is settled
// before we decide. Doubles as a beat after paint — appearing mid-render
// reads as a glitch rather than an annotation.
const APPEAR_DELAY_MS = 1200
const VISIBLE_MS = 8000

// The arrow's tip within its own 120×90 viewBox. Used to line the SVG up with
// the GitHub pill: the pill's position is measured at show time rather than
// hardcoded, because the pill's width tracks the star count ("9" vs "3.3k"
// differ by ~20px) and a pointing annotation that misses its target is worse
// than no annotation.
const TIP_X = 106
const TIP_Y = 18
const SVG_W = 120

const visible = ref(false)
const anchored = ref(false)
const pos = ref({ top: 0, right: 0 })

function placeAtAnchor() {
  const anchor = document.querySelector('[data-star-anchor]')
  if (!anchor) return false

  const r = anchor.getBoundingClientRect()
  // jsdom (and a display:none anchor) report an all-zero rect. Positioning
  // against that would park the arrow in the top-left corner pointing at
  // nothing, so treat it as "no anchor" and stay hidden.
  if (r.width === 0 && r.height === 0) return false

  // Anchoring by `right` rather than `left` means the label can be any width
  // — the container grows leftward and the arrow tip stays put.
  pos.value = {
    top: r.bottom + 8 - TIP_Y,
    right: window.innerWidth - (r.left + r.width / 2) - (SVG_W - TIP_X),
  }
  return true
}

let appearTimer: ReturnType<typeof setTimeout> | undefined
let hideTimer: ReturnType<typeof setTimeout> | undefined

function dismiss() {
  visible.value = false
  clearTimeout(hideTimer)
  document.removeEventListener('click', dismiss)
}

onMounted(() => {
  if (localStorage.getItem(SEEN_KEY)) return

  appearTimer = setTimeout(() => {
    if (props.suppressed || !placeAtAnchor()) return

    anchored.value = true
    visible.value = true
    localStorage.setItem(SEEN_KEY, '1')

    hideTimer = setTimeout(dismiss, VISIBLE_MS)
    document.addEventListener('click', dismiss)
  }, APPEAR_DELAY_MS)
})

onUnmounted(() => {
  clearTimeout(appearTimer)
  clearTimeout(hideTimer)
  document.removeEventListener('click', dismiss)
})

// Any navigation means the user has moved on; the nudge has had its moment.
const router = useRouter()
router.afterEach(dismiss)
</script>

<template>
  <!-- aria-hidden: this is a visual annotation pointing at a control that is
       already in the a11y tree with its own label ("JClaw on GitHub — N
       stars"). Announcing it too would be a redundant, unrequested
       interruption on first load. pointer-events-none keeps it from eating
       clicks on the header beneath it. -->
  <Transition name="nudge">
    <div
      v-if="anchored && visible"
      class="fixed z-40 flex items-end gap-1 pointer-events-none select-none
             text-[#d94f2f] dark:text-[#f4795c]"
      :style="{ top: `${pos.top}px`, right: `${pos.right}px` }"
      aria-hidden="true"
    >
      <span class="nudge-label pb-3 text-3xl leading-none whitespace-nowrap">Leave a star!</span>
      <svg
        width="120"
        height="90"
        viewBox="0 0 120 90"
        fill="none"
        stroke="currentColor"
        stroke-width="3"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <path d="M2 74C46 82 92 72 106 18" />
        <path d="M106 18 93.8 31.3" />
        <path d="M106 18 110.2 35.5" />
      </svg>
    </div>
  </Transition>
</template>

<style scoped>
.nudge-label {
  font-family: Caveat, cursive;
  font-weight: 600;
}

.nudge-enter-active,
.nudge-leave-active {
  transition: opacity 0.45s ease, transform 0.45s ease;
}

.nudge-enter-from,
.nudge-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

@media (prefers-reduced-motion: reduce) {
  .nudge-enter-active,
  .nudge-leave-active {
    transition: none;
  }

  .nudge-enter-from,
  .nudge-leave-to {
    transform: none;
  }
}
</style>
