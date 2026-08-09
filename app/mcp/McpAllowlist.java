package mcp;

import models.Agent;
import models.AgentSkillAllowedTool;
import play.db.jpa.JPA;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-agent MCP tool allowlist (JCLAW-32).
 *
 * <p>Reuses the {@link AgentSkillAllowedTool} table — the existing
 * Confused-Deputy-Proof authority for shell allowlists — by namespacing
 * each MCP server's grants under {@code skill_name = "mcp:<server>"}. The
 * table comment makes the strong claim that this table, NOT in-memory
 * state, is the call-time authority; this class extends that guarantee
 * to MCP tool invocations.
 *
 * <p>{@code tool_name} stored here is the <em>inner</em> MCP tool name
 * (e.g. {@code "create_issue"}), not the prefixed adapter name
 * ({@code "mcp_github_create_issue"}). The prefix is redundant given
 * {@code skill_name} already carries the server.
 *
 * <p><b>Granting model.</b> JCLAW-32 broadcasts: on connect, every existing
 * top-level agent gets one row per advertised tool. JCLAW-33's admin UI will
 * layer per-agent toggles on top by selectively deleting rows. The wire-format
 * row (agent, skill, tool) doesn't change between the two stories — only
 * the policy that decides which rows to write.
 *
 * <p><b>Spawned subagents are excluded from the broadcast</b> and get their
 * grants once, from {@link #backfillForAgent} at creation. They outnumber real
 * agents by two orders of magnitude on a busy instance (each spawn leaves a row
 * behind), and broadcasting to them made every reconnect rewrite
 * {@code subagents × tools} rows — minutes of work before a server could report
 * CONNECTED. The broadcast therefore also leaves their rows ALONE rather than
 * deleting them, so a reconnect mid-run cannot strip a live subagent's access.
 *
 * <p><b>Transactions.</b> Every method here is tx-agnostic: each call
 * issues plain JPA operations and expects to run inside a caller-supplied
 * transaction (so the check + the audit log can land in one atomic write).
 * Callers wrap with {@link services.Tx#run}.
 */
public final class McpAllowlist {

    public static final String SKILL_PREFIX = "mcp:";

    private static final String QUERY_SKILL_NAME = "skillName = ?1";

    /** Agents the broadcast covers: everything that is not a spawned subagent. */
    private static final String TOP_LEVEL_AGENTS = "parentAgent is null";

    /** Rows this server broadcast to top-level agents — the set the broadcast owns. */
    private static final String QUERY_SKILL_TOP_LEVEL = "skillName = ?1 and agent.parentAgent is null";

    /** Same set, phrased for a bulk DELETE: JPQL forbids the implicit join an
     *  {@code agent.parentAgent} path would need there, so it goes via a subquery. */
    private static final String DELETE_SKILL_TOP_LEVEL =
            "skillName = ?1 and agent in (select a from Agent a where a.parentAgent is null)";

    private McpAllowlist() {}

    /**
     * Replace this server's allowlist rows for every top-level agent with the
     * current tool list. Idempotent — clears prior rows for this server
     * scope first, then inserts fresh. Safe to call on every reconnect or
     * when the server's tool list changes via {@code tools/list_changed}.
     *
     * <p>Spawned subagents are outside this scope entirely, read and written —
     * see the class doc. Their rows are neither counted nor deleted here.
     *
     * <p>No-op short-circuit: an unchanged reconnect (or a {@code list_changed}
     * that didn't actually change anything) is the common case. When the
     * incoming tool-name set already matches the broadcast we last wrote — same
     * distinct tools, and exactly {@code agents × tools} rows still present —
     * we skip the delete+reinsert entirely and return the existing row count.
     * The {@code agents × tools} guard keeps this strictly behavior-preserving:
     * if a future per-agent toggle (JCLAW-33) selectively deleted rows, the
     * count won't match and we fall through to a full rewrite as before.
     */
    public static int registerForAllAgents(String serverName, List<McpToolDef> tools) {
        var skillName = SKILL_PREFIX + serverName;

        List<Agent> agents = Agent.<Agent>find(TOP_LEVEL_AGENTS).fetch();
        Set<String> incoming = new HashSet<>();
        for (var tool : tools) incoming.add(tool.name());

        List<AgentSkillAllowedTool> existing =
                AgentSkillAllowedTool.<AgentSkillAllowedTool>find(QUERY_SKILL_TOP_LEVEL, skillName).fetch();
        Set<String> current = new HashSet<>();
        for (var row : existing) current.add(row.toolName);
        if (current.equals(incoming) && existing.size() == agents.size() * incoming.size()) {
            return existing.size();
        }

        AgentSkillAllowedTool.delete(DELETE_SKILL_TOP_LEVEL, skillName);
        if (tools.isEmpty()) return 0;
        int written = 0;
        for (var agent : agents) {
            for (var tool : tools) {
                var row = new AgentSkillAllowedTool();
                row.agent = agent;
                row.skillName = skillName;
                row.toolName = tool.name();
                row.save();
                written++;
            }
        }
        return written;
    }

    /** Remove every allowlist row this server contributed. Returns the row count. */
    public static int unregister(String serverName) {
        var skillName = SKILL_PREFIX + serverName;
        return AgentSkillAllowedTool.delete(QUERY_SKILL_NAME, skillName);
    }

    /**
     * Drop every MCP grant held by one agent. A subagent's grants are written
     * once at spawn and the broadcast never revisits them, so each spawn would
     * otherwise leak one row per tool per server permanently — the agent row
     * outlives the run to keep its transcript readable.
     *
     * <p>Scoped with a LIKE on the {@code mcp:} namespace so shell-allowlist
     * rows in the same table, which this class does not own, are left alone.
     *
     * @return rows removed
     */
    public static int revokeForAgent(Agent agent) {
        if (agent == null || agent.id == null) return 0;
        return AgentSkillAllowedTool.delete(
                "agent = ?1 and skillName like ?2", agent, SKILL_PREFIX + "%");
    }

    /**
     * Drop MCP grants belonging to subagents that are no longer running, and
     * rows naming a server that no longer exists.
     *
     * <p>Both are backlog: grants leaked before {@link #revokeForAgent} existed,
     * and rows stranded when a server was renamed or deleted while disconnected
     * (the delete path only clears the name it knew). Left in place they are
     * re-read on every connect, which is what made a large server take minutes
     * to report CONNECTED.
     *
     * @param liveServerNames servers currently configured; rows naming anything
     *                        else are stranded
     * @param runningAgentIds subagents with a run still in flight, whose grants
     *                        must survive
     * @return rows removed
     */
    public static int sweepStaleGrants(Set<String> liveServerNames, Set<Long> runningAgentIds) {
        int removed = 0;
        for (var skillName : distinctMcpSkillNames()) {
            if (!liveServerNames.contains(skillName.substring(SKILL_PREFIX.length()))) {
                removed += AgentSkillAllowedTool.delete(QUERY_SKILL_NAME, skillName);
            }
        }
        List<Agent> subagents = Agent.<Agent>find("parentAgent is not null").fetch();
        for (var agent : subagents) {
            if (runningAgentIds.contains(agent.id)) continue;
            removed += revokeForAgent(agent);
        }
        return removed;
    }

    /** Distinct {@code mcp:*} scopes present in the table, live or stranded. */
    private static List<String> distinctMcpSkillNames() {
        return JPA.em().createQuery(
                        "select distinct a.skillName from AgentSkillAllowedTool a "
                                + "where a.skillName like :prefix",
                        String.class)
                .setParameter("prefix", SKILL_PREFIX + "%")
                .getResultList();
    }

    /**
     * Backfill grants for a newly-created agent against every server
     * already connected. Without this an agent created post-connect
     * would silently see zero MCP tools — JCLAW-31's broadcast would
     * have happened before the agent existed.
     */
    public static int backfillForAgent(Agent agent) {
        if (agent == null || agent.id == null) return 0;
        int written = 0;
        for (var serverName : McpConnectionManager.connectedServerNames()) {
            var tools = McpConnectionManager.tools(serverName);
            if (tools.isEmpty()) continue;
            var skillName = SKILL_PREFIX + serverName;
            for (var tool : tools) {
                var row = new AgentSkillAllowedTool();
                row.agent = agent;
                row.skillName = skillName;
                row.toolName = tool.name();
                row.save();
                written++;
            }
        }
        return written;
    }

    /**
     * Confused-Deputy-Proof gate: does {@code agent} hold a row granting
     * {@code toolName} on {@code serverName}? Returns {@code false} when no
     * row exists, including the case where the agent or server doesn't
     * exist at all.
     */
    public static boolean isAllowed(Agent agent, String serverName, String toolName) {
        if (agent == null || agent.id == null) return false;
        var skillName = SKILL_PREFIX + serverName;
        return AgentSkillAllowedTool.count(
                "agent = ?1 AND skillName = ?2 AND toolName = ?3",
                agent, skillName, toolName) > 0;
    }
}
