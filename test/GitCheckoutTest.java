import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.GitCheckout;

/**
 * The commit reported by the Settings panels.
 *
 * <p>The suite runs from the JClaw checkout, so {@code describe()} is expected to
 * answer here — but the shape is what is asserted, not a literal id, which would
 * change with every commit including the one adding this test. The null case (a
 * packaged install with no repository) is not reachable from inside a checkout and
 * is left to the panels' own handling of a null field.
 */
class GitCheckoutTest extends UnitTest {

    @Test
    void reportsAnAbbreviatedCommitForThisCheckout() {
        var describe = GitCheckout.describe();
        assertNotNull(describe, "the test suite runs from a git checkout, so a commit is expected");
        // "-dirty" is appended, not substituted, so strip it before checking the id.
        var id = describe.endsWith("-dirty")
                ? describe.substring(0, describe.length() - "-dirty".length())
                : describe;
        assertTrue(id.matches("[0-9a-f]{8,}"),
                "expected an abbreviated hex commit id, got: " + describe);
    }

    /**
     * Two calls in a row must agree. describe() shells out twice — once for the id and
     * once for the dirty check — so a caller could otherwise see a torn answer; this
     * also catches the id being read from something that mutates per invocation.
     */
    @Test
    void isStableAcrossCalls() {
        assertEquals(GitCheckout.describe(), GitCheckout.describe());
    }
}
