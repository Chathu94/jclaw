import channels.SlackWebApi;
import channels.SlackWebApi.ChannelInfo;
import channels.SlackWebApi.ChannelLister;
import channels.SlackWebApi.ChannelLookup;
import channels.SlackWebApi.ChannelResolution;
import channels.SlackWebApi.DmOpener;
import channels.SlackWebApi.ScopeProber;
import channels.SlackWebApi.SlackReach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JCLAW-454/455/458 unit tests for the shared Slack channel lookup: {@link SlackWebApi#resolveChannel}
 * / {@code resolveChannelId} (literal-id passthrough, {@code #}-strip + lowercasing, bare-name lookup,
 * per-(token,name) caching, not-found, and the JCLAW-458 {@code missing_scope} surfacing),
 * {@link SlackWebApi#probeChannel} reachability classification,
 * {@link SlackWebApi#deliveryScopeWarning}, and the JCLAW-1018 {@code U…}→DM resolution. The
 * {@code conversations.list} lookup, scope probe and {@code conversations.open} are swapped via the
 * package-private {@code channelLister} / {@code scopeProber} / {@code dmOpener} seams (reflection),
 * mirroring {@code SlackFileUploaderTest}, so nothing hits the network. Probe/resolve tests use a
 * distinct bot token each, since {@code probeChannel}/resolution keep process-wide caches.
 */
class SlackWebApiResolveTest extends UnitTest {

    private static final Field LISTER_FIELD;
    private static final Field PROBER_FIELD;
    private static final Field DM_OPENER_FIELD;
    static {
        try {
            LISTER_FIELD = SlackWebApi.class.getDeclaredField("channelLister");
            LISTER_FIELD.setAccessible(true);
            PROBER_FIELD = SlackWebApi.class.getDeclaredField("scopeProber");
            PROBER_FIELD.setAccessible(true);
            DM_OPENER_FIELD = SlackWebApi.class.getDeclaredField("dmOpener");
            DM_OPENER_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    static final class CountingLister implements ChannelLister {
        final AtomicInteger calls = new AtomicInteger();
        String id = "C0RESOLVED"; // matched channel id; null = not found
        boolean member = true;    // membership reported for a matched channel
        String error;             // non-null = conversations.list API error (e.g. missing_scope)
        String lastName;

        @Override
        public ChannelLookup lookup(String botToken, String name) {
            calls.incrementAndGet();
            lastName = name;
            if (error != null) return new ChannelLookup(null, error);
            return new ChannelLookup(id == null ? null : new ChannelInfo(id, member), null);
        }
    }

    /** JCLAW-1018 counterpart to {@link CountingLister} for the {@code conversations.open} seam. */
    static final class CountingDmOpener implements DmOpener {
        final AtomicInteger calls = new AtomicInteger();
        String id = "D0OWNERDM"; // opened DM channel id; null = open refused
        String error;            // mapped failure code when id is null
        String lastUserId;

        @Override
        public ChannelResolution open(String botToken, String userId) {
            calls.incrementAndGet();
            lastUserId = userId;
            return new ChannelResolution(id, id == null ? error : null);
        }
    }

    private Object originalLister;
    private Object originalProber;
    private Object originalDmOpener;
    private CountingLister fake;
    private CountingDmOpener fakeDm;
    private String scopeProbeError; // what the swapped SCOPE_PROBER returns

    @BeforeEach
    void setup() throws Exception {
        SlackWebApiTestSync.acquire();
        originalLister = LISTER_FIELD.get(null);
        originalProber = PROBER_FIELD.get(null);
        originalDmOpener = DM_OPENER_FIELD.get(null);
        fake = new CountingLister();
        LISTER_FIELD.set(null, fake);
        fakeDm = new CountingDmOpener();
        DM_OPENER_FIELD.set(null, fakeDm);
        scopeProbeError = null;
        ScopeProber prober = botToken -> scopeProbeError;
        PROBER_FIELD.set(null, prober);
    }

    @AfterEach
    void teardown() throws Exception {
        LISTER_FIELD.set(null, originalLister);
        PROBER_FIELD.set(null, originalProber);
        DM_OPENER_FIELD.set(null, originalDmOpener);
        SlackWebApiTestSync.release();
    }

    @Test
    void literalChannelIdPassesThroughWithoutLookup() {
        assertEquals("C0ABC123", SlackWebApi.resolveChannelId("xoxb-id", "C0ABC123"));
        assertEquals(0, fake.calls.get(), "a literal channel id must not call conversations.list");
    }

    @Test
    void hashPrefixedNameIsStrippedAndLowercasedThenResolved() {
        assertEquals("C0RESOLVED", SlackWebApi.resolveChannelId("xoxb-hash", "#Daily-Briefings"));
        assertEquals("daily-briefings", fake.lastName,
                "leading # stripped and name lowercased before lookup");
    }

    @Test
    void bareNameIsResolved() {
        assertEquals("C0RESOLVED", SlackWebApi.resolveChannelId("xoxb-bare", "announcements"));
        assertEquals("announcements", fake.lastName);
    }

    @Test
    void resolutionIsCachedPerTokenAndName() {
        SlackWebApi.resolveChannelId("xoxb-cache", "ops");
        SlackWebApi.resolveChannelId("xoxb-cache", "ops");
        assertEquals(1, fake.calls.get(),
                "conversations.list is called once for a cached (token, name)");
    }

    @Test
    void unknownChannelResolvesToNull() {
        fake.id = null; // lister finds nothing
        assertNull(SlackWebApi.resolveChannelId("xoxb-missing", "ghost-channel"));
    }

    @Test
    void blankAndNullInputsResolveToNullWithoutLookup() {
        assertNull(SlackWebApi.resolveChannelId("xoxb", null));
        assertNull(SlackWebApi.resolveChannelId("xoxb", "   "));
        assertNull(SlackWebApi.resolveChannelId(null, "general"));
        assertNull(SlackWebApi.resolveChannelId("", "general"));
        assertEquals(0, fake.calls.get(), "blank/null inputs short-circuit before any lookup");
    }

    // ──────── JCLAW-455: reachability probe ────────

    @Test
    void probeReachableWhenBotIsMember() {
        fake.id = "C0OPS";
        fake.member = true;
        var r = SlackWebApi.probeChannel("xoxb-probe-reach", "#ops");
        assertEquals(SlackReach.REACHABLE, r.status());
        assertNull(r.advisory(), "a reachable channel needs no advisory");
        assertFalse(r.needsAttention());
    }

    @Test
    void probePublicNotMemberWhenFoundButNotMember() {
        fake.id = "C0ANN";
        fake.member = false;
        var r = SlackWebApi.probeChannel("xoxb-probe-pubnm", "announcements");
        assertEquals(SlackReach.PUBLIC_NOT_MEMBER, r.status());
        assertNotNull(r.advisory());
        assertTrue(r.advisory().contains("chat:write.public"), r.advisory());
        assertTrue(r.needsAttention());
    }

    @Test
    void probeUnresolvedWhenNotFoundAdvisesBothCauses() {
        fake.id = null; // not found in conversations.list
        var r = SlackWebApi.probeChannel("xoxb-probe-unres", "#daily-briefings");
        assertEquals(SlackReach.UNRESOLVED, r.status());
        assertNotNull(r.advisory());
        assertTrue(r.advisory().contains("private") && r.advisory().contains("public"),
                "an unresolved channel can't be classified, so the advisory names both causes: " + r.advisory());
        assertTrue(r.advisory().contains("#daily-briefings"), r.advisory());
    }

    @Test
    void probeLiteralIdIsUnknownWithoutLookup() {
        var r = SlackWebApi.probeChannel("xoxb-probe-id", "C0LITERAL1");
        assertEquals(SlackReach.UNKNOWN, r.status());
        assertNull(r.advisory());
        assertEquals(0, fake.calls.get(), "a literal channel id is not probed via conversations.list");
    }

    @Test
    void probeIsCachedPerTokenAndName() {
        fake.id = "C0CACHE";
        fake.member = false;
        SlackWebApi.probeChannel("xoxb-probe-cache", "ops");
        SlackWebApi.probeChannel("xoxb-probe-cache", "ops");
        assertEquals(1, fake.calls.get(), "the probe verdict is cached per (token, name) within the TTL");
    }

    // ──────── JCLAW-458: missing_scope surfacing + bind-time scope warning ────────

    @Test
    void resolveChannelSurfacesMissingScope() {
        fake.error = "missing_scope";
        var r = SlackWebApi.resolveChannel("xoxb-ms-res", "#daily-briefings");
        assertNull(r.channelId());
        assertEquals("missing_scope", r.error(),
                "a missing_scope list error must surface, not be flattened to channel_not_found");
    }

    @Test
    void resolveChannelNotFoundReportsChannelNotFound() {
        fake.id = null; // found nothing, no API error
        var r = SlackWebApi.resolveChannel("xoxb-nf-res", "#ghost");
        assertNull(r.channelId());
        assertEquals("channel_not_found", r.error());
    }

    @Test
    void probeMissingScopeNamesTheScope() {
        fake.error = "missing_scope";
        var r = SlackWebApi.probeChannel("xoxb-ms-probe", "#daily-briefings");
        assertEquals(SlackReach.MISSING_SCOPE, r.status());
        assertNotNull(r.advisory());
        assertTrue(r.advisory().contains("groups:read"), r.advisory());
        assertTrue(r.needsAttention());
    }

    @Test
    void deliveryScopeWarningWhenListReturnsMissingScope() {
        scopeProbeError = "missing_scope";
        var w = SlackWebApi.deliveryScopeWarning("xoxb-noscope");
        assertNotNull(w, "a missing_scope probe must produce a warning");
        assertTrue(w.contains("groups:read"), w);
    }

    @Test
    void deliveryScopeWarningNullWhenListOk() {
        scopeProbeError = null;
        assertNull(SlackWebApi.deliveryScopeWarning("xoxb-ok"));
    }

    @Test
    void deliveryScopeWarningNullForBlankToken() {
        scopeProbeError = "missing_scope";
        assertNull(SlackWebApi.deliveryScopeWarning(""));
        assertNull(SlackWebApi.deliveryScopeWarning(null));
    }

    // ──────── JCLAW-1018: U… user ids resolve to a DM channel ────────

    @Test
    void userIdResolvesToDmChannel() {
        // The owner-fallback destination MessageTool hands down is a U… user id, which
        // chat.postMessage can't address — it must come back as the D… DM channel.
        assertEquals("D0OWNERDM", SlackWebApi.resolveChannelId("xoxb-dm", "UA6PAPH8U"));
        assertEquals("UA6PAPH8U", fakeDm.lastUserId);
        assertEquals(0, fake.calls.get(), "a user id must not be looked up as a channel name");
    }

    @Test
    void dmResolutionIsCachedPerTokenAndUser() {
        SlackWebApi.resolveChannelId("xoxb-dm-cache", "UA6PAPH8U");
        SlackWebApi.resolveChannelId("xoxb-dm-cache", "UA6PAPH8U");
        assertEquals(1, fakeDm.calls.get(), "conversations.open runs once for a cached (token, user)");
    }

    @Test
    void unopenableDmSurfacesTheScopeGapNotChannelNotFound() {
        fakeDm.id = null;
        fakeDm.error = "dm_missing_scope";
        var r = SlackWebApi.resolveChannel("xoxb-dm-noscope", "UA6PAPH8U");
        assertNull(r.channelId());
        assertEquals("dm_missing_scope", r.error(),
                "an im:write gap must not be reported as channel_not_found");
    }

    @Test
    void unopenableDmSurfacesOpenFailure() {
        fakeDm.id = null;
        fakeDm.error = "dm_open_failed";
        var r = SlackWebApi.resolveChannel("xoxb-dm-fail", "UA6PAPH8U");
        assertNull(r.channelId());
        assertEquals("dm_open_failed", r.error());
    }

    @Test
    void hashPrefixedNameIsNeverTreatedAsAUserId() {
        // The documented escape hatch for the CHANNEL_ID/USER_ID shape ambiguity: a channel
        // literally named "updates" reaches the name path when written #UPDATES.
        assertEquals("C0RESOLVED", SlackWebApi.resolveChannelId("xoxb-dm-hash", "#UPDATES"));
        assertEquals("updates", fake.lastName);
        assertEquals(0, fakeDm.calls.get(), "a #-prefixed target must not open a DM");
    }

    @Test
    void probeUserIdIsUnknownWithoutLookup() {
        var r = SlackWebApi.probeChannel("xoxb-dm-probe", "UA6PAPH8U");
        assertEquals(SlackReach.UNKNOWN, r.status());
        assertNull(r.advisory(), "a DM target must not draw a channel-membership advisory");
        assertEquals(0, fake.calls.get());
    }
}
