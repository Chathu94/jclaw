<script setup lang="ts">
// Heroicons ships no brand marks, and Lucide dropped its `github` glyph in the
// 1.x brand-icon purge — so the Octocat below is an inline path rather than an
// icon import. The star stays Heroicons to match the header's other controls.
import { StarIcon } from '@heroicons/vue/24/outline'

const REPO = 'tsukhani/jclaw'
const CACHE_KEY = 'jclaw-github-stars'
// Unauthenticated api.github.com allows 60 requests/hour/IP. Refreshing at most
// once every six hours keeps a long-lived browser tab nowhere near that ceiling
// while a vanity count stays fresh enough to be worth showing.
const TTL_MS = 6 * 60 * 60 * 1000

interface CachedStars {
  count: number
  at: number
}

const repoUrl = `https://github.com/${REPO}`
const stars = ref<number | null>(null)

function readCache(): CachedStars | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    const parsed = raw ? JSON.parse(raw) as CachedStars : null
    return typeof parsed?.count === 'number' && typeof parsed?.at === 'number' ? parsed : null
  }
  catch {
    // Private-mode localStorage throws on access, and a hand-mangled entry
    // throws on parse. Either way: no cache, fall through to the network.
    return null
  }
}

onMounted(async () => {
  const cached = readCache()
  // Paint the cached count first so the pill doesn't visibly resize mid-load.
  if (cached) stars.value = cached.count
  if (cached && Date.now() - cached.at < TTL_MS) return

  try {
    const repo = await $fetch<{ stargazers_count: number }>(`https://api.github.com/repos/${REPO}`)
    stars.value = repo.stargazers_count
    localStorage.setItem(CACHE_KEY, JSON.stringify({ count: repo.stargazers_count, at: Date.now() }))
  }
  catch {
    // Air-gapped install, offline laptop, or a rate-limited IP. The link is the
    // point; the count is decoration, so degrade to an icon-only pill instead
    // of surfacing an error the operator can do nothing about.
  }
})

/** 3_300 → "3.3k", 3_000 → "3k", 12_400 → "12k". Matches GitHub's own rounding. */
function formatStars(n: number): string {
  if (n < 1000) return String(n)
  const k = n / 1000
  return `${k < 10 ? k.toFixed(1).replace(/\.0$/, '') : Math.round(k)}k`
}

const label = computed(() => (stars.value === null ? null : formatStars(stars.value)))
const ariaLabel = computed(() =>
  stars.value === null
    ? 'JClaw on GitHub'
    : `JClaw on GitHub — ${stars.value} stars`,
)
</script>

<template>
  <a
    :href="repoUrl"
    target="_blank"
    rel="noopener"
    :aria-label="ariaLabel"
    title="Star JClaw on GitHub"
    data-star-anchor
    class="flex items-center gap-1.5 shrink-0 pl-2 pr-2.5 py-1.5
           border border-fg-muted/40 rounded-full text-sm
           text-fg-muted hover:text-fg-strong hover:border-ring transition-colors"
  >
    <svg
      class="w-4 h-4"
      viewBox="0 0 24 24"
      fill="currentColor"
      aria-hidden="true"
    >
      <path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12" />
    </svg>
    <StarIcon
      class="w-4 h-4"
      aria-hidden="true"
    />
    <span
      v-if="label"
      class="tabular-nums"
    >{{ label }}</span>
  </a>
</template>
