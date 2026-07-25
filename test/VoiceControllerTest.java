import controllers.VoiceController;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationService;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;

/**
 * Voice-mode WebSocket coverage. Two concerns, both testable without a socket —
 * Play's FunctionalTest can't perform a WS upgrade, so the wiring itself is
 * exercised manually.
 *
 * <ol>
 *   <li>CSWSH guard (JCLAW-791): the handshake's browser-set Origin must match
 *       our own host, else a cross-site page could open a cookie-authenticated
 *       voice socket and drive the agent as the victim.</li>
 *   <li>Per-session conversation (JCLAW-862): each session gets a fresh row, so
 *       reopening voice mode starts clean and the operator's typed chat is never
 *       touched.</li>
 * </ol>
 */
class VoiceControllerTest extends UnitTest {

    @Test
    void sameOriginIsAccepted() {
        assertTrue(VoiceController.originMatchesHost("http://localhost:9000", "localhost:9000"));
        assertTrue(VoiceController.originMatchesHost("https://jclaw.example.com", "jclaw.example.com"));
        // Scheme is ignored (http page → wss socket is same-origin); authority is what matters.
        assertTrue(VoiceController.originMatchesHost("https://HOST.example.com", "host.example.com"));
    }

    @Test
    void crossOriginIsRejected() {
        assertFalse(VoiceController.originMatchesHost("https://evil.example.com", "jclaw.example.com"));
        // Same host, different port is a different origin.
        assertFalse(VoiceController.originMatchesHost("http://localhost:3000", "localhost:9000"));
    }

    @Test
    void missingOrMalformedOriginIsRejected() {
        assertFalse(VoiceController.originMatchesHost(null, "localhost:9000"));
        assertFalse(VoiceController.originMatchesHost("", "localhost:9000"));
        assertFalse(VoiceController.originMatchesHost("   ", "localhost:9000"));
        assertFalse(VoiceController.originMatchesHost("not a url", "localhost:9000"));
        assertFalse(VoiceController.originMatchesHost("http://localhost:9000", null));
        assertFalse(VoiceController.originMatchesHost("http://localhost:9000", ""));
    }

    // =====================
    // Per-session conversation — JCLAW-862
    // =====================

    /** Invoke the private session-binding factory the init frame uses. */
    private static void openVoiceSession(Agent agent, String username) throws Exception {
        Method m = VoiceController.class.getDeclaredMethod("newSessionBinding", Agent.class, String.class);
        m.setAccessible(true);
        m.invoke(null, agent, username);
    }

    private static Agent freshAgent() {
        // play1 runs test classes concurrently, so the name must not collide.
        return AgentService.create("voice-session-" + System.nanoTime(), "openrouter", "gpt-4.1");
    }

    @Test
    void eachVoiceSessionGetsItsOwnConversation() throws Exception {
        // The reset the operator asked for is achieved by NOT reusing a row:
        // opening voice mode twice must leave two conversations, so the second
        // session starts with no history rather than resuming the first.
        var agent = freshAgent();
        openVoiceSession(agent, "admin");
        openVoiceSession(agent, "admin");

        long voiceConvs = Conversation.count("agent = ?1 and channelType = ?2", agent, "voice");
        assertEquals(2L, voiceConvs,
                "two sessions must produce two conversations, not one reused row");
    }

    @Test
    void voiceSessionsDoNotTouchTheWebConversation() throws Exception {
        // The whole point of giving voice its own row: the operator's typed chat
        // must survive untouched. Resetting by clearing the shared conversation
        // would have deleted it.
        var agent = freshAgent();
        var web = ConversationService.findOrCreate(agent, "web", "admin");
        var webId = web.id;

        openVoiceSession(agent, "admin");
        openVoiceSession(agent, "admin");

        var stillThere = ConversationService.findById(webId);
        assertNotNull(stillThere, "the web conversation must survive a voice session");
        assertEquals("web", stillThere.channelType,
                "the web conversation must not be re-channelled by voice");
        assertEquals(1L, Conversation.count("agent = ?1 and channelType = ?2", agent, "web"),
                "voice must not create or replace web conversations");
    }

    @Test
    void sessionConversationsAreIndividuallyAddressable() throws Exception {
        // Peer ids carry a random suffix so two sessions never collide on the same
        // key — otherwise findByAgentChannelPeer would be left choosing between
        // rows that look identical.
        var agent = freshAgent();
        openVoiceSession(agent, "admin");
        openVoiceSession(agent, "admin");

        // Deliberately NOT streamed as Conversation: Play's find().fetch() hands
        // back a raw list, so a typed stream compiles and then fails at runtime.
        // Cast each element explicitly instead.
        List<?> rows = Conversation.find("agent = ?1 and channelType = ?2", agent, "voice").fetch();
        var peers = new HashSet<String>();
        for (Object row : rows) peers.add(((Conversation) row).peerId);

        assertEquals(2, peers.size(), "each session's conversation needs a distinct peer id");
    }
}
