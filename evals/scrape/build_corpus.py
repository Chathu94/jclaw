#!/usr/bin/env python3
"""Build the scrape corpus for ./jclaw.sh scrapetest (JCLAW-1081).

Samples domains across Tranco rank bands, probes each one, and classifies it on
three independent axes — edge vendor, protection outcome, rendering mode — then
emits an equal-allocation corpus plus the population prevalence needed to
post-stratify the harness results.

The axes are independent and conflating them is what broke the first corpus:
CloudFront in front does not mean CloudFront is blocking, and a page with no
server-rendered text may be a JS gate or an ordinary client-rendered app.

Stdlib only, on purpose: this is one-time data generation, not app code.

    python3 evals/scrape/build_corpus.py --sample 40000 --per-stratum 25
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

# Large enough that a real page is not truncated mid-document. At 64 KiB the cap
# landed inside <head> for big sites, so visible_text() saw only scripts and meta
# and reported ~40 characters — nasa.gov, taobao.com, squarespace.com and cnet.com
# all classified as client-rendered while WebFetchTool extracted thousands of
# characters from them.
BODY_CAP = 1_048_576

# Below this many characters of visible text a 200 carrying a gate marker is a gate,
# not a page. A JS-rendered Turnstile wall returns 200 with an empty body
# (hxuakdlb.com: 0 chars); a real page that merely embeds the widget returns
# hundreds (wiley.com: 597).
SERVED_MIN_TEXT = 200

# A served 200 with markup but no text is a client-rendered app
# (abundent.academy: 68 chars of text behind 5,515 bytes of HTML).
SPA_MAX_TEXT = 200
SPA_MIN_HTML = 2_000

# The floor is derived from raw visible text but scored against Readability's
# extraction, which strips nav and chrome — on a portal page that is nearly all of
# it. At a 500 ceiling, wikipedia.org (475 extracted) and mozilla.org (363) failed
# while genuine shells returned 7-15. Any threshold in that 25x gap separates them;
# 300 sits inside it with margin.
SERVED_FLOOR_MAX = 300
GATED_FLOOR = 500

STRATA = ["unprotected-ssr", "unprotected-spa", "edge-served",
          "denied", "challenge", "interactive"]

# Edge vendors that announce themselves in the response. Detection is
# fingerprint-based, so "none" means "no vendor I can identify" — an upper bound on
# unprotected, never a guarantee of it.
VENDORS = [
    ("cloudflare", lambda h, b: "cf-ray" in h),
    ("cloudfront", lambda h, b: "cloudfront" in h.get("server", "") or "x-amz-cf-id" in h),
    ("akamai",     lambda h, b: "akamai" in h.get("server", "")
                                or any(k.startswith("x-akamai") for k in h)),
    ("datadome",   lambda h, b: "x-datadome" in h or "datadome" in h.get("set-cookie", "")),
    ("imperva",    lambda h, b: "x-iinfo" in h or "incap_ses" in h.get("set-cookie", "")),
    ("sucuri",     lambda h, b: "x-sucuri-id" in h),
    ("fastly",     lambda h, b: "fastly" in h.get("server", "")),
]

CHALLENGE_MARKERS = ("/cdn-cgi/challenge-platform/", "just a moment", "cf_chl_opt",
                     "cf-please-wait", "_incapsula_resource", "checking your browser")
INTERACTIVE_MARKERS = ("challenges.cloudflare.com/turnstile", "cf-turnstile",
                       "captcha-delivery.com", "geo.captcha-delivery",
                       "g-recaptcha", "hcaptcha.com/1/api.js")

SCRIPT_RE = re.compile(r"<(script|style)[^>]*>.*?</\1>", re.S)
TAG_RE = re.compile(r"<[^>]+>")
TITLE_RE = re.compile(r"<title[^>]*>(.*?)</title>", re.S)


# A truncated read can cut mid-<script>, leaving it unclosed. SCRIPT_RE cannot match
# an unclosed tag, so its JavaScript is then counted as prose: pinterest.com reported
# 467,893 characters of "visible text" against 75 in the untruncated page, which put a
# pure SPA into the server-rendered stratum.
UNCLOSED_SCRIPT_RE = re.compile(r"<(script|style)[^>]*>(?:(?!</\1>).)*$", re.S)


def visible_text(body):
    body = UNCLOSED_SCRIPT_RE.sub("", body)
    return " ".join(TAG_RE.sub(" ", SCRIPT_RE.sub("", body)).split())


def read_body(r):
    """Read up to BODY_CAP, reporting truncation in BYTES.

    The previous guard compared the decoded character count against a byte cap. UTF-8
    multi-byte content decodes to fewer characters than bytes, so `len(body) >=
    BODY_CAP` was false even on a truncated read — pinterest.com truncated at 1,048,576
    bytes and decoded to 1,048,508 characters, and the guard silently never fired.
    """
    raw = r.read(BODY_CAP + 1)
    truncated = len(raw) > BODY_CAP
    return raw[:BODY_CAP].decode("utf-8", "replace").lower(), truncated


def fetch(url, timeout=12):
    """GET url, returning (status, lowercased-headers, body-prefix). Never raises."""
    # Verify certificates, because WebFetchTool does. Skipping verification here put
    # googlevideo.com, windows.net and ezviz7.com into the corpus as "served" when our
    # own stack cannot reach them at all — a cert mismatch is not a scraping-capability
    # question, and those entries only depress the score with noise.
    ctx = ssl.create_default_context()
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            h = {k.lower(): v.lower() for k, v in r.headers.items()}
            body, truncated = read_body(r)
            return r.status, h, body, truncated
    except urllib.error.HTTPError as ex:
        h = {k.lower(): v.lower() for k, v in ex.headers.items()} if ex.headers else {}
        try:
            body, truncated = read_body(ex)
        except Exception:
            body, truncated = "", False
        return ex.code, h, body, truncated
    except Exception:
        return 0, {}, "", False


def probe(domain):
    """Probe an origin, retrying once on www. — 14% of apex failures answer there
    (fanfiction.net, huawei.com, sss.gov.ph), and those skew to the stable,
    well-known sites a durable corpus wants."""
    url = "https://" + domain
    status, headers, body, truncated = fetch(url)
    if status == 0:
        alt = "https://www." + domain
        s2, h2, b2, t2 = fetch(alt)
        if s2 != 0:
            return alt, s2, h2, b2, t2
    return url, status, headers, body, truncated


def detect_vendor(headers, body):
    for name, test in VENDORS:
        if test(headers, body):
            return name
    return "none"


def classify(status, headers, body, text=None, truncated=False):
    """Return (vendor, outcome, rendering).

    Outcome records what the origin *did*, never whether we failed — a 403 alone is
    not a tier, and a marker alone is not a gate. wiley.com and onetrust.com embed a
    Turnstile widget and serve real content; labelling them by marker presence put
    open sites in the hardest tier and inverted the whole benchmark.
    """
    if text is None:
        text = visible_text(body)
    vendor = detect_vendor(headers, body)
    if status == 0:
        return vendor, "unreachable", None

    gated = ("cf-mitigated" in headers
             or any(m in body for m in CHALLENGE_MARKERS)
             or any(m in body for m in INTERACTIVE_MARKERS))

    # A 200 with no gate marker is a served page at any size — example.com is 142
    # characters of complete content. It takes a marker *and* a body too thin to be
    # a page before this counts as a gate.
    if status == 200 and (not gated or len(text) >= SERVED_MIN_TEXT):
        # A body that hit the read cap was not seen whole, so its text/markup ratio
        # says nothing about rendering. Report it as unknown rather than guess — the
        # rendering-defined strata exclude it instead of taking a coin flip.
        if truncated:
            return vendor, "served", "unknown"
        rendering = ("spa" if (len(text) < SPA_MAX_TEXT and len(body) > SPA_MIN_HTML)
                     else "ssr")
        return vendor, "served", rendering

    if any(m in body for m in INTERACTIVE_MARKERS):
        return vendor, "interactive", None
    if "cf-mitigated" in headers or any(m in body for m in CHALLENGE_MARKERS):
        return vendor, "challenge", None
    if status in (401, 403, 406, 429, 451, 503):
        return vendor, "denied", None
    return vendor, "unreachable", None


def stratum_of(vendor, outcome, rendering):
    if outcome == "served":
        if rendering == "unknown":
            # Rendering could not be judged, so the rendering-defined strata cannot
            # take it. edge-served is rendering-agnostic and still can.
            return None if vendor == "none" else "edge-served"
        if vendor == "none":
            return "unprotected-spa" if rendering == "spa" else "unprotected-ssr"
        return "edge-served"
    return outcome if outcome in ("denied", "challenge", "interactive") else None


def ground_truth(status, body, outcome, text=None):
    """Assertions the harness scores against.

    {reject_markers} is the load-bearing half: a Cloudflare interstitial is valid HTML
    that extracts to clean markdown, so without it the harness scores "checking your
    browser" as a success.

    {min_chars} is derived from observed visible text — a blanket 600 scored
    example.com, 198 extracted characters and the canonical known-one, as blocked.
    But it is only derived when the origin actually served us: for a gated entry the
    observation IS the gate, so deriving a floor from it lets a 75-character gate page
    pass (outschool.com did exactly that). Those get a fixed bar instead.
    """
    if text is None:
        text = visible_text(body)
    if outcome == "served":
        floor = max(50, min(SERVED_FLOOR_MAX, len(text) // 4))
    else:
        # For a gated entry the observation IS the gate, so no floor can be derived
        # from it. A fixed bar above what an interstitial extracts to instead.
        floor = GATED_FLOOR
    gt = {"min_chars": floor,
          "observed_text": len(text),
          "observed_html": len(body),
          "reject_markers": list(CHALLENGE_MARKERS + INTERACTIVE_MARKERS)}
    if outcome == "served" and status == 200:
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
    # own-infrastructure and carries almost no third-party edge.
    bands, lo = [], 0
    for hi in (1_000, 10_000, 100_000, len(ranked)):
        bands.append(list(enumerate(ranked[lo:hi], start=lo + 1)))
        lo = hi
    rng = random.Random(seed)
    per_band = sample_n // len(bands)
    picked = []
    for b in bands:
        picked += rng.sample(b, min(per_band, len(b)))
    rng.shuffle(picked)
    return list_id, picked


def select(pool, want):
    """Best-ranked first, round-robin across vendors.

    Rank order because long-tail domains churn and a corpus that rots between gate
    runs stops being comparable. Round-robin because a single vendor would otherwise
    fill a blocked stratum — CloudFront is 25x more common than DataDome — and the
    per-vendor breakdown would have nothing to say about the rare ones.
    """
    by_vendor = {}
    for r in sorted(pool, key=lambda r: r["rank"]):
        by_vendor.setdefault(r["vendor"], []).append(r)
    out, queues = [], list(by_vendor.values())
    while len(out) < want and any(queues):
        for q in queues:
            if q and len(out) < want:
                out.append(q.pop(0))
        queues = [q for q in queues if q]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sample", type=int, default=40_000)
    ap.add_argument("--per-stratum", type=int, default=25)
    ap.add_argument("--workers", type=int, default=64)
    ap.add_argument("--seed", type=int, default=1081)
    ap.add_argument("--out", default=HERE)
    ap.add_argument("--recompute", action="store_true",
                    help="rewrite ground-truth floors from stored observations, no re-probe")
    args = ap.parse_args()

    if args.recompute:
        path = os.path.join(args.out, "corpus.json")
        doc = json.load(open(path))
        for e in doc["entries"]:
            gt = e["ground_truth"]
            gt["min_chars"] = (max(50, min(SERVED_FLOOR_MAX, gt["observed_text"] // 4))
                               if e["outcome"] == "served" else GATED_FLOOR)
        json.dump(doc, open(path, "w"), indent=2)
        print("==> recomputed floors for %d entries" % len(doc["entries"]), file=sys.stderr)
        return

    list_id, domains = tranco_domains(args.sample, args.seed)
    print("==> Tranco %s, probing %d domains with %d workers"
          % (list_id, len(domains), args.workers), file=sys.stderr)

    results, done, t0 = [], 0, time.time()
    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        futs = {ex.submit(probe, d): (rank, d) for rank, d in domains}
        for f in as_completed(futs):
            rank, d = futs[f]
            url, status, headers, body, truncated = f.result()
            text = visible_text(body)
            vendor, outcome, rendering = classify(status, headers, body, text, truncated)
            results.append({
                "domain": d, "url": url, "rank": rank, "status": status,
                "vendor": vendor, "outcome": outcome, "rendering": rendering,
                "stratum": stratum_of(vendor, outcome, rendering),
                "ground_truth": ground_truth(status, body, outcome, text),
            })
            done += 1
            if done % 1000 == 0:
                print("    %d/%d (%.0fs)" % (done, len(domains), time.time() - t0),
                      file=sys.stderr)

    outcomes, vendors, pairs = {}, {}, {}
    for r in results:
        outcomes[r["outcome"]] = outcomes.get(r["outcome"], 0) + 1
        vendors[r["vendor"]] = vendors.get(r["vendor"], 0) + 1
        key = "%s/%s" % (r["vendor"], r["outcome"])
        pairs[key] = pairs.get(key, 0) + 1

    corpus = []
    for stratum in STRATA:
        pool = [r for r in results if r["stratum"] == stratum]
        take = select(pool, args.per_stratum)
        if len(take) < args.per_stratum:
            print("!!  stratum %-18s only %d/%d — widen --sample"
                  % (stratum, len(take), args.per_stratum), file=sys.stderr)
        for r in take:
            corpus.append({k: r[k] for k in
                           ("url", "stratum", "vendor", "outcome", "rendering",
                            "rank", "ground_truth")})

    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    json.dump({
        "tranco_list_id": list_id, "probed_on": stamp, "sample_size": len(domains),
        "seed": args.seed, "allocation": "equal", "per_stratum": args.per_stratum,
        "strata": STRATA, "entries": corpus,
    }, open(os.path.join(args.out, "corpus.json"), "w"), indent=2)

    json.dump({
        "tranco_list_id": list_id, "probed_on": stamp, "sample_size": len(domains),
        "by_outcome": outcomes, "by_vendor": vendors, "by_vendor_outcome": pairs,
    }, open(os.path.join(args.out, "prevalence.json"), "w"), indent=2)

    print("\n==> probed %d in %.0fs" % (len(results), time.time() - t0), file=sys.stderr)
    print("    outcomes:", dict(sorted(outcomes.items(), key=lambda kv: -kv[1])), file=sys.stderr)
    print("    vendors :", dict(sorted(vendors.items(), key=lambda kv: -kv[1])), file=sys.stderr)
    got = {}
    for e in corpus:
        got[e["stratum"]] = got.get(e["stratum"], 0) + 1
    print("==> corpus: %d entries %s -> corpus.json" % (len(corpus), got), file=sys.stderr)


if __name__ == "__main__":
    main()
