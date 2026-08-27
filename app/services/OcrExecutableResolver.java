// Copyright Abundent Sdn Bhd (https://abundent.com)
// Dual-licensed: PolyForm Noncommercial 1.0.0 (see LICENSE.md)
// or a commercial license from Abundent (see COMMERCIAL-LICENSE.md).
package services;

import play.Play;
import utils.PlatformProcess;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves Tesseract from explicit configuration, the host environment, or platform defaults. */
public final class OcrExecutableResolver {

    public record Resolution(Path executable, String source, boolean available) {
        public Path directory() {
            return executable.toAbsolutePath().getParent();
        }
    }

    private OcrExecutableResolver() {}

    public static Resolution resolve() {
        var configured = Play.configuration == null ? ""
                : Play.configuration.getProperty("ocr.tesseract.path", "");
        return resolve(configured, System.getProperty("os.name", ""), System.getenv(),
                System.getenv("PATH"));
    }

    public static Resolution resolve(String configured, String osName, Map<String, String> env, String path) {
        var windows = osName != null && osName.toLowerCase(Locale.ROOT).contains("windows");
        var executableName = windows ? "tesseract.exe" : "tesseract";

        if (configured != null && !configured.isBlank()) {
            return explicit(configured, executableName, "ocr.tesseract.path");
        }
        var envPath = env.get("TESSERACT_PATH");
        if (envPath != null && !envPath.isBlank()) {
            return explicit(envPath, executableName, "TESSERACT_PATH");
        }

        var resolved = PlatformProcess.resolveOnPath(executableName, osName, path,
                env.getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD"));
        if (resolved != null) return new Resolution(Path.of(resolved), "PATH", true);

        if (windows) {
            for (var candidate : windowsCandidates(env)) {
                if (Files.isRegularFile(candidate)) {
                    return new Resolution(candidate, "standard Windows install", true);
                }
            }
        }
        return null;
    }

    private static Resolution explicit(String raw, String executableName, String source) {
        var value = raw.strip();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            var path = Path.of(value);
            var executable = Files.isDirectory(path) ? path.resolve(executableName) : path;
            return new Resolution(executable, source, Files.isRegularFile(executable));
        } catch (InvalidPathException _) {
            return new Resolution(Path.of(executableName), source, false);
        }
    }

    private static List<Path> windowsCandidates(Map<String, String> env) {
        var candidates = new ArrayList<Path>();
        add(candidates, env.get("ProgramFiles"), "Tesseract-OCR", "tesseract.exe");
        add(candidates, env.get("ProgramFiles(x86)"), "Tesseract-OCR", "tesseract.exe");
        add(candidates, env.get("LOCALAPPDATA"), "Programs", "Tesseract-OCR", "tesseract.exe");
        add(candidates, env.get("USERPROFILE"), "scoop", "apps", "tesseract", "current", "tesseract.exe");
        return candidates;
    }

    private static void add(List<Path> paths, String root, String... segments) {
        if (root == null || root.isBlank()) return;
        var path = Path.of(root);
        for (var segment : segments) path = path.resolve(segment);
        paths.add(path);
    }
}
