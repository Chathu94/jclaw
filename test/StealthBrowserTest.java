import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.ConfigService;
import services.StealthSidecarManager;
import services.scrape.BlockClassifier;
import services.scrape.ScrapeReason;
import services.scrape.ScrapeRung;
import tools.scrape.RenderedFetcher;
import utils.SsrfGuard;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Rung 3 — the stealth rendering sidecar (JCLAW-1088).
 *
 * <p>The load-bearing test here is the SSRF parity one. Moving the browser launch out of
 * the JVM moved {@code --host-resolver-rules} with it, and the sidecar had to gain its
 * own IP-range check for the hosts a page reaches on its own. That is a second
 * implementation of a security check, so it is pinned against the first rather than
 * trusted to stay in step.
 */
class StealthBrowserTest extends UnitTest {

    /** Deliberately spans both families and every category the guard rejects. */
    private static final List<String> ADDRESSES = List.of(
            "8.8.8.8", "1.1.1.1", "93.184.215.14",       // public v4
            "2606:4700:4700::1111",                       // public v6
            "127.0.0.1", "::1",                           // loopback
            "10.0.0.1", "172.16.0.1", "192.168.1.1",      // private v4
            "fd00::1",                                    // private v6
            "169.254.169.254", "fe80::1",                 // link-local (incl. cloud metadata)
            "224.0.0.1", "ff02::1",                       // multicast
            "0.0.0.0", "::",                              // unspecified
            // Classes where the two implementations do NOT agree. The table held none
            // of them, so it passed while asserting equality it could not have caught
            // a drift in. fec0::/10 was the one that mattered: Java rejects it as
            // site-local, ipaddress called it public, and the sidecar admitted it.
            "fec0::1",                                    // v6 site-local
            "240.0.0.1", "192.0.2.1", "198.18.0.1",       // v4 reserved/doc/benchmark
            "255.255.255.255", "64:ff9b::7f00:1");        // broadcast, v4-mapped v6

    @AfterEach
    void clearOverrides() {
        ConfigService.set(StealthSidecarManager.CFG_ENABLED, "true");
        ConfigService.clearCache();
    }

    // ==================== The duplicated guard ====================

    @Test
    void theSidecarsAddressCheckAgreesWithSsrfGuard() throws Exception {
        var script = new File(Play.applicationPath, "sidecar/stealth/ssrf.py");
        assertTrue(script.isFile(), "ssrf.py missing — the sidecar's guard has moved or gone");

        var cmd = new java.util.ArrayList<>(List.of("python3", "-c", """
                import sys, json
                sys.path.insert(0, sys.argv[1])
                from ssrf import is_public_ip
                print(json.dumps({a: is_public_ip(a) for a in sys.argv[2:]}))
                """, script.getParent()));
        cmd.addAll(ADDRESSES);

        var proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "python3 parity probe timed out");
        var stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, proc.exitValue(), "python3 parity probe failed: " + stdout);

        var python = JsonParser.parseString(stdout).getAsJsonObject();
        for (var address : ADDRESSES) {
            boolean pythonSaysPublic = python.get(address).getAsBoolean();
            boolean javaSaysPublic = !SsrfGuard.isUnsafe(InetAddress.getByName(address));
            // Not equality. The security property is one-directional: the sidecar must
            // never admit what the JVM would reject, and being stricter costs only
            // reach. Asserting equality made the stricter-side differences look like
            // failures, which is why the divergent addresses were absent from the table
            // and the fail-open one went unnoticed.
            if (pythonSaysPublic) {
                assertTrue(javaSaysPublic, "the sidecar admits " + address
                        + " while SsrfGuard rejects it — the duplicated guard has drifted open");
            }
        }
    }

    // ==================== Containment that survives the move ====================

    @Test
    void anUnsafeEntryUrlNeverReachesTheBrowser() {
        // The JVM stays authoritative for the entry URL: hostResolverRule throws
        // everything assertUrlSafe does, so the sidecar is never even contacted.
        for (var url : List.of("http://127.0.0.1:9000/api/status",
                               "http://169.254.169.254/latest/meta-data/",
                               "http://[::1]:9000/")) {
            assertThrows(SecurityException.class, () -> RenderedFetcher.fetch(url),
                    "expected an SsrfGuard refusal for " + url);
        }
    }

    // ==================== Feature detection ====================

    @Test
    void disablingTheRungDegradesRatherThanErroring() {
        ConfigService.set(StealthSidecarManager.CFG_ENABLED, "false");
        ConfigService.clearCache();
        assertFalse(StealthSidecarManager.available());
        assertFalse(RenderedFetcher.available());
    }

    // ==================== Ladder position ====================

    @Test
    void thinContentEscalatesToTheBrowserWithoutABlockBeingDetected() {
        // The rung must be reachable for a rendering failure, not only for a block:
        // a client-rendered page at zero protection is THIN_CONTENT, and BROWSER is
        // what fixes it.
        assertEquals(ScrapeRung.BROWSER,
                BlockClassifier.nextRung(ScrapeReason.THIN_CONTENT, ScrapeRung.PLAIN));
        assertEquals(ScrapeRung.BROWSER,
                BlockClassifier.nextRung(ScrapeReason.JS_CHALLENGE, ScrapeRung.PLAIN));
    }
}
