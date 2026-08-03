package services.evals;

import play.Play;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Where memory-quality eval artifacts are allowed to live (JCLAW-529).
 *
 * <p>These suites are generated from the operator's own memory corpus, so a case is
 * personal data by construction: the query "what is my NAS IP" is the secret, not a
 * pointer to one, and the gold answers are the memories themselves. This repository is
 * mirrored publicly, so an artifact that reaches a commit is published.
 *
 * <p>{@code .gitignore} covers {@code evals/local/}, but an ignore rule only protects
 * files that were written where it expects. This class is the second layer: every write
 * resolves through {@link #suiteFile}, which refuses any path that escapes the local
 * directory and any id that is not a plain slug. A caller cannot accidentally write a
 * generated suite next to the tracked ones in {@code evals/suites/}.
 */
public final class MemoryEvalPaths {

    private MemoryEvalPaths() {}

    /** Ignored wholesale by .gitignore; see the class note for why that is not enough. */
    public static final String LOCAL_DIR = "evals/local";

    /** Tracked, and therefore forbidden as a destination for generated artifacts. */
    private static final List<String> TRACKED_DIRS = List.of("evals/suites", "evals/schema");

    /** No separators, no dots: a traversal cannot be spelled in an accepted id. */
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    public static Path localDir() {
        return Play.applicationPath.toPath().resolve(LOCAL_DIR).normalize();
    }

    /**
     * The file a generated suite may be written to.
     *
     * @throws IllegalArgumentException if {@code id} is not a plain slug, or if the
     *         resolved path would land outside {@link #LOCAL_DIR} — including inside a
     *         tracked eval directory
     */
    public static Path suiteFile(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Suite id must match " + SAFE_ID.pattern() + " — got: " + id);
        }
        var dir = localDir();
        var file = dir.resolve(id + ".json").normalize();
        if (!file.startsWith(dir)) {
            throw new IllegalArgumentException("Refusing to write outside " + LOCAL_DIR + ": " + file);
        }
        var appRoot = Play.applicationPath.toPath().normalize();
        for (var tracked : TRACKED_DIRS) {
            if (file.startsWith(appRoot.resolve(tracked).normalize())) {
                throw new IllegalArgumentException(
                        "Refusing to write a generated suite into tracked " + tracked
                                + " — it contains personal data and this repository is mirrored publicly");
            }
        }
        return file;
    }

    public static void ensureLocalDir() throws IOException {
        Files.createDirectories(localDir());
    }
}
