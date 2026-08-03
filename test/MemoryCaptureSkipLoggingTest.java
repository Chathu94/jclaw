import memory.MemoryAutoCapture;
import models.EventLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.EventLogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JCLAW-928: a capture that cannot resolve its context must say so. Exercises
 * {@code resolveExtractContext} directly — {@code captureAsync} returns early in test
 * mode, so the branch is unreachable through the async entry point.
 */
class MemoryCaptureSkipLoggingTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        EventLogger.clear();
    }

    private models.Agent agent() {
        var a = new models.Agent();
        a.name = "skip-log-agent";
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.save();
        return a;
    }

    private static long skipEvents() {
        EventLogger.flush();
        return EventLog.count("category = ?1 AND message LIKE ?2", "memory", "Auto-capture skipped:%");
    }

    @Test
    void missingConversationIsLoggedNotSilentlySwallowed() {
        var a = agent();

        var ctx = MemoryAutoCapture.resolveExtractContext(a, 999_999_999L, a.name);

        assertNull(ctx, "a capture with no conversation must not proceed");
        assertEquals(1, skipEvents(), "the reason must reach the event log — silence is the defect");
        EventLogger.flush();
        var ev = EventLog.find("category = ?1 AND message LIKE ?2 ORDER BY id DESC",
                "memory", "Auto-capture skipped:%").<EventLog>first();
        assertNotNull(ev);
        assertTrue(ev.message.contains("999999999"),
                "the message must name the conversation so the race is diagnosable, got: " + ev.message);
    }

    @Test
    void voiceChannelSkipStaysSilent() {
        var a = agent();
        var conv = new models.Conversation();
        conv.agent = a;
        conv.channelType = models.ChannelType.VOICE.value;
        conv.save();

        var ctx = MemoryAutoCapture.resolveExtractContext(a, conv.id, a.name);

        assertNull(ctx, "voice turns are deliberately not auto-captured (JCLAW-866)");
        assertEquals(0, skipEvents(),
                "an intentional per-turn skip must not log, or voice sessions flood the event log");
    }
}
