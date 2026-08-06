import models.Agent;
import models.Conversation;
import models.VideoGenerationJob;
import models.WhatsAppBinding;
import models.WhatsAppConversationWindow;
import models.WhatsAppTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.DB;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AttachmentService;
import services.ConversationService;

import java.sql.ResultSet;
import java.time.Instant;

/**
 * JCLAW-984: the two tables that referenced their parents through plain id columns now do so
 * through foreign keys, so their rows cannot outlive what they describe.
 *
 * <p>Asserts against the schema Hibernate builds from the entities ({@code jpa.ddl=create} in
 * the test lane), which is what makes these cascades a property of the mapping rather than of
 * the boot migration — {@code OrphanFkMigratorTest} covers the migration path for a database
 * that predates them.
 */
class UnconstrainedParentRefCascadeTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    // ==================== video_generation_job ====================

    @Test
    void deletingAnAgentTakesItsVideoJobsWithIt() {
        var agent = agent("vgj-agent");
        var bystander = agent("vgj-bystander");
        job(agent, null);
        job(agent, null);
        job(bystander, null);
        JPA.em().flush();

        var agentId = agent.id;
        agent.delete();
        JPA.em().flush();
        JPA.em().clear();

        assertEquals(0, VideoGenerationJob.count("agent.id = ?1", agentId),
                "the database cascade removes the deleted agent's jobs");
        assertEquals(1, VideoGenerationJob.count(),
                "another agent's job is untouched");
    }

    @Test
    void deletingAConversationTakesItsVideoJobsWithIt() {
        var agent = agent("vgj-conv-agent");
        var conversation = ConversationService.create(agent, "web", "u-vgj");
        job(agent, conversation);
        JPA.em().flush();

        var conversationId = conversation.id;
        conversation.delete();
        JPA.em().flush();
        JPA.em().clear();

        assertEquals(0, VideoGenerationJob.count("conversation.id = ?1", conversationId));
    }

    @Test
    void deletingTheResultAttachmentLeavesTheJobRecordIntact() {
        // The attachment is the job's result, not its owner, and it stays an unconstrained id
        // (see VideoGenerationJob#resultAttachmentId). What matters is that losing the video
        // does not cost the record that the job ran, nor block the delete.
        var agent = agent("vgj-att-agent");
        var conversation = ConversationService.create(agent, "web", "u-att");
        var job = job(agent, conversation);
        var message = ConversationService.appendAssistantMessage(conversation, "here it is", null);
        // The production factory rather than a hand-built row: it is what actually links a
        // finished video to its job, so the fixture cannot drift from the real shape.
        var attachment = AttachmentService.createGeneratedVideoPlaceholder(agent, message, job.id, null);
        job.resultAttachmentId = attachment.id;
        job.save();
        JPA.em().flush();

        var jobId = job.id;
        attachment.delete();
        JPA.em().flush();
        JPA.em().clear();

        VideoGenerationJob reloaded = VideoGenerationJob.findById(jobId);
        assertNotNull(reloaded, "the job record survives losing its result");
        assertNotNull(reloaded.agent, "and keeps the owners that are constrained");
    }

    // ==================== whatsapp_conversation_window ====================

    @Test
    void deletingAWhatsAppBindingTakesItsWindowsWithIt() {
        var binding = binding("wa-cascade-agent", "PN-1");
        var bystander = binding("wa-bystander-agent", "PN-2");
        WhatsAppConversationWindow.recordInbound(binding.id, "15551230000", Instant.now());
        WhatsAppConversationWindow.recordInbound(bystander.id, "15559990000", Instant.now());
        JPA.em().flush();
        assertEquals(2, WhatsAppConversationWindow.count(), "test premise: both windows exist");

        var bindingId = binding.id;
        binding.delete();
        JPA.em().flush();
        JPA.em().clear();

        assertEquals(0, WhatsAppConversationWindow.count("binding.id = ?1", bindingId));
        assertEquals(1, WhatsAppConversationWindow.count(),
                "another binding's window is untouched");
    }

    @Test
    void aWindowIsNotRecordedForABindingThatNoLongerExists() {
        // binding_id is NOT NULL and now a foreign key, so there is no row to write. Skipping
        // is the only option that neither throws on the webhook thread nor invents a parent.
        WhatsAppConversationWindow.recordInbound(999999L, "15551230000", Instant.now());
        JPA.em().flush();
        assertEquals(0, WhatsAppConversationWindow.count());
    }

    // ==================== the deliberate soft references (AC6) ====================

    @Test
    void theDeliberatelyDanglingReferencesStayUnconstrained() throws Exception {
        // JCLAW-540 keeps data lifetimes separate: a notification outlives the task whose
        // audit row was purged, and a memory's supersession pointer is provenance rather than
        // ownership. Constraining these would be a regression, so pin them as unconstrained.
        assertNull(deleteRule("NOTIFICATION", "SOURCE_TASK_ID"),
                "a notification must survive its source task being hard-deleted");
        assertNull(deleteRule("NOTIFICATION", "SOURCE_TASK_RUN_ID"),
                "and its source task run being purged by audit retention");
        assertNull(deleteRule("MEMORY", "SUPERSEDED_BY_ID"),
                "a superseded-by pointer is provenance, not ownership");
        assertNull(deleteRule("MESSAGE", "SUBAGENT_RUN_ID"),
                "the subagent-run id is a chat-UI folding marker, not an ownership edge");
        assertNull(deleteRule("VIDEO_GENERATION_JOB", "RESULT_ATTACHMENT_ID"),
                "a job's result pointer is not ownership, and Message.attachments is mapped "
                        + "orphanRemoval=true, so a managed association here breaks the flush");
        assertNull(deleteRule("CHAT_MESSAGE_ATTACHMENT", "GENERATION_JOB_ID"),
                "the mirror of the same link, and unconstrained for the same reason — neither the "
                        + "job nor the attachment owns the other, and both cascade from the conversation");
    }

    @Test
    void theNewlyConstrainedReferencesCarryTheRightDeleteRule() throws Exception {
        assertEquals("CASCADE", deleteRule("VIDEO_GENERATION_JOB", "AGENT_ID"));
        assertEquals("CASCADE", deleteRule("VIDEO_GENERATION_JOB", "CONVERSATION_ID"));
        assertEquals("CASCADE", deleteRule("WHATSAPP_CONVERSATION_WINDOW", "BINDING_ID"));
    }

    // ==================== helpers ====================

    /** The delete rule the live schema records for a column's foreign key, or null when it has none. */
    private static String deleteRule(String table, String column) throws Exception {
        try (var conn = DB.getDataSource().getConnection();
             var ps = conn.prepareStatement(
                     "SELECT rc.delete_rule "
                             + "FROM information_schema.referential_constraints rc "
                             + "JOIN information_schema.key_column_usage kcu "
                             + "  ON kcu.constraint_name = rc.constraint_name "
                             + " AND kcu.constraint_schema = rc.constraint_schema "
                             + "WHERE UPPER(kcu.table_name) = ? AND UPPER(kcu.column_name) = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static Agent agent(String name) {
        var a = new Agent();
        a.name = name + "-" + System.nanoTime();
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.enabled = true;
        a.save();
        return a;
    }

    private static VideoGenerationJob job(Agent agent, Conversation conversation) {
        var j = new VideoGenerationJob();
        j.agent = agent;
        j.conversation = conversation;
        j.prompt = "a comet";
        j.provider = "replicate";
        j.state = VideoGenerationJob.State.PENDING;
        j.save();
        return j;
    }

    private static WhatsAppBinding binding(String agentName, String phoneNumberId) {
        var b = new WhatsAppBinding();
        b.agent = agent(agentName);
        b.transport = WhatsAppTransport.CLOUD_API;
        b.phoneNumberId = phoneNumberId + "-" + System.nanoTime();
        b.accessToken = "AT1";
        b.appSecret = "secret";
        b.verifyToken = "vt-" + System.nanoTime();
        b.enabled = true;
        b.save();
        return b;
    }
}
