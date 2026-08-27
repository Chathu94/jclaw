import com.google.gson.JsonParser;
import models.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.AgentService;
import services.ConfigService;
import services.Tx;

import java.util.function.Supplier;

class ApiAccessControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    @Test
    void accessEndpointsRequireAuth() {
        assertEquals(401, GET("/api/access/tenants").status.intValue());
        assertEquals(401, GET("/api/access/teams").status.intValue());
        assertEquals(401, GET("/api/access/users").status.intValue());
    }

    @Test
    void allAdminCanCreateTenantTeamAndUser() {
        login();

        var tenants = GET("/api/access/tenants");
        assertIsOk(tenants);
        assertTrue(getContent(tenants).contains("\"slug\":\"default\""));

        var tenantResp = POST("/api/access/tenants", "application/json",
                "{\"slug\":\"acme\",\"name\":\"Acme\"}");
        assertIsOk(tenantResp);
        var tenantId = idOf(tenantResp);

        var teamResp = POST("/api/access/teams", "application/json",
                "{\"tenantId\":%d,\"slug\":\"platform\",\"name\":\"Platform\"}".formatted(tenantId));
        assertIsOk(teamResp);
        var teamId = idOf(teamResp);

        var userResp = POST("/api/access/users", "application/json",
                ("{\"username\":\"alice\",\"displayName\":\"Alice\",\"tenantId\":%d,"
                        + "\"teamId\":%d,\"role\":\"TEAM_ADMIN\",\"password\":\"alice-password-123\"}")
                        .formatted(tenantId, teamId));
        assertIsOk(userResp);
        var userJson = getContent(userResp);
        assertTrue(userJson.contains("\"username\":\"alice\""), userJson);
        assertTrue(userJson.contains("\"role\":\"TEAM_ADMIN\""), userJson);
        assertTrue(userJson.contains("\"approved\":false"), userJson);
        assertTrue(userJson.contains("\"tenantSlug\":\"acme\""), userJson);

        var userId = idOf(userResp);
        var approveResp = POST("/api/access/users/" + userId + "/approve", "application/json", "{}");
        assertIsOk(approveResp);
        assertTrue(getContent(approveResp).contains("\"approved\":true"));

        var users = GET("/api/access/users");
        assertIsOk(users);
        assertTrue(getContent(users).contains("\"username\":\"alice\""));
    }

    @Test
    void tenantUserCanLoginWithOwnPassword() {
        login();
        var tenantId = idOf(POST("/api/access/tenants", "application/json",
                "{\"slug\":\"acme\",\"name\":\"Acme\"}"));
        var teamId = idOf(POST("/api/access/teams", "application/json",
                "{\"tenantId\":%d,\"slug\":\"support\",\"name\":\"Support\"}".formatted(tenantId)));
        var userResp = POST("/api/access/users", "application/json",
                ("{\"username\":\"bob\",\"displayName\":\"Bob\",\"tenantId\":%d,"
                        + "\"teamId\":%d,\"role\":\"USER\",\"password\":\"bob-password-123\"}")
                        .formatted(tenantId, teamId));
        assertIsOk(userResp);
        assertTrue(getContent(userResp).contains("\"passwordSet\":true"), getContent(userResp));

        POST("/api/auth/logout", "application/json", "{}");
        var loginResp = POST("/api/auth/login", "application/json",
                "{\"username\":\"bob\",\"password\":\"bob-password-123\"}");
        assertIsOk(loginResp);
        assertTrue(getContent(loginResp).contains("\"username\":\"bob\""), getContent(loginResp));
        assertIsOk(GET("/api/access/users"));
    }

    @Test
    void tenantUserGetsPersonalAgentInsteadOfSharedMain() {
        login();
        var tenantId = idOf(POST("/api/access/tenants", "application/json",
                "{\"slug\":\"agentco\",\"name\":\"Agent Co\"}"));
        var teamId = idOf(POST("/api/access/teams", "application/json",
                "{\"tenantId\":%d,\"slug\":\"product\",\"name\":\"Product\"}".formatted(tenantId)));
        var userResp = POST("/api/access/users", "application/json",
                ("{\"username\":\"erin\",\"displayName\":\"Erin\",\"tenantId\":%d,"
                        + "\"teamId\":%d,\"role\":\"USER\",\"password\":\"erin-password-123\"}")
                        .formatted(tenantId, teamId));
        assertIsOk(userResp);
        var userId = idOf(userResp);

        POST("/api/auth/logout", "application/json", "{}");
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"erin\",\"password\":\"erin-password-123\"}"));

        var agents = GET("/api/agents");
        assertIsOk(agents);
        var body = getContent(agents);
        assertTrue(body.contains("\"name\":\"user-" + userId + "-main\""), body);
        assertTrue(body.contains("\"ownerUsername\":\"erin\""), body);
        assertFalse(body.contains("\"name\":\"main\""), body);
    }

    @Test
    void tenantUserReceivesPrivateCopyOfInitialConfigDefaults() {
        login();
        assertIsOk(POST("/api/config", "application/json",
                "{\"key\":\"provider.test-provider.baseUrl\",\"value\":\"http://template-provider.test\"}"));
        assertIsOk(POST("/api/config", "application/json",
                "{\"key\":\"provider.test-provider.apiKey\",\"value\":\"sk-template\"}"));
        assertIsOk(POST("/api/providers/test-provider/models", "application/json",
                "{\"id\":\"template-model\",\"name\":\"Template Model\"}"));
        assertIsOk(POST("/api/config", "application/json",
                "{\"key\":\"agent.main.queue.mode\",\"value\":\"collect\"}"));

        var tenantId = idOf(POST("/api/access/tenants", "application/json",
                "{\"slug\":\"defaultsco\",\"name\":\"Defaults Co\"}"));
        var teamId = idOf(POST("/api/access/teams", "application/json",
                "{\"tenantId\":%d,\"slug\":\"support\",\"name\":\"Support\"}".formatted(tenantId)));
        var userResp = POST("/api/access/users", "application/json",
                ("{\"username\":\"gina\",\"displayName\":\"Gina\",\"tenantId\":%d,"
                        + "\"teamId\":%d,\"role\":\"USER\",\"password\":\"gina-password-123\"}")
                        .formatted(tenantId, teamId));
        assertIsOk(userResp);
        var userId = idOf(userResp);
        var userPrefix = "user." + userId + ".";
        var personalAgent = "user-" + userId + "-main";

        assertEquals("http://template-provider.test",
                ConfigService.get(userPrefix + "provider.test-provider.baseUrl"));
        assertNotNull(ConfigService.get(userPrefix + "provider.test-provider.models"));
        assertEquals("collect",
                ConfigService.get(userPrefix + "agent." + personalAgent + ".queue.mode"));
        assertNull(ConfigService.get(userPrefix + "agent.main.queue.mode"));

        POST("/api/auth/logout", "application/json", "{}");
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"gina\",\"password\":\"gina-password-123\"}"));
        var userModels = getContent(GET("/api/providers/test-provider/models"));
        assertTrue(userModels.contains("template-model"), userModels);
        assertIsOk(POST("/api/config", "application/json",
                "{\"key\":\"provider.test-provider.baseUrl\",\"value\":\"http://gina-provider.test\"}"));

        POST("/api/auth/logout", "application/json", "{}");
        login();
        var adminProvider = getContent(GET("/api/config/provider.test-provider.baseUrl"));
        assertTrue(adminProvider.contains("http://template-provider.test"), adminProvider);
        assertFalse(adminProvider.contains("gina-provider"), adminProvider);
    }

    @Test
    void tenantUserBackfillsMissingConfigDefaultsAfterInitialSeed() {
        login();
        var tenantId = idOf(POST("/api/access/tenants", "application/json",
                "{\"slug\":\"backfillco\",\"name\":\"Backfill Co\"}"));
        var teamId = idOf(POST("/api/access/teams", "application/json",
                "{\"tenantId\":%d,\"slug\":\"ops\",\"name\":\"Ops\"}".formatted(tenantId)));
        var userResp = POST("/api/access/users", "application/json",
                ("{\"username\":\"hank\",\"displayName\":\"Hank\",\"tenantId\":%d,"
                        + "\"teamId\":%d,\"role\":\"USER\",\"password\":\"hank-password-123\"}")
                        .formatted(tenantId, teamId));
        assertIsOk(userResp);
        var userId = idOf(userResp);
        var userPrefix = "user." + userId + ".";
        var personalAgent = "user-" + userId + "-main";
        assertEquals("true", ConfigService.get(userPrefix + "auth.initialConfigSeeded"));

        assertIsOk(POST("/api/config", "application/json",
                "{\"key\":\"provider.later-provider.baseUrl\",\"value\":\"http://later-provider.test\"}"));
        assertIsOk(POST("/api/providers/later-provider/models", "application/json",
                "{\"id\":\"later-model\",\"name\":\"Later Model\"}"));
        assertIsOk(POST("/api/config", "application/json",
                "{\"key\":\"agent.main.queue.mode\",\"value\":\"dispatch\"}"));

        POST("/api/auth/logout", "application/json", "{}");
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"hank\",\"password\":\"hank-password-123\"}"));

        assertEquals("http://later-provider.test",
                ConfigService.get(userPrefix + "provider.later-provider.baseUrl"));
        assertNotNull(ConfigService.get(userPrefix + "provider.later-provider.models"));
        assertEquals("dispatch",
                ConfigService.get(userPrefix + "agent." + personalAgent + ".queue.mode"));
        assertNull(ConfigService.get(userPrefix + "agent.main.queue.mode"));
    }

    @Test
    void tenantUserCannotChatThroughBootstrapMainAgentById() {
        var mainAgentId = commitInFreshTx(() ->
                AgentService.create(Agent.MAIN_AGENT_NAME, "openrouter", "gpt-4.1").id);

        login();
        var tenantId = idOf(POST("/api/access/tenants", "application/json",
                "{\"slug\":\"chatco\",\"name\":\"Chat Co\"}"));
        var teamId = idOf(POST("/api/access/teams", "application/json",
                "{\"tenantId\":%d,\"slug\":\"support\",\"name\":\"Support\"}".formatted(tenantId)));
        var userResp = POST("/api/access/users", "application/json",
                ("{\"username\":\"frank\",\"displayName\":\"Frank\",\"tenantId\":%d,"
                        + "\"teamId\":%d,\"role\":\"USER\",\"password\":\"frank-password-123\"}")
                        .formatted(tenantId, teamId));
        assertIsOk(userResp);

        POST("/api/auth/logout", "application/json", "{}");
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"frank\",\"password\":\"frank-password-123\"}"));

        var chatResp = POST("/api/chat/send", "application/json",
                "{\"agentId\":%d,\"message\":\"/help\"}".formatted(mainAgentId));
        assertEquals(404, chatResp.status.intValue());
    }

    @Test
    void pendingAdminCannotLoginUntilAllAdminApproves() {
        login();
        var tenantId = idOf(POST("/api/access/tenants", "application/json",
                "{\"slug\":\"pendingco\",\"name\":\"Pending Co\"}"));
        var teamId = idOf(POST("/api/access/teams", "application/json",
                "{\"tenantId\":%d,\"slug\":\"ops\",\"name\":\"Ops\"}".formatted(tenantId)));
        var userResp = POST("/api/access/users", "application/json",
                ("{\"username\":\"carol\",\"displayName\":\"Carol\",\"tenantId\":%d,"
                        + "\"teamId\":%d,\"role\":\"TEAM_ADMIN\",\"password\":\"carol-password-123\"}")
                        .formatted(tenantId, teamId));
        assertIsOk(userResp);
        var userId = idOf(userResp);

        POST("/api/auth/logout", "application/json", "{}");
        var denied = POST("/api/auth/login", "application/json",
                "{\"username\":\"carol\",\"password\":\"carol-password-123\"}");
        assertEquals(401, denied.status.intValue());

        login();
        assertIsOk(POST("/api/access/users/" + userId + "/approve", "application/json", "{}"));
        POST("/api/auth/logout", "application/json", "{}");
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"carol\",\"password\":\"carol-password-123\"}"));
    }

    @Test
    void adminCanResetVisibleUserPassword() {
        login();
        var tenantId = idOf(POST("/api/access/tenants", "application/json",
                "{\"slug\":\"resetco\",\"name\":\"Reset Co\"}"));
        var teamId = idOf(POST("/api/access/teams", "application/json",
                "{\"tenantId\":%d,\"slug\":\"ops\",\"name\":\"Ops\"}".formatted(tenantId)));
        var userResp = POST("/api/access/users", "application/json",
                ("{\"username\":\"dave\",\"displayName\":\"Dave\",\"tenantId\":%d,"
                        + "\"teamId\":%d,\"role\":\"USER\",\"password\":\"dave-password-123\"}")
                        .formatted(tenantId, teamId));
        assertIsOk(userResp);
        var userId = idOf(userResp);

        var resetResp = POST("/api/access/users/" + userId + "/password", "application/json",
                "{\"password\":\"dave-password-456\"}");
        assertIsOk(resetResp);
        assertTrue(getContent(resetResp).contains("\"passwordSet\":true"), getContent(resetResp));

        POST("/api/auth/logout", "application/json", "{}");
        assertEquals(401, POST("/api/auth/login", "application/json",
                "{\"username\":\"dave\",\"password\":\"dave-password-123\"}").status.intValue());
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"dave\",\"password\":\"dave-password-456\"}"));
    }

    private void login() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
    }

    private static long idOf(play.mvc.Http.Response response) {
        return JsonParser.parseString(getContent(response)).getAsJsonObject().get("id").getAsLong();
    }

    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable e) {
                err.set(e);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }
}
