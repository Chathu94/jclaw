"""IP-range half of the SSRF check, mirrored from the JVM's SsrfGuard (JCLAW-1088).

This is a SECOND implementation of a security check, which is a cost worth naming.
The JVM guard stays authoritative for the entry URL; this exists because the browser
follows redirects and loads subresources on its own, and the sidecar has to decide
about those hosts without a round trip per request.

Kept stdlib-only and free of any Patchright import so StealthBrowserTest can run it
against the same address table the Java guard is fed and fail when the two drift.
"""

import ipaddress
import socket


def is_public_ip(addr):
    """True when `addr` (a string or ip_address) is publicly routable.

    Covers SsrfGuard.isUnsafe and then some. Exact parity is NOT the invariant --
    this side must never be more permissive, and it is allowed to be stricter, which
    is what StealthBrowserTest asserts.

    fec0::/10 is checked explicitly: Java's isSiteLocalAddress() rejects the
    deprecated IPv6 site-local range, and ipaddress classifies it as neither private
    nor reserved, so mirroring "is_private" alone admitted an address the JVM blocks.
    """
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return False
    if ip.version == 6 and (int(ip) >> 118) == 0x3FB:
        return False
    return not (ip.is_private or ip.is_loopback or ip.is_link_local
                or ip.is_multicast or ip.is_reserved or ip.is_unspecified)


def is_public_host(host):
    """True when every address `host` resolves to is publicly routable.

    ALL addresses must pass: a hostname answering with one public and one private
    address would otherwise be admitted on the strength of the public one.
    """
    try:
        infos = socket.getaddrinfo(host, None)
    except (OSError, UnicodeError, ValueError):
        # An over-long or empty DNS label raises UnicodeEncodeError from the idna
        # codec, not OSError. Catching only OSError let it escape the route handler,
        # leaving the request neither continued nor aborted until the render timed out.
        return False
    return bool(infos) and all(is_public_ip(i[4][0]) for i in infos)
