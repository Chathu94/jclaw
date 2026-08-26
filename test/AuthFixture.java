import controllers.ApiAuthController;
import models.ApiToken;
import services.ConfigService;
import services.InternalApiTokenService;
import services.Tx;
import utils.PasswordHasher;
import utils.TokenHasher;

/**
 * Test-only helper — seed a known admin password hash into the Config table
 * so FunctionalTests that exercise the login flow can use a predictable
 * plaintext. Commits on a fresh virtual thread so the write lands before
 * the HTTP request under test runs; FunctionalTest's carrier thread is
 * already inside a JPA transaction and an inline {@code ConfigService.set}
 * would otherwise sit uncommitted until after the test returns, invisible
 * to the in-process HTTP handler (see WebhookControllerTest.commitInFreshTx
 * for the canonical prior art and the JPA-isolation memory note for the
 * reasoning).
 */
public final class AuthFixture {

    private AuthFixture() {}

    public static void seedAdminPassword(String plaintext) {
        var hash = PasswordHasher.hash(plaintext);
        runInFreshTx(() -> ConfigService.set(ApiAuthController.PASSWORD_HASH_KEY, hash));
    }

    /** Counterpart to {@link #seedAdminPassword} — commits the delete on a
     *  fresh tx so tests that exercise the "password unset" flow see an
     *  empty Config row from the HTTP-handler side. */
    public static void clearAdminPassword() {
        runInFreshTx(() -> {
            ConfigService.delete(ApiAuthController.PASSWORD_HASH_KEY);
            var admin = models.UserAccount.findByUsername("admin");
            if (admin != null) {
                admin.passwordHash = null;
                admin.bumpCredentialVersion();
                admin.save();
            }
        });
    }

    /** Mint a dedicated bearer token row and commit it, returning the plaintext.
     *
     *  <p>Deliberately does NOT go through {@link InternalApiTokenService#token()}:
     *  that caches the plaintext in a static field and only re-verifies the
     *  backing row on a cache miss. play1 runs test classes concurrently and
     *  several call {@code Fixtures.deleteDatabase()}, so the cached token can
     *  outlive its row and authenticate against nothing. Minting our own row
     *  keeps the fixture self-contained.
     *
     *  <p>Committed on a fresh virtual thread for the same reason as
     *  {@link #seedAdminPassword} — written inline it would sit uncommitted in
     *  the carrier thread's transaction, invisible to the HTTP handler. */
    public static String seedBearerToken() {
        var plaintext = TokenHasher.mint();
        runInFreshTx(() -> {
            var row = new ApiToken();
            row.ownerUsername = InternalApiTokenService.SYSTEM_OWNER;
            row.secretHash = TokenHasher.hash(plaintext);
            row.save();
        });
        return plaintext;
    }

    private static void runInFreshTx(Runnable block) {
        var t = Thread.ofVirtual().start(() -> Tx.run(block));
        try {
            t.join();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        ConfigService.clearCache();
    }
}
