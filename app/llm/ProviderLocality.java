package llm;

import services.ConfigService;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * Whether a provider runs on the operator's own machine or network (JCLAW-939).
 *
 * <p>Decided from the configured base URL, not the provider name. Embedding a memory sends
 * its full text to the provider, so what matters is where the bytes go — a provider named
 * {@code openai} pointed at a local proxy keeps them here, and a provider named
 * {@code my-local-llm} pointed at a public host does not. Names are operator-chosen and
 * carry no such guarantee.
 *
 * <p>{@link PaymentModality} is deliberately not used for this. Its empty supported-set
 * marks both free-at-point-of-use providers and unrecognised ones, so an unknown cloud
 * provider would read as local.
 *
 * <p>Hostnames are never resolved. A DNS lookup on the settings path would be slow, and a
 * name that resolves to a loopback address today can resolve elsewhere tomorrow, which
 * would make the guarantee depend on whoever answers the query. Only address literals and
 * names that are local by definition count; everything else is treated as remote.
 */
public final class ProviderLocality {

    private ProviderLocality() {}

    /**
     * True when {@code providerName} is configured against a local base URL.
     *
     * <p>Reads the configured URL directly rather than going through
     * {@link ProviderRegistry}, so this agrees with the same key the endpoints validate
     * against. The registry is populated from a sync and can lag a just-saved provider,
     * which would classify it as remote and refuse a provider the operator has in fact
     * pointed at their own machine.
     */
    public static boolean isLocal(String providerName) {
        if (providerName == null || providerName.isBlank()) return false;
        return isLocalUrl(ConfigService.get("provider." + providerName + ".baseUrl"));
    }

    public static boolean isLocalUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return false;
        String host;
        try {
            host = URI.create(baseUrl.trim()).getHost();
        } catch (IllegalArgumentException _) {
            return false;
        }
        return host != null && isLocalHost(host);
    }

    static boolean isLocalHost(String host) {
        var h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);   // URI keeps the brackets on IPv6 literals
        }
        if (h.isEmpty()) return false;
        // Local by definition, whatever a resolver would say.
        if (h.equals("localhost") || h.endsWith(".localhost")
                || h.endsWith(".local") || h.equals("host.docker.internal")) {
            return true;
        }
        InetAddress addr;
        try {
            addr = InetAddress.ofLiteral(h);      // never performs a DNS lookup
        } catch (IllegalArgumentException _) {
            return false;                          // a hostname we refuse to resolve
        }
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
            return true;
        }
        // IPv6 unique-local (fc00::/7). Java's isSiteLocalAddress only covers the
        // deprecated fec0::/10 for v6, so ULA needs checking directly.
        var bytes = addr.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
