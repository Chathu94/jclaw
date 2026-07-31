package controllers;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import services.printing.JobAttributes;
import services.printing.PrinterDefaults;
import services.printing.PrinterDiscovery;
import utils.ApiResponses;

import java.util.List;

import static utils.GsonHolder.GSON;

/**
 * Printer discovery and the operator's default-printer choice (JCLAW-911).
 *
 * <p>Backs Settings → Printers. Discovery is a live mDNS browse on every call —
 * see {@link PrinterDiscovery} for why nothing is cached — so the panel shows
 * what is reachable now rather than what was reachable when the page loaded.
 */
@With(AuthCheck.class)
public class ApiPrintersController extends Controller {

    /**
     * @param name         advertised printer name
     * @param host         resolved address
     * @param port         advertised port
     * @param protocol     the protocol it was advertised under
     * @param formats      the {@code pdl} TXT record (supported document formats), or null
     * @param isDefault    whether this printer is the saved default
     */
    public record PrinterEntry(String name, String host, int port, String protocol,
                               String formats, boolean isDefault) {}

    /** GET /api/printers — live mDNS browse, with the saved default flagged. */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = PrinterEntry.class))))
    public static void discover() {
        var saved = PrinterDefaults.load();
        var entries = PrinterDiscovery.discover().stream()
                .map(p -> new PrinterEntry(p.name(), p.host(), p.port(), p.protocol().name(),
                        p.capabilities().get("pdl"),
                        saved.matches(p.host(), p.port())))
                .toList();
        renderJSON(GSON.toJson(entries));
    }

    /** GET /api/printers/default — the saved default printer and job options. */
    public static void getDefault() {
        renderJSON(GSON.toJson(PrinterDefaults.load()));
    }

    /**
     * PUT /api/printers/default — save the default printer and its job options.
     *
     * <p>Body mirrors {@link PrinterDefaults}. An empty {@code host} clears the
     * default entirely, which is the only way back to "no default" — leaving a
     * half-cleared default pointing at a printer that has been unplugged is worse
     * than having none.
     */
    @SuppressWarnings("java:S2259")
    public static void saveDefault() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        var host = str(body, "host");
        if (host == null) {
            PrinterDefaults.clear();
            ApiResponses.ok("cleared", true);
            return;
        }

        var attributes = new JobAttributes(str(body, "sides"), str(body, "color"), str(body, "media"));
        var invalid = attributes.validationError();
        if (invalid != null) {
            // Rejected on save rather than at print time, so a bad default cannot
            // sit in config waiting to fail the first real job.
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, invalid);
            return;
        }

        var saved = new PrinterDefaults.Defaults(
                str(body, "name"), host,
                body.has("port") && !body.get("port").isJsonNull() ? body.get("port").getAsInt() : 0,
                str(body, "protocol"),
                attributes.sides(), attributes.colorMode(), attributes.media());
        PrinterDefaults.save(saved);
        renderJSON(GSON.toJson(saved));
    }

    /** Valid values for the job-option selects, so the panel doesn't hardcode them. */
    public static void options() {
        renderJSON(GSON.toJson(java.util.Map.of(
                "sides", JobAttributes.SIDES_VALUES,
                "color", JobAttributes.COLOR_MODE_VALUES,
                "protocols", List.of("IPP", "IPPS", "RAW", "LPD"))));
    }

    private static String str(com.google.gson.JsonObject body, String key) {
        if (!body.has(key) || body.get(key).isJsonNull()) {
            return null;
        }
        var v = body.get(key).getAsString().trim();
        return v.isEmpty() ? null : v;
    }
}
