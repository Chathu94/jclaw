#!/usr/bin/env python3
"""TLS-impersonating fetch sidecar for jclaw — escalation rung 2 (JCLAW-1087).

Cloudflare and its peers fingerprint the TLS ClientHello (JA3/JA4) and the
HTTP/2 SETTINGS frame. Both are emitted before our request is sent, so no
combination of headers rescues a fetch that JSSE has already given away.
curl_cffi binds curl-impersonate, which reproduces a real Chrome handshake
byte for byte.

This runs as a separate process rather than in the JVM because the one Java
library that impersonates in-process (impersonator-okhttp) ships its own copy
of 309 okhttp3/ classes, OkHttpClient included, and would collide with the
okhttp-jvm 5.4.0 the rest of jclaw runs on.

Protocol (bound to 127.0.0.1 only):
  GET  /health   -> 200 {status, model, curl_cffi, profile_supported}
                    "model" is the impersonation profile, so the JVM's
                    isHealthy(expectedModel) respawns when an operator repins it.
  GET  /capability-> 200 {kind, runnable, profile, reason}
  (CLI) --probe  -> the same capability JSON on stdout, one-shot, no server
  POST /fetch {url, pins?, headers?, profile?, timeoutMs?, maxBytes?}
        -> 200  upstream body verbatim; upstream status and Location ride in
                X-Upstream-* headers (see below)
        -> 400  {error}  malformed request
        -> 502  {error}  transport failure reaching the origin
  POST /shutdown -> 200 {status} then exit, so a restarted JVM can evict an
                    orphan it has no Process handle for.

`pins` maps a hostname to the address SsrfGuard already resolved and approved,
and becomes a CURLOPT_RESOLVE entry so curl never looks the name up itself. Without
it the JVM validated a hostname and then handed the hostname over to be resolved a
second time, which is a rebinding window the render sidecar closes at launch. Pin
targets are re-checked here (ssrf.py, shared with the render sidecar and pinned
against the JVM guard by StealthBrowserTest) because this endpoint is
unauthenticated and a pin decides where curl connects.

Note what this sidecar still does NOT do: it performs no SSRF check on the request
URL itself. The JVM is authoritative for that, and stays so on every redirect hop
because of the next paragraph.

REDIRECTS ARE NOT FOLLOWED, and that is load-bearing. curl_cffi follows them by
default, and its impersonation actively disguises that a hop happened — which
would hand a remote origin the ability to bounce a crawl onto a private address
without SsrfGuard ever seeing the hop. The 3xx is handed back to the JVM
instead, which re-validates and re-calls. This is the one place where the
stealth lane and SSRF containment genuinely conflict, and containment wins.

Upstream status travels in a header rather than as this server's HTTP status so
a 403 from the origin stays distinguishable from a 403 the sidecar itself
raised; a 200 here means only "the exchange completed".
"""

import argparse
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from ssrf import is_public_ip

DEFAULT_PROFILE = "chrome"
# Ceiling on what this process will hold for one response. The JVM applies its
# own, smaller cap on the way out; this one only stops a hostile origin from
# ballooning the sidecar's heap before the JVM gets a chance to.
HARD_MAX_BYTES = 25 * 1024 * 1024
DEFAULT_TIMEOUT_MS = 30_000

try:
    import certifi
    import curl_cffi
    from curl_cffi import requests as curl_requests
    from curl_cffi import Curl, CurlOpt
    _CURL_CFFI_VERSION = getattr(curl_cffi, "__version__", None)
    _CA_BUNDLE = certifi.where()
    _IMPORT_ERROR = None
except Exception as exc:  # pragma: no cover - exercised only on a broken install
    curl_requests = None
    Curl = CurlOpt = None
    _CURL_CFFI_VERSION = None
    _CA_BUNDLE = None
    _IMPORT_ERROR = "%s: %s" % (type(exc).__name__, exc)


def _default_port(url):
    """The port curl will connect on, which a RESOLVE entry has to name exactly."""
    parts = urlsplit(url)
    if parts.port:
        return parts.port
    return 80 if parts.scheme.lower() == "http" else 443


def _header_safe(value):
    """Origin-supplied text, made safe to put in an HTTP header value.

    send_header encodes latin-1 and raises on anything else, and a Location or
    Content-Type carrying raw UTF-8 is common in the wild — it killed the whole
    response rather than the one header.
    """
    return str(value).encode("ascii", "backslashreplace").decode("ascii")


def _supported_profiles():
    """The impersonation targets this curl_cffi build accepts, or () if unknown."""
    if curl_requests is None:
        return ()
    try:
        import typing
        from curl_cffi.requests.impersonate import BrowserTypeLiteral
        return typing.get_args(BrowserTypeLiteral)
    except Exception:
        return ()


def capability(profile):
    profiles = _supported_profiles()
    if curl_requests is None:
        return {"kind": "fetch", "runnable": False, "profile": profile,
                "reason": "curl_cffi unavailable (%s)" % _IMPORT_ERROR}
    # An unknown name is reported rather than rejected: curl_cffi accepts rolling
    # aliases ("chrome") that are absent from the literal list on some builds.
    known = (not profiles) or (profile in profiles)
    return {"kind": "fetch", "runnable": True, "profile": profile,
            "profileKnown": known,
            "profileCount": len(profiles),
            "reason": "" if known else "profile %r not in this build's list" % profile}


class SidecarState:
    def __init__(self, profile, idle_timeout_s):
        self.profile = profile
        self.idle_timeout_s = idle_timeout_s
        self.last_used = time.time()

    def touch(self):
        self.last_used = time.time()


class Handler(BaseHTTPRequestHandler):
    state: SidecarState = None  # injected in main()

    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[fetch-sidecar] %s\n" % (fmt % args))

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
            pass  # client went away mid-response — not worth a traceback

    def do_GET(self):
        if self.path == "/health":
            # A health probe signals imminent use — touching the idle clock closes
            # the evict race between the JVM's check and its subsequent request.
            self.state.touch()
            self._send_json(200, {
                "status": "ok",
                "model": self.state.profile,
                "curl_cffi": _CURL_CFFI_VERSION,
                "profile_supported": capability(self.state.profile)["runnable"],
            })
        elif self.path == "/capability":
            self._send_json(200, capability(self.state.profile))
        else:
            self._send_json(404, {"error": "unknown path %s" % self.path})

    def do_POST(self):
        if self.path == "/shutdown":
            sys.stderr.write("[fetch-sidecar] shutdown requested — exiting\n")
            self._send_json(200, {"status": "bye"})
            threading.Thread(target=lambda: (time.sleep(0.2), os._exit(0)), daemon=True).start()
            return
        if self.path == "/fetch":
            self._handle_fetch()
            return
        self._send_json(404, {"error": "unknown path %s" % self.path})

    def _handle_fetch(self):
        self.state.touch()
        if curl_requests is None:
            self._send_json(502, {"error": "curl_cffi unavailable (%s)" % _IMPORT_ERROR})
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
        headers = req.get("headers") or {}
        profile = req.get("profile") or self.state.profile
        timeout_s = max(1.0, float(req.get("timeoutMs") or DEFAULT_TIMEOUT_MS) / 1000.0)
        max_bytes = min(int(req.get("maxBytes") or HARD_MAX_BYTES), HARD_MAX_BYTES)

        pins = req.get("pins") or {}
        # A pin decides where curl connects, so an unvalidated one points this fetcher
        # wherever the caller likes. The JVM only ever pins an address SsrfGuard
        # approved; this endpoint is unauthenticated, so it re-checks rather than
        # trusting the caller to have been that JVM.
        for pinned_host, pinned_ip in pins.items():
            if not is_public_ip(pinned_ip):
                self._send_json(400, {
                    "error": "pin for %s is not a public address" % pinned_host})
                return

        try:
            curl_handle = Curl()
            if pins:
                # CURLOPT_RESOLVE pre-seeds curl's DNS cache, so the name is never
                # looked up again here. Without it the JVM validated a hostname and
                # then handed the hostname over for curl to re-resolve independently —
                # the rebinding window rung 3 closes with its launch pin.
                port = _default_port(url)
                curl_handle.setopt(CurlOpt.RESOLVE,
                                   ["%s:%d:%s" % (h, port, ip) for h, ip in pins.items()])
            resp = curl_requests.Session(curl=curl_handle).get(
                url,
                headers=headers,
                impersonate=profile,
                timeout=timeout_s,
                # certifi, not curl's bundled store: the latter rejects chains the JVM
                # and openssl both accept, which showed up as three corpus entries
                # failing TLS that rung 1 fetched fine. Verification stays on.
                verify=_CA_BUNDLE,
                # Load-bearing: see the module docstring. Every hop goes back to
                # the JVM so SsrfGuard re-validates it.
                allow_redirects=False,
                stream=True,
            )
        except Exception as exc:
            self._send_json(502, {"error": "%s: %s" % (type(exc).__name__, exc)})
            return

        # Everything that can fail happens BEFORE the first byte is written, so a
        # failure here is still answerable as 502. The previous shape claimed headers
        # "may already be on the wire" and swallowed the error; they are not —
        # send_response and send_header only append to _headers_buffer, and nothing
        # flushes until end_headers(). So the caller received no status line at all
        # and blocked to its own 90s timeout, turning a diagnosable transport error
        # into an indistinguishable TIMEOUT.
        try:
            body = bytearray()
            truncated = False
            for chunk in resp.iter_content():
                body.extend(chunk)
                if len(body) > max_bytes:
                    del body[max_bytes:]
                    truncated = True
                    break
            headers = [
                ("Content-Type", "application/octet-stream"),
                ("X-Upstream-Status", str(resp.status_code)),
                ("X-Upstream-Content-Type", _header_safe(resp.headers.get("Content-Type", ""))),
                ("X-Upstream-Url", _header_safe(resp.url or url)),
            ]
            location = resp.headers.get("Location")
            if location:
                headers.append(("X-Upstream-Location", _header_safe(location)))
            if truncated:
                headers.append(("X-Upstream-Truncated", "true"))
            headers.append(("Content-Length", str(len(body))))
        except Exception as exc:
            sys.stderr.write("[fetch-sidecar] body relay failed for %s: %s\n" % (url, exc))
            self._send_json(502, {"error": "%s: %s" % (type(exc).__name__, exc)})
            return
        finally:
            resp.close()

        self.send_response(200)
        for name, value in headers:
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(bytes(body))


def _idle_watcher(state):
    if state.idle_timeout_s <= 0:
        return
    while True:
        time.sleep(30)
        if time.time() - state.last_used > state.idle_timeout_s:
            sys.stderr.write("[fetch-sidecar] idle for %.0fs — exiting\n"
                             % (time.time() - state.last_used))
            os._exit(0)


def main():
    ap = argparse.ArgumentParser(description="jclaw TLS-impersonating fetch sidecar")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int)
    ap.add_argument("--model", default=DEFAULT_PROFILE,
                    help="curl_cffi impersonation profile, e.g. chrome or chrome146")
    ap.add_argument("--cache-dir", default=os.path.join("data", "fetch-sidecar"))
    ap.add_argument("--idle-timeout-min", type=float, default=15.0)
    ap.add_argument("--probe", action="store_true",
                    help="print capability JSON and exit without binding a port")
    args = ap.parse_args()

    if args.probe:
        print(json.dumps(capability(args.model)))
        return
    if args.port is None:
        ap.error("--port is required unless --probe is given")

    # Accepted and created but otherwise unused: the daemon passes --cache-dir to
    # every sidecar, and its orphan reaper matches the running argv on it.
    os.makedirs(os.path.abspath(args.cache_dir), exist_ok=True)

    Handler.state = SidecarState(args.model, args.idle_timeout_min * 60.0)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    threading.Thread(target=_idle_watcher, args=(Handler.state,), daemon=True).start()
    sys.stderr.write("[fetch-sidecar] listening on http://%s:%d (profile=%s)\n"
                     % (args.host, args.port, args.model))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
