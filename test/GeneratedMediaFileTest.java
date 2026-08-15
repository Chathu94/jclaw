import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.WorkspaceFiles;
import tools.GenerateAudioTool;
import tools.GenerateImageTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * The workspace-save path added to {@code generate_image} / {@code generate_audio}
 * (JCLAW-1057).
 *
 * <p>Both tools deliver their bytes as an inline chat attachment and return no path,
 * which is right for an interactive turn and unusable from a scheduled task. Asked to
 * "save it to a file, then send that file", an agent with no way to comply improvised:
 * a production run copied an unrelated local file and sent it onward as the generated
 * image. These tests pin the two properties that stop that recurring — the option exists
 * and is advertised, and it cannot be used to write outside the workspace.
 *
 * <p>Owns its workspace directory rather than borrowing a seeded agent's. The first
 * version of this test resolved the {@code main} agent from the database and passed in
 * isolation but failed in the full suite, where another test had wiped the row — the
 * path guard needs a directory, not a persisted agent.
 */
class GeneratedMediaFileTest extends UnitTest {

    private static final String AGENT = "generated-media-file-test";

    private Path root;

    @BeforeEach
    void createWorkspace() throws IOException {
        root = WorkspaceFiles.workspacePath(AGENT);
        Files.createDirectories(root);
    }

    @AfterEach
    void removeWorkspace() throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    /**
     * The model only reaches for an option it can see. The whole defect began with a tool
     * whose schema offered no way to obtain a file, so the description has to say both
     * that the option exists and that nothing is written without it.
     */
    @Test
    void bothToolsAdvertiseTheSaveOption() {
        for (var params : new Object[]{new GenerateImageTool().parameters(),
                new GenerateAudioTool().parameters()}) {
            var json = params.toString();
            assertTrue(json.contains("save_to"),
                    "the save option must be in the schema or the model cannot use it: " + json);
            assertTrue(json.contains("NO file exists on disk"),
                    "the schema must say a file is NOT written by default — the agent that "
                            + "assumed otherwise went hunting the filesystem: " + json);
        }
    }

    /**
     * A caller-supplied name reaches the write straight from model output, so traversal
     * must be rejected rather than sanitised into something surprising.
     */
    @Test
    void refusesToWriteOutsideTheWorkspace() {
        for (var escape : new String[]{"../escaped.png", "../../escaped.png",
                "sub/../../escaped.png"}) {
            assertThrows(SecurityException.class,
                    () -> WorkspaceFiles.acquireWorkspacePath(AGENT, escape),
                    "'" + escape + "' must not resolve to a writable path");
        }
        assertFalse(Files.exists(root.getParent().resolve("escaped.png")),
                "nothing may be written beside the workspace");
    }

    @Test
    void resolvesAPlainNameInsideTheWorkspace() throws Exception {
        var resolved = WorkspaceFiles.acquireWorkspacePath(AGENT, "tea-reminder.png");
        assertTrue(resolved.startsWith(root.toRealPath()),
                "a plain filename must land inside the workspace: " + resolved);
    }

    /** A nested name is legitimate as long as it stays inside; the parent is created. */
    @Test
    void writesIntoASubdirectoryOfTheWorkspace() throws Exception {
        var resolved = WorkspaceFiles.acquireWorkspacePath(AGENT, "media/tea.png");
        Files.createDirectories(resolved.getParent());
        Files.write(resolved, new byte[]{1, 2, 3});

        assertTrue(Files.isRegularFile(resolved));
        assertTrue(resolved.startsWith(root.toRealPath()));
    }
}
