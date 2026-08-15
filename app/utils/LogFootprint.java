package utils;

import play.Logger;
import play.Play;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Disk taken by {@code logs/} (JCLAW-1057).
 *
 * <p>Exists because the interesting number was invisible. The live file is capped by
 * the size-based rollover, so it is never the problem — the archives are, and until
 * JCLAW-1057 nothing pruned them: 70 daily files and 97 MB had accumulated on a
 * developer instance with no indication anywhere in the UI.
 *
 * <p>{@code totalBytes} covers the whole directory rather than just the two categories
 * below, so ad-hoc files (autotest runs, a hand-copied log) cannot hide from the total.
 *
 * @param liveBytes     size of the appender's current file, or -1 when absent
 * @param archiveCount  compressed rolled-over files
 * @param archiveBytes  their combined size
 * @param totalBytes    every file in the directory, whatever its name
 * @param retentionDays days of archives the configuration keeps
 */
public record LogFootprint(long liveBytes, int archiveCount, long archiveBytes,
                           long totalBytes, int retentionDays) {

    /**
     * Kept in step with the {@code IfLastModified age} in the log4j2 configs by
     * {@code LogRetentionConfigTest}, which asserts both are 30 days — the panel would
     * otherwise promise a retention window the appender does not implement.
     */
    public static final int RETENTION_DAYS = 30;

    /**
     * Delete every rolled-over archive, returning how many went and what they freed.
     *
     * <p>Archives only. The appender's current file is excluded because it is open —
     * deleting it on Linux or macOS leaves log4j2 writing to an unlinked inode, so the
     * instance keeps logging into a file nobody can read until the next rollover. Any
     * other {@code .log} in the directory is left alone too: this reclaims the archives
     * the panel counts, and deleting more than the panel reported would surprise.
     *
     * <p>A file that vanishes between the listing and the delete is not an error — the
     * daily rollover prunes on its own schedule and may be doing so right now.
     */
    public static Purged purgeArchives() {
        return purgeArchives(new File(Play.applicationPath, "logs"));
    }

    /**
     * Directory-taking overload so a test can exercise this against a temp directory
     * rather than the working tree's real archives. Public because Play compiles
     * {@code test/} into the default package, which cannot see package-private members.
     */
    public static Purged purgeArchives(File dir) {
        var files = dir.listFiles();
        if (files == null) return new Purged(0, 0);

        int deleted = 0;
        long freed = 0;
        for (var f : files) {
            if (!f.isFile() || !f.getName().toLowerCase(Locale.ROOT).endsWith(".gz")) continue;
            var length = f.length();
            try {
                Files.delete(f.toPath());
                deleted++;
                freed += length;
            } catch (IOException e) {
                // A file that vanished under the daily rollover is not an error; anything
                // else is worth naming rather than counting as "not deleted".
                if (f.exists()) {
                    Logger.warn("logs: could not delete %s (%s)", f.getName(), e.getMessage());
                }
            }
        }
        return new Purged(deleted, freed);
    }

    /** @param deleted archives removed @param freedBytes disk they occupied */
    public record Purged(int deleted, long freedBytes) {}

    /** Empty rather than absent when {@code logs/} does not exist: nothing logged is a real zero. */
    public static LogFootprint snapshot() {
        var dir = new File(Play.applicationPath, "logs");
        var files = dir.listFiles();
        if (files == null) return new LogFootprint(-1, 0, 0, 0, RETENTION_DAYS);

        long live = -1;
        int archiveCount = 0;
        long archiveBytes = 0;
        long total = 0;
        for (var f : files) {
            if (!f.isFile()) continue;
            var name = f.getName().toLowerCase(Locale.ROOT);
            var length = f.length();
            total += length;
            if (name.endsWith(".gz")) {
                archiveCount++;
                archiveBytes += length;
            }
            // The live file differs per mode — application.log in dev and prod,
            // application-test.log under the test config — so match the appender's
            // uncompressed output rather than one hardcoded name.
            else if (name.startsWith("application") && name.endsWith(".log")) {
                live = Math.max(live, length);
            }
        }
        return new LogFootprint(live, archiveCount, archiveBytes, total, RETENTION_DAYS);
    }
}
