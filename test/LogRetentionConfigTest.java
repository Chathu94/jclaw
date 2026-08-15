import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import play.Play;
import play.test.UnitTest;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.regex.Pattern;

/**
 * Retention policy of the shipped log4j2 configs (JCLAW-1057).
 *
 * <p>Guards a defect that was silent for 70 days: the configs declared
 * {@code <DefaultRolloverStrategy max="30"/>}, which reads as "keep 30 archives" but
 * bounds the {@code %i} counter — and these filePatterns have no {@code %i}, so nothing
 * was ever pruned. Archives accumulated one per day until the directory reached 97 MB.
 *
 * <p>Testing configuration rather than behaviour is deliberate: whether log4j2 honours a
 * {@code Delete} action is log4j2's contract to keep, but whether JClaw still *asks* for
 * one is ours, and that is the half that regressed. Mirrors
 * {@code EvalSuiteConformanceTest}, which fails the build on a malformed dataset for the
 * same reason.
 */
class LogRetentionConfigTest extends UnitTest {

    private Element rollingFile(String configName) throws Exception {
        var file = new File(Play.applicationPath, "conf/" + configName);
        assertTrue(file.isFile(), "missing config: " + file);
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
        var nodes = doc.getElementsByTagName("RollingFile");
        assertEquals(1, nodes.getLength(), configName + " should declare exactly one RollingFile");
        return (Element) nodes.item(0);
    }

    private Element deleteAction(Element rollingFile, String configName) {
        var strategies = rollingFile.getElementsByTagName("DefaultRolloverStrategy");
        assertEquals(1, strategies.getLength(), configName + " should declare one rollover strategy");
        var strategy = (Element) strategies.item(0);

        // The original defect in one assertion: max= governs %i, so on a %i-less pattern
        // it prunes nothing while reading exactly like a retention cap.
        assertEquals("", strategy.getAttribute("max"),
                configName + " must not rely on max= for retention — it bounds the %i "
                        + "counter, which these date-only patterns do not use, so it "
                        + "silently keeps every archive forever");

        var deletes = strategy.getElementsByTagName("Delete");
        assertEquals(1, deletes.getLength(),
                configName + " must declare a Delete action or archives accumulate without bound");
        return (Element) deletes.item(0);
    }

    private void assertRetains(String configName, String ownArchive, String foreignArchive)
            throws Exception {
        var rolling = rollingFile(configName);
        var delete = deleteAction(rolling, configName);

        var ages = delete.getElementsByTagName("IfLastModified");
        assertEquals(1, ages.getLength(), configName + " should bound retention by age");
        assertEquals("P30D", ((Element) ages.item(0)).getAttribute("age"),
                configName + " should keep 30 days of archives");

        var names = delete.getElementsByTagName("IfFileName");
        assertEquals(1, names.getLength(), configName + " should scope Delete to its own files");
        var regex = Pattern.compile(((Element) names.item(0)).getAttribute("regex"));

        assertTrue(regex.matcher(ownArchive).matches(),
                configName + " must prune its own archives; " + regex + " missed " + ownArchive);
        // The hazard this scoping exists for: a bare application-*.log.gz would also match
        // the test appender's files, so one config would prune another's history.
        assertFalse(regex.matcher(foreignArchive).matches(),
                configName + " must not prune another appender's archives; " + regex
                        + " matched " + foreignArchive);
    }

    @Test
    void devConfigPrunesOnlyItsOwnArchives() throws Exception {
        assertRetains("log4j2.xml", "application-2026-06-06.log.gz",
                "application-test-2026-06-06.log.gz");
    }

    @Test
    void prodConfigPrunesOnlyItsOwnArchives() throws Exception {
        assertRetains("log4j2-prod.xml", "application-2026-06-06.log.gz",
                "application-test-2026-06-06.log.gz");
    }

    @Test
    void testConfigPrunesOnlyItsOwnArchives() throws Exception {
        assertRetains("log4j2-test.xml", "application-test-2026-06-06.log.gz",
                "application-2026-06-06.log.gz");
    }

    /**
     * A second rollover inside one day needs {@code %i} in the pattern, which would add a
     * {@code -N} suffix. The retention regexes accept it already, so adding it later
     * cannot silently switch pruning back off — the failure mode this whole test exists
     * to prevent.
     */
    @Test
    void retentionSurvivesAnIndexedArchiveName() throws Exception {
        var delete = deleteAction(rollingFile("log4j2.xml"), "log4j2.xml");
        var regex = Pattern.compile(((Element) delete.getElementsByTagName("IfFileName").item(0))
                .getAttribute("regex"));
        assertTrue(regex.matcher("application-2026-06-06-2.log.gz").matches(),
                "an indexed archive must still be prunable: " + regex);
    }
}
