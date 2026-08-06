package mcp;

import models.AgentToolConfig;
import play.db.jpa.JPA;
import services.EventLogger;

import java.util.List;

/**
 * Keeps per-agent MCP grants in step with the servers they name (JCLAW-982).
 *
 * <p>A grant row is keyed by the tool's name, and an MCP tool's name is built from its
 * server's name — {@code mcp_<server>} for the server-level handle,
 * {@code mcp_<server>_<tool>} for each action. So the join between a grant and its server
 * is a string prefix, and nothing was maintaining it: deleting a server removed the row
 * and its allowlist entries but left every grant behind, and renaming one stranded the
 * old set while granting nothing under the new name.
 *
 * <p>Measured on a live instance before this existed: 49,018 of 139,687 grant rows — 35% —
 * named a server that no longer existed, 48,803 of them from a single rename. Not a
 * privilege leak, because a grant for an absent server grants nothing; the cost is that a
 * third of the table described nothing, and the agent editor reads that table.
 *
 * <p>Deliberately not wired into {@link McpConnectionManager#stop}, which is the obvious
 * place and the wrong one: stop also runs when a server is merely disabled or renamed, so
 * sweeping there would silently revoke an operator's grants on a toggle.
 */
public final class McpGrants {

    private McpGrants() {}

    private static final String EVENT_CATEGORY = "MCP_TOOL_UNREGISTER";

    /** The server-level handle, exactly as {@code McpServerTool.name()} builds it. */
    public static String handle(String serverName) {
        return "mcp_" + serverName;
    }

    /** True when {@code toolName} is the handle for {@code serverName} or one of its actions. */
    static boolean belongsTo(String toolName, String serverName) {
        var h = handle(serverName);
        return toolName.equals(h) || toolName.startsWith(h + "_");
    }

    /**
     * Drop every grant naming {@code serverName}. Call from the delete path only.
     *
     * @return rows removed
     */
    public static int deleteForServer(String serverName) {
        var removed = namesFor(serverName);
        if (removed.isEmpty()) return 0;
        int n = deleteByToolName(removed);
        if (n > 0) {
            EventLogger.info(EVENT_CATEGORY,
                    "Removed %d agent grant(s) for deleted MCP server '%s'".formatted(n, serverName));
        }
        return n;
    }

    /**
     * Re-point grants from {@code oldName} to {@code newName}, preserving each agent's
     * choice across a rename rather than stranding it under a handle nothing will emit.
     *
     * @return rows moved
     */
    public static int renameServer(String oldName, String newName) {
        if (oldName.equals(newName)) return 0;
        var affected = namesFor(oldName);
        if (affected.isEmpty()) return 0;
        int oldPrefix = handle(oldName).length();
        int n = 0;
        for (var row : rowsByToolName(affected)) {
            row.toolName = handle(newName) + row.toolName.substring(oldPrefix);
            row.save();
            n++;
        }
        EventLogger.info(EVENT_CATEGORY,
                "Moved %d agent grant(s) from MCP server '%s' to '%s'".formatted(n, oldName, newName));
        return n;
    }

    /**
     * Remove grants whose handle names no configured server — the accumulated residue of
     * deletes and renames that happened before either was maintained.
     *
     * <p>Keeps a grant if it matches <em>any</em> live server, so a server name that is a
     * prefix of another cannot strand the longer one's rows.
     *
     * @return rows removed
     */
    public static int sweepOrphans() {
        List<String> live = JPA.em()
                .createQuery("select s.name from McpServer s", String.class).getResultList();
        var orphans = distinctMcpToolNames().stream()
                .filter(t -> live.stream().noneMatch(s -> belongsTo(t, s)))
                .toList();
        if (orphans.isEmpty()) return 0;
        int n = deleteByToolName(orphans);
        // Logged rather than silent: a sweep that removes tens of thousands of rows on a
        // boot nobody asked about should be visible in the record.
        EventLogger.info(EVENT_CATEGORY,
                "Swept %d orphaned MCP grant row(s) across %d handle(s) naming no server"
                        .formatted(n, orphans.size()));
        return n;
    }

    private static List<String> distinctMcpToolNames() {
        return JPA.em().createQuery(
                        "select distinct c.toolName from AgentToolConfig c where c.toolName like 'mcp!_%' escape '!'",
                        String.class)
                .getResultList();
    }

    private static List<String> namesFor(String serverName) {
        return distinctMcpToolNames().stream().filter(t -> belongsTo(t, serverName)).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<AgentToolConfig> rowsByToolName(List<String> toolNames) {
        return JPA.em().createQuery("select c from AgentToolConfig c where c.toolName in :names")
                .setParameter("names", toolNames)
                .getResultList();
    }

    /** Chunked because a single server can span every agent — 489 here, 280 tools each. */
    private static int deleteByToolName(List<String> toolNames) {
        int n = 0;
        for (int i = 0; i < toolNames.size(); i += 200) {
            var chunk = toolNames.subList(i, Math.min(i + 200, toolNames.size()));
            n += JPA.em().createQuery("delete from AgentToolConfig c where c.toolName in :names")
                    .setParameter("names", chunk)
                    .executeUpdate();
        }
        return n;
    }
}
