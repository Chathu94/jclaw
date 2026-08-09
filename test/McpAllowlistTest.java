import com.google.gson.JsonObject;
import mcp.McpAllowlist;
import mcp.McpToolDef;
import models.Agent;
import models.AgentSkillAllowedTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.Tx;

import java.util.List;

/**
 * Direct unit coverage of {@link McpAllowlist}: row writes/deletes against
 * the agent_skill_allowed_tool table and the isAllowed() gate. Wraps every
 * call in {@link Tx#run} since McpAllowlist is intentionally tx-agnostic.
 */
class McpAllowlistTest extends UnitTest {

    @BeforeEach
    void setUp() {
        Fixtures.deleteDatabase();
    }

    // ==================== registerForAllAgents ====================

    @Test
    void registerForAllAgentsWritesOneRowPerAgentPerTool() {
        var agentIds = Tx.run(() -> {
            var a1 = newAgent("alpha");
            var a2 = newAgent("beta");
            return List.of(a1.id, a2.id);
        });
        var tools = List.of(toolDef("create_issue"), toolDef("close_issue"));

        var written = Tx.run(() -> McpAllowlist.registerForAllAgents("github", tools));
        assertEquals(2 * 2, written, "2 agents x 2 tools = 4 rows");

        Tx.run(() -> {
            var rowsByName = countRows("mcp:github");
            assertEquals(4, rowsByName);
            for (var aid : agentIds) {
                assertTrue(allowed(aid, "github", "create_issue"));
                assertTrue(allowed(aid, "github", "close_issue"));
            }
        });
    }

    @Test
    void registerIsIdempotentAndRefreshesShrinkingToolList() {
        Tx.run(() -> newAgent("alpha"));
        // First publish: 3 tools.
        Tx.run(() -> McpAllowlist.registerForAllAgents("svc",
                List.of(toolDef("a"), toolDef("b"), toolDef("c"))));
        // Second publish: shrink to 1 tool — old rows must be gone.
        Tx.run(() -> McpAllowlist.registerForAllAgents("svc", List.of(toolDef("a"))));
        Tx.run(() -> {
            assertEquals(1, countRows("mcp:svc"),
                    "shrinking the tool list must remove rows for tools no longer advertised");
        });
    }

    @Test
    void registerWithIdenticalToolSetIsZeroWriteNoOp() {
        Tx.run(() -> {
            newAgent("alpha");
            newAgent("beta");
        });
        var tools = List.of(toolDef("a"), toolDef("b"));
        Tx.run(() -> McpAllowlist.registerForAllAgents("svc", tools));

        // Capture the row IDs written by the first publish.
        var idsBefore = Tx.run(() -> rowIds("mcp:svc"));
        assertEquals(4, idsBefore.size(), "2 agents x 2 tools");

        // Re-publish the identical tool set: a delete+reinsert would assign new
        // IDs, so unchanged IDs prove the short-circuit fired (zero writes).
        var written = Tx.run(() -> McpAllowlist.registerForAllAgents("svc", tools));
        assertEquals(4, written, "no-op returns the existing row count");
        var idsAfter = Tx.run(() -> rowIds("mcp:svc"));
        assertEquals(idsBefore, idsAfter,
                "identical tool set must leave the existing rows in place (no delete+reinsert)");
    }

    @Test
    void registerWithEmptyListClearsRows() {
        Tx.run(() -> newAgent("alpha"));
        Tx.run(() -> McpAllowlist.registerForAllAgents("svc",
                List.of(toolDef("x"), toolDef("y"))));
        Tx.run(() -> McpAllowlist.registerForAllAgents("svc", List.of()));
        Tx.run(() -> assertEquals(0, countRows("mcp:svc")));
    }

    // ==================== unregister ====================

    @Test
    void unregisterRemovesAllRowsForServerOnly() {
        Tx.run(() -> newAgent("alpha"));
        Tx.run(() -> McpAllowlist.registerForAllAgents("svc1", List.of(toolDef("a"))));
        Tx.run(() -> McpAllowlist.registerForAllAgents("svc2", List.of(toolDef("b"))));

        var deleted = Tx.run(() -> McpAllowlist.unregister("svc1"));
        assertEquals(1, deleted);
        Tx.run(() -> {
            assertEquals(0, countRows("mcp:svc1"));
            assertEquals(1, countRows("mcp:svc2"), "unrelated server's rows must be untouched");
        });
    }

    // ==================== isAllowed ====================

    @Test
    void isAllowedReturnsFalseForUnknownAgent() {
        Tx.run(() -> {
            var agent = newAgent("alpha");
            McpAllowlist.registerForAllAgents("svc", List.of(toolDef("a")));
            // Synthetic agent with id never seen.
            var ghost = new Agent();
            ghost.id = 999_999L;
            assertFalse(McpAllowlist.isAllowed(ghost, "svc", "a"));
            assertTrue(McpAllowlist.isAllowed(agent, "svc", "a"));
        });
    }

    @Test
    void isAllowedReturnsFalseAfterUnregister() {
        var agentId = Tx.run(() -> newAgent("alpha").id);
        Tx.run(() -> McpAllowlist.registerForAllAgents("svc", List.of(toolDef("a"))));
        assertTrue(Tx.run(() -> allowed(agentId, "svc", "a")));
        Tx.run(() -> McpAllowlist.unregister("svc"));
        assertFalse(Tx.run(() -> allowed(agentId, "svc", "a")));
    }

    @Test
    void isAllowedFalseForToolNotAdvertised() {
        var agentId = Tx.run(() -> newAgent("alpha").id);
        Tx.run(() -> McpAllowlist.registerForAllAgents("svc", List.of(toolDef("a"))));
        assertFalse(Tx.run(() -> allowed(agentId, "svc", "not_advertised")));
    }

    // ==================== backfillForAgent ====================

    @Test
    void backfillForAgentSkippedWhenNoConnectedServers() {
        var agent = Tx.run(() -> newAgent("late"));
        var written = Tx.run(() -> McpAllowlist.backfillForAgent(agent));
        assertEquals(0, written);
    }

    // ==================== spawned subagents ====================

    @Test
    void broadcastSkipsSpawnedSubagents() {
        // A subagent gets its grants once, at spawn, from backfillForAgent.
        // Including them in the broadcast made every reconnect rewrite
        // subagents x tools rows — the whole reason a busy instance took
        // minutes to bring a large server up.
        var ids = Tx.run(() -> {
            var parent = newAgent("parent");
            var child = newSubagent("parent-sub-abc", parent);
            return List.of(parent.id, child.id);
        });

        var written = Tx.run(() -> McpAllowlist.registerForAllAgents("svc",
                List.of(toolDef("a"), toolDef("b"))));

        assertEquals(2, written, "only the top-level agent is broadcast to");
        Tx.run(() -> {
            assertTrue(allowed(ids.get(0), "svc", "a"));
            assertFalse(allowed(ids.get(1), "svc", "a"),
                    "subagent must not be granted by the broadcast");
        });
    }

    @Test
    void reconnectLeavesALiveSubagentsGrantsIntact() {
        // The delete half of the broadcast is scoped too. If it weren't, a
        // reconnect mid-run would strip a running subagent's access to a
        // server it was already using.
        var childId = Tx.run(() -> {
            var parent = newAgent("parent");
            var child = newSubagent("parent-sub-live", parent);
            grant(child, "mcp:svc", "a");
            return child.id;
        });

        Tx.run(() -> McpAllowlist.registerForAllAgents("svc", List.of(toolDef("a"))));

        Tx.run(() -> assertTrue(allowed(childId, "svc", "a"),
                "a reconnect must not revoke a running subagent's grant"));
    }

    @Test
    void revokeForAgentDropsOnlyMcpScopedRows() {
        var childId = Tx.run(() -> {
            var parent = newAgent("parent");
            var child = newSubagent("parent-sub-done", parent);
            grant(child, "mcp:svc", "a");
            // A shell-allowlist row in the same table, which McpAllowlist does not own.
            var shell = new AgentSkillAllowedTool();
            shell.agent = child;
            shell.skillName = "my-shell-skill";
            shell.toolName = "ls";
            shell.save();
            return child.id;
        });

        var removed = Tx.run(() -> McpAllowlist.revokeForAgent(Agent.findById(childId)));

        assertEquals(1, removed);
        Tx.run(() -> {
            assertEquals(0, countRows("mcp:svc"));
            assertEquals(1, countRows("my-shell-skill"), "non-MCP grants are not ours to delete");
        });
    }

    @Test
    void sweepReclaimsFinishedSubagentsAndRemovedServers() {
        var runningId = Tx.run(() -> {
            var parent = newAgent("parent");
            var finished = newSubagent("parent-sub-finished", parent);
            var running = newSubagent("parent-sub-running", parent);
            grant(finished, "mcp:svc", "a");
            grant(running, "mcp:svc", "a");
            // Rows naming a server that no longer exists.
            McpAllowlist.registerForAllAgents("gone", List.of(toolDef("x")));
            McpAllowlist.registerForAllAgents("svc", List.of(toolDef("a")));
            return running.id;
        });

        var removed = Tx.run(() ->
                McpAllowlist.sweepStaleGrants(java.util.Set.of("svc"), java.util.Set.of(runningId)));

        assertTrue(removed >= 2, "the finished subagent's row and the removed server's row, at least");
        Tx.run(() -> {
            assertEquals(0, countRows("mcp:gone"), "rows for a server that no longer exists");
            assertTrue(allowed(runningId, "svc", "a"), "a running subagent keeps its grants");
            assertEquals(2, countRows("mcp:svc"),
                    "the top-level agent's row plus the running subagent's");
        });
    }

    @Test
    void subagentInheritsExactlyItsParentsGrantsAndNoMore() {
        // A delegate must not out-reach its spawner: the parent holds one
        // server, so the child gets that one — not every connected server.
        var ids = Tx.run(() -> {
            var parent = newAgent("parent");
            grant(parent, "mcp:allowed", "a");
            grant(parent, "mcp:allowed", "b");
            var child = newSubagent("parent-sub-heir", parent);
            McpAllowlist.inheritFromParent(child);
            return List.of(parent.id, child.id);
        });

        Tx.run(() -> {
            assertTrue(allowed(ids.get(1), "allowed", "a"));
            assertTrue(allowed(ids.get(1), "allowed", "b"));
            assertFalse(allowed(ids.get(1), "denied", "x"),
                    "a server the parent cannot reach must not be reachable by its subagent");
        });
    }

    @Test
    void inheritCopiesNothingForAnAgentWithNoParent() {
        var agentId = Tx.run(() -> {
            var a = newAgent("top");
            grant(a, "mcp:svc", "a");
            return a.id;
        });
        var written = Tx.run(() -> McpAllowlist.inheritFromParent(Agent.findById(agentId)));
        assertEquals(0, written, "a top-level agent has no parent to inherit from");
    }

    @Test
    void inheritSkipsNonMcpGrantsHeldByTheParent() {
        // Shell allowlist rows live in the same table under a different scope
        // and are not this class's to propagate.
        var childId = Tx.run(() -> {
            var parent = newAgent("parent");
            grant(parent, "mcp:svc", "a");
            grant(parent, "some-shell-skill", "rm");
            var child = newSubagent("parent-sub-scoped", parent);
            McpAllowlist.inheritFromParent(child);
            return child.id;
        });

        Tx.run(() -> {
            assertTrue(allowed(childId, "svc", "a"));
            assertEquals(1, AgentSkillAllowedTool.count("agent.id = ?1", childId),
                    "only the mcp: row is inherited");
        });
    }

    @Test
    void releaseDropsGrantsWhenTheRunnerWasASpawnedSubagent() {
        var childId = Tx.run(() -> {
            var parent = newAgent("parent");
            var child = newSubagent("parent-sub-settled", parent);
            grant(child, "mcp:svc", "a");
            grant(child, "mcp:svc", "b");
            return child.id;
        });

        var removed = Tx.run(() -> McpAllowlist.releaseSubagentGrants(Agent.findById(childId)));

        assertEquals(2, removed);
        Tx.run(() -> assertEquals(0, AgentSkillAllowedTool.count("agent.id = ?1", childId)));
    }

    @Test
    void releaseLeavesAReusedTopLevelAgentsGrantsAlone() {
        // A spawn may name an existing agent to run as (the agentId path), and
        // that row stays top-level on purpose. Revoking when its run settles
        // would strip an operator's agent of MCP access on first delegation.
        var agentId = Tx.run(() -> {
            var reused = newAgent("operator-owned");
            grant(reused, "mcp:svc", "a");
            grant(reused, "mcp:svc", "b");
            return reused.id;
        });

        var removed = Tx.run(() -> McpAllowlist.releaseSubagentGrants(Agent.findById(agentId)));

        assertEquals(0, removed, "a top-level agent's grants are not the run's to release");
        Tx.run(() -> assertEquals(2, AgentSkillAllowedTool.count("agent.id = ?1", agentId)));
    }

    @Test
    void deletingAnAgentRemovesItsAllowlistRows() {
        // The Subagents page delete path ends in agent.delete(); the FK carries
        // ON DELETE CASCADE, so the grants must go with it rather than
        // stranding rows keyed to an id nothing can resolve.
        var childId = Tx.run(() -> {
            var parent = newAgent("parent");
            var child = newSubagent("parent-sub-doomed", parent);
            grant(child, "mcp:svc", "a");
            grant(child, "mcp:svc", "b");
            return child.id;
        });
        assertEquals(2, Tx.run(() -> AgentSkillAllowedTool.count("agent.id = ?1", childId)));

        Tx.run(() -> {
            Agent child = Agent.findById(childId);
            child.delete();
        });

        assertEquals(0, Tx.run(() -> AgentSkillAllowedTool.count("agent.id = ?1", childId)),
                "deleting an agent must take its MCP grants with it");
    }

    // ==================== helpers ====================

    private Agent newSubagent(String name, Agent parent) {
        var a = newAgent(name);
        a.parentAgent = parent;
        a.save();
        return a;
    }

    /** Row written the way backfillForAgent writes one at spawn, without
     *  needing a live connection manager. */
    private static void grant(Agent agent, String skillName, String toolName) {
        var row = new AgentSkillAllowedTool();
        row.agent = agent;
        row.skillName = skillName;
        row.toolName = toolName;
        row.save();
    }

    private Agent newAgent(String name) {
        var a = new Agent();
        a.name = name;
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.enabled = true;
        a.save();
        return a;
    }

    private static McpToolDef toolDef(String name) {
        return new McpToolDef(name, name + " desc", new JsonObject());
    }

    private static long countRows(String skillName) {
        return AgentSkillAllowedTool.count("skillName = ?1", skillName);
    }

    private static java.util.Set<Long> rowIds(String skillName) {
        var ids = new java.util.HashSet<Long>();
        for (Object row : AgentSkillAllowedTool.find("skillName = ?1", skillName).fetch()) {
            ids.add(((AgentSkillAllowedTool) row).id);
        }
        return ids;
    }

    private static boolean allowed(Long agentId, String server, String tool) {
        Agent agent = Agent.findById(agentId);
        return McpAllowlist.isAllowed(agent, server, tool);
    }
}
