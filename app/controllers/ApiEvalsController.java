package controllers;

import com.google.gson.JsonObject;
import models.Agent;
import play.mvc.Before;
import play.mvc.Controller;
import services.evals.EvalCapture;
import services.evals.EvalDatasetLoader;
import services.evals.EvalRunner;
import services.evals.EvalSuite;
import utils.ApiResponses;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static utils.GsonHolder.GSON;

/**
 * Drives an eval suite against a live agent and returns the recorded run
 * (JCLAW-883). The offline half — validating the dataset and scoring a recording —
 * stays in {@code ./jclaw.sh evals}, which boots no framework; this endpoint exists
 * because driving an agent needs what that CLI deliberately does without: JPA, a
 * configured provider, and the tool registry.
 *
 * <p>Gated by {@link LoadtestAuthCheck} — loopback origin plus {@code X-Loadtest-Auth}
 * carrying {@code application.secret}. Same trust boundary and same reason as the
 * loadtest endpoints: an operator-run measurement harness on the local host, with no
 * plaintext admin credential to log in with. Sharing the gate rather than inventing a
 * second one keeps the number of ways into the harness surface at one.
 *
 * <p>The response body IS the recorded-run file. Redirect it to disk and score it with
 * {@code ./jclaw.sh evals --responses}; keeping capture and scoring as two steps means
 * a sweep can be re-scored against a changed suite without paying the model again.
 */
public class ApiEvalsController extends Controller {

    /** Where the versioned suites live, relative to the app root. */
    private static final String SUITE_DIR = "evals/suites";

    /**
     * Upper bound on the fan-out an operator can request. The ceiling exists so a
     * sweep cannot be turned into a load test against a shared provider by a typo
     * in the request body.
     */
    private static final int MAX_CONCURRENCY = 16;

    @Before
    static void requireLoadtestAuth() {
        LoadtestAuthCheck.checkLoadtestAuth();
    }

    /**
     * {@code POST /api/evals/capture} with
     * {@code {"suite": "<id>", "agent": "<name>", "version": <n>?, "concurrency": <n>?}}.
     *
     * <p>Both {@code suite} and {@code agent} are required. Defaulting the agent —
     * to the main one, or to whatever is first in the table — would let a sweep run
     * against the operator's working agent by omission, which is exactly the accident
     * worth designing out.
     */
    public static void capture() {
        var body = JsonBodyReader.readJsonBody();
        var suiteId = JsonBodyReader.requiredOr400(body, "suite");
        var agentName = JsonBodyReader.requiredOr400(body, "agent");

        var agent = Agent.findByName(agentName);
        if (agent == null) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "No agent named '%s'".formatted(agentName));
        }

        var suite = resolveSuite(suiteId, readInt(body, "version", 0));
        int concurrency = Math.clamp(readInt(body, "concurrency", EvalRunner.DEFAULT_CONCURRENCY),
                1, MAX_CONCURRENCY);

        renderJSON(GSON.toJson(EvalCapture.run(suite, agent, concurrency)));
    }

    /**
     * Load the dataset and pick one suite. A {@code version} of 0 means "unspecified":
     * the highest shipped version wins, and the caller can see which one ran because
     * the recorded file names it.
     */
    private static EvalSuite resolveSuite(String suiteId, int version) {
        List<EvalSuite> suites;
        try {
            suites = EvalDatasetLoader.loadAll(Path.of(SUITE_DIR));
        } catch (RuntimeException e) {
            // Also the path a production distribution takes: evals/ is a developer
            // artifact and does not ship, so say that rather than 500 on a missing dir.
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "Cannot read the eval dataset at %s: %s".formatted(SUITE_DIR, e.getMessage()));
            return null;  // unreachable: error() throws
        }
        var match = suites.stream()
                .filter(s -> s.id().equals(suiteId))
                .filter(s -> version <= 0 || s.version() == version)
                .max(Comparator.comparingInt(EvalSuite::version))
                .orElse(null);
        if (match == null) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND,
                    "No suite '%s'%s in %s".formatted(
                            suiteId, version > 0 ? " at version " + version : "", SUITE_DIR));
        }
        return match;
    }

    private static int readInt(JsonObject body, String key, int defaultValue) {
        if (body == null || !body.has(key) || body.get(key).isJsonNull()) return defaultValue;
        try {
            return body.get(key).getAsInt();
        } catch (RuntimeException e) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "'%s' must be a number".formatted(key));
            return defaultValue;  // unreachable: error() throws
        }
    }
}
