#!/usr/bin/env python3
"""Stealth rendering sidecar for jclaw — escalation rung 3 (JCLAW-1088).

Two failure modes need a real browser, and they are independent:

  THIN_CONTENT  a client-rendered page, at any protection tier including none
  JS_CHALLENGE  a browser-fingerprint gate that must execute its own JavaScript

Patchright drives the browser here rather than the JVM driving it over CDP.
That is not a preference. Patchright's patches are DRIVER-side — it avoids
issuing Runtime.enable (using isolated ExecutionContexts instead) and disables
Console.enable — so attaching a stock Playwright client over connectOverCDP
re-introduces exactly the leaks the patches remove. To get the stealth,
Patchright has to launch AND drive.

Protocol (bound to 127.0.0.1 only):
  GET  /health   -> 200 {status, model, patchright, browser_ready}
  GET  /capability -> 200 {kind, runnable, reason}
  (CLI) --probe  -> the same capability JSON on stdout, one-shot, no browser
  POST /render {url, pins?, language?, timeoutMs?, settleMs?, waitUntil?}
        -> 200  rendered HTML; X-Upstream-Status / X-Upstream-Url /
                X-Blocked-Hosts carry the outcome
        -> 400  {error}  malformed request
        -> 502  {error}  navigation failed
  POST /shutdown -> exits, so a restarted JVM can evict an orphan.

SSRF containment mirrors what PlaywrightBrowserTool does in-JVM (JCLAW-731),
because moving the launch out of the JVM moves the pinning with it:

  1. --host-resolver-rules MAP clauses, supplied by the JVM from its own
     SsrfGuard resolution, pin the entry host to the address the guard actually
     validated. That closes the DNS-rebinding window between the guard's
     lookup and the browser's.
  2. Route interceptors re-check every request the page makes — redirects and
     subresources included, which the launch pin alone does not cover — and
     abort any host that resolves to a non-public address. Registered on the
     CONTEXT and paired with a WebSocket interceptor: page-level routing does not
     cover popups, service workers or ws:// at all, and a page that could open a
     socket to loopback could read it and write the reply into the DOM we return.

Both interceptors fail CLOSED. A URL whose host cannot be parsed is aborted rather
than allowed, and a scheme that is not http/https/data/blob/about is aborted too.

Guard (2) is a SECOND implementation of the JVM's IP-range check, which is a real
duplication and is treated as one: it lives in ssrf.py, stdlib-only, and
StealthBrowserTest runs that file against the same address table the Java guard is
fed. The asserted invariant is that this side is never MORE PERMISSIVE than the
JVM's — it may be stricter. Exact parity was the earlier claim and it was not true:
ipaddress admitted fec0::/10, which Java's isSiteLocalAddress() rejects.
"""

import argparse
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

DEFAULT_TIMEOUT_MS = 35_000
# Cloudflare's interstitial resolves itself a few seconds after load. Waiting for
# "networkidle" instead hangs on exactly those pages — a challenge that keeps
# polling never goes idle — so the sidecar waits for domcontentloaded and then
# settles for a fixed interval.
DEFAULT_SETTLE_MS = 4_000
DEFAULT_WAIT_UNTIL = "domcontentloaded"
# Rungs 1 and 2 both state a language; a render that did not silently answered a
# multilingual site in English while the same crawl asked for something else.
DEFAULT_LANGUAGE = "en"
DEFAULT_IDENTITY = "patchright-chromium"
# Each permit is a live headless Chromium, so this bounds memory rather than
# correctness. LocalSidecarDaemon passes a fixed argv and has no slot for this, so
# the default is what the JVM gets; --max-concurrent exists for standalone runs.
DEFAULT_MAX_CONCURRENT = 4

# Chromium's headless builds put "HeadlessChrome" in the User-Agent. Patchright
# removes the CDP artifacts a JS challenge inspects, but the UA is plain text that
# any WAF reads first — measured as 52 TRUST_BLOCKs on a corpus run, including
# origins the cheaper impersonation rung fetched without trouble. The default UA is
# read once from the browser itself and the token corrected, so the platform and
# version stay exactly what this build really is.
#
# Sec-CH-UA still names HeadlessChrome and is NOT fixable from the route interceptor:
# rewriting it in continue_(headers=...) is accepted and then discarded, because
# Chromium regenerates browser-managed client hints after interception. Verified by
# sending an x-probe header through the same call — the probe arrives, the brand list
# does not change. Fixing it needs Emulation.setUserAgentOverride with
# userAgentMetadata; deferred until measurement shows the brand list is what is
# actually costing access.
from ssrf import is_public_host, is_public_ip

try:
    from patchright.sync_api import sync_playwright
    _IMPORT_ERROR = None
except Exception as exc:  # pragma: no cover - exercised only on a broken install
    sync_playwright = None
    _IMPORT_ERROR = "%s: %s" % (type(exc).__name__, exc)

_UA_LOCK = threading.Lock()
_UA_OVERRIDE = None

# Launch the FULL Chromium, never the headless shell. Recent Playwright defaults
# headless=True to chromium-headless-shell, a stripped build, and that — not
# Chromium-versus-Chrome — was what made rung 3 look automated. Measured against a
# headful Chrome on the same probe, the headless shell differs on eight signals that
# the full build matches exactly: navigator.plugins (0 vs 5), mimeTypes (0 vs 2),
# window.chrome (undefined vs an object with loadTimes), WebGL (SwiftShader vs the
# real GPU), languages, Notification.permission, pdfViewerEnabled.
#
# The full build ships WITH Patchright, so this needs no system browser and behaves
# identically on a headless Linux server. Proprietary codecs (H.264, AAC, MP3) and
# Widevine are present in it too, checked rather than assumed.
_CHANNEL = "chromium"

# One signal the full Chromium still does not match: userAgentData.brands says
# "Chromium" where Chrome says "Google Chrome", and Sec-CH-UA is generated from it.
# Emulation.setUserAgentOverride fixes the header, the JS API and the UA string in one
# call. Every field except the brand list is read back from the browser itself, so a
# Linux host reports Linux rather than whatever the developer's machine was.
#
# The read-back has to happen on a SECURE origin: navigator.userAgentData does not
# exist on about:blank, and probing there silently yielded an empty platform, which
# the override then pinned as empty — worse than not overriding at all. The probe page
# is served locally through a fulfilled route, so it is a real https origin with no
# network request.
_UA_PROBE = """async () => {
  const d = navigator.userAgentData;
  const hi = d ? await d.getHighEntropyValues(
      ['architecture', 'bitness', 'model', 'platformVersion']) : {};
  return {ua: navigator.userAgent, platform: d ? d.platform : '',
          mobile: d ? d.mobile : false, ...hi};
}"""


def _header_safe(value):
    """Origin-supplied text, made safe to put in an HTTP header value. send_header
    encodes latin-1 and raises on anything else, taking the whole response with it."""
    return str(value).encode("ascii", "backslashreplace").decode("ascii")


class SidecarState:
    def __init__(self, identity, idle_timeout_s, max_concurrent):
        self.identity = identity
        self.idle_timeout_s = idle_timeout_s
        self.last_used = time.time()
        # A browser is launched per render because the DNS pin is a launch argument
        # and cannot be varied on a shared instance. Patchright's sync API is not
        # thread-safe across threads, but one sync_playwright() context per thread is
        # fine (verified: three concurrent renders complete in ~1.2s), so renders run
        # in parallel behind a bound rather than a mutex — serializing them would make
        # a 150-page corpus run take twenty minutes. The bound is memory, not safety:
        # each permit is a live headless Chromium.
        self.render_slots = threading.Semaphore(max_concurrent)

    def touch(self):
        self.last_used = time.time()


def capability():
    if sync_playwright is None:
        return {"kind": "render", "runnable": False,
                "reason": "patchright unavailable (%s)" % _IMPORT_ERROR}
    return {"kind": "render", "runnable": True, "reason": ""}


class Handler(BaseHTTPRequestHandler):
    state: SidecarState = None  # injected in main()

    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[stealth-sidecar] %s\n" % (fmt % args))

    def _send_json(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        return json.loads(raw.decode("utf-8"))

    def handle(self):
        try:
            super().handle()
        except BrokenPipeError:
            pass

    def do_GET(self):
        if self.path == "/health":
            self.state.touch()
            self._send_json(200, {
                "status": "ok",
                "model": self.state.identity,
                "patchright": sync_playwright is not None,
                "channel": _CHANNEL,
                "browser_ready": capability()["runnable"],
            })
        elif self.path == "/capability":
            self._send_json(200, capability())
        else:
            self._send_json(404, {"error": "unknown path %s" % self.path})

    def do_POST(self):
        if self.path == "/shutdown":
            sys.stderr.write("[stealth-sidecar] shutdown requested — exiting\n")
            self._send_json(200, {"status": "bye"})
            threading.Thread(target=lambda: (time.sleep(0.2), os._exit(0)), daemon=True).start()
            return
        if self.path == "/render":
            self._handle_render()
            return
        self._send_json(404, {"error": "unknown path %s" % self.path})

    def _handle_render(self):
        self.state.touch()
        if sync_playwright is None:
            self._send_json(502, {"error": "patchright unavailable (%s)" % _IMPORT_ERROR})
            return
        try:
            req = self._read_json()
        except Exception as exc:
            self._send_json(400, {"error": "malformed request: %s" % exc})
            return
        url = req.get("url")
        if not url:
            self._send_json(400, {"error": "url is required"})
            return

        pins = req.get("pins") or {}
        # A pin exempts its host from the route gate, so an unvalidated one is a way
        # around the gate rather than an input to it. The JVM only ever pins an address
        # SsrfGuard approved; this endpoint is unauthenticated, so it re-checks rather
        # than trusting the caller to have been that JVM.
        for pinned_host, pinned_ip in pins.items():
            if not is_public_ip(pinned_ip):
                self._send_json(400, {
                    "error": "pin for %s is not a public address" % pinned_host})
                return
        timeout_ms = int(req.get("timeoutMs") or DEFAULT_TIMEOUT_MS)
        settle_ms = int(req.get("settleMs") or DEFAULT_SETTLE_MS)
        wait_until = req.get("waitUntil") or DEFAULT_WAIT_UNTIL
        language = req.get("language") or DEFAULT_LANGUAGE

        with self.state.render_slots:
            try:
                html, status, final_url, blocked = self._render(
                    url, pins, timeout_ms, settle_ms, wait_until, language)
            except Exception as exc:
                self._send_json(502, {"error": "%s: %s" % (type(exc).__name__, exc)})
                return

        body = html.encode("utf-8", "replace")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("X-Upstream-Status", str(status))
        # Both values are origin-influenced — final_url comes from the page, and the
        # blocked set from URLs it chose to request — and send_header raises on
        # anything outside latin-1, which would drop the whole response.
        self.send_header("X-Upstream-Url", _header_safe(final_url))
        if blocked:
            self.send_header("X-Blocked-Hosts",
                             _header_safe(",".join(sorted(blocked)[:20])))
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    @staticmethod
    def _ua_override(context):
        """Chrome-shaped UA metadata built from this browser's own values, cached."""
        global _UA_OVERRIDE
        with _UA_LOCK:
            if _UA_OVERRIDE is None:
                page = context.new_page()
                try:
                    page.route("**/*", lambda r: r.fulfill(
                        status=200, content_type="text/html", body="<html></html>"))
                    page.goto("https://ua-probe.jclaw.invalid/",
                              wait_until="domcontentloaded", timeout=15000)
                    info = page.evaluate(_UA_PROBE)
                finally:
                    page.close()
                ua = info["ua"].replace("HeadlessChrome/", "Chrome/")
                full = ua.split("Chrome/")[1].split(" ")[0]
                major = full.split(".")[0]
                _UA_OVERRIDE = {
                    "userAgent": ua,
                    "userAgentMetadata": {
                        "brands": [{"brand": "Not=A?Brand", "version": "99"},
                                   {"brand": "Google Chrome", "version": major},
                                   {"brand": "Chromium", "version": major}],
                        "fullVersion": full,
                        "platform": info.get("platform") or "",
                        "platformVersion": info.get("platformVersion") or "",
                        "architecture": info.get("architecture") or "",
                        "bitness": info.get("bitness") or "",
                        "model": info.get("model") or "",
                        "mobile": bool(info.get("mobile")),
                    },
                }
            return _UA_OVERRIDE

    def _render(self, url, pins, timeout_ms, settle_ms, wait_until, language):
        args = []
        if pins:
            clauses = ["MAP %s %s" % (h, ip) for h, ip in pins.items()]
            args.append("--host-resolver-rules=" + ",".join(clauses))

        blocked = set()
        decided = {}

        def gate(route):
            # Pinned hosts are already guard-validated; anything else the page
            # reaches for gets resolved and range-checked before it is allowed.
            parts = urlsplit(route.request.url)
            scheme = parts.scheme.lower()
            if scheme in ("http", "https"):
                # urlsplit, not string slicing: the hand-rolled split produced "[2606"
                # for an IPv6 literal and "" for anything it could not parse, and an
                # empty host then skipped the check entirely.
                host = parts.hostname
                if not host:
                    blocked.add(route.request.url[:80])
                    route.abort()
                    return
                if host not in pins:
                    allowed = decided.get(host)
                    if allowed is None:
                        allowed = is_public_host(host)
                        decided[host] = allowed
                    if not allowed:
                        blocked.add(host)
                        route.abort()
                        return
            elif scheme not in ("data", "blob", "about"):
                # data/blob/about reach no network and a page legitimately uses them.
                # Everything else -- file:, ftp:, chrome-extension: -- has no business
                # being fetched by a rendered page, and defaulting them to "allow" is
                # the wrong way round for a security gate.
                blocked.add(scheme + ":")
                route.abort()
                return
            route.continue_()

        def ws_gate(ws):
            # page.route never sees WebSocket traffic -- it is a separate API -- so
            # until this existed a page could open ws://127.0.0.1, read a loopback
            # service and write the reply into the DOM we hand back.
            host = urlsplit(ws.url).hostname
            if not host or (host not in pins and not is_public_host(host)):
                blocked.add(host or ws.url[:80])
                return
            ws.connect_to_server()

        with sync_playwright() as p:
            try:
                browser = p.chromium.launch(headless=True, channel=_CHANNEL, args=args)
            except Exception as exc:
                # Only reachable when the full Chromium build was never downloaded;
                # the headless shell still renders, just more visibly automated.
                sys.stderr.write("[stealth-sidecar] full Chromium unavailable (%s) — "
                                 "falling back to the headless shell; run "
                                 "'patchright install chromium'\n" % type(exc).__name__)
                browser = p.chromium.launch(headless=True, args=args)
            try:
                # Routed on the CONTEXT, not the page: a popup the page opens is a
                # separate Page with no page-level handler, and service workers issue
                # requests the page handler never sees at all.
                # Carries the caller's language the way rungs 1 and 2 do. locale
                # sets navigator.language too, so a page branching in JavaScript sees
                # the same preference the header states.
                context = browser.new_context(service_workers="block",
                                              locale=language,
                                              extra_http_headers={
                                                  "Accept-Language": language + ", *;q=0.5"})
                context.route("**/*", gate)
                context.route_web_socket("**/*", ws_gate)
                page = context.new_page()
                context.new_cdp_session(page).send(
                    "Emulation.setUserAgentOverride", self._ua_override(context))
                response = page.goto(url, wait_until=wait_until, timeout=timeout_ms)
                if settle_ms > 0:
                    page.wait_for_timeout(settle_ms)
                return (page.content(),
                        response.status if response else 0,
                        page.url,
                        blocked)
            finally:
                browser.close()


def _idle_watcher(state):
    if state.idle_timeout_s <= 0:
        return
    while True:
        time.sleep(30)
        if time.time() - state.last_used > state.idle_timeout_s:
            sys.stderr.write("[stealth-sidecar] idle — exiting\n")
            os._exit(0)


def main():
    ap = argparse.ArgumentParser(description="jclaw stealth rendering sidecar")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int)
    ap.add_argument("--model", default=DEFAULT_IDENTITY)
    ap.add_argument("--cache-dir", default=os.path.join("data", "stealth-sidecar"))
    ap.add_argument("--idle-timeout-min", type=float, default=15.0)
    ap.add_argument("--max-concurrent", type=int, default=DEFAULT_MAX_CONCURRENT,
                    help="live headless browsers allowed at once")
    ap.add_argument("--probe", action="store_true",
                    help="print capability JSON and exit without launching a browser")
    args = ap.parse_args()

    if args.probe:
        print(json.dumps(capability()))
        return
    if args.port is None:
        ap.error("--port is required unless --probe is given")

    os.makedirs(os.path.abspath(args.cache_dir), exist_ok=True)
    Handler.state = SidecarState(args.model, args.idle_timeout_min * 60.0, args.max_concurrent)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    threading.Thread(target=_idle_watcher, args=(Handler.state,), daemon=True).start()
    sys.stderr.write("[stealth-sidecar] listening on http://%s:%d\n" % (args.host, args.port))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
