// Copyright Abundent Sdn Bhd (https://abundent.com)
// Dual-licensed: PolyForm Noncommercial 1.0.0 (see LICENSE.md)
// or a commercial license from Abundent (see COMMERCIAL-LICENSE.md).
package utils;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Cross-platform command preparation for {@link ProcessBuilder}. */
public final class PlatformProcess {

    private static final String DEFAULT_WINDOWS_PATHEXT = ".COM;.EXE;.BAT;.CMD";

    private PlatformProcess() {}

    public static boolean isWindows() {
        return isWindows(System.getProperty("os.name", ""));
    }

    /** Run shell syntax with POSIX sh, or PowerShell on a native Windows host without Git Bash. */
    public static List<String> shellCommand(String command) {
        return shellCommandFor(command, System.getProperty("os.name", ""), System.getenv("PATH"));
    }

    public static List<String> shellCommandFor(String command, String osName, String path) {
        if (!isWindows(osName)) return List.of("/bin/sh", "-c", command);
        var sh = resolveOnPath("sh.exe", osName, path, ".EXE");
        if (sh != null) return List.of(sh, "-c", command);
        return List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-Command", command);
    }

    /** POSIX shell required by the packaged {@code jclaw.sh} lifecycle helper. */
    public static List<String> posixShellCommand(String command) {
        if (!isWindows()) return List.of("/bin/sh", "-c", command);
        var sh = resolveOnPath("sh.exe", System.getProperty("os.name", ""),
                System.getenv("PATH"), ".EXE");
        return List.of(sh == null ? "sh.exe" : sh, "-c", command);
    }

    /**
     * Resolve Windows command shims before handing argv to {@link ProcessBuilder}.
     * npm-family tools install as {@code .cmd} files, which CreateProcess cannot
     * launch directly; cmd.exe is required for those while native executables stay direct.
     */
    public static List<String> command(List<String> command) {
        return commandFor(command, System.getProperty("os.name", ""), System.getenv("PATH"),
                System.getenv().getOrDefault("PATHEXT", DEFAULT_WINDOWS_PATHEXT),
                System.getenv().getOrDefault("ComSpec", "cmd.exe"));
    }

    public static List<String> commandFor(List<String> command, String osName, String path,
                                          String pathExt, String comSpec) {
        if (!isWindows(osName) || command.isEmpty()) return command;
        var resolved = resolveOnPath(command.getFirst(), osName, path, pathExt);
        var executable = resolved != null ? resolved : command.getFirst();
        var normalized = new ArrayList<String>();
        if (isCmdScript(executable)) {
            normalized.add(comSpec == null || comSpec.isBlank() ? "cmd.exe" : comSpec);
            normalized.add("/d");
            normalized.add("/s");
            normalized.add("/c");
        }
        normalized.add(executable);
        normalized.addAll(command.subList(1, command.size()));
        return List.copyOf(normalized);
    }

    public static String resolveOnPath(String command) {
        return resolveOnPath(command, System.getProperty("os.name", ""), System.getenv("PATH"),
                System.getenv().getOrDefault("PATHEXT", DEFAULT_WINDOWS_PATHEXT));
    }

    public static String resolveOnPath(String command, String osName, String path, String pathExt) {
        if (command == null || command.isBlank()) return null;
        var windows = isWindows(osName);
        var extensions = windows ? extensions(command, pathExt) : List.of("");
        if (hasPathSeparator(command)) {
            return existing(command, extensions);
        }
        if (path == null || path.isBlank()) return null;
        var separator = windows ? ";" : java.io.File.pathSeparator;
        for (var entry : path.split(java.util.regex.Pattern.quote(separator))) {
            var dir = unquote(entry.strip());
            if (dir.isEmpty()) continue;
            var found = existing(Path.of(dir).resolve(command).toString(), extensions);
            if (found != null) return found;
        }
        return null;
    }

    private static String existing(String base, List<String> extensions) {
        for (var ext : extensions) {
            for (var candidate : List.of(base + ext, base + ext.toLowerCase(Locale.ROOT))) {
                try {
                    var path = Path.of(candidate);
                    if (Files.isRegularFile(path)) return path.toString();
                } catch (InvalidPathException _) {
                    return null;
                }
            }
        }
        return null;
    }

    private static List<String> extensions(String command, String pathExt) {
        var lower = command.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".com") || lower.endsWith(".exe")
                || lower.endsWith(".bat") || lower.endsWith(".cmd")) return List.of("");
        var raw = pathExt == null || pathExt.isBlank() ? DEFAULT_WINDOWS_PATHEXT : pathExt;
        var out = new ArrayList<String>();
        for (var ext : raw.split(";")) {
            if (!ext.isBlank()) out.add(ext.startsWith(".") ? ext : "." + ext);
        }
        return out;
    }

    private static boolean hasPathSeparator(String command) {
        return command.indexOf('/') >= 0 || command.indexOf('\\') >= 0;
    }

    private static boolean isCmdScript(String command) {
        var lower = command.toLowerCase(Locale.ROOT);
        return lower.endsWith(".cmd") || lower.endsWith(".bat");
    }

    private static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("windows");
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
