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

    The rejected set mirrors SsrfGuard.isUnsafe: loopback, private, link-local,
    multicast, reserved and unspecified.
    """
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
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
    except OSError:
        return False
    return bool(infos) and all(is_public_ip(i[4][0]) for i in infos)
