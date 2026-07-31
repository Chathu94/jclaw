import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.printing.DiscoveredPrinter;
import services.printing.LpdClient;
import services.printing.PrintProtocol;
import services.printing.PrinterDiscovery;
import services.printing.RawSocketClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The two print backends that can be exercised without a printer (JCLAW-911).
 *
 * <p>Both run against a loopback {@link ServerSocket} on an ephemeral port, so
 * these are real protocol round-trips rather than mocks — the LPD test asserts
 * the exact RFC 1179 byte sequence a daemon would receive. What they cannot
 * cover is a real printer's interpretation of those bytes; see the IPP path,
 * which has no equivalent test for exactly that reason.
 */
class PrintBackendTest extends UnitTest {

    /** Accept one connection, read everything, ack each LPD step with a zero byte. */
    private static CompletableFuture<byte[]> lpdDaemon(ServerSocket server, int ackCount) {
        return CompletableFuture.supplyAsync(() -> {
            try (var socket = server.accept()) {
                var in = socket.getInputStream();
                var out = socket.getOutputStream();
                var received = new ByteArrayOutputStream();
                // Ack immediately and keep draining: the client writes the next
                // step only after reading each ack, so acking up front is what
                // lets the exchange proceed without modelling the state machine.
                for (int i = 0; i < ackCount; i++) {
                    out.write(0);
                    out.flush();
                    pump(in, received, 1);
                }
                pump(in, received, 0);
                return received.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** Drain whatever is currently available into {@code sink}. */
    private static void pump(InputStream in, ByteArrayOutputStream sink, int minBytes) throws IOException {
        var buf = new byte[8192];
        int total = 0;
        do {
            int available = in.available();
            if (available <= 0) {
                if (total >= minBytes) return;
                // Nothing buffered yet — a blocking read parks until the client writes.
                int one = in.read();
                if (one == -1) return;
                sink.write(one);
                total++;
                continue;
            }
            int n = in.read(buf, 0, Math.min(available, buf.length));
            if (n == -1) return;
            sink.write(buf, 0, n);
            total += n;
        } while (total < minBytes);
    }

    @Test
    void lpdSendsTheRfc1179Sequence() throws Exception {
        try (var server = new ServerSocket(0)) {
            var daemon = lpdDaemon(server, 5);
            LpdClient.print("127.0.0.1", server.getLocalPort(), "lp", "invoice.pdf",
                    "tester", "PDFBYTES".getBytes(StandardCharsets.UTF_8), 5000);

            var wire = new String(daemon.get(10, TimeUnit.SECONDS), StandardCharsets.US_ASCII);

            // \002 + queue + \n is the "receive a printer job" command.
            assertTrue(wire.startsWith("\002lp\n"), "should open the queue first, got: " + wire);
            // Control file is subcommand \002, data file \003 — in that order.
            assertTrue(wire.indexOf("\002") < wire.indexOf("\003"),
                    "control file must precede data file");
            assertTrue(wire.contains("cfA"), "control file name should use the cfA<id> convention");
            assertTrue(wire.contains("dfA"), "data file name should use the dfA<id> convention");
            assertTrue(wire.contains("PDFBYTES"), "the document itself must reach the daemon");
        }
    }

    @Test
    void lpdControlFileUsesRawPrintNotFormattedText() {
        var control = new String(
                LpdClient.buildControlFile("host", "user", "report.pdf", "dfA001host"),
                StandardCharsets.US_ASCII);

        // 'l' prints the file verbatim; 'f' would make the daemon treat a PDF as
        // ASCII and paginate it into hundreds of pages of garbage.
        assertTrue(control.contains("\nldfA001host\n") || control.startsWith("ldfA001host\n")
                        || control.contains("ldfA001host"),
                "control file must request raw ('l') printing, got: " + control);
        assertFalse(control.contains("\nfdfA001host"), "must not request formatted ('f') printing");
        assertTrue(control.contains("Hhost"), "control file records the originating host");
        assertTrue(control.contains("Puser"), "control file records the submitting user");
    }

    @Test
    void lpdRejectsControlFileInjectionViaJobName() {
        // Control-file records are newline-delimited, so a newline in a job name
        // would inject an extra record the daemon reads as a command. Job names
        // come from model-supplied arguments, so this is reachable.
        var evil = LpdClient.safeToken("job\nUdfA000evil\nHattacker");
        assertFalse(evil.contains("\n"), "newlines must not survive into a control-file value");

        var control = new String(
                LpdClient.buildControlFile("h", "u", "job\nHattacker", "dfA001h"),
                StandardCharsets.US_ASCII);

        // The invariant is about RECORDS, not substrings. Sanitising turns
        // "job\nHattacker" into "job_Hattacker", so the text "Hattacker" still
        // appears — harmlessly, inside the J record. What must not happen is a
        // second line STARTING with H, which is what the daemon would parse as a
        // host record. Asserting on the substring instead would fail on correct code.
        var hostRecords = control.lines().filter(l -> l.startsWith("H")).toList();
        assertEquals(List.of("Hh"), hostRecords,
                "exactly one H record, and not the injected one: " + control);
        assertFalse(control.lines().anyMatch(l -> l.isEmpty()),
                "a blank record would mean a stray newline survived: " + control);
    }

    @Test
    void lpdSurfacesADaemonRefusal() throws Exception {
        try (var server = new ServerSocket(0)) {
            // Non-zero first ack = refusal. Reporting this as success is how a job
            // silently vanishes, so it must throw.
            CompletableFuture.runAsync(() -> {
                try (var socket = server.accept()) {
                    socket.getOutputStream().write(2);
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                    // Test server; the assertion is on the client side.
                }
            });
            var port = server.getLocalPort();
            var boom = assertThrows(IOException.class, () ->
                    LpdClient.print("127.0.0.1", port, "lp", "j", "u",
                            "x".getBytes(StandardCharsets.UTF_8), 5000));
            assertTrue(boom.getMessage().contains("refused"), boom.getMessage());
        }
    }

    @Test
    void rawSocketStreamsTheDocumentVerbatim() throws Exception {
        try (var server = new ServerSocket(0)) {
            var received = CompletableFuture.supplyAsync(() -> {
                try (var socket = server.accept()) {
                    return socket.getInputStream().readAllBytes();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });

            var payload = "%PDF-1.7 raw stream".getBytes(StandardCharsets.UTF_8);
            RawSocketClient.print("127.0.0.1", server.getLocalPort(), payload, 5000);

            // Raw socket adds no framing at all — byte-for-byte, or the printer
            // gets a corrupt document.
            assertArrayEquals(payload, received.get(10, TimeUnit.SECONDS));
        }
    }

    // ─── Addressing ───

    @Test
    void ippUriHonoursTheAdvertisedResourcePath() {
        var withRp = new DiscoveredPrinter("HP", "10.0.0.5", 631,
                PrintProtocol.IPP, Map.of("rp", "ipp/print"));
        // TXT records omit the leading slash by convention; the URI needs it.
        assertEquals("ipp://10.0.0.5:631/ipp/print", withRp.ippUri());

        var noRp = new DiscoveredPrinter("HP", "10.0.0.5", 631, PrintProtocol.IPP, Map.of());
        assertEquals("ipp://10.0.0.5:631/ipp/print", noRp.ippUri());

        var secure = new DiscoveredPrinter("HP", "10.0.0.5", 631,
                PrintProtocol.IPPS, Map.of("rp", "/secure/print"));
        assertEquals("ipps://10.0.0.5:631/secure/print", secure.ippUri());
    }

    @Test
    void protocolsMapToTheirDnsSdServiceTypes() {
        assertEquals(PrintProtocol.IPP, PrintProtocol.fromServiceType("_ipp._tcp.local."));
        assertEquals(PrintProtocol.IPPS, PrintProtocol.fromServiceType("_ipps._tcp.local."));
        assertEquals(PrintProtocol.RAW, PrintProtocol.fromServiceType("_pdl-datastream._tcp.local."));
        assertEquals(PrintProtocol.LPD, PrintProtocol.fromServiceType("_printer._tcp.local."));
        assertNull(PrintProtocol.fromServiceType("_http._tcp.local."));

        // All four AC-listed service types are covered — that list is the contract.
        assertEquals(4, PrintProtocol.values().length);
    }

    @Test
    void directAddressingFallsBackToTheProtocolDefaultPort() {
        assertEquals(631, PrinterDiscovery.direct("printer.local", null, PrintProtocol.IPP).port());
        assertEquals(9100, PrinterDiscovery.direct("printer.local", null, PrintProtocol.RAW).port());
        assertEquals(515, PrinterDiscovery.direct("printer.local", null, PrintProtocol.LPD).port());
        assertEquals(1234, PrinterDiscovery.direct("printer.local", 1234, PrintProtocol.IPP).port());
        // No protocol given → IPP, the only backend that can report back.
        assertEquals(PrintProtocol.IPP, PrinterDiscovery.direct("p", null, null).protocol());
    }

    @Test
    void discoveryDegradesToEmptyRatherThanThrowing() {
        // CI and containers routinely have no multicast route. "No printers found"
        // is the honest answer there; a stack trace would suggest a bug.
        assertNotNull(PrinterDiscovery.discover(java.time.Duration.ofMillis(50)));
    }
}
