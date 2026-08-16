import models.Agent;
import models.AgentSkillConfig;
import models.AgentToolConfig;
import models.ApiToken;
import models.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.AgentService;
import services.ConfigService;
import services.Tx;
import utils.TokenHasher;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JCLAW-1023: the writes that hand an agent new capability are operator-only.
 *
 * <p>An agent reaches the API through the {@code jclaw_api} tool, which authenticates with the
 * internal bearer token; {@code AuthCheck} then stamps the session with the agent principal.
 * Seven writes across four controllers grant capability and are gated on that principal:
 * the per-agent tool grant and MCP-group grant ({@code ApiToolsController}), the
 * {@code acpAllowed} flag that is {@code SubagentAcpRunner}'s entire security boundary
 * ({@code ApiAgentsController}), the skill install and the skill toggle that move
 * shell-allowlist rows ({@code ApiSkillsController}), and the config write and delete
 * ({@code ApiConfigController}, JCLAW-1022).
 *
 * <p>Config is the widest of them and the reason the others are not sufficient alone: the table
 * holds the values the gates above read, so a caller able to write it widens every control
 * rather than defeating one.
 *
 * <p>Tests marked CONTROL pass with the guards reverted. They are here to pin that the guards
 * did not over-reach — reads and non-privileged writes must keep working for both principals.
 */
class ApiToolsControllerOperatorOnlyTest extends FunctionalTest {

    private static final String GRANT_BODY = "{\"enabled\":true}";

    /** No global skill by this name exists, so an ungated call falls through to 404/400 — which
     *  is what makes the 403 assertions below fail if the guard is removed. */
    private static final String MISSING_SKILL = "definitely-not-a-real-skill";

    // No Fixtures.deleteDatabase(): every assertion here is scoped to a freshly-created agent
    // or a freshly-minted token row, and play1 runs test classes concurrently — wiping the
    // shared H2 would only put this class in the way of its siblings (JCLAW-1012).
    @BeforeEach
    void setup() {
        AuthFixture.seedAdminPassword("changeme");
    }

    @AfterEach
    void dropCookieJar() {
        clearCookies();
    }

    private void login() {
        // FunctionalTest.savedCookies is JVM-global and login() does not clear the session it
        // inherits, so a bearer response left in the jar by a concurrent class would carry the
        // agent principal straight into this operator session and 403 the assertions below.
        clearCookies();
        var response = POST("/api/auth/login", "application/json",
                "{\"username\": \"admin\", \"password\": \"changeme\"}");
        assertIsOk(response);
    }

    /** Run an agent-principal request and wipe the shared cookie jar afterwards: AuthCheck's
     *  bearer branch answers with a Set-Cookie carrying the agent principal, and
     *  FunctionalTest.savedCookies is a private static folded into every later request — from
     *  any class, since play1 runs test classes concurrently. Left behind it 403s a sibling. */
    private Http.Response asAgent(Supplier<Http.Response> call) {
        try {
            return call.get();
        }
        finally {
            clearCookies();
        }
    }

    /** A request carrying the internal bearer token — indistinguishable from a jclaw_api call. */
    private static Http.Request agentRequest() {
        return requestWithToken(AuthFixture.seedBearerToken());
    }

    private static Http.Request requestWithToken(String token) {
        var request = newRequest();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + token));
        return request;
    }

    /** Mint a bearer row owned by someone other than the system owner — the identity a
     *  username-comparison guard would trust by default. */
    private static String seedTokenOwnedBy(String owner) {
        return fetchInFreshTx(() -> {
            var plaintext = TokenHasher.mint();
            var row = new ApiToken();
            row.ownerUsername = owner;
            row.secretHash = TokenHasher.hash(plaintext);
            row.save();
            return plaintext;
        });
    }

    private static <T> T fetchInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            }
            catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    private Long createAgent(String name) {
        return fetchInFreshTx(() -> AgentService.create(name, "openrouter", "gpt-4.1").id);
    }

    private static boolean grantExistsFor(Long agentId, String tool) {
        return fetchInFreshTx(() -> {
            Agent agent = Agent.findById(agentId);
            var config = AgentToolConfig.findByAgentAndTool(agent, tool);
            return config != null && config.enabled;
        });
    }

    private static boolean acpAllowedFor(Long agentId) {
        return fetchInFreshTx(() -> {
            Agent agent = Agent.findById(agentId);
            return agent != null && agent.acpAllowed;
        });
    }

    private static boolean skillConfigExistsFor(Long agentId, String skill) {
        return fetchInFreshTx(() -> {
            Agent agent = Agent.findById(agentId);
            return AgentSkillConfig.findByAgentAndSkill(agent, skill) != null;
        });
    }

    // --- PUT /api/agents/{id}/tools/{name} ---

    @Test
    void agentPrincipalCannotGrantItselfATool() {
        var id = createAgent("operator-only-self-grant");

        var resp = asAgent(() -> PUT(agentRequest(), "/api/agents/" + id + "/tools/exec",
                "application/json", GRANT_BODY));

        assertStatus(403, resp);
        assertTrue(getContent(resp).contains("operator_only"),
                "expected the operator_only error code; got: " + getContent(resp));
        assertFalse(grantExistsFor(id, "exec"),
                "the rejected PUT must not have written a grant row");
    }

    @Test
    void agentPrincipalCannotGrantAnotherAgentATool() {
        // The gate is on the principal, not on self-reference: granting a sibling agent exec
        // and then talking to it is the same escalation one hop out.
        var victim = createAgent("operator-only-cross-grant-victim");

        var resp = asAgent(() -> PUT(agentRequest(), "/api/agents/" + victim + "/tools/exec",
                "application/json", GRANT_BODY));

        assertStatus(403, resp);
        assertFalse(grantExistsFor(victim, "exec"),
                "a cross-agent grant must be refused too");
    }

    /** CONTROL — passes with the guard reverted; pins that the operator path still writes. */
    @Test
    void operatorSessionCanStillGrantATool() {
        login();
        var id = createAgent("operator-only-operator-grant");

        var resp = PUT("/api/agents/" + id + "/tools/exec", "application/json", GRANT_BODY);

        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"status\":\"ok\""), getContent(resp));
        assertTrue(grantExistsFor(id, "exec"),
                "the operator's grant must persist");
    }

    @Test
    void everyBearerPrincipalIsRefusedNotOnlyTheSystemOwner() {
        // The guard tests how the request authenticated, not who it claims to be. A token row
        // owned by any other name is still an agent driving the API, and a username comparison
        // would wave it through — the wrong polarity for a security check.
        var id = createAgent("operator-only-other-owner");
        var token = seedTokenOwnedBy("not-the-system-owner");

        var resp = asAgent(() -> PUT(requestWithToken(token), "/api/agents/" + id + "/tools/exec",
                "application/json", GRANT_BODY));

        assertStatus(403, resp);
        assertFalse(grantExistsFor(id, "exec"),
                "a bearer principal with a different owner name must not grant either");
    }

    @Test
    void theGuardRunsBeforeTheAgentLookup() {
        // A nonexistent agent id would 404 for the operator; the agent principal must still
        // see 403, so the rejection cannot be used to probe which agent ids exist.
        var resp = asAgent(() -> PUT(agentRequest(), "/api/agents/999999/tools/exec",
                "application/json", GRANT_BODY));

        assertStatus(403, resp);
    }

    // --- PUT /api/agents/{id}/tool-groups/{group} ---

    @Test
    void agentPrincipalCannotToggleAToolGroup() {
        var id = createAgent("operator-only-group-grant");

        var resp = asAgent(() -> PUT(agentRequest(),
                "/api/agents/" + id + "/tool-groups/definitely-not-a-real-group",
                "application/json", GRANT_BODY));

        assertStatus(403, resp);
        assertTrue(getContent(resp).contains("operator_only"),
                "expected the operator_only error code; got: " + getContent(resp));
    }

    /** CONTROL — passes with the guard reverted; pins that the operator still reaches the toggle. */
    @Test
    void operatorSessionStillReachesTheGroupToggle() {
        // No such MCP server is registered, so 404 is the operator's answer here — reaching
        // that branch at all is what proves the guard did not fire on an operator session.
        login();
        var id = createAgent("operator-only-group-operator");

        var resp = PUT("/api/agents/" + id + "/tool-groups/definitely-not-a-real-group",
                "application/json", GRANT_BODY);

        assertStatus(404, resp);
    }

    // --- PUT /api/agents/{id} — the acpAllowed grant ---

    @Test
    void agentPrincipalCannotGrantItselfTheAcpRuntime() {
        // acpAllowed is a larger grant than any tool row: SubagentAcpRunner denies a non-main
        // agent without it, and the harness subprocess it unlocks runs commands outside the
        // shell allowlist entirely.
        var id = createAgent("operator-only-acp-grant");

        var resp = asAgent(() -> PUT(agentRequest(), "/api/agents/" + id,
                "application/json", "{\"acpAllowed\":true}"));

        assertStatus(403, resp);
        assertTrue(getContent(resp).contains("operator_only"),
                "expected the operator_only error code; got: " + getContent(resp));
        assertFalse(acpAllowedFor(id), "the rejected PUT must not have flipped acpAllowed");
    }

    /** CONTROL — passes with the guard reverted; pins that the gate is on the acpAllowed
     *  change alone, so an agent's ordinary edits (here a description, with the flag echoed
     *  back unchanged) are not collateral. */
    @Test
    void agentPrincipalKeepsNonPrivilegedAgentUpdates() {
        var id = createAgent("operator-only-acp-echo");

        var resp = asAgent(() -> PUT(agentRequest(), "/api/agents/" + id, "application/json",
                "{\"description\":\"written-by-the-agent\",\"acpAllowed\":false}"));

        assertIsOk(resp);
        assertTrue(getContent(resp).contains("written-by-the-agent"), getContent(resp));
    }

    /** CONTROL — passes with the guard reverted; pins that the operator can still grant acp. */
    @Test
    void operatorCanStillGrantTheAcpRuntime() {
        login();
        var id = createAgent("operator-only-acp-operator");

        var resp = PUT("/api/agents/" + id, "application/json", "{\"acpAllowed\":true}");

        assertIsOk(resp);
        assertTrue(acpAllowedFor(id), "the operator's acp grant must persist");
    }

    // --- POST /api/agents/{id}/skills/{name}/copy ---

    @Test
    void agentPrincipalCannotInstallASkillOntoAnAgent() {
        // The copy syncs the skill's AgentSkillAllowedTool rows, which ShellExecTool unions
        // into the effective allowlist — self-widening exec.
        var id = createAgent("operator-only-skill-copy");

        var resp = asAgent(() -> POST(agentRequest(),
                "/api/agents/" + id + "/skills/" + MISSING_SKILL + "/copy",
                "application/json", "{}"));

        assertStatus(403, resp);
        assertTrue(getContent(resp).contains("operator_only"),
                "expected the operator_only error code; got: " + getContent(resp));
    }

    /** CONTROL — passes with the guard reverted; the operator reaches the global-skill lookup
     *  (404 for a name no registry skill has) instead of being refused at the principal check. */
    @Test
    void operatorStillReachesTheSkillCopy() {
        login();
        var id = createAgent("operator-only-skill-copy-operator");

        var resp = POST("/api/agents/" + id + "/skills/" + MISSING_SKILL + "/copy",
                "application/json", "{}");

        assertStatus(404, resp);
    }

    // --- PUT /api/agents/{id}/skills/{name} ---

    @Test
    void agentPrincipalCannotEnableASkill() {
        // Enabling lifts the skill out of ShellExecTool's disabled-skill filter, re-admitting
        // every allowlist row a previous disable had shut out.
        var id = createAgent("operator-only-skill-enable");

        var resp = asAgent(() -> PUT(agentRequest(),
                "/api/agents/" + id + "/skills/" + MISSING_SKILL,
                "application/json", GRANT_BODY));

        assertStatus(403, resp);
        assertTrue(getContent(resp).contains("operator_only"),
                "expected the operator_only error code; got: " + getContent(resp));
    }

    @Test
    void agentPrincipalCannotWriteASkillConfigRowAtAll() {
        // enabled=false is the branch that skips the installed-on-disk check and writes an
        // AgentSkillConfig row outright, so it is the DB-visible half of the same gate.
        var id = createAgent("operator-only-skill-disable");

        var resp = asAgent(() -> PUT(agentRequest(),
                "/api/agents/" + id + "/skills/" + MISSING_SKILL,
                "application/json", "{\"enabled\":false}"));

        assertStatus(403, resp);
        assertFalse(skillConfigExistsFor(id, MISSING_SKILL),
                "the rejected toggle must not have written a skill config row");
    }

    /** CONTROL — passes with the guard reverted; the operator reaches the installed-on-disk
     *  check (400) instead of being refused at the principal check. */
    @Test
    void operatorStillReachesTheSkillToggle() {
        login();
        var id = createAgent("operator-only-skill-toggle-operator");

        var resp = PUT("/api/agents/" + id + "/skills/" + MISSING_SKILL,
                "application/json", GRANT_BODY);

        assertStatus(400, resp);
    }

    // --- Reads stay open to both principals ---

    /** CONTROL — passes with the guards reverted; pins that nothing read-only was gated. */
    @Test
    void agentPrincipalCanStillReadTheToolCatalogAndItsOwnConfig() {
        var id = createAgent("operator-only-reads");
        var token = AuthFixture.seedBearerToken();

        assertIsOk(asAgent(() -> GET(requestWithToken(token), "/api/tools")));
        assertIsOk(asAgent(() -> GET(requestWithToken(token), "/api/tools/meta")));
        assertIsOk(asAgent(() -> GET(requestWithToken(token), "/api/agents/" + id + "/tools")));
        assertIsOk(asAgent(() -> GET(requestWithToken(token), "/api/agents/" + id + "/skills")));
    }

    // --- JCLAW-1022: the config table holds the controls the gates above read ---

    /** An inert key: the guard runs before any key inspection, so which key is written does not
     *  matter to what is under test — and play1 runs test classes concurrently, so naming a real
     *  control here would reconfigure a sibling class mid-run. */
    private static final String PROBE_KEY = "jclaw1022.probe.inert";

    private static String configValueOf(String key) {
        return fetchInFreshTx(() -> {
            var row = Config.findByKey(key);
            return row == null ? null : row.value;
        });
    }

    @Test
    void agentPrincipalCannotWriteConfig() {
        var resp = asAgent(() -> POST(agentRequest(), "/api/config", "application/json",
                "{\"key\":\"" + PROBE_KEY + "\",\"value\":\"written-by-agent\"}"));

        assertStatus(403, resp);
        assertTrue(getContent(resp).contains("operator_only"),
                "expected the operator_only error code; got: " + getContent(resp));
        assertNull(configValueOf(PROBE_KEY),
                "the rejected POST must not have written a config row");
    }

    @Test
    void agentPrincipalCannotDeleteConfig() {
        // Deleting is as good as writing: dropping a row reverts the control to its code
        // default, which for a tightened setting is the looser value.
        var key = PROBE_KEY + ".delete";
        fetchInFreshTx(() -> {
            ConfigService.set(key, "seeded");
            return null;
        });

        var resp = asAgent(() -> DELETE(agentRequest(), "/api/config/" + key));

        assertStatus(403, resp);
        assertEquals("seeded", configValueOf(key),
                "the rejected DELETE must have left the row in place");
    }

    /** CONTROL — passes with the guard reverted; pins that the operator path still writes. */
    @Test
    void operatorSessionCanStillWriteConfig() {
        login();
        var key = PROBE_KEY + ".operator";

        var resp = POST("/api/config", "application/json",
                "{\"key\":\"" + key + "\",\"value\":\"written-by-operator\"}");

        assertIsOk(resp);
        assertEquals("written-by-operator", configValueOf(key),
                "the operator's config write must persist");
    }

    /** CONTROL — config reads are masked already, so they stay open to both principals. */
    @Test
    void agentPrincipalCanStillReadConfig() {
        var token = AuthFixture.seedBearerToken();

        assertIsOk(asAgent(() -> GET(requestWithToken(token), "/api/config")));
    }
}
