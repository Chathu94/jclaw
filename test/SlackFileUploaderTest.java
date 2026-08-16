import channels.SlackFileUploader;
import channels.SlackFileUploader.UploadUrl;
import channels.SlackFileUploader.Uploader;
import channels.SlackWebApi;
import channels.SlackWebApi.ChannelInfo;
import channels.SlackWebApi.ChannelLister;
import channels.SlackWebApi.ChannelLookup;
import channels.SlackWebApi.ChannelResolution;
import channels.SlackWebApi.DmOpener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link SlackFileUploader} (JCLAW-345). Exercises the 3-step
 * external-upload orchestration (getUploadURLExternal → POST bytes →
 * completeUploadExternal), the target resolution it now delegates to
 * {@link SlackWebApi#resolveChannel} (JCLAW-1060), and the short-circuits — against an
 * injected {@link Uploader} (swapped via the package-private {@code IMPL} seam by
 * reflection, mirroring {@code SlackFileDownloaderTest}) so nothing hits the network.
 *
 * <p>Because the DM open is now {@code SlackWebApi}'s single seam rather than the
 * uploader's own, this class shares it with {@code SlackWebApiResolveTest} and holds
 * {@link SlackWebApiTestSync} for the duration of each test. Targets use realistic Slack
 * ids: {@code resolveChannel} requires 7+ characters to read a value as a literal id, so
 * the old {@code C123} / {@code U999} fixtures would now resolve as channel names.
 */
class SlackFileUploaderTest extends UnitTest {

    private static final Field IMPL_FIELD;
    private static final Field DM_OPENER_FIELD;
    private static final Field LISTER_FIELD;
    static {
        try {
            IMPL_FIELD = SlackFileUploader.class.getDeclaredField("IMPL");
            IMPL_FIELD.setAccessible(true);
            DM_OPENER_FIELD = SlackWebApi.class.getDeclaredField("dmOpener");
            DM_OPENER_FIELD.setAccessible(true);
            LISTER_FIELD = SlackWebApi.class.getDeclaredField("channelLister");
            LISTER_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    static final class FakeUploader implements Uploader {
        final List<String> getUploadFor = new ArrayList<>(); // filenames
        final List<String> postedTo = new ArrayList<>();      // upload urls
        final List<String> completedTo = new ArrayList<>();   // channel ids
        UploadUrl uploadUrl = new UploadUrl("https://files.slack.com/upload/v1/ABC", "F1");
        boolean postOk = true;
        boolean completeOk = true;

        @Override public UploadUrl getUploadUrl(String botToken, String filename, long length) {
            getUploadFor.add(filename);
            return uploadUrl;
        }
        @Override public boolean postBytes(String url, File file, String contentType) {
            postedTo.add(url);
            return postOk;
        }
        @Override public boolean completeUpload(String botToken, String fileId, String title,
                                                String channelId, String comment, String threadTs) {
            completedTo.add(channelId);
            return completeOk;
        }
    }

    private Object originalImpl;
    private Object originalDmOpener;
    private Object originalLister;
    private FakeUploader fake;
    private final List<String> openDmFor = new ArrayList<>();
    private final List<String> listedNames = new ArrayList<>();
    private String dmChannel = "D0UPLOADDM";
    private File tmp;

    @BeforeEach
    void setup() throws Exception {
        SlackWebApiTestSync.acquire();
        originalImpl = IMPL_FIELD.get(null);
        originalDmOpener = DM_OPENER_FIELD.get(null);
        originalLister = LISTER_FIELD.get(null);
        fake = new FakeUploader();
        IMPL_FIELD.set(null, fake);
        openDmFor.clear();
        listedNames.clear();
        DmOpener opener = (botToken, userId) -> {
            openDmFor.add(userId);
            return new ChannelResolution(dmChannel, dmChannel == null ? "dm_open_failed" : null);
        };
        DM_OPENER_FIELD.set(null, opener);
        ChannelLister lister = (botToken, name) -> {
            listedNames.add(name);
            return new ChannelLookup(new ChannelInfo("C0NAMED123", true), null);
        };
        LISTER_FIELD.set(null, lister);
        tmp = Files.createTempFile("sk-up", ".png").toFile();
        Files.writeString(tmp.toPath(), "fake-bytes");
    }

    @AfterEach
    void teardown() throws Exception {
        IMPL_FIELD.set(null, originalImpl);
        DM_OPENER_FIELD.set(null, originalDmOpener);
        LISTER_FIELD.set(null, originalLister);
        if (tmp != null) {
            Files.deleteIfExists(tmp.toPath());
        }
        SlackWebApiTestSync.release();
    }

    @Test
    void channelTargetUploadsWithoutOpeningDm() {
        var ok = SlackFileUploader.upload("xoxb-up-chan", "C0CHAN123", "1700.0", tmp, "chart.png", "the chart");
        assertTrue(ok, "3-step upload to a channel must succeed");
        assertTrue(openDmFor.isEmpty(), "a C-channel target must not open a DM");
        assertEquals(List.of("chart.png"), fake.getUploadFor);
        assertEquals(List.of("https://files.slack.com/upload/v1/ABC"), fake.postedTo);
        assertEquals(List.of("C0CHAN123"), fake.completedTo);
    }

    @Test
    void userTargetResolvesToDmChannel() {
        var ok = SlackFileUploader.upload("xoxb-up-user", "U0USER123", null, tmp, "x.png", null);
        assertTrue(ok);
        assertEquals(List.of("U0USER123"), openDmFor, "a U-user target opens a DM first");
        assertEquals(List.of("D0UPLOADDM"), fake.completedTo, "upload shares to the resolved DM channel");
    }

    @Test
    void dmChannelCachedPerToken() {
        SlackFileUploader.upload("xoxb-up-cache", "U0CACHED1", null, tmp, "a.png", null);
        SlackFileUploader.upload("xoxb-up-cache", "U0CACHED1", null, tmp, "b.png", null);
        assertEquals(1, openDmFor.size(), "conversations.open is called once for a cached (token, user)");
    }

    @Test
    void dmOpenedByATextSendIsReusedByAnUpload() {
        // JCLAW-1060: the point of routing uploads through SlackWebApi — a text send and a
        // file upload to the same owner now share one cache entry instead of opening twice.
        var textSend = SlackWebApi.resolveChannel("xoxb-up-shared", "U0SHARED1");
        assertEquals("D0UPLOADDM", textSend.channelId());

        SlackFileUploader.upload("xoxb-up-shared", "U0SHARED1", null, tmp, "x.png", null);

        assertEquals(1, openDmFor.size(),
                "one conversations.open across both the text send and the upload");
        assertEquals(List.of("D0UPLOADDM"), fake.completedTo);
    }

    @Test
    void nameTargetResolvesToChannelIdInsteadOfPassingThroughRaw() {
        // Before JCLAW-1060 the uploader forwarded anything non-U verbatim, so a #name
        // reached files.completeUploadExternal — which only accepts an id — and failed.
        var ok = SlackFileUploader.upload("xoxb-up-name", "#Daily-Briefings", null, tmp, "x.png", null);
        assertTrue(ok);
        assertEquals(List.of("daily-briefings"), listedNames, "# stripped and lowercased before lookup");
        assertEquals(List.of("C0NAMED123"), fake.completedTo, "the upload shares to the resolved id");
    }

    @Test
    void unopenableDmAbortsTheUploadBeforeReservingASlot() {
        dmChannel = null; // conversations.open refused
        var ok = SlackFileUploader.upload("xoxb-up-nodm", "U0NODM123", null, tmp, "x.png", null);
        assertFalse(ok);
        assertTrue(fake.getUploadFor.isEmpty(),
                "an unresolvable target must not reserve an upload slot");
    }

    @Test
    void getUploadUrlFailureShortCircuits() {
        fake.uploadUrl = null;
        var ok = SlackFileUploader.upload("xoxb-up-noslot", "C0CHAN123", null, tmp, "x.png", null);
        assertFalse(ok);
        assertTrue(fake.postedTo.isEmpty(), "no POST when the upload slot couldn't be reserved");
    }

    @Test
    void postFailureShortCircuits() {
        fake.postOk = false;
        var ok = SlackFileUploader.upload("xoxb-up-nopost", "C0CHAN123", null, tmp, "x.png", null);
        assertFalse(ok);
        assertTrue(fake.completedTo.isEmpty(), "no completeUpload when the byte POST failed");
    }

    @Test
    void completeFailureReturnsFalse() {
        fake.completeOk = false;
        assertFalse(SlackFileUploader.upload("xoxb-up-nocomplete", "C0CHAN123", null, tmp, "x.png", null));
    }

    @Test
    void missingFileReturnsFalse() {
        assertFalse(SlackFileUploader.upload("xoxb-up-nofile", "C0CHAN123", null,
                new File("/no/such/file.png"), "x.png", null));
    }
}
