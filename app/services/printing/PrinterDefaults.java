package services.printing;

import services.ConfigService;

/**
 * The operator's default printer and job options (JCLAW-911), stored in the
 * Config DB under the {@code printer.default.*} namespace.
 *
 * <p>Exists so an agent does not have to be told the address every time. That is
 * not the tool guessing a target — the distinction the printer tool cares about
 * is whether a <em>human</em> chose the destination, and a saved default is a
 * human choice made once in Settings rather than inferred per call.
 *
 * <p>The host is stored, not the mDNS name. Names are how printers advertise
 * themselves and change with firmware updates and vendor whims; the address is
 * what a job is actually sent to. The name is kept alongside for display only.
 */
public final class PrinterDefaults {

    static final String KEY_NAME = "printer.default.name";
    static final String KEY_HOST = "printer.default.host";
    static final String KEY_PORT = "printer.default.port";
    static final String KEY_PROTOCOL = "printer.default.protocol";
    static final String KEY_SIDES = "printer.default.sides";
    static final String KEY_COLOR = "printer.default.color";
    static final String KEY_MEDIA = "printer.default.media";

    /**
     * @param name     display name, or null
     * @param host     address jobs are sent to; null means no default is configured
     * @param port     port, or 0 to use the protocol's standard port
     * @param protocol forced protocol, or null to auto-select
     * @param sides    default duplex mode, or null for the printer's own default
     * @param color    default colour mode, or null
     * @param media    default paper/tray, or null
     */
    public record Defaults(String name, String host, int port, String protocol,
                           String sides, String color, String media) {

        /** True when no default printer has been chosen. */
        public boolean isUnset() {
            return host == null || host.isBlank();
        }

        /** True when this default points at the given discovered printer. */
        public boolean matches(String otherHost, int otherPort) {
            if (isUnset()) {
                return false;
            }
            // Port 0 means "whatever the protocol's standard is", so a saved default
            // with no explicit port still matches the printer it was chosen from.
            return host.equals(otherHost) && (port == 0 || port == otherPort);
        }

        /** The job options as the print path consumes them. */
        public JobAttributes jobAttributes() {
            return new JobAttributes(sides, color, media);
        }
    }

    /** No default configured. */
    public static final Defaults NONE = new Defaults(null, null, 0, null, null, null, null);

    private PrinterDefaults() {}

    /** Read the saved default, or {@link #NONE} when unset. */
    public static Defaults load() {
        var host = blankToNull(ConfigService.get(KEY_HOST));
        if (host == null) {
            return NONE;
        }
        return new Defaults(
                blankToNull(ConfigService.get(KEY_NAME)),
                host,
                parsePort(ConfigService.get(KEY_PORT)),
                blankToNull(ConfigService.get(KEY_PROTOCOL)),
                blankToNull(ConfigService.get(KEY_SIDES)),
                blankToNull(ConfigService.get(KEY_COLOR)),
                blankToNull(ConfigService.get(KEY_MEDIA)));
    }

    /**
     * Invert what {@link #set} writes: it stores {@code ""} for an absent value,
     * so reading must turn that back into null.
     *
     * <p>Without this, clearing one job option leaves {@code ""} in config, which
     * {@link JobAttributes#validationError()} then rejects as an invalid keyword —
     * so a printer that had duplex switched off would refuse every subsequent job
     * with "invalid 'sides' value ''". Caught by cross-test pollution rather than
     * by the round-trip test, which only ever set real values or cleared all of them.
     */
    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    /** Persist the default printer and its job options. */
    public static void save(Defaults d) {
        set(KEY_NAME, d.name());
        set(KEY_HOST, d.host());
        set(KEY_PORT, d.port() > 0 ? String.valueOf(d.port()) : null);
        set(KEY_PROTOCOL, d.protocol());
        set(KEY_SIDES, d.sides());
        set(KEY_COLOR, d.color());
        set(KEY_MEDIA, d.media());
    }

    /** Remove the default entirely, so the tool goes back to requiring an explicit target. */
    public static void clear() {
        save(NONE);
    }

    /** Write, or blank the key when the value is absent — no stale halves left behind. */
    private static void set(String key, String value) {
        ConfigService.set(key, value == null ? "" : value);
    }

    private static int parsePort(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            // A hand-edited config row should not break printing; the protocol's
            // standard port is the safe reading of "not a number".
            return 0;
        }
    }
}
