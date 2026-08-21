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
  POST /render {url, pins?, timeoutMs?, settleMs?, waitUntil?}
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
  2. A route interceptor re-checks every request the page makes — redirects and
     subresources included, which the launch pin alone does not cover — and
     aborts any host that resolves to a non-public address.

Guard (2) is a SECOND implementation of the JVM's IP-range check, which is a real
duplication and is treated as one: it lives in ssrf.py, stdlib-only, and
StealthBrowserTest runs that file against the same address table the Java guard is
fed so the two cannot drift apart silently.
"""

import argparse
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DEFAULT_TIMEOUT_MS = 35_000
# Cloudflare's interstitial resolves itself a few seconds after load. Waiting for
# "networkidle" instead hangs on exactly those pages — a challenge that keeps
# polling never goes idle — so the sidecar waits for domcontentloaded and then
# settles for a fixed interval.
DEFAULT_SETTLE_MS = 4_000
DEFAULT_WAIT_UNTIL = "domcontentloaded"
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
_UA_LOCK = threading.Lock()
_REAL_UA = None

from ssrf import is_public_host

try:
    from patchright.sync_api import sync_playwright
    _IMPORT_ERROR = None
except Exception as exc:  # pragma: no cover - exercised only on a broken install
    sync_playwright = None
    _IMPORT_ERROR = "%s: %s" % (type(exc).__name__, exc)


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
        timeout_ms = int(req.get("timeoutMs") or DEFAULT_TIMEOUT_MS)
        settle_ms = int(req.get("settleMs") or DEFAULT_SETTLE_MS)
        wait_until = req.get("waitUntil") or DEFAULT_WAIT_UNTIL

        with self.state.render_slots:
            try:
                html, status, final_url, blocked = self._render(
                    url, pins, timeout_ms, settle_ms, wait_until)
            except Exception as exc:
                self._send_json(502, {"error": "%s: %s" % (type(exc).__name__, exc)})
                return

        body = html.encode("utf-8", "replace")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("X-Upstream-Status", str(status))
        self.send_header("X-Upstream-Url", final_url)
        if blocked:
            self.send_header("X-Blocked-Hosts", ",".join(sorted(blocked)[:20]))
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    @staticmethod
    def _headful_user_agent(browser):
        """The browser's own UA with the headless token corrected, cached per process."""
        global _REAL_UA
        with _UA_LOCK:
            if _REAL_UA is None:
                probe = browser.new_context()
                try:
                    ua = probe.new_page().evaluate("navigator.userAgent")
                finally:
                    probe.close()
                _REAL_UA = ua.replace("HeadlessChrome/", "Chrome/")
            return _REAL_UA

    def _render(self, url, pins, timeout_ms, settle_ms, wait_until):
        args = []
        if pins:
            clauses = ["MAP %s %s" % (h, ip) for h, ip in pins.items()]
            args.append("--host-resolver-rules=" + ",".join(clauses))

        blocked = set()
        decided = {}

        def gate(route):
            # Pinned hosts are already guard-validated; anything else the page
            # reaches for gets resolved and range-checked before it is allowed.
            host = ""
            try:
                host = route.request.url.split("//", 1)[1].split("/", 1)[0].split(":")[0]
            except IndexError:
                pass
            if host and host not in pins:
                allowed = decided.get(host)
                if allowed is None:
                    allowed = is_public_host(host)
                    decided[host] = allowed
                if not allowed:
                    blocked.add(host)
                    route.abort()
                    return
            route.continue_()

        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True, args=args)
            try:
                context = browser.new_context(user_agent=self._headful_user_agent(browser))
                page = context.new_page()
                page.route("**/*", gate)
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
