# Scrape corpus — CF-100

The measuring stick for [JCLAW-1080](https://jira.abundent.com/browse/JCLAW-1080). `cf-100.json`
is what `./jclaw.sh scrapetest` runs against; `prevalence.json` is what turns its per-tier results
into a real-world estimate.

This is **not** an eval suite. `evals/suites/` is deliberately offline — no backend, no network, no
model call, validated inside `play autotest` by `EvalSuiteConformanceTest`. A corpus of live URLs
can never be offline, so it lives beside them rather than among them, and the harness follows the
`loadtest` precedent (live backend, loopback + `X-Loadtest-Auth`) instead.

## Regenerating

```bash
python3 evals/scrape/build_corpus.py --sample 15000 --per-tier 25
```

Stdlib only — one-time data generation, not app code. Downloads the current Tranco list into
`.cache/` (gitignored, ~22 MB) and probes concurrently. Roughly 20 minutes at `--workers 64`.

## Where the URLs come from

No public list of "Cloudflare sites with bot protection enabled" exists — Cloudflare does not
publish one, and Wappalyzer's commercial dataset records the script's *presence*, not the tier. So
the corpus is **discovered, not looked up**:

```
Tranco top-1M, sampled across log-spaced rank bands   ← popularity list, CF-agnostic
        ↓  GET, retry once on www.
   cf-ray header present?                             ← "behind Cloudflare"
        ↓  read headers + body markers
   tier assigned from deployed protection             ← never from "we failed"
        ↓  best-ranked 25 per tier
   cf-100.json
```

Sampling avoids the head of the list on purpose: the top ranks are big-tech own-infrastructure and
carry almost no Cloudflare. Within a tier, selection is **best-ranked first, deterministic** —
long-tail domains churn, and a corpus that rots between gate runs silently stops being comparable.

## Classification

Tier comes from what is *deployed*, never from whether our own client got in. A `403` alone is not
a tier; if the corpus were labelled by our failures, the benchmark would be circular.

| Tier | Rule |
|---|---|
| `open` | `cf-ray`, HTTP 200, real body |
| `basic` | `cf-ray`, 403/406/429, no challenge markup |
| `managed-challenge` | `cf-mitigated` header, or `/cdn-cgi/challenge-platform/` in body |
| `turnstile` | `challenges.cloudflare.com/turnstile` or `cf-turnstile` in body |
| `other-waf` | no `cf-ray`, but CloudFront / Akamai / DataDome / Imperva / Sucuri / Fastly fingerprint |
| `not-cloudflare`, `unreachable` | excluded from the corpus |

`other-waf` is tracked and excluded rather than counted against us — a CloudFront 403 says nothing
about the target this epic named. `unreachable` is largely Tranco infrastructure domains
(`gtld-servers.net`, `akamai.net`) that never serve a page; the `www.` retry recovers ~14% of them,
and those skew to stable well-known sites.

The probe presents a browser User-Agent, as tech-detection crawlers do: a bot UA trips crude
UA-based blocking and misreports the tier.

## Equal allocation, and why the gate depends on it

25 per tier **regardless of natural prevalence**. The corpus is deliberately far more hostile than
the real web.

Proportional sampling would yield 1–4 Turnstile sites. One site changing a dashboard setting would
swing that tier by 25–100%, and the tiers most worth measuring would be the ones carrying no signal.

This is what makes the epic gate bite. Under equal allocation, 90/100 means a perfect 25/25 on three
tiers is still only 75 — you need ≥ 60% on the hardest tier to reach 90. Under proportional
weighting the same threshold is nearly free: if ~75% of Cloudflare origins are unprotected, rung 1
alone scores ~75 before any Cloudflare capability exists, and you could pass while failing every
Turnstile site in existence.

So: **gate on the equal-allocation score. Report the prevalence-weighted score as context.**
`prevalence.json` carries the observed population distribution for that re-weighting.

## Schema

```jsonc
// cf-100.json
{
  "tranco_list_id": "K9LYW",   // regenerate the identical seed list from this
  "probed_on": "2026-08-19",   // tiers drift; JCLAW-1091 re-classifies before the gate run
  "sample_size": 15000,
  "seed": 1081,
  "allocation": "equal",
  "per_tier": 25,
  "entries": [
    {
      "url": "https://example.com",
      "tier": "open",
      "rank": 4213,
      "ground_truth": {
        "min_chars": 600,
        "reject_markers": ["/cdn-cgi/challenge-platform/", "just a moment", "..."],
        "expect_title": "example domain"      // open tier only — see below
      }
    }
  ]
}
```

### `ground_truth` is the load-bearing field

A Cloudflare interstitial is valid HTML. It has a title and body text and extracts to a few hundred
characters of clean markdown. **Without these assertions the harness scores a challenge page as a
success** — and would report a healthy number while agents receive nothing but "checking your
browser".

`reject_markers` is the half that always applies, and it is what the known-zero validation checks:
a URL that always challenges must score 0%, not 60%.

`expect_title` is only capturable for origins that already answered the builder, i.e. the `open`
tier. Protected entries carry negative assertions plus `min_chars` — which is sufficient, because
the failure mode being guarded against is precisely "a challenge page counted as content".
