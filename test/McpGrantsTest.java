import agents.ToolRegistry;
import mcp.McpGrantKeyMigration;
import mcp.McpGrants;
import models.Agent;
import models.AgentToolConfig;
import models.McpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-983: a per-agent MCP grant is keyed by its server's id, not by the server's name.
 *
 * <p>An MCP tool's name is built from its server's name, so keying the grant by that name
 * made the join a string prefix nothing maintained — a rename stranded every grant and a
 * delete left them behind (49,018 rows where JCLAW-982 first measured it). Those two
 * handlers are gone; the id and the database's cascade replace them.
 *
 * <p>Every test here keeps more than one server configured. With a single server a change
 * that touches the right rows and one that touches all of them are indistinguishable — the
 * same blind spot that let the core-memory cap ship measuring the wrong thing.
 */
class McpGrantsTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        agent = new Agent();
        agent.name = "grants-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();
    }

    // ==================== rename (AC1) ====================

    @Test
    void renamingAServerWritesNoGrantRow() throws Exception {
        var alpha = server("alpha");
        server("bystander");
        ToolRegistrySync.withTools(List.of(handle("alpha"), action("alpha", "search"),
                handle("bystander")), () -> {
            var granted = McpGrants.newRow(agent, "mcp_alpha");
            granted.enabled = true;
            granted.save();
            JPA.em().flush();

            var id = granted.id;
            var serverIdBefore = granted.mcpServer.id;

            alpha.name = "renamed";
            alpha.save();
            JPA.em().flush();
            JPA.em().clear();
            ToolRegistry.clearDisabledToolsCache();

            // Row-level, not just "the effective list still looks right": the point of the
            // change is that a rename produces no write at all.
            assertEquals(1, AgentToolConfig.count(), "a rename must not add or remove a row");
            AgentToolConfig after = AgentToolConfig.findById(id);
            assertNotNull(after, "the same row must still be there");
            assertEquals(serverIdBefore, after.mcpServer.id, "still pointing at the same server");
            assertEquals("", after.mcpAction, "the server-level handle, unchanged");
            assertNull(after.toolName, "an MCP row carries no name to go stale");
        });
    }

    @Test
    void aRenamedServersGrantFollowsItToTheNewHandle() throws Exception {
        var alpha = server("alpha");
        server("bystander");
        ToolRegistrySync.withTools(List.of(handle("alpha"), handle("bystander")), () -> {
            var granted = McpGrants.newRow(agent, "mcp_alpha");
            granted.enabled = true;
            granted.save();
            JPA.em().flush();

            alpha.name = "renamed";
            alpha.save();
            JPA.em().flush();
            JPA.em().clear();

            var reloaded = (AgentToolConfig) AgentToolConfig.findById(granted.id);
            assertEquals("mcp_renamed", reloaded.handle(),
                    "the row's handle is derived from the server, so it renames with it");

            // Republished under the new name, as the reconnect after a rename does.
            ToolRegistry.publish(List.of(handle("renamed"), handle("bystander")));
            ToolRegistry.clearDisabledToolsCache();

            var disabled = ToolRegistry.loadDisabledTools(Agent.findById(agent.id));
            assertFalse(disabled.contains("mcp_renamed"),
                    "the grant follows the rename because it never named the server");
            assertTrue(disabled.contains("mcp_bystander"),
                    "and it still grants only the server it was written for");
        });
    }

    // ==================== delete (AC5) ====================

    @Test
    void deletingAServerTakesItsGrantsWithIt() throws Exception {
        var alpha = server("alpha");
        server("beta");
        ToolRegistrySync.withTools(List.of(handle("alpha"), action("alpha", "search"),
                handle("beta"), nativeTool("filesystem")), () -> {
            for (var name : List.of("mcp_alpha", "mcp_alpha_search", "mcp_beta", "filesystem")) {
                var row = McpGrants.newRow(agent, name);
                row.enabled = true;
                row.save();
            }
            JPA.em().flush();
            var alphaId = alpha.id;

            // No application code drops these rows — the FK's ON DELETE CASCADE does.
            alpha.delete();
            JPA.em().flush();
            JPA.em().clear();

            assertEquals(0, AgentToolConfig.count("mcpServer.id = ?1", alphaId),
                    "the database cascade removes every grant for the deleted server");
            assertEquals(1, AgentToolConfig.count("mcpServer is not null"),
                    "another server's grant is untouched");
            assertEquals(1, AgentToolConfig.count("toolName = ?1", "filesystem"),
                    "native tool grants are not MCP's business");
        });
    }

    // ==================== native tools (AC3) ====================

    @Test
    void nativeGrantsAreStillStoredAndFoundByName() throws Exception {
        server("alpha");
        ToolRegistrySync.withTools(List.of(nativeTool("filesystem"), handle("alpha")), () -> {
            var row = McpGrants.newRow(agent, "filesystem");
            row.enabled = false;
            row.save();
            JPA.em().flush();

            assertEquals("filesystem", row.toolName);
            assertNull(row.mcpServer, "a native grant names no server");
            assertNull(row.mcpAction);
            assertEquals("filesystem", row.handle());
            assertNotNull(AgentToolConfig.findByAgentAndTool(agent, "filesystem"),
                    "the by-name finder still resolves it");
            assertEquals(row.id, McpGrants.find(agent, "filesystem").id);
        });
    }

    @Test
    void anMcpGrantIsFoundByServerAndAction() throws Exception {
        server("alpha");
        server("beta");
        ToolRegistrySync.withTools(List.of(handle("alpha"), action("alpha", "search"),
                handle("beta")), () -> {
            var row = McpGrants.newRow(agent, "mcp_alpha_search");
            row.enabled = true;
            row.save();
            JPA.em().flush();

            assertNull(row.toolName);
            assertEquals("search", row.mcpAction);
            assertEquals("mcp_alpha_search", row.handle());
            assertEquals(row.id, McpGrants.find(agent, "mcp_alpha_search").id);
            assertNull(McpGrants.find(agent, "mcp_alpha"),
                    "the server-level handle is a different grant from one of its actions");
            assertNull(McpGrants.find(agent, "mcp_beta"));
        });
    }

    // ==================== the read path is unchanged (AC4) ====================

    @Test
    void theDisabledSetIsIdenticalBeforeAndAfterRekeying() throws Exception {
        server("alpha");
        server("beta");
        ToolRegistrySync.withTools(List.of(
                handle("alpha"), action("alpha", "search"),
                handle("beta"), action("beta", "query"),
                nativeTool("filesystem"), nativeTool("exec")), () -> {
            // Seeded in the old name-keyed shape: one server granted, one denied, one native
            // denied. Every row names a live server, because "the same configuration" is
            // what this compares — a row naming no server is covered by the backfill tests.
            nameKeyed("mcp_alpha", true);
            nameKeyed("mcp_beta", false);
            nameKeyed("filesystem", false);
            JPA.em().flush();

            ToolRegistry.clearDisabledToolsCache();
            var before = ToolRegistry.loadDisabledTools(Agent.findById(agent.id));

            var result = McpGrantKeyMigration.backfill();
            JPA.em().flush();
            JPA.em().clear();
            ToolRegistry.clearDisabledToolsCache();
            var after = ToolRegistry.loadDisabledTools(Agent.findById(agent.id));

            assertEquals(2, result.backfilled(), "both MCP rows re-keyed");
            assertEquals(0, result.removed(), "every row named a live server");
            assertEquals(before, after,
                    "re-keying must not change what any agent may call");
            assertTrue(before.contains("mcp_beta") && before.contains("filesystem")
                            && before.contains("mcp_alpha_search"),
                    "test premise: the fixture must actually disable something, "
                            + "or two empty sets would compare equal: " + before);
        });
    }

    // ==================== backfill (AC2) ====================

    @Test
    void backfillKeysRowsByServerIdAndDropsThoseNamingNoServer() {
        var alpha = server("alpha");
        server("beta");
        nameKeyed("mcp_alpha", true);
        nameKeyed("mcp_alpha_search", true);
        nameKeyed("mcp_ghost_search", true);      // server deleted long ago
        nameKeyed("filesystem", false);
        JPA.em().flush();

        var result = McpGrantKeyMigration.backfill();
        JPA.em().flush();
        JPA.em().clear();

        assertEquals(2, result.backfilled());
        assertEquals(1, result.removed());
        assertEquals(2, AgentToolConfig.count("mcpServer.id = ?1", alpha.id));
        assertEquals(1, AgentToolConfig.count("mcpServer.id = ?1 AND mcpAction = ?2", alpha.id, ""),
                "the server-level handle keeps the empty action");
        assertEquals(1, AgentToolConfig.count("mcpServer.id = ?1 AND mcpAction = ?2",
                        alpha.id, "search"));
        assertEquals(1, AgentToolConfig.count("toolName is not null"),
                "the native row is the only one left carrying a name");
        assertEquals(1, AgentToolConfig.count("toolName = ?1", "filesystem"),
                "a native row is not the backfill's business");
    }

    @Test
    void backfillLeavesAServerWhoseNamePrefixesAnotherAlone() {
        // "google-workspace" and "google-workspace-mcp" both existed on the reporting
        // instance, and a prefix match without the separator would fold them together.
        var shorter = server("google-workspace");
        var longer = server("google-workspace-mcp");
        nameKeyed("mcp_google-workspace_drive", true);
        nameKeyed("mcp_google-workspace-mcp_drive", true);
        JPA.em().flush();

        assertEquals(2, McpGrantKeyMigration.backfill().backfilled());
        JPA.em().flush();
        JPA.em().clear();

        assertEquals(1, AgentToolConfig.count("mcpServer.id = ?1 AND mcpAction = ?2",
                shorter.id, "drive"));
        assertEquals(1, AgentToolConfig.count("mcpServer.id = ?1 AND mcpAction = ?2",
                longer.id, "drive"));
    }

    @Test
    void backfillReadsAnAmbiguousNameAsTheLongerServer() {
        // `mcp_svc_x_run` is both `svc`'s `x_run` and `svc_x`'s `run`. Nothing in the name
        // decides it, so the migration takes the more specific server.
        server("svc");
        var longer = server("svc_x");
        nameKeyed("mcp_svc_x_run", true);
        JPA.em().flush();

        McpGrantKeyMigration.backfill();
        JPA.em().flush();
        JPA.em().clear();

        assertEquals(1, AgentToolConfig.count("mcpServer.id = ?1 AND mcpAction = ?2",
                longer.id, "run"));
    }

    @Test
    void backfillIsIdempotent() {
        server("alpha");
        nameKeyed("mcp_alpha", true);
        nameKeyed("mcp_ghost", true);
        JPA.em().flush();

        McpGrantKeyMigration.backfill();
        JPA.em().flush();

        var second = McpGrantKeyMigration.backfill();
        assertEquals(0, second.backfilled(), "a re-keyed row carries no name left to match");
        assertEquals(0, second.removed());
    }

    // ==================== the NOT NULL relaxation ====================

    @Test
    void relaxNotNullDropsTheConstraintOnceThenNoOps() throws Exception {
        // Its own throwaway H2 rather than the shared test schema: the ALTER is real DDL and
        // must not be visible to the concurrent test lanes (mirrors CascadeFkMigratorTest).
        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:mem:grantmig_" + System.nanoTime() + ";MODE=MYSQL")) {
            conn.setAutoCommit(true);
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE relax_me (id BIGINT PRIMARY KEY, tool_name VARCHAR(255) NOT NULL)");
            }

            assertTrue(McpGrantKeyMigration.relaxNotNull(conn, "RELAX_ME", "TOOL_NAME"),
                    "a NOT NULL column must be relaxed");
            try (Statement s = conn.createStatement()) {
                s.execute("INSERT INTO relax_me VALUES (1, NULL)");
                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM relax_me WHERE tool_name IS NULL")) {
                    rs.next();
                    assertEquals(1, rs.getInt(1), "an MCP row must be able to carry no name");
                }
            }

            assertFalse(McpGrantKeyMigration.relaxNotNull(conn, "RELAX_ME", "TOOL_NAME"),
                    "already nullable — a fresh install must do no DDL");
            assertFalse(McpGrantKeyMigration.relaxNotNull(conn, "NO_SUCH_TABLE", "TOOL_NAME"),
                    "an absent column is skipped, not an error");
        }
    }

    // ==================== duplicate visibility (JCLAW-982) ====================

    @Test
    void anIdenticalSecondRegistrationIsFlaggedAgainstTheOlderOne() throws Exception {
        // Two byte-identical registrations sat side by side for two days: same command,
        // same env, different name. Telling them apart meant reading two long endpoint
        // strings and noticing they matched.
        var first = new McpServer();
        first.name = "workspace";
        first.transport = McpServer.Transport.STDIO;
        first.configJson = "{\"command\":\"uvx\",\"args\":[\"workspace-mcp\"]}";
        first.enabled = false;
        first.save();
        Thread.sleep(5);   // createdAt decides which of the pair is "the newer one"

        var second = new McpServer();
        second.name = "workspace-temp";
        second.transport = McpServer.Transport.STDIO;
        second.configJson = first.configJson;
        second.enabled = false;
        second.save();

        var views = services.McpServerService.listAll();
        var older = views.stream().filter(v -> v.name().equals("workspace")).findFirst().orElseThrow();
        var newer = views.stream().filter(v -> v.name().equals("workspace-temp")).findFirst().orElseThrow();

        assertNull(older.duplicateOf(), "the original is not a duplicate of anything");
        assertEquals("workspace", newer.duplicateOf(),
                "the later registration points at the one it duplicates");
    }

    @Test
    void serversThatMerelyShareATransportAreNotDuplicates() {
        // Every stdio server shares a transport; only an identical config is a duplicate.
        var a = new McpServer();
        a.name = "alpha";
        a.transport = McpServer.Transport.STDIO;
        a.configJson = "{\"command\":\"alpha-bin\",\"args\":[]}";
        a.enabled = false;
        a.save();

        var b = new McpServer();
        b.name = "beta";
        b.transport = McpServer.Transport.STDIO;
        b.configJson = "{\"command\":\"beta-bin\",\"args\":[]}";
        b.enabled = false;
        b.save();

        assertTrue(services.McpServerService.listAll().stream().allMatch(v -> v.duplicateOf() == null),
                "different commands are different servers, however alike their transport");
    }

    // ==================== helpers ====================

    // ============ VULN-032: the per-agent toggle must revoke, not merely hide ============

    @Test
    void disablingAnMcpServerRefusesItAtDispatchNotJustInTheSchema() throws Exception {
        // VULN-032 (JCLAW-1042) reads the toggle as writing AgentToolConfig while enforcement
        // reads AgentSkillAllowedTool, concluding "the toggle never revokes". It does, through
        // a route the finding does not consider: JCLAW-883 refuses any tool absent from the set
        // the turn actually offered, and that set is derived from AgentToolConfig. This pins
        // both halves so the guarantee cannot regress silently.
        server("alpha");
        server("beta");
        ToolRegistrySync.withTools(List.of(
                handle("alpha"), action("alpha", "search"), handle("beta")), () -> {
            // Exactly the writes ApiToolsController.updateGroupForAgent performs. Both are
            // explicit: MCP is default-disabled for a non-main agent, so without granting beta
            // the comparison would be two absent tools and would pass for the wrong reason.
            var disabled = McpGrants.newRow(agent, McpServer.toolName("alpha", ""));
            disabled.enabled = false;
            disabled.save();
            var granted = McpGrants.newRow(agent, McpServer.toolName("beta", ""));
            granted.enabled = true;
            granted.save();
            JPA.em().flush();
            ToolRegistry.clearDisabledToolsCache();

            var managed = (Agent) Agent.findById(agent.id);
            var offered = ToolRegistry.getToolDefsForAgent(managed).stream()
                    .map(d -> d.function().name())
                    .collect(java.util.stream.Collectors.toSet());

            assertFalse(offered.contains("mcp_alpha"),
                    "a disabled server must not be put in front of the model");
            assertTrue(offered.contains("mcp_beta"),
                    "premise: the other server is still offered, so this is not an empty-set pass");

            var refused = ToolRegistry.executeRich("mcp_alpha", "{}", managed, offered);
            assertEquals(ToolRegistry.ToolResult.Outcome.NOT_ENABLED, refused.outcome(),
                    "the disabled server must be refused at dispatch even if the model names it "
                            + "anyway — that is what makes the AgentToolConfig toggle a "
                            + "revocation rather than a schema filter");
        });
    }

    private McpServer server(String name) {
        var s = new McpServer();
        s.name = name;
        s.transport = McpServer.Transport.STDIO;
        s.configJson = "{\"command\":\"true\",\"args\":[]}";
        s.enabled = false;
        s.save();
        return s;
    }

    /** A grant written the old way — by name — as an un-migrated database still holds it. */
    private void nameKeyed(String toolName, boolean enabled) {
        var c = new AgentToolConfig();
        c.agent = agent;
        c.toolName = toolName;
        c.enabled = enabled;
        c.save();
    }

    private static ToolRegistry.Tool handle(String serverName) {
        return stub(McpServer.toolName(serverName, ""), serverName, true);
    }

    private static ToolRegistry.Tool action(String serverName, String actionName) {
        return stub(McpServer.toolName(serverName, actionName), serverName, false);
    }

    private static ToolRegistry.Tool nativeTool(String name) {
        return stub(name, null, false);
    }

    private static ToolRegistry.Tool stub(String name, String group, boolean serverLevel) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " stub"; }
            @Override public Map<String, Object> parameters() { return Map.of(); }
            @Override public String execute(String argsJson, Agent agent) { return ""; }
            @Override public String group() { return group; }
            @Override public boolean isServerLevel() { return serverLevel; }
        };
    }
}
