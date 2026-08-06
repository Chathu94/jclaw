import jobs.OrphanFkMigrator;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * JCLAW-984: a parent reference that shipped as a plain id column gets a real foreign key,
 * and the rows whose parent is already gone are resolved first so the constraint can be
 * created at all.
 *
 * <p>Runs against a dedicated throwaway in-memory H2 per test, in {@code MODE=MYSQL} — the
 * same H2 build and mode the app's test DB uses — rather than the shared play test database.
 * The DDL is real, so keeping it self-contained is what stops it being seen by the concurrent
 * unit and functional lanes (mirrors {@code CascadeFkMigratorTest}).
 */
class OrphanFkMigratorTest extends UnitTest {

    private static Connection freshH2() throws Exception {
        var url = "jdbc:h2:mem:orphanfk_" + System.nanoTime() + ";MODE=MYSQL";
        var conn = DriverManager.getConnection(url);
        conn.setAutoCommit(true);
        return conn;
    }

    /** Parent plus a child holding a plain id column and no foreign key — the pre-JCLAW-984 shape. */
    private static void seedUnconstrained(Connection conn) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE orphan_parent (id BIGINT PRIMARY KEY)");
            s.execute("CREATE TABLE orphan_child (id BIGINT PRIMARY KEY, parent_id BIGINT)");
            s.execute("INSERT INTO orphan_parent VALUES (1)");
            s.execute("INSERT INTO orphan_child VALUES (10, 1)");    // parent alive
            s.execute("INSERT INTO orphan_child VALUES (11, 99)");   // parent long gone
            s.execute("INSERT INTO orphan_child VALUES (12, NULL)"); // never had one
        }
    }

    private static int count(Connection conn, String sql) throws Exception {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String deleteRule(Connection conn, String childTable, String childColumn)
            throws Exception {
        try (var ps = conn.prepareStatement(
                "SELECT rc.delete_rule "
                        + "FROM information_schema.referential_constraints rc "
                        + "JOIN information_schema.key_column_usage kcu "
                        + "  ON kcu.constraint_name = rc.constraint_name "
                        + " AND kcu.constraint_schema = rc.constraint_schema "
                        + "WHERE UPPER(kcu.table_name) = ? AND UPPER(kcu.column_name) = ?")) {
            ps.setString(1, childTable);
            ps.setString(2, childColumn);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static final OrphanFkMigrator.Ref REF = new OrphanFkMigrator.Ref(
            "ORPHAN_CHILD", "PARENT_ID", "ORPHAN_PARENT");

    @Test
    void theOrphanIsDeletedThenTheColumnIsConstrained() throws Exception {
        try (Connection conn = freshH2()) {
            seedUnconstrained(conn);
            assertNull(deleteRule(conn, "ORPHAN_CHILD", "PARENT_ID"),
                    "precondition: the column must start with no foreign key at all");

            var result = OrphanFkMigrator.constrain(conn, List.of(REF));

            assertEquals(1, result.constrained());
            assertEquals(1, result.orphansResolved(), "only the row naming a dead parent goes");
            assertEquals(2, count(conn, "SELECT COUNT(*) FROM orphan_child"),
                    "the live row and the never-linked row both survive");
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM orphan_child WHERE id = 11"));
            assertEquals("CASCADE", deleteRule(conn, "ORPHAN_CHILD", "PARENT_ID"));

            // And the constraint it installed actually cascades.
            try (Statement s = conn.createStatement()) {
                s.execute("DELETE FROM orphan_parent WHERE id = 1");
            }
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM orphan_child WHERE id = 10"),
                    "deleting the parent must now take the child with it");
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM orphan_child WHERE id = 12"),
                    "a row that never named a parent is unaffected by the cascade");
        }
    }

    @Test
    void anAlreadyConstrainedColumnIsSkippedWhole() throws Exception {
        try (Connection conn = freshH2()) {
            seedUnconstrained(conn);
            OrphanFkMigrator.constrain(conn, List.of(REF));

            var second = OrphanFkMigrator.constrain(conn, List.of(REF));

            assertEquals(0, second.constrained(), "a fresh install must do no DDL");
            assertEquals(0, second.orphansResolved(),
                    "and must not re-scan for orphans a constraint already makes impossible");
        }
    }

    @Test
    void theInventoryLeavesTheResultPointerAlone() {
        // A cascade from the attachment would delete the job when a chat message is deleted,
        // and any managed association there breaks the flush before the DDL rule applies.
        assertTrue(OrphanFkMigrator.REFS.stream()
                        .noneMatch(r -> r.childColumn().equals("RESULT_ATTACHMENT_ID")),
                "the job's result pointer is not an ownership reference and must stay unconstrained");
        for (var ref : OrphanFkMigrator.REFS) {
            assertFalse(ref.childTable().isBlank());
            assertFalse(ref.childColumn().isBlank());
            assertFalse(ref.parentTable().isBlank());
        }
    }
}
