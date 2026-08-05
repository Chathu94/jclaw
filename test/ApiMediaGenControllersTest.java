import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;

/**
 * The imagegen and videogen read endpoints (JCLAW-226 / JCLAW-232), which back the
 * Settings self-hosted panels and the chat progress chip.
 *
 * <p>All of these run with no sidecar and no GPU, which is the configuration that
 * matters: a fresh install has neither, and the panels have to render something honest
 * rather than error. What is pinned here is that an absent capability reports as absent.
 *
 * <p>{@code POST /api/imagegen/local/pull} is deliberately not exercised — it starts a
 * real multi-gigabyte model download. Flagged, not faked.
 */
class ApiMediaGenControllersTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\": \"admin\", \"password\": \"changeme\"}"));
    }

    // --- auth ---

    @Test
    void everyMediaGenEndpointRequiresAuth() {
        assertEquals(401, GET("/api/imagegen/local/state").status.intValue());
        assertEquals(401, GET("/api/imagegen/models").status.intValue());
        assertEquals(401, GET("/api/imagegen/progress").status.intValue());
        assertEquals(401, GET("/api/imagegen/capability").status.intValue());
        assertEquals(401, GET("/api/videogen/jobs").status.intValue());
        assertEquals(401, GET("/api/videogen/models").status.intValue());
        assertEquals(401, GET("/api/videogen/capability").status.intValue());
        assertEquals(401, GET("/api/videogen/jobs/recent").status.intValue());
    }

    // --- imagegen ---

    @Test
    void localStateReportsTheConfiguredModelAndWhetherUvIsPresent() {
        login();
        var resp = GET("/api/imagegen/local/state");
        assertIsOk(resp);
        var body = getContent(resp);
        // The panel needs the model name to show which weights a pull would fetch, and the
        // uv verdict to decide between the download button and the missing-prereq banner.
        assertTrue(body.contains("\"model\""), body);
        assertTrue(body.contains("uvAvailable") || body.contains("\"uvReason\"")
                || body.contains("\"state\""), body);
    }

    @Test
    void theSelectableModelCatalogIsAListEvenWithNoProviderConfigured() {
        login();
        var resp = GET("/api/imagegen/models");
        assertIsOk(resp);
        assertTrue(getContent(resp).trim().startsWith("["), getContent(resp));
    }

    @Test
    void imageProgressReportsAPercentWhenNothingIsGenerating() {
        // Polled by the chat bar on a timer, so it must answer cheaply and always —
        // an error here would surface as a broken chip on every idle turn.
        login();
        var resp = GET("/api/imagegen/progress");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("percent"), getContent(resp));
    }

    @Test
    void imageCapabilityAnswersOnAHostWithNoGpu() {
        login();
        var resp = GET("/api/imagegen/capability");
        assertIsOk(resp);
        assertTrue(getContent(resp).trim().startsWith("{"), getContent(resp));
    }

    @Test
    void probingImageCapabilityIsAcceptedAndLeavesAReadableState() {
        login();
        assertIsOk(POST("/api/imagegen/capability/probe", "application/json", "{}"));
        // The probe is asynchronous; what matters is that the snapshot stays readable
        // straight afterwards rather than 500-ing while a probe is in flight.
        assertIsOk(GET("/api/imagegen/capability"));
    }

    // --- videogen ---

    @Test
    void jobsWithNoIdsReturnsAnEmptyListRatherThanEveryJob() {
        // The chat chip polls with the ids it is tracking; a missing parameter must not
        // become "give me everything".
        login();
        var resp = GET("/api/videogen/jobs");
        assertIsOk(resp);
        assertEquals("[]", getContent(resp).trim());
    }

    @Test
    void jobsSkipsUnparseableAndUnknownIdsInsteadOfFailing() {
        login();
        var resp = GET("/api/videogen/jobs?ids=abc,,-1,999999");
        assertIsOk(resp);
        assertEquals("[]", getContent(resp).trim(),
                "a stale or malformed id from a reloaded page must not break the poll");
    }

    @Test
    void recentJobsIsAListOnAFreshInstall() {
        login();
        var resp = GET("/api/videogen/jobs/recent");
        assertIsOk(resp);
        assertTrue(getContent(resp).trim().startsWith("["), getContent(resp));
    }

    @Test
    void videoModelCatalogAndCapabilityAnswerWithoutASidecar() {
        login();
        assertTrue(getContent(GET("/api/videogen/models")).trim().startsWith("["));
        assertTrue(getContent(GET("/api/videogen/capability")).trim().startsWith("{"));
    }

    @Test
    void probingVideoCapabilityIsAcceptedAndLeavesAReadableState() {
        login();
        assertIsOk(POST("/api/videogen/capability/probe", "application/json", "{}"));
        assertIsOk(GET("/api/videogen/capability"));
    }
}
