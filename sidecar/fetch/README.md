# fetch sidecar

TLS-impersonating fetch for jclaw — rung 2 of the scrape escalation ladder (JCLAW-1087).

## Why a separate process

Cloudflare and its peers fingerprint the TLS ClientHello (JA3/JA4) and the HTTP/2
SETTINGS frame. Both go out *before* our request does, so no header combination
rescues a fetch that JSSE has already given away. [`curl_cffi`](https://github.com/lexiforest/curl_cffi)
binds curl-impersonate, which reproduces a real Chrome handshake byte for byte.

It is a process rather than a library because the one Java equivalent
(`impersonator-okhttp`) ships its own copy of 309 `okhttp3/` classes —
`OkHttpClient` included — and would collide with the `okhttp-jvm` the rest of
jclaw runs on.

## Redirects are not followed, deliberately

`curl_cffi` follows redirects by default and its impersonation actively disguises
that the hop happened. Left on, a remote origin could bounce a crawl onto a
private address with `SsrfGuard` never seeing the hop. The sidecar returns the
3xx and its `Location` to the JVM, which re-validates and re-calls.

This is the one place where the stealth lane and SSRF containment genuinely
conflict. Containment wins.

## Impersonation profiles

`--model` selects the profile (the daemon reuses its `--model` slot for it, so
repinning it in config forces a respawn). Defaults to `chrome`, the rolling alias
that tracks the newest Chrome this build knows; pin an exact one (`chrome146`)
when you need reproducibility across machines. `--probe` prints the profile list
size and whether the requested name is known.

**A static JA3 is not a concern here.** Chrome ≥110 permutes its TLS extension
order per connection, and `curl_cffi` does the same — three consecutive requests
on one profile produced three different JA3 hashes and one unchanging JA4
(measured against `tls.browserleaks.com`, curl_cffi 0.16.0, profile `chrome`).
JA4 sorts extensions before hashing, which is exactly why it replaced JA3; the
stable identity we present is the JA4, and it matches Chrome's.

## Protocol

Bound to `127.0.0.1` only.

| Route | Result |
|---|---|
| `GET /health` | `{status, model, curl_cffi, profile_supported}` — `model` is the profile |
| `GET /capability` | `{kind, runnable, profile, profileKnown, profileCount, reason}` |
| `POST /fetch` | upstream body verbatim; see below |
| `POST /shutdown` | exits, so a restarted JVM can evict an orphan |
| `--probe` (CLI) | the capability JSON on stdout, no server, no port |

`POST /fetch` takes `{url, headers?, profile?, timeoutMs?, maxBytes?}` and answers
`200` when the exchange completed — **not** when the origin was happy. The
origin's own result rides in headers, so a 403 from the origin stays
distinguishable from a 403 raised by the sidecar itself:

- `X-Upstream-Status` — the origin's status code
- `X-Upstream-Location` — present only on a 3xx
- `X-Upstream-Content-Type` — the origin's content type
- `X-Upstream-Url` — the URL actually requested
- `X-Upstream-Truncated` — `true` when the body hit `maxBytes`

`400` means a malformed request, `502` a transport failure reaching the origin.

## Running it standalone

```bash
uv run serve.py --probe
uv run serve.py --port 9531 --model chrome
```

The JVM launches it through `LocalSidecarDaemon` and never needs these directly.
