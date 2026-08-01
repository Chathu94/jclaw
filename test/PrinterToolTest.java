import agents.ToolRegistry;
import jobs.ToolRegistrationJob;
import models.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.printing.PrinterDefaults;
import tools.PrinterTool;

import java.util.List;
import java.util.Map;

/**
 * The printer tool's own surface (JCLAW-911): argument validation, dispatch, and
 * the guards that stop a physical, irreversible action from firing on a
 * half-specified request.
 *
 * <p>Nothing here reaches a printer. Every case asserted below is one the tool
 * must reject or answer BEFORE any network I/O, which is exactly the set that can
 * be pinned without hardware. The one exception is
 * {@link #anUnreachableDefaultOffersWhatIsOnTheNetworkInstead}, which needs a
 * refused connection to a closed loopback port to reach the guard at all.
 */
class PrinterToolTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        // play1 runs test classes concurrently against one DB, so a default saved
        // by PrinterDefaultsTest can otherwise leak in and satisfy the very
        // "no target" checks below. Clear it so these assert the tool, not the order.
        PrinterDefaults.clear();
        agent = AgentService.create("printer-test", "openrouter", "gpt-4.1");
    }

    private String run(String argsJson) {
        return new PrinterTool().execute(argsJson, agent);
    }

    // ─── Argument validation ───

    @Test
    void missingActionIsRejectedWithTheValidSet() {
        var out = run("{}");
        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("discover"), "the error should name the valid actions: " + out);
    }

    @Test
    void unknownActionIsRejected() {
        var out = run("{\"action\":\"fax\"}");
        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("fax"), out);
    }

    @Test
    void malformedArgumentsDoNotThrow() {
        // The model can emit anything; a throw here costs the whole turn.
        assertTrue(run("not json").startsWith("Error:"));
        assertTrue(run("[1,2,3]").startsWith("Error:"));
    }

    @Test
    void printWithoutATargetIsRefused() {
        // Never guess a printer — the output is physical and lands in someone's room.
        var out = run("{\"action\":\"print\",\"text\":\"hello\"}");
        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("printer") || out.contains("host"), out);
    }

    @Test
    void printWithoutContentIsRefused() {
        var out = run("{\"action\":\"print\",\"host\":\"127.0.0.1\"}");
        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("path") && out.contains("text"), out);
    }

    @Test
    void printRejectsBothPathAndText() {
        // Ambiguous intent on an irreversible action — refuse rather than pick.
        var out = run("{\"action\":\"print\",\"host\":\"127.0.0.1\","
                + "\"path\":\"a.pdf\",\"text\":\"hi\"}");
        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("not both"), out);
    }

    @Test
    void printRefusesAPathOutsideTheWorkspace() {
        var out = run("{\"action\":\"print\",\"host\":\"127.0.0.1\","
                + "\"path\":\"../../../../etc/passwd\"}");
        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("outside the agent workspace"), out);
    }

    @Test
    void printRefusesAMissingWorkspaceFile() {
        var out = run("{\"action\":\"print\",\"host\":\"127.0.0.1\","
                + "\"path\":\"nope-does-not-exist.pdf\"}");
        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("no such file"), out);
    }

    @Test
    void cancelRequiresAJobId() {
        var out = run("{\"action\":\"cancel\"}");
        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("jobId"), out);
    }

    // ─── Schema ───

    @Test
    void schemaDeclaresTheFourActionsAndRequiresAction() {
        var tool = new PrinterTool();
        var params = tool.parameters();

        @SuppressWarnings("unchecked")
        var required = (List<String>) params.get("required");
        assertEquals(List.of("action"), required);

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) params.get("properties");
        @SuppressWarnings("unchecked")
        var action = (Map<String, Object>) properties.get("action");
        @SuppressWarnings("unchecked")
        var actionEnum = (List<String>) action.get("enum");

        // The enum is what stops the model inventing an action name; it must match
        // the dispatch switch exactly or a valid-looking call 404s at runtime.
        assertEquals(List.of("discover", "print", "status", "cancel"), actionEnum);
        assertEquals(actionEnum.size(), tool.actions().size(),
                "every dispatchable action needs a ToolAction entry for the Tools page");
    }

    @Test
    void schemaOffersTheJobTemplateAttributes() {
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) new PrinterTool().parameters().get("properties");

        // Map.of caps at ten pairs and this set is eleven — a regression to Map.of
        // would not compile, but a dropped property would compile fine.
        assertTrue(properties.containsKey("sides"), "duplex control missing");
        assertTrue(properties.containsKey("color"), "colour control missing");
        assertTrue(properties.containsKey("media"), "tray/paper control missing");

        @SuppressWarnings("unchecked")
        var sides = (Map<String, Object>) properties.get("sides");
        @SuppressWarnings("unchecked")
        var sidesEnum = (List<String>) sides.get("enum");
        // The RFC 8011 keywords exactly — a printer rejects anything else.
        assertEquals(List.of("one-sided", "two-sided-long-edge", "two-sided-short-edge"), sidesEnum);
    }

    @Test
    void invalidJobAttributesAreRejectedBeforeAnyNetworkCall() {
        // Caught here rather than at the printer, which answers with an IPP status
        // that names no offending value.
        var out = run("{\"action\":\"print\",\"host\":\"127.0.0.1\","
                + "\"text\":\"hi\",\"sides\":\"both\"}");
        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("sides") && out.contains("both"), out);

        var color = run("{\"action\":\"print\",\"host\":\"127.0.0.1\","
                + "\"text\":\"hi\",\"color\":\"rainbow\"}");
        assertTrue(color.startsWith("Error:"), color);
        assertTrue(color.contains("rainbow"), color);
    }

    @Test
    void anUnreachableDefaultOffersWhatIsOnTheNetworkInstead() throws Exception {
        // A default outlives the DHCP lease it was saved under. Reaching a dead one
        // used to cost a 60s IPP timeout and then a RAW fallback that cannot confirm
        // anything printed, so the address is probed before any job is committed.
        int deadPort;
        try (var probe = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            deadPort = probe.getLocalPort();
        }
        PrinterDefaults.save(new PrinterDefaults.Defaults(
                "Ghost printer", "127.0.0.1", deadPort, "IPP", Map.of()));

        var out = run("{\"action\":\"print\",\"text\":\"hi\"}");

        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("not answering"), out);
        // Names the stale target: "the default is unreachable" is only actionable if
        // the operator can see which address was tried.
        assertTrue(out.contains("127.0.0.1:" + deadPort), out);
        // Asserted on the shared prefix only — whether an mDNS browse finds anything
        // depends on the machine running the suite, and both branches must say this.
    }

    @Test
    void anExplicitlyNamedTargetIsNotProbedFirst() throws Exception {
        // Only the saved default is pre-checked. A host the operator typed is their
        // call and gets attempted as given, so a printer that ignores mDNS or answers
        // slowly is still reachable by address.
        int deadPort;
        try (var probe = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            deadPort = probe.getLocalPort();
        }
        var out = run("{\"action\":\"print\",\"text\":\"hi\",\"host\":\"127.0.0.1\",\"port\":"
                + deadPort + "}");

        // It fails — nothing is listening — but as a delivery failure, not as the
        // stale-default message, which would mean the guard fired on the wrong path.
        assertTrue(out.startsWith("Error:"), out);
        assertFalse(out.contains("not answering"), out);
    }

    @Test
    void documentFormatIsInferredFromTheExtension() {
        assertEquals("application/pdf", PrinterTool.formatFor("report.PDF"));
        assertEquals("application/postscript", PrinterTool.formatFor("a.ps"));
        assertEquals("text/plain", PrinterTool.formatFor("notes.md"));
        // Unknown extension is octet-stream, which tells a conforming printer to
        // sniff — better than asserting a format that is wrong.
        assertEquals("application/octet-stream", PrinterTool.formatFor("mystery.xyz"));
    }

    @Test
    void everyRenderableImageTypeIsNamedRatherThanLeftToSniffing() {
        // Every type PrintRenderer.readImage decodes must be nameable here, or the
        // renderer's image branch is unreachable from the tool and images render as text.
        assertEquals("image/jpeg", PrinterTool.formatFor("photo.jpg"));
        assertEquals("image/jpeg", PrinterTool.formatFor("photo.JPEG"));
        assertEquals("image/png", PrinterTool.formatFor("diagram.png"));
        assertEquals("image/gif", PrinterTool.formatFor("anim.gif"));
        assertEquals("image/bmp", PrinterTool.formatFor("old.bmp"));
        assertEquals("image/tiff", PrinterTool.formatFor("scan.tif"));
        assertEquals("image/tiff", PrinterTool.formatFor("scan.tiff"));
        assertEquals("image/webp", PrinterTool.formatFor("modern.webp"));
    }

    @Test
    void printerIsNotParallelSafe() {
        // Two jobs racing to one device interleave at the printer, where there is
        // no undo. Serialisation is the point, not an oversight.
        assertFalse(new PrinterTool().parallelSafe());
    }

    // ─── Registration ───

    @Test
    void registeredInTheBuiltInCatalog() {
        ToolRegistrationJob.registerAll();
        var names = ToolRegistry.listTools().stream().map(ToolRegistry.Tool::name).toList();
        assertTrue(names.contains(PrinterTool.TOOL_NAME),
                "printer must ship in the built-in catalog, got: " + names);
    }

    @Test
    void defaultOffForEveryAgentUntilExplicitlyEnabled() {
        ToolRegistrationJob.registerAll();

        // The whole safety story rests on this. Registering a tool makes it visible
        // in the catalog, NOT active — and the user guide promises printing is
        // opt-in. Without this the promise is just prose: a fresh agent would get
        // the ability to emit paper in someone's room with nobody having chosen it.
        var visible = ToolRegistry.getToolDefsForAgent(agent).stream()
                .map(d -> d.function().name()).toList();

        // Guard the guard: an empty list would satisfy the assertion below while
        // proving nothing. Anchor on a tool that IS default-on, so this fails if
        // the visibility mechanism breaks rather than silently passing.
        assertTrue(visible.contains("datetime"),
                "expected default-on tools to be visible; got: " + visible);
        assertFalse(visible.contains(PrinterTool.TOOL_NAME),
                "printer must be hidden from an agent that has not opted in, got: " + visible);
    }
}
