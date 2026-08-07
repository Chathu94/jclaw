import channels.InboundMessage;
import channels.PendingAttachment;
import channels.TelegramMediaGroupBuffer;
import models.MessageAttachment;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link TelegramMediaGroupBuffer} (JCLAW-136). Bypasses the
 * scheduler via {@code flushForTest} so the tests don't rely on timing.
 */
class TelegramMediaGroupBufferTest extends UnitTest {

    @Test
    void passesThroughMessagesWithoutMediaGroupId() {
        var dispatched = new AtomicReference<InboundMessage>();
        var msg = new InboundMessage(
                "chat", "private", "hello", "user", "user",
                List.of(), null);

        TelegramMediaGroupBuffer.add(msg, dispatched::set);

        assertNotNull(dispatched.get(),
                "non-group messages must dispatch synchronously, not buffer");
        assertSame(msg, dispatched.get());
    }

    @Test
    void reassemblesPhotosInSameMediaGroup() {
        var dispatched = new AtomicReference<InboundMessage>();

        var first = new InboundMessage(
                "chat", "private", "album caption", "user", "user",
                List.of(new PendingAttachment(
                        "F1", null, "image/jpeg", 100L, MessageAttachment.KIND_IMAGE)),
                "group-A");
        var second = new InboundMessage(
                "chat", "private", "", "user", "user",
                List.of(new PendingAttachment(
                        "F2", null, "image/jpeg", 200L, MessageAttachment.KIND_IMAGE)),
                "group-A");
        var third = new InboundMessage(
                "chat", "private", "", "user", "user",
                List.of(new PendingAttachment(
                        "F3", null, "image/jpeg", 300L, MessageAttachment.KIND_IMAGE)),
                "group-A");

        TelegramMediaGroupBuffer.add(first, dispatched::set);
        TelegramMediaGroupBuffer.add(second, dispatched::set);
        TelegramMediaGroupBuffer.add(third, dispatched::set);

        assertNull(dispatched.get(),
                "while idle window is pending, nothing should dispatch yet");

        TelegramMediaGroupBuffer.flushForTest("group-A");

        var merged = dispatched.get();
        assertNotNull(merged, "flush must produce a merged dispatch");
        assertEquals(3, merged.attachments().size(),
                "merged inbound must carry all 3 photos");
        assertEquals("album caption", merged.text(),
                "merged inbound uses the first non-empty caption encountered");
        assertNull(merged.mediaGroupId(),
                "merged inbound drops the media_group_id — it's been consumed");
    }

    @Test
    void capturesCaptionFromWhicheverMessageHasIt() {
        // Telegram usually puts the caption on the first message in an album,
        // but operators have observed it occasionally arriving on a later one.
        // The buffer picks whichever arrives first with non-empty text.
        var dispatched = new AtomicReference<InboundMessage>();

        var noCaption = new InboundMessage(
                "chat", "private", "", "user", "user",
                List.of(new PendingAttachment(
                        "F1", null, "image/jpeg", 100L, MessageAttachment.KIND_IMAGE)),
                "group-B");
        var withCaption = new InboundMessage(
                "chat", "private", "describe these", "user", "user",
                List.of(new PendingAttachment(
                        "F2", null, "image/jpeg", 200L, MessageAttachment.KIND_IMAGE)),
                "group-B");

        TelegramMediaGroupBuffer.add(noCaption, dispatched::set);
        TelegramMediaGroupBuffer.add(withCaption, dispatched::set);
        TelegramMediaGroupBuffer.flushForTest("group-B");

        assertEquals("describe these", dispatched.get().text());
    }

    @Test
    void mergedAlbumPreservesEveryComponentExceptTextAndAttachments() {
        // Guards the JCLAW-397 regression: this lane once merged through a
        // 7-arg constructor that silently dropped the sender/message metadata.
        var dispatched = new AtomicReference<InboundMessage>();

        var first = new InboundMessage(
                "chat", "supergroup", "album caption", "user", "handle",
                "Display Name", true,
                List.of(new PendingAttachment(
                        "F1", null, "image/jpeg", 100L, MessageAttachment.KIND_IMAGE)),
                "group-E", 100, 7, "in reply to: hi");
        var second = new InboundMessage(
                "chat", "supergroup", "", "user", "handle",
                "Display Name", false,
                List.of(new PendingAttachment(
                        "F2", null, "image/jpeg", 200L, MessageAttachment.KIND_IMAGE)),
                "group-E", 101, 7, null);

        TelegramMediaGroupBuffer.add(first, dispatched::set);
        TelegramMediaGroupBuffer.add(second, dispatched::set);
        TelegramMediaGroupBuffer.flushForTest("group-E");

        var merged = dispatched.get();
        assertNotNull(merged, "flush must produce a merged dispatch");
        assertEquals("chat", merged.chatId());
        assertEquals("supergroup", merged.chatType());
        assertEquals("user", merged.fromId());
        assertEquals("handle", merged.fromUsername());
        assertEquals("Display Name", merged.fromDisplayName());
        assertTrue(merged.botMentioned(),
                "merged album keeps the FIRST piece's botMentioned flag");
        assertEquals(Integer.valueOf(100), merged.messageId(),
                "merged album keeps the FIRST piece's messageId");
        assertEquals(Integer.valueOf(7), merged.messageThreadId(),
                "merged album keeps the FIRST piece's messageThreadId");
        assertEquals("in reply to: hi", merged.replyContext(),
                "merged album keeps the FIRST piece's replyContext");
        assertNull(merged.mediaGroupId(),
                "merged album drops the media_group_id — it's been consumed");
        assertEquals("album caption", merged.text(), "text is the merged caption");
        assertEquals(2, merged.attachments().size(), "attachments are the merged album");
    }

    @Test
    void distinctGroupsDoNotInterfere() {
        var dispatchedA = new AtomicReference<InboundMessage>();
        var dispatchedB = new AtomicReference<InboundMessage>();

        var groupA = new InboundMessage(
                "chat", "private", "A-caption", "user", "user",
                List.of(new PendingAttachment(
                        "A1", null, "image/jpeg", 100L, MessageAttachment.KIND_IMAGE)),
                "group-C");
        var groupB = new InboundMessage(
                "chat", "private", "B-caption", "user", "user",
                List.of(new PendingAttachment(
                        "B1", null, "image/jpeg", 100L, MessageAttachment.KIND_IMAGE)),
                "group-D");

        TelegramMediaGroupBuffer.add(groupA, dispatchedA::set);
        TelegramMediaGroupBuffer.add(groupB, dispatchedB::set);
        TelegramMediaGroupBuffer.flushForTest("group-C");

        assertEquals("A-caption", dispatchedA.get().text(),
                "group C flush dispatches only its own bucket");
        assertNull(dispatchedB.get(),
                "group D remains buffered — distinct groups don't interfere");

        TelegramMediaGroupBuffer.flushForTest("group-D");
        assertEquals("B-caption", dispatchedB.get().text());
    }
}
