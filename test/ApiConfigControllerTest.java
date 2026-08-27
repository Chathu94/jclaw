import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.ConfigService;

class ApiConfigControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    private void login() {
        var resp = POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}");
        assertIsOk(resp);
    }

    private static void commitInFreshTx(Runnable block) {
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try { services.Tx.run(block); } catch (Throwable ex) { err.set(ex); }
        });
        try { t.join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        if (err.get() != null) throw new RuntimeException(err.get());
    }

    @Test
    void listRequiresAuth() {
        assertEquals(401, GET("/api/config").status.intValue());
    }

    @Test
    void getRequiresAuth() {
        assertEquals(401, GET("/api/config/example.key").status.intValue());
    }

    @Test
    void saveRequiresAuth() {
        var resp = POST("/api/config", "application/json",
                "{\"key\":\"x\",\"value\":\"y\"}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void deleteRequiresAuth() {
        assertEquals(401, DELETE("/api/config/example.key").status.intValue());
    }

    @Test
    void listReturnsJsonEntries() {
        login();
        commitInFreshTx(() -> ConfigService.set("ui.theme", "dark"));
        var resp = GET("/api/config");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"entries\""), "must carry an entries array: " + body);
        assertTrue(body.contains("\"ui.theme\""), "must include the seeded key: " + body);
    }

    @Test
    void getReturns404ForUnknownKey() {
        login();
        var resp = GET("/api/config/definitely-not-a-real-key");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void getReturnsEntryForExistingKey() {
        login();
        commitInFreshTx(() -> ConfigService.set("ui.density", "compact"));
        var resp = GET("/api/config/ui.density");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"key\":\"ui.density\""));
        assertTrue(body.contains("\"value\":\"compact\""));
    }

    /**
     * Three POST validation branches share the login + POST + assertEquals
     * shape: missing-fields (400), blank-key (400), reserved-key-prefix (409).
     */
    @ParameterizedTest(name = "save[{0}]Returns[{1}]")
    @CsvSource(delimiter = '|', value = {
            "MissingFields       | 400 | {}",
            "BlankKey            | 400 | {\"key\":\"\",\"value\":\"v\"}",
            "ReservedKeyPrefix   | 409 | {\"key\":\"auth.internal.something\",\"value\":\"v\"}"
    })
    void saveReturnsExpectedStatus(String label, int expectedStatus, String body) {
        login();
        var resp = POST("/api/config", "application/json", body);
        assertEquals(expectedStatus, resp.status.intValue());
    }

    @Test
    void saveSucceedsForRegularKey() {
        login();
        var resp = POST("/api/config", "application/json",
                "{\"key\":\"ui.locale\",\"value\":\"en\"}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"status\":\"ok\""));
    }

    @Test
    void tenantUserConfigIsScopedAwayFromAdminConfig() {
        login();
        assertIsOk(POST("/api/config", "application/json",
                "{\"key\":\"ui.theme\",\"value\":\"admin-dark\"}"));

        var tenantId = idOf(POST("/api/access/tenants", "application/json",
                "{\"slug\":\"settingsco\",\"name\":\"Settings Co\"}"));
        var teamId = idOf(POST("/api/access/teams", "application/json",
                "{\"tenantId\":%d,\"slug\":\"support\",\"name\":\"Support\"}".formatted(tenantId)));
        assertIsOk(POST("/api/access/users", "application/json",
                ("{\"username\":\"settings-user\",\"displayName\":\"Settings User\",\"tenantId\":%d,"
                        + "\"teamId\":%d,\"role\":\"USER\",\"password\":\"settings-password-123\"}")
                        .formatted(tenantId, teamId)));

        POST("/api/auth/logout", "application/json", "{}");
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"settings-user\",\"password\":\"settings-password-123\"}"));
        var userInitial = GET("/api/config/ui.theme");
        assertIsOk(userInitial);
        assertTrue(getContent(userInitial).contains("\"value\":\"admin-dark\""), getContent(userInitial));
        assertIsOk(POST("/api/config", "application/json",
                "{\"key\":\"ui.theme\",\"value\":\"user-light\"}"));

        var userGet = GET("/api/config/ui.theme");
        assertIsOk(userGet);
        var userBody = getContent(userGet);
        assertTrue(userBody.contains("\"key\":\"ui.theme\""), userBody);
        assertTrue(userBody.contains("\"value\":\"user-light\""), userBody);
        assertFalse(userBody.contains("admin-dark"), userBody);

        var userList = GET("/api/config");
        assertIsOk(userList);
        assertTrue(getContent(userList).contains("\"value\":\"user-light\""), getContent(userList));
        assertFalse(getContent(userList).contains("admin-dark"), getContent(userList));

        POST("/api/auth/logout", "application/json", "{}");
        login();
        var adminGet = GET("/api/config/ui.theme");
        assertIsOk(adminGet);
        var adminBody = getContent(adminGet);
        assertTrue(adminBody.contains("\"value\":\"admin-dark\""), adminBody);
        assertFalse(adminBody.contains("user-light"), adminBody);
    }

    @Test
    void tenantUserProviderSettingsAndModelsAreScopedAwayFromAdminProviderSettings() {
        login();
        assertIsOk(POST("/api/config", "application/json",
                "{\"key\":\"provider.test-provider.baseUrl\",\"value\":\"http://admin-provider.test\"}"));
        assertIsOk(POST("/api/config", "application/json",
                "{\"key\":\"provider.test-provider.apiKey\",\"value\":\"sk-admin\"}"));
        assertIsOk(POST("/api/providers/test-provider/models", "application/json",
                "{\"id\":\"admin-model\",\"name\":\"Admin Model\"}"));

        var tenantId = idOf(POST("/api/access/tenants", "application/json",
                "{\"slug\":\"providerco\",\"name\":\"Provider Co\"}"));
        var teamId = idOf(POST("/api/access/teams", "application/json",
                "{\"tenantId\":%d,\"slug\":\"support\",\"name\":\"Support\"}".formatted(tenantId)));
        assertIsOk(POST("/api/access/users", "application/json",
                ("{\"username\":\"provider-user\",\"displayName\":\"Provider User\",\"tenantId\":%d,"
                        + "\"teamId\":%d,\"role\":\"USER\",\"password\":\"provider-password-123\"}")
                        .formatted(tenantId, teamId)));

        POST("/api/auth/logout", "application/json", "{}");
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"provider-user\",\"password\":\"provider-password-123\"}"));
        var userInitialModels = GET("/api/providers/test-provider/models");
        assertIsOk(userInitialModels);
        assertTrue(getContent(userInitialModels).contains("admin-model"), getContent(userInitialModels));
        assertIsOk(POST("/api/config", "application/json",
                "{\"key\":\"provider.test-provider.baseUrl\",\"value\":\"http://user-provider.test\"}"));
        assertIsOk(POST("/api/config", "application/json",
                "{\"key\":\"provider.test-provider.apiKey\",\"value\":\"sk-user\"}"));
        assertIsOk(POST("/api/providers/test-provider/models", "application/json",
                "{\"id\":\"user-model\",\"name\":\"User Model\"}"));

        var userModels = getContent(GET("/api/providers/test-provider/models"));
        assertTrue(userModels.contains("admin-model"), userModels);
        assertTrue(userModels.contains("user-model"), userModels);

        POST("/api/auth/logout", "application/json", "{}");
        login();
        var adminModels = getContent(GET("/api/providers/test-provider/models"));
        assertTrue(adminModels.contains("admin-model"), adminModels);
        assertFalse(adminModels.contains("user-model"), adminModels);
    }

    @Test
    void deleteRejectsReservedKeyPrefix() {
        login();
        assertEquals(409, DELETE("/api/config/auth.internal.foo").status.intValue());
    }

    @Test
    void deleteIsIdempotentForUnknownKey() {
        // deleteWithSideEffects is no-op for missing rows; controller still returns ok.
        login();
        var resp = DELETE("/api/config/never.set.this");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"status\":\"ok\""));
    }

    private static long idOf(play.mvc.Http.Response response) {
        return com.google.gson.JsonParser.parseString(getContent(response)).getAsJsonObject().get("id").getAsLong();
    }
}
