import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.RestartService;

import java.util.ArrayList;

/**
 * The restart endpoint. Every test here installs
 * {@link RestartService#spawnerForTest} first — without it a POST would hand
 * off for real and reboot the JVM running the suite.
 */
class ApiSystemControllerTest extends FunctionalTest {

    private final ArrayList<RestartService.Plan> spawned = new ArrayList<>();

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        spawned.clear();
        RestartService.spawnerForTest = spawned::add;
    }

    @AfterEach
    void clearSeam() {
        RestartService.spawnerForTest = null;
    }

    private void login() {
        var response = POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """);
        assertIsOk(response);
    }

    // --- Auth gate ---

    @Test
    void preflightRequiresAuth() {
        assertEquals(401, GET("/api/system/restart").status.intValue());
    }

    @Test
    void restartRequiresAuth() {
        assertEquals(401, POST("/api/system/restart", "application/json", "{}").status.intValue());
        // An unauthenticated caller must not even reach the handoff.
        assertEquals(0, spawned.size());
    }

    // --- Preflight ---

    @Test
    void preflightReportsAvailabilityAndInFlightWorkWithoutRestarting() {
        login();
        var response = GET("/api/system/restart");
        assertIsOk(response);

        var body = getContent(response);
        assertTrue(body.contains("\"available\":true"));
        assertTrue(body.contains("\"runningTasks\":0"));
        assertTrue(body.contains("\"activeSubagentRuns\":0"));
        // GET is a preflight; it must never hand off.
        assertEquals(0, spawned.size());
    }

    // --- Restart ---

    @Test
    void restartAcksWith202AndHandsOffExactlyOnce() {
        login();
        var response = POST("/api/system/restart", "application/json", "{}");

        // 202, not 200: the reboot is accepted, not completed. This also pins
        // the bug where the success render sat inside a catch-all — Play's
        // RenderJson is a RuntimeException, so the handler caught its own
        // result and answered 500.
        assertEquals(202, response.status.intValue());
        assertTrue(getContent(response).contains("\"status\":\"ok\""));
        assertEquals(1, spawned.size());
        assertTrue(spawned.getFirst().command().contains("restart"));
    }
}
