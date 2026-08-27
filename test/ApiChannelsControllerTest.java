import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;

class ApiChannelsControllerTest extends FunctionalTest {

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

    @Test
    void listRequiresAuth() {
        assertEquals(401, GET("/api/channels").status.intValue());
    }

    @Test
    void activeRequiresAuth() {
        assertEquals(401, GET("/api/channels/active").status.intValue());
    }

    @Test
    void getRequiresAuth() {
        assertEquals(401, GET("/api/channels/telegram").status.intValue());
    }

    @Test
    void saveRequiresAuth() {
        var resp = PUT("/api/channels/telegram", "application/json", "{\"enabled\":true}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void listReturnsJsonArray() {
        login();
        var resp = GET("/api/channels");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        assertTrue(getContent(resp).startsWith("["));
    }

    @Test
    void activeReturnsCountAndChannelTypes() {
        login();
        var resp = GET("/api/channels/active");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"count\""), "must carry count: " + body);
        assertTrue(body.contains("\"channelTypes\""), "must carry channelTypes: " + body);
    }

    @Test
    void getReturns404ForUnknownChannel() {
        login();
        assertEquals(404, GET("/api/channels/definitely-not-a-real-channel").status.intValue());
    }

    @Test
    void saveCreatesChannelConfigOnFirstWrite() {
        login();
        var resp = PUT("/api/channels/slack", "application/json",
                "{\"config\":{\"webhookSecret\":\"xyz\"},\"enabled\":false}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"channelType\":\"slack\""), "got: " + body);
        assertTrue(body.contains("\"enabled\":false"));

        // Subsequent GET surfaces the persisted row.
        var fetched = getContent(GET("/api/channels/slack"));
        assertTrue(fetched.contains("\"webhookSecret\""), "config persisted: " + fetched);
    }

    @Test
    void saveReturns400OnEmptyBody() {
        login();
        var resp = PUT("/api/channels/slack", "application/json", "");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void listMasksSecretConfigValues() {
        // JCLAW-780: the agent-reachable channels list must not leak secret
        // config values. Secret-bearing keys (botToken) are masked; a benign
        // field (chatId) round-trips unchanged.
        login();
        PUT("/api/channels/slack", "application/json",
                "{\"config\":{\"botToken\":\"supersecrettoken\",\"chatId\":\"12345\"},\"enabled\":false}");
        var body = getContent(GET("/api/channels"));
        assertTrue(body.contains("\"botToken\""), "key must survive: " + body);
        assertFalse(body.contains("supersecrettoken"), "raw secret must not leak: " + body);
        assertTrue(body.contains("supe****"), "botToken value must be masked: " + body);
        assertTrue(body.contains("\"chatId\":\"12345\""), "non-secret field must round-trip: " + body);
    }

    @Test
    void saveUpdatesExistingChannelConfig() {
        login();
        PUT("/api/channels/slack", "application/json",
                "{\"config\":{\"k\":\"v1\"},\"enabled\":true}");
        var resp = PUT("/api/channels/slack", "application/json",
                "{\"enabled\":false}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"enabled\":false"),
                "re-PUT must flip enabled: " + getContent(resp));
    }

    @Test
    void tenantUserChannelSettingsAreScopedAwayFromAdminChannelSettings() {
        login();
        assertIsOk(PUT("/api/channels/slack", "application/json",
                "{\"config\":{\"chatId\":\"admin-room\"},\"enabled\":true}"));

        var tenantId = idOf(POST("/api/access/tenants", "application/json",
                "{\"slug\":\"channelco\",\"name\":\"Channel Co\"}"));
        var teamId = idOf(POST("/api/access/teams", "application/json",
                "{\"tenantId\":%d,\"slug\":\"support\",\"name\":\"Support\"}".formatted(tenantId)));
        assertIsOk(POST("/api/access/users", "application/json",
                ("{\"username\":\"channel-user\",\"displayName\":\"Channel User\",\"tenantId\":%d,"
                        + "\"teamId\":%d,\"role\":\"USER\",\"password\":\"channel-password-123\"}")
                        .formatted(tenantId, teamId)));

        POST("/api/auth/logout", "application/json", "{}");
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"channel-user\",\"password\":\"channel-password-123\"}"));
        assertEquals(404, GET("/api/channels/slack").status.intValue());
        assertIsOk(PUT("/api/channels/slack", "application/json",
                "{\"config\":{\"chatId\":\"user-room\"},\"enabled\":false}"));

        var userBody = getContent(GET("/api/channels/slack"));
        assertTrue(userBody.contains("\"channelType\":\"slack\""), userBody);
        assertTrue(userBody.contains("user-room"), userBody);
        assertFalse(userBody.contains("admin-room"), userBody);

        var userList = getContent(GET("/api/channels"));
        assertTrue(userList.contains("user-room"), userList);
        assertFalse(userList.contains("admin-room"), userList);

        POST("/api/auth/logout", "application/json", "{}");
        login();
        var adminBody = getContent(GET("/api/channels/slack"));
        assertTrue(adminBody.contains("admin-room"), adminBody);
        assertFalse(adminBody.contains("user-room"), adminBody);
    }

    private static long idOf(play.mvc.Http.Response response) {
        return com.google.gson.JsonParser.parseString(getContent(response)).getAsJsonObject().get("id").getAsLong();
    }
}
