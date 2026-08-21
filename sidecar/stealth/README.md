# stealth sidecar

Rendering with anti-detection — rung 3 of the scrape escalation ladder (JCLAW-1088).

## Two jobs, deliberately kept apart

| Failure mode | What it is | Protection tier |
|---|---|---|
| `THIN_CONTENT` | a client-rendered page whose text only exists after JavaScript runs | any, including none |
| `JS_CHALLENGE` / `TURNSTILE` | a fingerprint gate that must execute its own JavaScript | protected only |

Conflating them would credit anti-bot work for a rendering fix, or the reverse. The
harness scores them on separate axes: `byRendering` against the corpus's SSR/SPA label,
`byStratum` against its protection label.

## Why Patchright drives, instead of the JVM attaching over CDP

The story this implements originally called for `chromium.connectOverCDP` from
Playwright Java. That does not work, and the reason is worth recording so nobody
re-proposes it.

Patchright's anti-detection is **driver-side**. It avoids issuing `Runtime.enable`
(using isolated execution contexts instead) and disables `Console.enable` outright —
things only the client sending CDP commands can do, not the browser binary. Attach a
stock Playwright client over CDP and it issues those commands itself, re-introducing
exactly the leaks the patches remove. You get a real browser with no stealth: enough
for `THIN_CONTENT`, useless for `JS_CHALLENGE`.

Camoufox was the other candidate in the story and is structurally impossible here —
it is Firefox-based, Playwright drives Firefox over Juggler, and `connect_over_cdp`
answers *"CDP connections are only supported by Chromium"*.

So the JVM asks this process to **render a URL** and gets HTML back. Patchright
launches and drives; the stealth survives.

## SSRF containment moved with the launch

`--host-resolver-rules` is a launch-time flag. Moving the launch out of the JVM moved
the pinning with it, so the containment is rebuilt here in two layers, mirroring what
`PlaywrightBrowserTool` does in-process (JCLAW-731):

1. **Launch pin.** The JVM resolves and validates the entry host with `SsrfGuard` and
   sends the address it actually approved. The sidecar turns it into a `MAP` clause, so
   the browser connects only where the guard checked — closing the rebinding window
   between our lookup and the browser's. Verified enforced: a `MAP` to `127.0.0.1`
   makes the navigation fail rather than silently resolving normally.
2. **Route gate.** The launch pin covers the entry host only. A page also follows
   redirects and pulls subresources, so every request is intercepted, its host resolved
   and range-checked, and non-public ones aborted. Blocked hosts come back in
   `X-Blocked-Hosts` rather than failing silently.

Layer 2 is a **second implementation of a security check**, which is a real cost. It
lives in `ssrf.py` — stdlib-only, no Patchright import — and `StealthBrowserTest` runs
that exact file against the same address table it feeds `SsrfGuard.isUnsafe`, failing
when the two disagree. (Confirmed to fail on a deliberate mutation, not just pass.)

## Protocol

Bound to `127.0.0.1` only.

| Route | Result |
|---|---|
| `GET /health` | `{status, model, patchright, browser_ready}` |
| `GET /capability` | `{kind, runnable, reason}` |
| `POST /render` | rendered HTML; outcome in `X-Upstream-*` / `X-Blocked-Hosts` |
| `POST /shutdown` | exits, so a restarted JVM can evict an orphan |
| `--probe` (CLI) | capability JSON on stdout, no browser launched |

`POST /render` takes `{url, pins?, timeoutMs?, settleMs?, waitUntil?}`.

`waitUntil` defaults to `domcontentloaded`, **not** `networkidle`. A challenge page
that keeps polling never goes idle, so waiting for it hangs on exactly the pages this
rung exists for. A fixed `settleMs` window afterwards gives the challenge time to
resolve itself.

## Looking like a real browser

**No system browser is required.** Everything below uses the browser Playwright
downloads — Patchright is a Playwright fork and shares the same `ms-playwright` cache —
so a headless Linux server behaves exactly like a developer laptop. That browser is
Google's official **Chrome for Testing** build, not a community Chromium, which is why
proprietary codecs (H.264, AAC, MP3) and Widevine are present in it.

Measured: 106/150 on the corpus against 107/150 for the operator's installed Chrome —
a one-entry difference, inside run-to-run noise. The `channel="chrome"` preference was
removed rather than kept as an optimisation, because a second code path that only some
hosts exercise is a reproducibility problem, not a feature.

The original problem was not Chromium-versus-Chrome. Recent Playwright defaults
`headless=True` to **`chromium-headless-shell`**, a stripped build, and that is what
made rung 3 look automated. Launching the **full** Chromium (`channel="chromium"`,
already downloaded by `patchright install`) closes almost all of it. Measured against a
real headful Chrome on the same probe:

| Signal | headless shell | full Chromium |
|---|---|---|
| `navigator.plugins` / `mimeTypes` | 0 / 0 | **5 / 2** |
| `window.chrome` | `undefined` | **object, with `loadTimes`** |
| WebGL renderer | SwiftShader | **the real GPU** |
| `languages` | `en-US` | **the host's real list** |
| `Notification.permission` | `denied` | **`default`** |
| `pdfViewerEnabled` | false | **true** |

Proprietary codecs (H.264, AAC, MP3) and Widevine are present in the bundled build
too — checked, not assumed.

One signal the full Chromium still gets wrong: `userAgentData.brands` says `Chromium`
where Chrome says `Google Chrome`, and **`Sec-CH-UA` is generated from it**. A single
`Emulation.setUserAgentOverride` fixes the header, the JS API and the User-Agent string
together. Every field but the brand list is read back from the browser itself, so a
Linux host reports Linux rather than whatever the author's machine was.

That read-back must happen on a **secure origin** — `navigator.userAgentData` does not
exist on `about:blank`, and probing there silently yields an empty platform which the
override then pins as empty, worse than not overriding at all. The probe page is served
locally through a fulfilled route: a real `https://` origin with no network request.

Result: **20 of 21 probed signals identical to a real headful Chrome**, and no failing
rows on `bot.sannysoft.com`.

The one that remains is `outerWidth`/`outerHeight`, which equal the viewport because
headless has no window chrome to add. It is not fixable here — Patchright disables
`add_init_script` (verified: a marker set in one is absent from the page, at both
context and page level), which is the only hook running early enough for a detector
reading the value during load. It is also a weak tell, since a real browser in
fullscreen or kiosk mode reports the same.

**This is not indistinguishability, and should not be described as such.** Every
*static* fingerprint matches. What remains distinguishable is behaviour: a page is
loaded, settles, and is read — no mouse movement, no scrolling, no dwell time. A
detector scoring behaviour rather than fingerprints can still tell, and a render-only
rung structurally cannot produce those signals.

## Concurrency

A browser is launched per render, because the DNS pin is a launch argument and cannot
be varied on a shared instance. Renders run in parallel behind `--max-concurrent`
(default 4) rather than a mutex — serializing them turns a 150-page corpus run into a
twenty-minute one. The bound is memory, not safety: each permit is a live headless
Chromium. One `sync_playwright()` context per thread is safe; sharing one across
threads is not.

## Running it standalone

```bash
uv run serve.py --probe
uv run serve.py --port 9532 --max-concurrent 4
```
