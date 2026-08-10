import models.Agent;
import models.Memory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;

/**
 * JCLAW-529: deleting a memory must not leave retired rows pointing at an id that no
 * longer exists.
 *
 * <p>{@code supersededById} carries no foreign key — only {@code agent_id} does — so
 * referential integrity here is the application's job. A live store had accumulated 265
 * dangling pointers before this was closed.
 */
class MemoryLineageTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
        agent = new Agent();
        agent.name = "lineage-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();
    }

    /** The index lock is JVM-global and the engine runs lanes concurrently: holding it
     *  past the test starves every other Lucene-touching class until it times out. */
    @AfterEach
    void release() {
        LuceneTestSync.release();
    }

    private Memory mem(String text) {
        var m = new Memory();
        m.agent = agent;
        m.text = text;
        m.category = "fact";
        m.importance = 0.5;
        m.save();
        return m;
    }

    @Test
    void deletingASuccessorClearsThePointersAimedAtIt() {
        var older = mem("The user lives in Berlin");
        var newer = mem("The user lives in Porto");
        older.supersede(newer.id);

        newer.deleteWithLineage();

        var reloaded = (Memory) Memory.findById(older.id);
        assertNull(reloaded.supersededById,
                "the successor is gone, so the pointer must say so rather than name a missing row");
        assertNotNull(reloaded.supersededAt,
                "the row is still retired — only the pointer to a vanished successor is cleared");
    }

    @Test
    void deletingAMemoryNothingPointsAtIsUnaffected() {
        var solo = mem("The deploy pipeline requires manual approval");
        solo.deleteWithLineage();
        assertNull(Memory.findById(solo.id));
    }

    @Test
    void theRepairSweepClearsPointersLeftByAnEarlierDelete() {
        // The 265 already in the live store: written before deleteWithLineage existed.
        var older = mem("The user lives in Berlin");
        var newer = mem("The user lives in Porto");
        older.supersede(newer.id);
        newer.delete();                     // the old path, which left the pointer dangling

        assertEquals(1, Memory.clearDanglingSupersessionPointers());
        assertNull(((Memory) Memory.findById(older.id)).supersededById);
    }

    @Test
    void theRepairSweepIsIdempotentAndSparesLivePointers() {
        var older = mem("The user lives in Berlin");
        var newer = mem("The user lives in Porto");
        older.supersede(newer.id);

        assertEquals(0, Memory.clearDanglingSupersessionPointers(),
                "the successor still exists, so this lineage is intact and must be left alone");
        assertEquals(newer.id, ((Memory) Memory.findById(older.id)).supersededById);
    }
}
