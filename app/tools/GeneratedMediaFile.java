package tools;

import models.Agent;
import services.WorkspaceFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Write generated media to the agent's workspace and hand the model a real path
 * (JCLAW-1057).
 *
 * <p>{@code generate_image} and {@code generate_audio} deliver their bytes out-of-band as
 * an inline chat attachment and tell the model not to link it — correct for an
 * interactive turn, where the UI renders the attachment. A scheduled task has no chat
 * surface, so the model receives no path and no file exists.
 *
 * <p>That gap is not merely inconvenient. A task instructed to "save it to a file, then
 * send that file" cannot comply, and an agent asked to do the impossible improvises: a
 * production task was observed running {@code cp $(find /tmp -name '*.png' -mmin -5 |
 * head -1)} and, on another turn, copying an unrelated screenshot out of a local cache
 * directory — then sending it to a WhatsApp contact as though it were the generated
 * image. The fix is to make the honest path available, so the tool can hand back
 * somewhere real instead of the model inventing one.
 */
public final class GeneratedMediaFile {

    private GeneratedMediaFile() {}

    /**
     * Write {@code bytes} to {@code relativePath} inside {@code agent}'s workspace.
     *
     * <p>Confined by {@link WorkspaceFiles#acquireWorkspacePath}, which double-resolves
     * and throws on any escape — a caller-supplied name reaches this method straight from
     * model output, so {@code ../} must not be able to place a file outside the
     * workspace.
     *
     * @return the absolute path written, for the model to reference
     * @throws IllegalArgumentException when the path escapes the workspace or the write
     *                                  fails; the caller surfaces the message as tool text
     */
    static String write(Agent agent, String relativePath, byte[] bytes) {
        return write(agent.name, relativePath, bytes);
    }

    /**
     * Name-taking form, for callers that have no {@code Agent} instance to hand — the
     * video job completes on a background poller, in another package and long after the
     * submitting turn ended, which is why this one is public.
     */
    public static String write(String agentName, String relativePath, byte[] bytes) {
        try {
            var target = WorkspaceFiles.acquireWorkspacePath(agentName, relativePath);
            var parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            // Write-then-rename, so the file never exists in a half-written state. A
            // caller waiting on an async generation has no completion signal other than
            // the file appearing, and `test -f` on a partial download is exactly the
            // race that would send a truncated clip.
            var tmp = target.resolveSibling(target.getFileName() + ".partial");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return target.toAbsolutePath().toString();
        } catch (SecurityException e) {
            throw new IllegalArgumentException(
                    "'" + relativePath + "' resolves outside the agent workspace.", e);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "could not write '" + relativePath + "': " + e.getMessage(), e);
        }
    }
}
