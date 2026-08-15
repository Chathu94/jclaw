import models.Conversation;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.videogen.VideoDelivery;

/**
 * Which conversations a finished clip is pushed to (JCLAW-1057).
 *
 * <p>A verified 5 MB clip generated from Telegram was stored on the message and never
 * sent: video is the one generator whose bytes arrive after the reply has gone, and
 * nothing forwarded them. Web chat masked it, because its UI polls the attachment into
 * place on its own.
 *
 * <p>What is asserted here is the routing decision, not the transport — the guards that
 * decide whether a send is even attempted. Actually sending needs a live channel with
 * credentials, which a unit test has no business standing up.
 */
class VideoDeliveryTest extends UnitTest {

    private Conversation conversation(String channelType, String peerId) {
        var c = new Conversation();
        c.channelType = channelType;
        c.peerId = peerId;
        return c;
    }

    /** A task-submitted job has no conversation at all. */
    @Test
    void skipsAJobWithNoConversation() {
        assertFalse(VideoDelivery.send(null, new byte[]{1}, "a prompt"));
    }

    /**
     * Web is deliberately excluded, not overlooked: its UI polls the message and renders
     * the clip once the attachment fills, so a push would duplicate it.
     */
    @Test
    void skipsWebBecauseItsUiCollectsTheClipItself() {
        assertFalse(VideoDelivery.send(conversation("web", "peer-1"), new byte[]{1}, "a prompt"));
    }

    /** Without a peer there is nowhere to send, and the channel call would throw. */
    @Test
    void skipsAConversationWithNoPeer() {
        assertFalse(VideoDelivery.send(conversation("telegram", null), new byte[]{1}, "p"));
        assertFalse(VideoDelivery.send(conversation("telegram", "  "), new byte[]{1}, "p"));
    }

    @Test
    void skipsAConversationWithNoChannelType() {
        assertFalse(VideoDelivery.send(conversation(null, "peer-1"), new byte[]{1}, "p"));
    }

    /**
     * Delivery is best-effort by contract. A channel that cannot be resolved, or refuses,
     * must return false rather than throw — the clip is already stored on the message, and
     * an exception here would surface as a failed job for a generation that succeeded.
     */
    @Test
    void neverThrowsWhenTheChannelCannotBeResolved() {
        assertDoesNotThrow(() ->
                VideoDelivery.send(conversation("telegram", "no-such-peer"), new byte[]{1}, "p"));
    }
}
