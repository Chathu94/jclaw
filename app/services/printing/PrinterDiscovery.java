package services.printing;

import services.EventLogger;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds printers on the local link via mDNS/DNS-SD (JCLAW-911).
 *
 * <p>Browses the four service types printers advertise under and returns what
 * answered inside a bounded window. Nothing is cached: a discovery result goes
 * stale the moment a printer sleeps or a laptop changes networks, and a stale
 * cache here would send a job to an address that stopped answering — a failure
 * mode that looks like "the printer ate it" rather than "the address was wrong".
 */
public final class PrinterDiscovery {

    private static final String CATEGORY = "printer";

    /**
     * How long to let printers answer the browse. mDNS is a broadcast-and-wait
     * protocol with no completion signal, so the only termination condition is a
     * timer. Two seconds is above the ~1s that sleeping printers typically take to
     * wake their network interface and answer, and short enough that an agent
     * calling {@code discover} does not appear hung.
     */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    private PrinterDiscovery() {}

    /** Browse every supported service type with the default window. */
    public static List<DiscoveredPrinter> discover() {
        return discover(DEFAULT_TIMEOUT);
    }

    /**
     * Browse every supported service type and return what answered within
     * {@code timeout}.
     *
     * <p>Returns an empty list rather than throwing when the host has no usable
     * multicast interface — a container with host networking disabled, a locked-down
     * corporate VLAN, or CI. "No printers found" is the honest answer there, and it
     * is what the tool surfaces; a stack trace would suggest a bug that isn't one.
     */
    public static List<DiscoveredPrinter> discover(Duration timeout) {
        var found = new LinkedHashMap<String, DiscoveredPrinter>();
        try (var jmdns = JmDNS.create(InetAddress.getLocalHost())) {
            for (var protocol : PrintProtocol.values()) {
                // list() blocks for the given window and returns what responded. It
                // is called per service type because JmDNS has no multi-type browse.
                var services = jmdns.list(protocol.serviceType(), timeout.toMillis());
                for (var info : services) {
                    var printer = toPrinter(info, protocol);
                    if (printer != null) {
                        // Key on host:port so one physical printer advertising both
                        // _ipp and _ipps doesn't render as two devices. First wins,
                        // and PrintProtocol's declaration order puts IPP first.
                        found.putIfAbsent(printer.host() + ":" + printer.port(), printer);
                    }
                }
            }
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, "mDNS discovery unavailable: " + e.getMessage());
            return List.of();
        }
        return List.copyOf(found.values());
    }

    /** Map one JmDNS record to a printer, or null when it carries no usable address. */
    private static DiscoveredPrinter toPrinter(ServiceInfo info, PrintProtocol protocol) {
        var addresses = info.getHostAddresses();
        if (addresses == null || addresses.length == 0) {
            // Advertised but unresolvable — nothing to print to.
            return null;
        }
        var capabilities = new LinkedHashMap<String, String>();
        var keys = info.getPropertyNames();
        while (keys.hasMoreElements()) {
            var key = keys.nextElement();
            var value = info.getPropertyString(key);
            if (value != null) {
                capabilities.put(key, value);
            }
        }
        var port = info.getPort() > 0 ? info.getPort() : protocol.defaultPort();
        return new DiscoveredPrinter(info.getName(), addresses[0], port, protocol, capabilities);
    }

    /**
     * A printer addressed directly by host rather than discovered, for the case
     * where mDNS is blocked but the address is known. Capabilities are empty
     * because nothing advertised them.
     */
    public static DiscoveredPrinter direct(String host, Integer port, PrintProtocol protocol) {
        var resolved = protocol == null ? PrintProtocol.IPP : protocol;
        return new DiscoveredPrinter(host, host,
                port == null || port <= 0 ? resolved.defaultPort() : port,
                resolved, Map.of());
    }

    /** Discovered printers whose name or host matches {@code query}, case-insensitively. */
    public static List<DiscoveredPrinter> matching(List<DiscoveredPrinter> printers, String query) {
        if (query == null || query.isBlank()) {
            return printers;
        }
        var needle = query.trim().toLowerCase();
        var hits = new ArrayList<DiscoveredPrinter>();
        for (var p : printers) {
            if (p.name().toLowerCase().contains(needle) || p.host().toLowerCase().contains(needle)) {
                hits.add(p);
            }
        }
        return hits;
    }
}
