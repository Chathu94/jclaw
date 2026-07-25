import models.ApiToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.InternalApiTokenService;
import utils.TokenHasher;

/**
 * Verify the auto-bootstrap and self-healing behavior of
 * {@link InternalApiTokenService} (JCLAW-282).
 *
 * <p>Three invariants this layer must guarantee:
 * <ol>
 *   <li>First call mints a token and persists both halves (config row
 *       carrying the plaintext, ApiToken row carrying the hash).</li>
 *   <li>Subsequent calls reuse the existing token instead of minting a
 *       new one on every boot (which would leave dead rows behind).</li>
 *   <li>If the ApiToken row is wiped but the config row survives (or
 *       vice-versa), the next call re-mints both — the system can't be
 *       left in a "stored plaintext but no row to validate against"
 *       state where {@code jclaw_api} starts hitting 401.</li>
 * </ol>
 */
class InternalApiTokenServiceTest extends UnitTest {

    private void resetState() {
        ApiToken.deleteAll();
        ConfigService.delete(InternalApiTokenService.INTERNAL_TOKEN_CONFIG_KEY);
    }

    @BeforeEach
    void clearState() { resetState(); }

    @AfterEach
    void cleanup() { resetState(); }

    @Test
    void firstCallMintsTokenAndPersists() {
        var token = InternalApiTokenService.token();
        assertNotNull(token);
        assertTrue(token.startsWith(TokenHasher.TOKEN_PREFIX),
                "minted token should carry the jcl_ prefix so it's recognizable in logs; got: " + token);

        // Config row carries the plaintext for the tool's HTTP call.
        var stored = ConfigService.get(InternalApiTokenService.INTERNAL_TOKEN_CONFIG_KEY);
        assertEquals(token, stored);

        // ApiToken row carries the hash for AuthCheck to validate against.
        var row = ApiToken.findActiveByPlaintext(token);
        assertNotNull(row);
        assertEquals(InternalApiTokenService.SYSTEM_OWNER, row.ownerUsername);
    }

    @Test
    void subsequentCallsReuseStoredToken() {
        var first = InternalApiTokenService.token();
        var second = InternalApiTokenService.token();
        assertEquals(first, second,
                "second call should read the cached config row, not mint fresh");
        // And only ONE ApiToken row exists.
        long rows = ApiToken.count();
        assertEquals(1L, rows,
                "expected exactly one ApiToken row after bootstrap+reuse; got: " + rows);
    }

    @Test
    void reMintsWhenApiTokenRowMissing() {
        var first = InternalApiTokenService.token();
        // Simulate: operator manually deleted the ApiToken row (e.g. cleanup
        // script, restore from backup) but the config row survives. The next
        // call must repair the gap instead of leaving the tool unable to auth.
        //
        // JCLAW-852: this test used to call invalidateCache() here, which is the
        // only reason it passed — production never invalidated, so the repair
        // below was unreachable and a deleted row broke jclaw_api until the JVM
        // restarted. The absence of that call is now the regression guard.
        ApiToken.deleteAll();

        var second = InternalApiTokenService.token();
        assertNotEquals(first, second,
                "stale plaintext should be replaced when its row is gone");

        // And the new row really exists.
        var row = ApiToken.findActiveByPlaintext(second);
        assertNotNull(row, "self-healing path must create a fresh ApiToken row");
    }

    @Test
    void mintsFreshWhenConfigRowMissing() {
        InternalApiTokenService.token();
        // Inverse scenario: config row wiped (e.g. via an admin's
        // /api/config DELETE before we filtered the prefix). Cache
        // invalidation forces a re-read; since the config row is gone
        // we mint a fresh one.
        ConfigService.delete(InternalApiTokenService.INTERNAL_TOKEN_CONFIG_KEY);

        var fresh = InternalApiTokenService.token();
        var stored = ConfigService.get(InternalApiTokenService.INTERNAL_TOKEN_CONFIG_KEY);
        assertEquals(fresh, stored,
                "missing config row should be repopulated by the bootstrap path");
    }

    @Test
    void tokenResolvesWithoutAnAmbientTransaction() {
        // JCLAW-852: outside boot the only production caller is JClawApiTool,
        // which runs in the agent tool loop with no transaction open by design.
        // Now that every call re-reads the config and token rows, that path must
        // carry its own transaction or it throws "No active EntityManager" —
        // the same failure JCLAW-849 fixed in the bearer filter.
        //
        // Run on a fresh virtual thread so the test's own transaction is not
        // inherited; an inline call would pass for the wrong reason.
        var result = new String[1];
        var error = new Throwable[1];
        var t = Thread.ofVirtual().start(() -> {
            try {
                result[0] = InternalApiTokenService.token();
            } catch (Throwable e) {
                error[0] = e;
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for token()", e);
        }

        // Assert on the SHAPE of any failure, not on success. This class's
        // @BeforeEach deletes from config and api_token inside the test's own
        // uncommitted transaction, so a genuinely independent transaction can
        // legitimately hit a lock timeout on those rows — that is the harness's
        // state, not a defect. Reaching SQL execution at all is itself proof a
        // transaction was open. The defect this guards against fails earlier and
        // differently, with no EntityManager to execute against.
        var failure = error[0] == null ? "" : error[0].toString();
        assertFalse(failure.contains("No active EntityManager"),
                "token() must carry its own transaction when called off the request path; got: " + failure);
    }
}
