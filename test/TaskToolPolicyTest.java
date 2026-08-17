import llm.LlmTypes.ToolDef;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.TaskToolPolicy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JCLAW-1068: per-task toolset restriction.
 *
 * <p>Pins both halves of the gate — that {@code Task.enabledToolNames} parses to an
 * allow-list, and that the allow-list narrows the {@code ToolDef} list. The second is
 * what makes the restriction real: {@code ToolCallLoopRunner.offeredToolNames} builds
 * the JCLAW-883 dispatch guard from this same list, so a def withheld here is also
 * refused by {@code ToolRegistry.executeRich}.
 */
public class TaskToolPolicyTest extends UnitTest {

    private static ToolDef def(String name) {
        return ToolDef.of(name, name + " description", Map.of("type", "object"));
    }

    private static List<ToolDef> defs(String... names) {
        return java.util.Arrays.stream(names).map(TaskToolPolicyTest::def).toList();
    }

    private static List<String> namesOf(List<ToolDef> defs) {
        return defs.stream().map(d -> d.function().name()).toList();
    }

    // ==================== parse ====================

    @Test
    public void absentOrBlankAllowlistIsUnrestricted() {
        assertNull(TaskToolPolicy.parse(null));
        assertNull(TaskToolPolicy.parse(""));
        assertNull(TaskToolPolicy.parse("   "));
    }

    @Test
    public void emptyArrayIsUnrestrictedNotZeroTools() {
        // A cleared multi-select must not strand the task with no tools.
        assertNull(TaskToolPolicy.parse("[]"));
    }

    @Test
    public void parsesJsonArrayOfNames() {
        assertEquals(Set.of("exec", "filesystem"),
                TaskToolPolicy.parse("[\"exec\",\"filesystem\"]"));
    }

    @Test
    public void trimsAndDropsBlankEntries() {
        assertEquals(Set.of("exec"), TaskToolPolicy.parse("[\"  exec  \",\"\",\"   \"]"));
    }

    @Test
    public void acceptsTheCommaSeparatedFormAlreadyInTheColumn() {
        // TaskTool stored whatever the model wrote and nothing validated it, so live rows
        // hold this shape. Rejecting it would run a task the operator believed was fenced.
        assertEquals(Set.of("datetime", "web_search", "web_fetch", "mcp_google-workspace-mcp"),
                TaskToolPolicy.parse("datetime,web_search,web_fetch,mcp_google-workspace-mcp"));
    }

    @Test
    public void acceptsABareSingleToolName() {
        assertEquals(Set.of("mcp_google-workspace-mcp"),
                TaskToolPolicy.parse("mcp_google-workspace-mcp"));
    }

    @Test
    public void truncatedJsonIsMalformedNotASingleToolName() {
        // The trap the delimited form opens: read as a name list, {@code ["exec",} would
        // fence the task down to nothing instead of failing open.
        assertNull(TaskToolPolicy.parse("[\"exec\","));
        assertNull(TaskToolPolicy.parse("{\"exec\""));
    }

    @Test
    public void malformedAllowlistFailsOpen() {
        // Fail open rather than strand the task; the parse path logs a warning.
        assertNull(TaskToolPolicy.parse("[\"exec\","));
        assertNull(TaskToolPolicy.parse("not json at all"));
        assertNull(TaskToolPolicy.parse("{\"exec\":true}"));
        assertNull(TaskToolPolicy.parse("[\"exec\", 7]"));
    }

    // ==================== restrict ====================

    @Test
    public void nullAllowlistLeavesDefsUntouched() {
        var all = defs("exec", "filesystem", "web_search");
        assertSame(all, TaskToolPolicy.restrict(all, null, "t", "a"));
    }

    @Test
    public void withholdsToolsOutsideTheAllowlist() {
        var kept = TaskToolPolicy.restrict(defs("exec", "filesystem", "web_search"),
                Set.of("filesystem"), "payslip", "main");
        assertEquals(List.of("filesystem"), namesOf(kept));
    }

    @Test
    public void keepsEveryToolWhenAllowlistCoversThemAll() {
        var kept = TaskToolPolicy.restrict(defs("exec", "filesystem"),
                Set.of("exec", "filesystem"), "t", "a");
        assertEquals(List.of("exec", "filesystem"), namesOf(kept));
    }

    @Test
    public void allowlistNameMatchingNoToolIsIgnoredNotFatal() {
        // Operator typo: the run proceeds with whatever did match.
        var kept = TaskToolPolicy.restrict(defs("exec", "filesystem"),
                Set.of("filesystem", "no_such_tool"), "t", "a");
        assertEquals(List.of("filesystem"), namesOf(kept));
    }

    @Test
    public void allowlistMatchingNothingYieldsNoTools() {
        var kept = TaskToolPolicy.restrict(defs("exec", "filesystem"),
                Set.of("totally_unknown"), "t", "a");
        assertTrue(kept.isEmpty());
    }

    @Test
    public void mcpServersAreAddressedByTheirServerLevelHandle() {
        // getToolDefsForAgent offers one entry per server (JCLAW-281), so that is the
        // name an allow-list has to carry.
        var kept = TaskToolPolicy.restrict(defs("exec", "mcp_google-workspace-mcp"),
                Set.of("mcp_google-workspace-mcp"), "payslip", "main");
        assertEquals(List.of("mcp_google-workspace-mcp"), namesOf(kept));
    }
}
