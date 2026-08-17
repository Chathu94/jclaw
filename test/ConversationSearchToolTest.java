import agents.ToolContext;
import agents.ToolRegistry;
import models.Agent;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConversationService;
import services.TimezoneResolver;
import services.Tx;
import services.search.DirectLuceneMessageSearchRepository;
import services.search.MessageSearchTestHooks;
import tools.ConversationSearchTool;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * JCLAW-1065: the tool's permission boundary is the point of the story, so these lean
 * on the negative cases. A caller reaches its own conversations and those of every
 * agent beneath it at any depth; it reaches no other agent's, and a subagent cannot
 * read upward to its parent.
 */
class ConversationSearchToolTest extends UnitTest {

    /** Message-body marker, deliberately absent from every query string. */
    private static final String BODY = "bodymarkerxyzzy";

    private ConversationSearchTool tool;

    @BeforeEach
    void setup() {
        LuceneTestSync.openForTest();
        Fixtures.deleteDatabase();
        // MessageSearch returns an empty list until a repository is installed, and test
        // mode never initialises one — without this every search silently finds nothing
        // and the boundary assertions below would pass without exercising the boundary.
        MessageSearchTestHooks.setRepository(new DirectLuceneMessageSearchRepository());
        tool = new ConversationSearchTool();
    }

    @AfterEach
    void teardown() {
        MessageSearchTestHooks.setRepository(null);
        LuceneTestSync.release();
    }

    private static <T> T commitInFreshTx(java.util.function.Supplier<T> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    private static Agent newAgent(String prefix, Agent parent) {
        var a = new Agent();
        a.name = prefix + "-" + System.nanoTime();
        a.modelProvider = "test-provider";
        a.modelId = "test-model";
        a.enabled = true;
        a.parentAgent = parent;
        a.save();
        return a;
    }

    private static String search(ConversationSearchTool tool, Agent caller, String query) {
        return tool.execute("{\"query\":\"" + query + "\"}", caller);
    }

    @Test
    void findsAMessageInTheCallersOwnConversation() {
        var token = "convsearchowntoken";
        long agentId = commitInFreshTx(() -> {
            var agent = newAgent("cs-own", null);
            var convo = ConversationService.create(agent, "web", "c-" + System.nanoTime());
            ConversationService.appendUserMessage(convo, BODY + " " + token);
            return agent.id;
        });

        var out = search(tool, Agent.findById(agentId), token);

        assertTrue(out.contains(BODY), "the caller's own message must be returned, got: " + out);
    }

    @Test
    void aSecondAgentCannotSeeTheFirstAgentsConversation() {
        var token = "convsearchprivatetoken";
        long otherId = commitInFreshTx(() -> {
            var owner = newAgent("cs-owner", null);
            var convo = ConversationService.create(owner, "web", "c-" + System.nanoTime());
            ConversationService.appendUserMessage(convo, BODY + " " + token);
            return newAgent("cs-other", null).id;
        });

        var out = search(tool, Agent.findById(otherId), token);

        // Assert on the message body, not the token: the tool echoes the query back in
        // its no-match line, so a token check would pass even on a total leak.
        assertFalse(out.contains(BODY),
                "an unrelated agent must not receive another agent's conversation content, got: " + out);
    }

    @Test
    void aParentCanFindItsSubagentsConversation() {
        var token = "convsearchchildtoken";
        long parentId = commitInFreshTx(() -> {
            var parent = newAgent("cs-parent", null);
            var child = newAgent("cs-child", parent);
            var pc = ConversationService.create(parent, "web", "p-" + System.nanoTime());
            var cc = ConversationService.create(child, "subagent", null);
            cc.parentConversation = pc;
            cc.save();
            ConversationService.appendUserMessage(cc, BODY + " " + token);

            var run = new SubagentRun();
            run.parentAgent = parent;
            run.childAgent = child;
            run.parentConversation = pc;
            run.childConversation = cc;
            run.label = "run-label";
            run.status = SubagentRun.Status.COMPLETED;
            run.endedAt = Instant.now();
            run.save();
            return parent.id;
        });

        var out = search(tool, Agent.findById(parentId), token);

        assertTrue(out.contains(BODY),
                "work delegated to a subagent is still the caller's own, got: " + out);
    }

    @Test
    void aSubagentCannotSeeItsParentsConversation() {
        var token = "convsearchparenttoken";
        long childId = commitInFreshTx(() -> {
            var parent = newAgent("cs-pparent", null);
            var child = newAgent("cs-pchild", parent);
            var pc = ConversationService.create(parent, "web", "p-" + System.nanoTime());
            var cc = ConversationService.create(child, "subagent", null);
            cc.parentConversation = pc;
            cc.save();
            ConversationService.appendUserMessage(pc, BODY + " " + token);

            var run = new SubagentRun();
            run.parentAgent = parent;
            run.childAgent = child;
            run.parentConversation = pc;
            run.childConversation = cc;
            run.label = "run-label";
            run.status = SubagentRun.Status.COMPLETED;
            run.endedAt = Instant.now();
            run.save();
            return child.id;
        });

        var out = search(tool, Agent.findById(childId), token);

        assertFalse(out.contains(BODY),
                "the reach is parent-to-child only; a child must not read upward, got: " + out);
    }

    @Test
    void reachesAGrandchildSubagentsConversation() {
        var token = "convsearchgrandchildtoken";
        long rootId = commitInFreshTx(() -> {
            var root = newAgent("cs-root", null);
            var mid = newAgent("cs-mid", root);
            var leaf = newAgent("cs-leaf", mid);
            var lc = ConversationService.create(leaf, "subagent", null);
            ConversationService.appendUserMessage(lc, BODY + " " + token);
            return root.id;
        });

        var out = search(tool, Agent.findById(rootId), token);

        assertTrue(out.contains(BODY),
                "the subtree is transitive: a grandchild's conversation is still the "
                        + "caller's own work, got: " + out);
    }

    @Test
    void excludesTheConversationTheCallerIsCurrentlyIn() {
        // The caller's own question lands in this conversation and is indexed before the
        // tool runs, so without the exclusion "which conversation discussed X" answers
        // "the one you are in".
        var token = "convsearchcurrenttoken";
        var ids = commitInFreshTx(() -> {
            var agent = newAgent("cs-cur", null);
            var older = ConversationService.create(agent, "web", "old-" + System.nanoTime());
            ConversationService.appendUserMessage(older, BODY + " " + token);
            var current = ConversationService.create(agent, "web", "cur-" + System.nanoTime());
            ConversationService.appendUserMessage(current, "asking about " + token);
            return List.of(agent.id, current.id, older.id);
        });
        var caller = Agent.<Agent>findById(ids.get(0));

        var out = ToolContext.withConversation(ids.get(1), () -> search(tool, caller, token));

        assertFalse(out.contains("conversation " + ids.get(1)),
                "the current conversation must not be returned to itself, got: " + out);
        assertTrue(out.contains("conversation " + ids.get(2)),
                "the earlier conversation is what the caller is looking for, got: " + out);
    }

    @Test
    void rendersTimestampsInTheOperatorsZoneNotUtc() {
        var token = "convsearchclocktoken";
        long agentId = commitInFreshTx(() -> {
            var agent = newAgent("cs-clock", null);
            var convo = ConversationService.create(agent, "web", "c-" + System.nanoTime());
            ConversationService.appendUserMessage(convo, BODY + " " + token);
            return agent.id;
        });

        var out = search(tool, Agent.findById(agentId), token);

        var expected = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm XXX")
                .format(Instant.now().atZone(TimezoneResolver.appZone()));
        assertTrue(out.contains(expected),
                "timestamp must read as the operator's wall clock with its offset ("
                        + expected + "), got: " + out);
        assertFalse(out.contains("T") && out.contains("Z"),
                "a raw UTC instant must not leak into the result line, got: " + out);
    }

    @Test
    void isOptInForNonMainAgents() {
        long agentId = commitInFreshTx(() -> newAgent("cs-optin", null).id);
        ToolRegistry.clearDisabledToolsCache();

        assertTrue(ToolRegistry.loadDisabledTools(Agent.findById(agentId))
                        .contains(ConversationSearchTool.TOOL_NAME),
                "a non-main agent must be granted conversation_search explicitly");
    }
}
