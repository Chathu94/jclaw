package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import services.WorkspaceFiles;
import services.printing.DiscoveredPrinter;
import services.printing.PrintDispatcher;
import services.printing.PrintProtocol;
import services.printing.PrinterDiscovery;
import utils.WorkspacePathGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Network printing (JCLAW-911). Discovers printers over mDNS and sends jobs
 * without CUPS, {@code lp}, or any OS print subsystem — everything is JVM-native,
 * so the tool behaves the same on macOS, Linux and Windows and works in a
 * container that has no printing stack at all.
 *
 * <p>Printing is irreversible in a way most tools are not: paper comes out of a
 * device in someone's room, and there is no undo. The tool therefore never
 * guesses a target — {@code print} requires an explicit printer — and it reports
 * honestly when the backend it fell back to cannot confirm the job actually
 * printed (see {@link PrintDispatcher.Outcome#verified()}).
 */
public class PrinterTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "printer";

    private static final String ARG_ACTION = "action";
    private static final String ARG_PRINTER = "printer";
    private static final String ARG_JOB_ID = "jobId";
    private static final String ARG_PATH = "path";
    private static final String ARG_TEXT = "text";

    /** Upper bound on a job we will read into memory and push to a printer. */
    private static final long MAX_DOCUMENT_BYTES = 64L * 1024 * 1024;

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String category() {
        return "Utilities";
    }

    @Override
    public String icon() {
        return "printer";
    }

    @Override
    public boolean parallelSafe() {
        // Discovery and status are read-only, but print is not: two jobs racing to
        // the same device interleave at the printer, not here. Serialised because
        // the failure mode is physical and unrecoverable.
        return false;
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction("discover", "Find printers on the local network over mDNS/Bonjour"),
                new ToolAction("print", "Send a workspace file or literal text to a printer"),
                new ToolAction("status", "Report a printer's state, and a job's state when given a job id"),
                new ToolAction("cancel", "Cancel a queued print job by id"));
    }

    @Override
    public String summary() {
        return "Discover network printers and print documents to them (no CUPS required).";
    }

    @Override
    public String description() {
        return "Discover printers on the local network and print to them. Actions: "
                + "'discover' lists printers found over mDNS; 'print' sends a workspace file "
                + "(path) or literal text to a named printer; 'status' reports printer/job state; "
                + "'cancel' cancels a job. Always run 'discover' first and print to a printer the "
                + "user named — never guess a target, because printing cannot be undone.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_ACTION, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.ENUM, List.of("discover", "print", "status", "cancel"),
                                SchemaKeys.DESCRIPTION, "Which operation to perform."),
                        ARG_PRINTER, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "Printer name or host, as reported by 'discover'. Required for "
                                        + "'print'. A bare hostname or IP is also accepted when mDNS "
                                        + "is unavailable."),
                        "host", Map.of(
                                SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "Explicit printer host, bypassing discovery. Use when the network "
                                        + "blocks mDNS but the address is known."),
                        "port", Map.of(
                                SchemaKeys.TYPE, SchemaKeys.INTEGER,
                                SchemaKeys.DESCRIPTION,
                                "Explicit port. Defaults to the protocol's standard port "
                                        + "(631 IPP, 9100 raw, 515 LPD)."),
                        "protocol", Map.of(
                                SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.ENUM, List.of("IPP", "IPPS", "RAW", "LPD"),
                                SchemaKeys.DESCRIPTION,
                                "Force a protocol instead of auto-selecting. Rarely needed."),
                        ARG_PATH, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "Workspace-relative path of the document to print (PDF, PostScript "
                                        + "or plain text). Mutually exclusive with 'text'."),
                        ARG_TEXT, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "Literal text to print. Mutually exclusive with 'path'."),
                        ARG_JOB_ID, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.INTEGER,
                                SchemaKeys.DESCRIPTION,
                                "Job id, as returned by 'print'. Required for 'cancel'.")),
                SchemaKeys.REQUIRED, List.of(ARG_ACTION));
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        JsonObject args;
        try {
            args = JsonParser.parseString(argsJson).getAsJsonObject();
        } catch (RuntimeException e) {
            return "Error: arguments were not a JSON object.";
        }
        var action = str(args, ARG_ACTION);
        if (action == null) {
            return "Error: missing required 'action' argument "
                    + "(one of: discover, print, status, cancel).";
        }
        try {
            return switch (action.toLowerCase()) {
                case "discover" -> discover();
                case "print" -> print(args, agent);
                case "status" -> status(args);
                case "cancel" -> cancel(args, agent);
                default -> "Error: unknown action '" + action
                        + "'. Valid actions: discover, print, status, cancel.";
            };
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    // ─── Actions ───

    private static String discover() {
        var printers = PrinterDiscovery.discover();
        if (printers.isEmpty()) {
            return "No printers found on the local network. mDNS is link-local, so this also "
                    + "reports empty when the host has no multicast route (many containers and "
                    + "VPNs). If the printer's address is known, pass it as 'host'.";
        }
        var sb = new StringBuilder("Found ").append(printers.size()).append(" printer(s):\n");
        for (var p : printers) {
            sb.append("- ").append(p.name())
                    .append(" — ").append(p.host()).append(':').append(p.port())
                    .append(" (").append(p.protocol()).append(')');
            var pdl = p.capabilities().get("pdl");
            if (pdl != null) {
                sb.append(" formats: ").append(pdl);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String print(JsonObject args, Agent agent) throws IOException {
        var target = resolveTarget(args);
        if (target == null) {
            return "Error: 'print' needs a 'printer' (from discover) or an explicit 'host'.";
        }

        var path = str(args, ARG_PATH);
        var text = str(args, ARG_TEXT);
        if (path != null && text != null) {
            return "Error: pass either 'path' or 'text', not both.";
        }

        byte[] document;
        String documentFormat;
        String jobName;
        if (path != null) {
            var root = WorkspaceFiles.workspacePath(agent.name);
            var resolved = WorkspacePathGuard.resolveContained(root, path);
            if (resolved == null) {
                return "Error: '" + path + "' resolves outside the agent workspace.";
            }
            if (!Files.isRegularFile(resolved)) {
                return "Error: no such file in the workspace: " + path;
            }
            var size = Files.size(resolved);
            if (size > MAX_DOCUMENT_BYTES) {
                return "Error: '%s' is %d bytes, over the %d-byte print limit."
                        .formatted(path, size, MAX_DOCUMENT_BYTES);
            }
            document = Files.readAllBytes(resolved);
            documentFormat = formatFor(path);
            jobName = resolved.getFileName().toString();
        } else if (text != null) {
            document = text.getBytes(StandardCharsets.UTF_8);
            documentFormat = "text/plain";
            jobName = "jclaw-text";
        } else {
            return "Error: 'print' needs either 'path' (a workspace file) or 'text'.";
        }

        if (document.length == 0) {
            return "Error: refusing to print an empty document.";
        }

        var outcome = PrintDispatcher.print(target, jobName, agent.name, documentFormat, document);
        var verdict = outcome.verified()
                ? "Printed via " + outcome.protocol() + " — " + outcome.detail()
                // Said plainly because the model will otherwise report this as a
                // confirmed print, which it is not.
                : "Sent via " + outcome.protocol() + " — " + outcome.detail()
                        + ". NOTE: this backend cannot confirm the document printed; "
                        + "check the printer if confirmation matters.";
        return verdict;
    }

    private static String status(JsonObject args) throws IOException {
        var target = resolveTarget(args);
        if (target == null) {
            return "Error: 'status' needs a 'printer' (from discover) or an explicit 'host'.";
        }
        return PrintDispatcher.status(target, intOrNull(args, ARG_JOB_ID));
    }

    private static String cancel(JsonObject args, Agent agent) throws IOException {
        var jobId = intOrNull(args, ARG_JOB_ID);
        if (jobId == null) {
            return "Error: 'cancel' needs a numeric 'jobId' (returned by 'print').";
        }
        var target = resolveTarget(args);
        var uri = target == null ? null : target.ippUri();
        return "Cancel requested for job " + jobId + ": "
                + PrintDispatcher.cancel(jobId, uri, agent.name);
    }

    // ─── Helpers ───

    /**
     * Work out which printer the caller means: an explicit host wins, otherwise the
     * name is matched against a fresh discovery. Returns null when neither was given.
     *
     * <p>Discovery is re-run rather than cached — see {@link PrinterDiscovery} for
     * why a stale address is worse than a slow lookup.
     */
    private static DiscoveredPrinter resolveTarget(JsonObject args) {
        var protocol = PrintProtocol.parse(str(args, "protocol"));
        var host = str(args, "host");
        if (host != null) {
            return PrinterDiscovery.direct(host, intOrNull(args, "port"), protocol);
        }
        var name = str(args, ARG_PRINTER);
        if (name == null) {
            return null;
        }
        var hits = PrinterDiscovery.matching(PrinterDiscovery.discover(Duration.ofSeconds(2)), name);
        if (hits.isEmpty()) {
            // Treat an unmatched name as a hostname rather than failing: the operator
            // may know the address on a network where mDNS is blocked.
            return PrinterDiscovery.direct(name, intOrNull(args, "port"), protocol);
        }
        return hits.getFirst();
    }

    /** MIME type from the filename, or null to let the printer sniff. */
    public static String formatFor(String path) {
        var lower = path.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".ps")) return "application/postscript";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain";
        // Deliberately not a guess: a conforming printer treats octet-stream as
        // "sniff it yourself", which beats asserting a format that is wrong.
        return "application/octet-stream";
    }

    private static String str(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        var v = args.get(key).getAsString().trim();
        return v.isEmpty() ? null : v;
    }

    private static Integer intOrNull(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
