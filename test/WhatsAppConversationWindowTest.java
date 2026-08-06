import models.Agent;
import models.WhatsAppBinding;
import models.WhatsAppConversationWindow;
import models.WhatsAppTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.Tx;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Unit coverage for {@link WhatsAppConversationWindow} (JCLAW-447) — the 24h
 * customer-service window store. Pins the upsert (insert-then-advance) and the
 * within-window predicate boundaries (no row, just-inside, just-outside).
 */
class WhatsAppConversationWindowTest extends UnitTest {

    private static final String PEER = "447900000001";

    /** JCLAW-984: binding_id is a foreign key now, so the fixture needs a binding that exists. */
    private Long binding;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        binding = Tx.run(() -> {
            var agent = new Agent();
            agent.name = "wa-window-agent-" + System.nanoTime();
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();

            var b = new WhatsAppBinding();
            b.agent = agent;
            b.transport = WhatsAppTransport.CLOUD_API;
            b.phoneNumberId = "PN-" + System.nanoTime();
            b.accessToken = "AT1";
            b.appSecret = "secret";
            b.verifyToken = "vt-" + System.nanoTime();
            b.enabled = true;
            b.save();
            return b.id;
        });
    }

    @Test
    void recordInboundInsertsThenAdvances() {
        var t0 = Instant.parse("2026-06-10T10:00:00Z");
        Tx.run(() -> WhatsAppConversationWindow.recordInbound(binding, PEER, t0));

        var first = Tx.run(() -> WhatsAppConversationWindow.findRow(binding, PEER));
        assertNotNull(first, "first inbound inserts a row");
        assertEquals(t0, first.lastUserMessageAt);

        var t1 = t0.plus(2, ChronoUnit.HOURS);
        Tx.run(() -> WhatsAppConversationWindow.recordInbound(binding, PEER, t1));

        // Still exactly one row for the (binding, peer) pair, timestamp advanced.
        long count = Tx.run(() -> WhatsAppConversationWindow.count(
                "binding.id = ?1 and peerId = ?2", binding, PEER));
        assertEquals(1, count, "upsert must not create a second row");
        var advanced = Tx.run(() -> WhatsAppConversationWindow.findRow(binding, PEER));
        assertEquals(t1, advanced.lastUserMessageAt, "timestamp advanced to the latest inbound");
    }

    @Test
    void noRowMeansOutsideWindow() {
        boolean within = Tx.run(() ->
                WhatsAppConversationWindow.isWithinWindow(binding, "never-messaged", Instant.now()));
        assertFalse(within, "a peer that never messaged is outside the window (template required)");
    }

    @Test
    void withinTwentyFourHoursIsInsideTheWindow() {
        var now = Instant.parse("2026-06-10T12:00:00Z");
        var twentyThreeHoursAgo = now.minus(23, ChronoUnit.HOURS);
        Tx.run(() -> WhatsAppConversationWindow.recordInbound(binding, PEER, twentyThreeHoursAgo));

        assertTrue(Tx.run(() -> WhatsAppConversationWindow.isWithinWindow(binding, PEER, now)),
                "an inbound 23h ago is inside the 24h window");
    }

    @Test
    void pastTwentyFourHoursIsOutsideTheWindow() {
        var now = Instant.parse("2026-06-10T12:00:00Z");
        var twentyFiveHoursAgo = now.minus(25, ChronoUnit.HOURS);
        Tx.run(() -> WhatsAppConversationWindow.recordInbound(binding, PEER, twentyFiveHoursAgo));

        assertFalse(Tx.run(() -> WhatsAppConversationWindow.isWithinWindow(binding, PEER, now)),
                "an inbound 25h ago has fallen out of the 24h window");
    }

    @Test
    void recordInboundIgnoresBlankPeerAndNulls() {
        Tx.run(() -> {
            WhatsAppConversationWindow.recordInbound(null, PEER, Instant.now());
            WhatsAppConversationWindow.recordInbound(binding, "", Instant.now());
            WhatsAppConversationWindow.recordInbound(binding, PEER, null);
        });
        // S1612: kept as a lambda — Tx.run is overloaded (Function0<T> + Runnable),
        // so a value-returning method reference is ambiguous here.
        long count = Tx.run(() -> WhatsAppConversationWindow.count());
        assertEquals(0, count, "null/blank inputs must not write a row");
    }
}
