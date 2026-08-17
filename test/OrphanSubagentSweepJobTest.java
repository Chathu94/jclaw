import jobs.OrphanSubagentSweepJob;
import models.Agent;
import models.SubagentRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConversationService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * JCLAW-1066: conversation deletion used to remove a subagent's run row and
 * conversations but not the agent itself, leaving rows nothing could reach. The
 * sweep clears the backlog; these pin what it may and may not delete.
 */
class OrphanSubagentSweepJobTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    private static Agent newAgent(String prefix, Agent parent, Instant createdAt) {
        var a = new Agent();
        a.name = prefix + "-" + System.nanoTime();
        a.modelProvider = "test-provider";
        a.modelId = "test-model";
        a.enabled = true;
        a.parentAgent = parent;
        a.save();
        // TimestampedModel maps createdAt updatable=false, so assigning the field and
        // re-saving writes nothing — age the row with SQL instead.
        JPA.em().createNativeQuery("UPDATE agent SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, createdAt).setParameter(2, a.id).executeUpdate();
        return a;
    }

    @Test
    void sweepsSubagentsWhoseRunIsGone() {
        var parent = newAgent("sw-parent", null, Instant.now());
        var orphan = newAgent("sw-orphan", parent, Instant.now().minus(2, ChronoUnit.DAYS));

        int swept = OrphanSubagentSweepJob.sweep(Instant.now().minus(1, ChronoUnit.HOURS));

        assertEquals(1, swept, "the orphaned subagent must be swept");
        assertNull(Agent.findById(orphan.id), "orphaned subagent row must be gone");
        assertNotNull(Agent.findById(parent.id), "its parent must survive");
    }

    @Test
    void leavesSubagentsThatStillHaveARun() {
        var parent = newAgent("sw-kparent", null, Instant.now());
        var child = newAgent("sw-kchild", parent, Instant.now().minus(2, ChronoUnit.DAYS));
        var pc = ConversationService.create(parent, "web", "p-" + System.nanoTime());
        var cc = ConversationService.create(child, "subagent", null);
        var run = new SubagentRun();
        run.parentAgent = parent;
        run.childAgent = child;
        run.parentConversation = pc;
        run.childConversation = cc;
        run.label = "run-label";
        run.status = SubagentRun.Status.COMPLETED;
        run.endedAt = Instant.now();
        run.save();

        assertEquals(0, OrphanSubagentSweepJob.sweep(Instant.now().minus(1, ChronoUnit.HOURS)),
                "a subagent with a run row is live history, not an orphan");
        assertNotNull(Agent.findById(child.id));
    }

    @Test
    void leavesSubagentsInsideTheSpawnGraceWindow() {
        // insertSubagentRun commits in its own transaction after the agent, so a
        // just-spawned child has no run row yet. Sweeping it would kill live work.
        var parent = newAgent("sw-gparent", null, Instant.now());
        var justSpawned = newAgent("sw-gchild", parent, Instant.now());

        assertEquals(0, OrphanSubagentSweepJob.sweep(Instant.now().minus(1, ChronoUnit.HOURS)),
                "a child younger than the grace window may be a spawn in progress");
        assertNotNull(Agent.findById(justSpawned.id));
    }

    @Test
    void isIdempotent() {
        var parent = newAgent("sw-iparent", null, Instant.now());
        newAgent("sw-ichild", parent, Instant.now().minus(2, ChronoUnit.DAYS));
        var cutoff = Instant.now().minus(1, ChronoUnit.HOURS);

        assertEquals(1, OrphanSubagentSweepJob.sweep(cutoff));
        assertEquals(0, OrphanSubagentSweepJob.sweep(cutoff), "a second run must find nothing");
    }
}
