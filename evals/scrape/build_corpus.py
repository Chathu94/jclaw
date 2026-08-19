#!/usr/bin/env python3
"""Build the CF-100 corpus for ./jclaw.sh scrapetest (JCLAW-1081).

Samples domains across Tranco rank bands, probes each one to discover what
protection is *deployed*, and emits an equal-allocation corpus plus the
population prevalence needed to post-stratify the harness results.

Stdlib only, on purpose: this is one-time data generation, not app code.

    python3 evals/scrape/build_corpus.py --sample 10000 --per-tier 25
"""

import argparse, csv, io, json, os, random, re, ssl, sys, time, urllib.request, zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache")
TRANCO_LATEST = "https://tranco-list.eu/api/lists/date/latest"

# Tech-detection probes (Wappalyzer, BuiltWith) present as a browser for the same
# reason we do: a bot UA trips crude UA-based blocking and misreports the tier.
UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

BODY_CAP = 65_536

TIERS = ["open", "basic", "managed-challenge", "turnstile"]

# Non-Cloudflare WAFs. Kept out of the corpus rather than counted as our failures —
# a CloudFront 403 says nothing about the target this epic named.
OTHER_WAF = [
    ("cloudfront",  lambda h, b: "cloudfront" in h.get("server", "") or "x-amz-cf-id" in h),
    ("akamai",      lambda h, b: "akamai" in h.get("server", "") or any(k.startswith("x-akamai") for k in h)),
    ("datadome",    lambda h, b: "x-datadome" in h or "datadome" in h.get("set-cookie", "")),
    ("imperva",     lambda h, b: "x-iinfo" in h or "incap_ses" in h.get("set-cookie", "")),
    ("sucuri",      lambda h, b: "x-sucuri-id" in h),
    ("fastly",      lambda h, b: "fastly" in h.get("server", "")),
]

CHALLENGE_MARKERS = ("/cdn-cgi/challenge-platform/", "just a moment", "cf_chl_opt", "cf-please-wait")
TURNSTILE_MARKERS = ("challenges.cloudflare.com/turnstile", "cf-turnstile")


def fetch(url, timeout=12):
    """GET url, returning (status, lowercased-headers, body-prefix). Never raises."""
    ctx = ssl.create_default_context()
    ctx.check_hostname = False          # classification only; we never trust this content
    ctx.verify_mode = ssl.CERT_NONE
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            h = {k.lower(): v.lower() for k, v in r.headers.items()}
            return r.status, h, r.read(BODY_CAP).decode("utf-8", "replace").lower()
    except urllib.error.HTTPError as ex:
        h = {k.lower(): v.lower() for k, v in ex.headers.items()} if ex.headers else {}
        try:
            body = ex.read(BODY_CAP).decode("utf-8", "replace").lower()
        except Exception:
            body = ""
        return ex.code, h, body
    except Exception as ex:
        return 0, {}, type(ex).__name__


def probe(domain):
    """Probe an origin, retrying once on www. — 14% of apex failures answer there
    (fanfiction.net, huawei.com, sss.gov.ph), and those skew to the stable,
    well-known sites a durable corpus wants."""
    url = "https://" + domain
    status, headers, body = fetch(url)
    if status == 0:
        alt = "https://www." + domain
        s2, h2, b2 = fetch(alt)
        if s2 != 0:
            return alt, s2, h2, b2
    return url, status, headers, body


def classify(status, headers, body, text=None):
    """Assign a protection tier from what the origin *did*, never from whether we failed.

    Two guards, and the second was learned the hard way. Circularity: 'we got a 403'
    alone is not a tier. Marker-presence: a page that *references* Turnstile is not a
    page *gated by* Turnstile — wiley.com and onetrust.com embed the widget and serve
    597 and 1574 characters of real content, and labelling them 'turnstile' put five
    open sites in the hardest tier and inverted the whole benchmark.

    So a marker only names a tier when the origin refused to serve content.
    """
    if status == 0:
        return "unreachable", None
    if text is None:
        text = visible_text(body)
    on_cf = "cf-ray" in headers
    if not on_cf:
        for name, test in OTHER_WAF:
            if test(headers, body):
                return "other-waf", name
        return "not-cloudflare", None

    gated = (any(m in body for m in TURNSTILE_MARKERS)
             or any(m in body for m in CHALLENGE_MARKERS)
             or "cf-mitigated" in headers)

    # A 200 with no gate marker is a served page at any size — example.com is 142
    # characters of complete content and is the canonical known-one, so thinness
    # alone must never read as blocked. It takes a marker *and* a body too thin to
    # be a page: hxuakdlb.com returns 200 with 0 characters behind a Turnstile
    # widget, while wiley.com returns 597 and merely embeds one.
    if status == 200 and (not gated or len(text) >= SERVED_MIN_TEXT):
        return "open", None

    if any(m in body for m in TURNSTILE_MARKERS):
        return "turnstile", None
    if "cf-mitigated" in headers or any(m in body for m in CHALLENGE_MARKERS):
        return "managed-challenge", None
    if status in (403, 406, 429):
        return "basic", None
    return "unreachable", "thin-or-%s" % status


TITLE_RE = re.compile(r"<title[^>]*>(.*?)</title>", re.S)
SCRIPT_RE = re.compile(r"<(script|style)[^>]*>.*?</\1>", re.S)
TAG_RE = re.compile(r"<[^>]+>")

# Below this many characters of visible text a 200 is a gate, not a page. A
# JS-rendered Turnstile wall returns 200 with an empty body (hxuakdlb.com: 0 chars);
# a real page that merely embeds a Turnstile widget returns hundreds (wiley.com: 597).
SERVED_MIN_TEXT = 200


def visible_text(body):
    return " ".join(TAG_RE.sub(" ", SCRIPT_RE.sub("", body)).split())


def ground_truth(status, body, tier, text=None):
    """Assertions the harness scores against.

    Negative markers are the load-bearing half: a Cloudflare interstitial is valid
    HTML that extracts to clean markdown, so without them the harness scores a
    challenge page as a success. Positive markers are only capturable for origins
    that already answered us, i.e. the open tier.

    {min_chars} is derived per entry rather than fixed, because a blanket 600 scored
    example.com - 198 extracted characters, and the canonical known-one - as blocked.
    A quarter of the observed visible text allows for Readability stripping nav and
    chrome; the floor and ceiling keep tiny and enormous pages both scoreable.
    """
    if text is None:
        text = visible_text(body)
    gt = {"min_chars": max(50, min(500, len(text) // 4)),
          # What the probe actually saw. An origin at the floor with a large HTML
          # body is a client-rendered app, not a small page - the harness needs that
          # apart from anti-bot failure.
          "observed_text": len(text),
          "observed_html": len(body),
          "reject_markers": list(CHALLENGE_MARKERS + TURNSTILE_MARKERS)}
    if tier == "open" and status == 200:
        m = TITLE_RE.search(body)
        if m:
            title = " ".join(m.group(1).split())[:120]
            if len(title) >= 8:
                gt["expect_title"] = title
    return gt


def tranco_domains(sample_n, seed):
    os.makedirs(CACHE, exist_ok=True)
    meta = json.load(urllib.request.urlopen(TRANCO_LATEST, timeout=30))
    list_id = meta["list_id"]
    path = os.path.join(CACHE, "tranco-%s.data" % list_id)
    if not os.path.exists(path):
        print("==> downloading Tranco %s" % list_id, file=sys.stderr)
        urllib.request.urlretrieve(meta["download"], path)
    # The download endpoint has served both a zip and bare text/csv; sniff rather
    # than trust either.
    with open(path, "rb") as fh:
        head = fh.read(2)
    if head == b"PK":
        with zipfile.ZipFile(path) as z:
            raw = z.read(z.namelist()[0]).decode()
    else:
        raw = open(path, encoding="utf-8").read()
    ranked = [row[1] for row in csv.reader(io.StringIO(raw)) if len(row) == 2]

    # Log-spaced bands, not the head. The top of any popularity list is big-tech
    # own-infrastructure and carries almost no Cloudflare.
    bands, lo = [], 0
    for hi in (1_000, 10_000, 100_000, len(ranked)):
        bands.append(list(enumerate(ranked[lo:hi], start=lo + 1))); lo = hi
    rng = random.Random(seed)
    per_band = sample_n // len(bands)
    picked = []
    for b in bands:
        picked += rng.sample(b, min(per_band, len(b)))
    rng.shuffle(picked)
    return list_id, picked


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sample", type=int, default=10_000, help="domains to probe")
    ap.add_argument("--per-tier", type=int, default=25, help="equal-allocation size per tier")
    ap.add_argument("--workers", type=int, default=32)
    ap.add_argument("--seed", type=int, default=1081)
    ap.add_argument("--out", default=HERE)
    args = ap.parse_args()

    list_id, domains = tranco_domains(args.sample, args.seed)
    print("==> Tranco %s, probing %d domains with %d workers"
          % (list_id, len(domains), args.workers), file=sys.stderr)

    results, done, t0 = [], 0, time.time()
    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        futs = {ex.submit(probe, d): (rank, d) for rank, d in domains}
        for f in as_completed(futs):
            rank, d = futs[f]
            url, status, headers, body = f.result()
            text = visible_text(body)
            tier, note = classify(status, headers, body, text)
            results.append({"domain": d, "url": url, "rank": rank, "status": status,
                            "tier": tier, "note": note,
                            "ground_truth": ground_truth(status, body, tier, text)})
            done += 1
            if done % 250 == 0:
                print("    %d/%d (%.0fs)" % (done, len(domains), time.time() - t0), file=sys.stderr)

    prevalence = {}
    for r in results:
        prevalence[r["tier"]] = prevalence.get(r["tier"], 0) + 1
    cf_total = sum(prevalence.get(t, 0) for t in TIERS)

    corpus = []
    for tier in TIERS:
        # Best-ranked first, not random: long-tail domains churn, and a corpus that
        # rots between gate runs silently stops being comparable.
        pool = sorted((r for r in results if r["tier"] == tier), key=lambda r: r["rank"])
        take = pool[:args.per_tier]
        if len(take) < args.per_tier:
            print("!!  tier %-18s only %d/%d available — widen --sample"
                  % (tier, len(take), args.per_tier), file=sys.stderr)
        for r in take:
            corpus.append({k: r[k] for k in ("url", "tier", "rank", "ground_truth")})

    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    json.dump({
        "tranco_list_id": list_id, "probed_on": stamp, "sample_size": len(domains),
        "seed": args.seed, "allocation": "equal", "per_tier": args.per_tier,
        "entries": corpus,
    }, open(os.path.join(args.out, "cf-100.json"), "w"), indent=2)

    json.dump({
        "tranco_list_id": list_id, "probed_on": stamp, "sample_size": len(domains),
        "counts": prevalence,
        "cloudflare_tier_share": {t: round(prevalence.get(t, 0) / cf_total, 4)
                                  for t in TIERS} if cf_total else {},
    }, open(os.path.join(args.out, "prevalence.json"), "w"), indent=2)

    print("\n==> probed %d in %.0fs" % (len(results), time.time() - t0), file=sys.stderr)
    for k in sorted(prevalence, key=lambda x: -prevalence[x]):
        print("    %-18s %5d" % (k, prevalence[k]), file=sys.stderr)
    print("==> corpus: %d entries -> cf-100.json" % len(corpus), file=sys.stderr)


if __name__ == "__main__":
    main()
