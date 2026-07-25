package jobs;

import models.ChannelType;
import models.Conversation;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.ConversationDeletionCascade;
import services.EventLogger;
import services.Tx;

import java.util.ArrayList;
import java.util.List;

/**
 * Drop every leftover voice conversation at boot (JCLAW-864).
 *
 * <p>Voice sessions are one-off: {@code VoiceController} creates a conversation
 * when the dialog opens and deletes it when the socket closes. A hard kill skips
 * that path, so rows can survive a crash — and rows created before JCLAW-864 were
 * never deleted at all.
 *
 * <p>The sweep needs no age cutoff or liveness heuristic, which is what makes it
 * worth having rather than a scheduled pruner. A voice conversation is only ever
 * reachable through its own live WebSocket, and no socket survives a restart, so
 * at this point in the lifecycle <em>every</em> surviving row is by definition an
 * orphan. "Delete all of them" is exact, not approximate.
 *
 * <p>Deliberately not folded into {@link BootConsistencyCheck}: that job returns
 * early when the scheduler hasn't bootstrapped, and cleaning up abandoned voice
 * rows has nothing to do with whether the scheduler came up.
 */
@OnApplicationStart
@NoTransaction
public class VoiceConversationSweepJob extends Job<Void> {

    /** The channel voice sessions own their conversations on (JCLAW-862). */
    private static final String VOICE_CHANNEL = ChannelType.VOICE.value;

    @Override
    public void doJob() {
        try {
            int deleted = Tx.run(() -> {
                // Raw list on purpose — Play's fetch() is untyped, so a typed
                // stream compiles and then fails at runtime.
                List<?> rows = Conversation.find("channelType = ?1", VOICE_CHANNEL).fetch();
                if (rows.isEmpty()) return 0;
                var ids = new ArrayList<Long>(rows.size());
                for (Object row : rows) ids.add(((Conversation) row).id);
                // Cascade, not a bare delete: messages, child conversations,
                // SubagentRuns and the Lucene entries all have to go with it.
                return ConversationDeletionCascade.deleteByIds(ids);
            });
            if (deleted > 0) {
                EventLogger.info("system",
                        "Voice sweep: removed %d abandoned voice conversation(s)".formatted(deleted));
            }
        } catch (RuntimeException e) {
            // Cleanup must never block startup — the rows are inert either way.
            EventLogger.warn("system", "Voice sweep failed: " + e.getMessage());
        }
    }
}
