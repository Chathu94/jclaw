# Scrape access: measured gate report

Epic JCLAW-1080, gate story JCLAW-1091. Measured 2026-08-21, median of three runs per
lane against the 150-site CF-100 corpus, re-classified the same day.

**Outcome, as originally written: the gate does not pass.** One aggregate criterion misses
by 0.9 points and three of six per-stratum floors fail.

**Outcome after the recorded re-set (below): the gate passes**, against hard-tier floors
lowered to measured local-only capability and reclassified from targets to regression
detectors. The original thresholds are retained as a stretch goal outside the gate. Both
results are stated because only reporting the second would misrepresent what was
achieved.

## What shipped

Three rungs, wired into an escalation ladder that `web_scrape` and `web_fetch` both use:

| Rung | Transport |
|---|---|
| 1 | OkHttp + SsrfGuard + Readability |
| 2 | TLS/HTTP2 impersonation (curl_cffi behind a sidecar) |
| 3 | Stealth rendering (Patchright-driven Chromium behind a sidecar) |
| 4 | **Descoped** — commercial residential-IP providers (JCLAW-1089/1090) |

## Access rates — median of three

| Lane | Runs | Median | Equal-allocation | Prevalence-weighted |
|---|---|---|---|---|
| rung 1 | 61, 59, 61 | 61/150 | 40.7% | 61.2% |
| rung 2 | 75, 75, 78 | 75/150 | 50.0% | 69.0% |
| rung 3 | 103, 106, 102 | 103/150 | 68.7% | 88.1% |
| **ladder** | 101, 102, 99 | **101/150** | **67.3%** | **89.1%** |

**Baseline to final: 61/150 → 101/150 equal-allocation; 60.9% → 89.1% prevalence-weighted.**

Rung 3 alone scores one above the ladder on equal allocation and one *below* it on
prevalence. Both are expected. Run standalone, rung 3 puts a browser on every URL
including the four the ladder refuses on policy; the ladder in turn keeps rung 1's result
where a browser would have returned a JavaScript shell, and those sites sit in the tier
carrying 85% of the prevalence weight.

## The two denominators, and why both are reported

The corpus is deliberately skewed toward difficulty — 25 per stratum regardless of how
rare that stratum is — because equal allocation is what forces work on the hard tiers.
That makes it the right denominator for a per-stratum floor and the wrong one for an
"overall" number.

Prevalence weights come from probing 30,000 Tranco domains. **9,363 (31.2%) did not
respond at all** — parked, dead, NXDOMAIN — and are excluded from the denominator.
Nothing can scrape a dead host, and including them caps the achievable score near 69% by
arithmetic rather than by capability. The exclusion is worth roughly thirty points: with
those domains counted, the ladder reads about 61% rather than 89.1%. Any citation of the
weighted figure must carry this sentence with it.

## Gate assessment

| Criterion | Measured | Required | |
|---|---|---|---|
| Overall, prevalence-weighted | 89.1% | ≥ 90% | **FAIL** |
| Local-only, equal-allocation | 67.3% | ≥ 60% | PASS |
| unprotected-ssr | 100% | ≥ 95% | PASS |
| unprotected-spa | 100% | ≥ 95% | PASS |
| edge-served | 92% | ≥ 90% | PASS |
| denied | 44% | ≥ 60% | **FAIL** |
| challenge | 42% | ≥ 60% | **FAIL** |
| interactive | 21% | ≥ 40% | **FAIL** |

## Gate re-set, 2026-08-21 — recorded, not quietly moved

The story's own AC required this: the 90% aggregate and the 40% `interactive` floor could
never both be honoured, and the measured ceilings are now the input for resolving that.
The operator's decision was to re-set the hard-tier floors to local-only capability and
close the epic on what it delivered.

**What changes is the floors' job, not just their value.** They were written as targets
before any rung existed — statements of ambition. With rung 4 descoped, the hard tiers are
bounded by 44 deterministic refusals the local ladder cannot pass, so a target the code
cannot reach is not a gate, it is a permanent red light. They become **regression
detectors**: their job is to catch the ladder losing ground, not to assert it did enough.

Each is set below the observed minimum across three runs, because a floor at the median
fails half the time given the measured flapping.

| Stratum | Was | Now | Median | Observed range | Rationale |
|---|---|---|---|---|---|
| unprotected-ssr | ≥95% | ≥95% | 100% | 100–100% | unchanged; passes with margin |
| unprotected-spa | ≥95% | ≥95% | 100% | 100–100% | unchanged |
| edge-served | ≥90% | ≥90% | 92% | 92–92% | unchanged |
| denied | ≥60% | **≥40%** | 44% | 40–48% | below observed minimum |
| challenge | ≥60% | **≥36%** | 42% | 38–46% | below observed minimum |
| interactive | ≥40% | **≥16%** | 21% | 17–21% | below observed minimum |
| overall, prevalence-weighted | ≥90% | **≥88%** | 89.1% | 88.8–89.4% | below observed minimum |
| local-only, equal-allocation | ≥60% | ≥60% | 67.3% | 66–68% | unchanged; passes with margin |

**The original 60/60/40 and 90% are retained as the epic's stretch goal, recorded here
and deliberately outside the gate.** They are what a residential-IP lane (JCLAW-1089/1090,
descoped) would address. Anyone re-opening that work should treat these as the target and
this table as the local-only baseline to beat.

Under the re-set thresholds the gate **passes**. That is a weaker claim than the original
gate would have made, and it is stated plainly rather than presented as the same result.

## Per-vendor access — the question the epic was written to answer

| Vendor | n | rung 1 | ladder |
|---|---|---|---|
| none | 67 | — | 82.1% |
| akamai | 9 | — | 88.9% |
| sucuri | 8 | — | 75.0% |
| cloudflare | 22 | 18.2% | **50.0%** |
| datadome | 14 | — | 50.0% |
| imperva | 11 | — | 45.5% |
| cloudfront | 18 | — | 44.4% |

Cloudflare — the vendor the epic was written about — went from 18.2% to 50.0%.

## Measurement quality

Within a single sweep the measurement is stable: the aggregate ranged 99–102 across
three runs and the weighted figure 88.8–89.4%. The easy strata did not move at all; the
hard strata ranged 8 points.

Across days it is not. Re-classifying the corpus the same day found **6 of 150 entries
had changed label**, including two `challenge` sites that had stopped gating entirely and
one `interactive` site that had softened to `challenge`. Protection tiers drift on the
order of a few percent per day, which is epic risk R3 measured rather than assumed. A
gate number is a measurement of a week, not a constant.

Re-classification also means the realised strata are no longer exactly 25 apiece
(`ssr 27, spa 25, edge 25, denied 25, challenge 24, interactive 24`). The sampling design
stays equal-allocation and the URL set is unchanged; every floor is a percentage scored
against its own stratum's n. Requiring exact equality would make "re-classify before the
run" and "gate on equal allocation" mutually exclusive.

## Why the hard tiers stop where they do

The median ladder run's unresolved failures: 32 `TRUST_BLOCK`, 7 `THIN_CONTENT`,
5 `JS_CHALLENGE`, 4 `POLICY_BLOCK`, 1 `TURNSTILE`.

`TRUST_BLOCK` dominates and means only that the origin returned 403/406/429/503 — the
classifier does not know why. It is **not** established that these are IP-reputation
blocks: this host egresses from a consumer ISP (AS9930, Kuala Lumpur), which is the
address class that scores well. Geography against US/EU properties, request behaviour,
residual fingerprint, and endpoints not meant for browsers at all are all live candidates
and cannot be separated with the evidence in hand. Anyone reasoning about what rung 4
would buy should start by resolving that question, not by assuming the answer.

## Variance in the hard tiers

The three hard strata ranged 8, 8 and 4 points across the sweep while the easy strata did
not move at all. That variance is **small and highly concentrated**, not a general
instability:

| Hard-tier sites (n=73) | |
|---|---|
| always readable | 23 |
| never readable | 44 |
| **flapping** | **6** (8%) |

Sixty-seven of seventy-three are deterministic within a sweep. The six that move:

| Pattern | Stratum | Vendor | Site | Fails as |
|---|---|---|---|---|
| `Y..` | denied | akamai | oracle.com | TRUST_BLOCK |
| `YY.` | denied | akamai | ebay.com | TRUST_BLOCK |
| `YY.` | interactive | cloudflare | petco.com | TRUST_BLOCK |
| `..Y` | challenge | none | ren.tv | TRUST_BLOCK |
| `.Y.` | challenge | imperva | anses.gob.ar | JS_CHALLENGE |
| `.Y.` | challenge | imperva | msu.edu | JS_CHALLENGE |

Four of six flap on `TRUST_BLOCK` — a 403/429, which is what per-request scoring and rate
limiting look like — and two on `JS_CHALLENGE`, where a challenge sometimes resolves
inside the settle window and sometimes does not.

**A possible self-inflicted component, stated as a hypothesis rather than a finding.**
Three of the six degrade monotonically across the sweep, and Akamai's per-vendor score
declined 8 → 7 → 6 over the three ladder runs. Twelve full corpus passes ran inside
twenty-five minutes, so each origin was contacted repeatedly in a short window, and
cumulative scoring would look exactly like this. Against that reading: rung 2 *improved*
across the same sweep (75 → 75 → 78) and rung 1 was flat, which is not what general
contamination would produce. The sample is two transitions across nine Akamai sites —
suggestive, not established.

**Consequence for the method:** median-of-three assumes independent runs, and back-to-back
sweeps may not be independent. Runs spaced across hours would settle this and should be
preferred when a gate decision turns on a margin of one or two sites.

**Consequence for the floors:** with ±1–2 sites of movement per hard tier, a floor set at
the measured median fails about half the time. A floor that is meant to hold has to sit
below the observed minimum.

## Sitemap seeding (JCLAW-1092) — measured separately, and why

The gate harness cannot see this feature. It fetches **one URL per corpus entry at depth
0**, and seeding activates at depth 1 by design, so `scrapetest` is structurally blind to
it. The harness measures *per-URL access* — can we read this page; seeding changes *crawl
coverage* — which pages a crawl returns. Nothing in the epic measured the second, so this
was measured directly: crawl each seed at depth 1, `maxPages` 25, with seeding on and off,
and compare the returned page sets.

| Seed | off | on | only-on |
|---|---|---|---|
| tumblr.com | 10 | 10 | 0 |
| spotify.com | 10 | 10 | 0 |
| **icloud.com** | **1** | **24** | **23** |
| wordpress.org | 15 | 15 | 0 |
| nginx.org | 7 | 7 | 0 |

**The ticket's prediction was wrong, and usefully so.** It expected `unprotected-ssr` to
benefit first. The benefit is entirely in `unprotected-spa`: a server-rendered site has
crawlable links, so harvesting saturates the page budget on its own and seeding is a
no-op. An SPA's entry page has no links to harvest — `icloud.com` returned one page
without seeding and twenty-four with it.

So the feature is worth having for exactly the case that motivated it and does nothing
elsewhere, which is the right shape: harvested links are merged first and the page budget
cuts from the end, so seeding only spends budget that harvesting could not fill.

## Policy blocks — the Web Bot Auth question

**4 origins** classify as `POLICY_BLOCK`: they state that they refuse automated access.
The ladder does not escalate these, deliberately. An origin refusing on identity is
answered by identification, not evasion, and the ladder's behaviour here is pinned by a
test so it cannot be optimised away for a metric.

Four sites out of 150 is the entire measured population for which Web Bot Auth — signed,
identified requests — would be the appropriate answer. That is the evidence this AC asked
for, and it argues the mechanism is not worth building for reach. It may still be worth
building for correctness.
