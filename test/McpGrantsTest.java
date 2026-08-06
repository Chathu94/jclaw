import mcp.McpGrants;
import models.Agent;
import models.AgentToolConfig;
import models.McpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;

/**
 * JCLAW-982: per-agent MCP grants must not outlive the servers they name.
 *
 * <p>A grant is keyed by the tool's name, and an MCP tool's name is built from its
 * server's — so the join is a string prefix that nothing was maintaining. Measured on a
 * live instance: 49,018 of 139,687 grant rows named a server that no longer existed,
 * 48,803 of them from one rename.
 *
 * <p>Every test here keeps more than one server configured. With a single server a sweep
 * that removes everything and a sweep that removes the right rows are indistinguishable —
 * the same blind spot that let the core-memory cap ship measuring the wrong thing.
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

    private void server(String name) {
        var s = new McpServer();
        s.name = name;
        s.transport = McpServer.Transport.STDIO;
        s.configJson = "{\"command\":\"true\",\"args\":[]}";
        s.enabled = false;
        s.save();
    }

    private void grant(String toolName) {
        var c = new AgentToolConfig();
        c.agent = agent;
        c.toolName = toolName;
        c.enabled = true;
        c.save();
    }

    private long grants(String like) {
        return AgentToolConfig.count("toolName like ?1", like);
    }

    private long allGrants() {
        return AgentToolConfig.count();
    }

    // --- delete ---

    @Test
    void deletingAServerTakesItsGrantsWithIt() {
        server("alpha");
        server("beta");
        grant("mcp_alpha");
        grant("mcp_alpha_search");
        grant("mcp_beta");
        grant("mcp_beta_search");
        grant("filesystem");

        int removed = McpGrants.deleteForServer("alpha");

        assertEquals(2, removed);
        assertEquals(0, grants("mcp_alpha%"), "every handle for the deleted server goes");
        assertEquals(2, grants("mcp_beta%"), "another server's grants are untouched");
        assertEquals(1, grants("filesystem"), "native tool grants are not MCP's business");
    }

    @Test
    void deletingAServerWhoseNamePrefixesAnotherLeavesTheLongerOneAlone() {
        // "google-workspace" and "google-workspace-mcp" both existed on the reporting
        // instance. A prefix match without the separator would take both.
        server("google-workspace");
        server("google-workspace-mcp");
        grant("mcp_google-workspace_drive");
        grant("mcp_google-workspace-mcp_drive");

        int removed = McpGrants.deleteForServer("google-workspace");

        // Counted exactly rather than matched with LIKE: '_' is a single-character
        // wildcard there, so "mcp_google-workspace_%" would also match the longer
        // server's rows and the assertion would pass for the wrong reason.
        assertEquals(1, removed, "only the named server's grant is removed");
        assertEquals(1, allGrants());
        assertEquals("mcp_google-workspace-mcp_drive",
                AgentToolConfig.<AgentToolConfig>findAll().getFirst().toolName,
                "the longer name is a different server, not a suffix of the deleted one");
    }

    // --- rename ---

    @Test
    void renamingAServerCarriesItsGrantsAcross() {
        // The largest orphan source: a rename stranded 48,803 rows under a handle nothing
        // would emit again, while granting nothing under the new name.
        server("old-name");
        server("bystander");
        grant("mcp_old-name");
        grant("mcp_old-name_search");
        grant("mcp_bystander_search");

        int moved = McpGrants.renameServer("old-name", "new-name");

        assertEquals(2, moved);
        assertEquals(0, grants("mcp_old-name%"), "nothing may be left under the old handle");
        assertEquals(1, grants("mcp_new-name"), "the server-level handle follows");
        assertEquals(1, grants("mcp_new-name_search"), "and so does each action");
        assertEquals(1, grants("mcp_bystander%"), "another server is not swept up in a rename");
    }

    @Test
    void renamingToTheSameNameChangesNothing() {
        server("alpha");
        grant("mcp_alpha_search");
        assertEquals(0, McpGrants.renameServer("alpha", "alpha"));
        assertEquals(1, grants("mcp_alpha_search"));
    }

    // --- sweep ---

    @Test
    void theSweepRemovesOnlyGrantsNamingNoServer() {
        server("alpha");
        server("beta");
        grant("mcp_alpha_search");            // live
        grant("mcp_beta");                    // live, server-level handle
        grant("mcp_ghost_search");            // server deleted long ago
        grant("mcp_another-ghost");           // ditto
        grant("filesystem");                  // not an MCP grant at all

        int removed = McpGrants.sweepOrphans();

        assertEquals(2, removed);
        assertEquals(1, grants("mcp_alpha%"), "a live server's grants survive");
        assertEquals(1, grants("mcp_beta%"), "including a bare server-level handle");
        assertEquals(1, grants("filesystem"), "native grants are never touched");
        assertEquals(0, grants("mcp_ghost%"));
        assertEquals(0, grants("mcp_another-ghost%"));
    }

    @Test
    void theSweepIsANoOpOnACleanTable() {
        server("alpha");
        grant("mcp_alpha_search");
        assertEquals(0, McpGrants.sweepOrphans(), "nothing orphaned means nothing removed");
        assertEquals(1, allGrants());
    }

    @Test
    void theSweepKeepsGrantsForAServerWhoseNamePrefixesAnother() {
        // Guards the direction that would be silent: keeping a grant if it matches ANY
        // live server, rather than the first one checked.
        server("google-workspace-mcp");
        grant("mcp_google-workspace-mcp_drive");
        grant("mcp_google-workspace_drive");   // the older, now-absent server

        int removed = McpGrants.sweepOrphans();

        assertEquals(1, removed);
        assertEquals(1, grants("mcp_google-workspace-mcp%"), "the live server keeps its grant");
    }

    // --- duplicate visibility (the reason this ticket exists) ---

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
}
