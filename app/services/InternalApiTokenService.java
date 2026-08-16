package services;

import models.ApiToken;
import utils.TokenHasher;

/**
 * Bootstrap and resolve the bearer token the in-process {@code jclaw_api}
 * tool uses to call its own {@code /api/**} endpoints (JCLAW-282).
 *
 * <p>JClaw's bearer-auth path needs an {@link ApiToken} row to validate
 * the {@code Authorization: Bearer <plaintext>} header against. For the
 * tool-to-localhost call, plaintext has to live somewhere the tool can
 * read at request time — it can't recompute the secret from the row's
 * hash. So at first boot we mint the token, save the row (FULL scope,
 * owner {@code "system"}), and stash the plaintext under the
 * {@link #INTERNAL_TOKEN_CONFIG_KEY} config row. Subsequent boots reuse
 * the existing value; if the config row was wiped but the ApiToken row
 * survives, we mint fresh and replace both so the system stays self-
 * healing.
 *
 * <p><b>Why a config row and not an environment variable?</b> The token
 * must be readable from any thread, persisted across restarts, and live
 * in the same backing store as the rest of JClaw's secrets. Env vars
 * also require operator action on every fresh install; the auto-bootstrap
 * approach matches {@code DefaultConfigJob}'s broader posture of "make
 * it work without operator setup".
 *
 * <p>The config key is filtered from {@code /api/config} listings (see
 * {@code ApiConfigController.RESERVED_KEY_PREFIX}-style guard) and the
 * row's owner of {@code "system"} keeps it out of the Settings UI
 * token listing (which filters to the admin username). The plaintext
 * never leaks through any HTTP-visible surface.
 */
public final class InternalApiTokenService {

    /** Plaintext bearer token used by {@code jclaw_api}. Filtered out
     *  of all {@code /api/config**} surfaces by the
     *  {@link #INTERNAL_KEY_PREFIX} guard in {@code ApiConfigController}. */
    public static final String INTERNAL_TOKEN_CONFIG_KEY = "auth.internal.apiToken";

    /** Every key starting with this prefix is reserved for JClaw-internal
     *  state (bearer tokens, future system secrets) and refused by the
     *  Config API. Operators who need to inspect it can read the DB
     *  directly — same posture as the password hash. */
    public static final String INTERNAL_KEY_PREFIX = "auth.internal.";

    /** Token owner reserved for auto-managed system tokens. Stashed on
     *  the {@link ApiToken} row so the bearer-auth filter can stamp
     *  {@code session.username} with a stable value when admitting
     *  internal requests. */
    public static final String SYSTEM_OWNER = "system";

    private InternalApiTokenService() {}

    /** Return the plaintext bearer token, bootstrapping it on first call and
     *  re-minting if its backing row has gone.
     *
     *  <p>JCLAW-852: this used to memoize the plaintext in a static field and
     *  verify the row only on a cache miss. Nothing missed after boot —
     *  {@code DefaultConfigJob} warms it at startup and only tests ever cleared
     *  it — so a deleted {@link ApiToken} row left the service handing out a
     *  credential that authenticated against nothing for the life of the JVM,
     *  breaking every {@code jclaw_api} tool call with a bare 401. The
     *  self-healing branch in {@link #ensureToken} existed and was tested the
     *  whole time; the cache simply made it unreachable.
     *
     *  <p>Verifying on every call closes that window outright rather than
     *  narrowing it, and costs little: {@code ConfigService.get} is TTL-cached
     *  and {@code findActiveByPlaintext} carries an L2 query cache sized for
     *  exactly this pattern. {@code AuthCheck} already runs the same lookup on
     *  every inbound request, so this is symmetric with the server side.
     *
     *  <p>{@link Tx#run} is what makes it safe to call from anywhere. The only
     *  production caller outside boot is {@code JClawApiTool}, which runs in the
     *  agent tool loop with no transaction open by design — reading the config
     *  and the token row there would otherwise throw the same
     *  "No active EntityManager" that JCLAW-849 fixed in the bearer filter. */
    public static String token() {
        return Tx.run(InternalApiTokenService::ensureToken);
    }

    private static String ensureToken() {
        var stored = ConfigService.get(INTERNAL_TOKEN_CONFIG_KEY);
        if (stored != null && !stored.isBlank()) {
            if (ApiToken.findActiveByPlaintext(stored) != null) return stored;

            // JCLAW-1034: "missing" and "revoked" used to be one branch, and re-minting on
            // both made revoking this token impossible — withdraw it and the next call
            // minted a replacement. Only an absent row is self-healing now. A revoked one
            // is handed back as-is so every call 401s, which is what revocation means; an
            // expired one is a rotation signal and does re-mint.
            var row = ApiToken.findAnyByPlaintext(stored);
            if (row != null && row.revokedAt != null) {
                EventLogger.warn("auth",
                        "Internal jclaw_api token is revoked — not re-minting; the tool stays "
                                + "unauthenticated until an operator clears the revocation");
                return stored;
            }
            EventLogger.info("auth",
                    "Internal jclaw_api token row missing or expired — re-minting");
        }
        return mintAndStore();
    }

    /** Mint a fresh token, persist both halves (config row carrying the
     *  plaintext, ApiToken row carrying the hash), commit on a fresh tx
     *  so startup code that runs outside a request thread is safe. */
    private static String mintAndStore() {
        var plaintext = TokenHasher.mint();
        Tx.run(() -> {
            ConfigService.set(INTERNAL_TOKEN_CONFIG_KEY, plaintext);
            var row = new ApiToken();
            row.ownerUsername = SYSTEM_OWNER;
            row.secretHash = TokenHasher.hash(plaintext);
            row.save();
        });
        EventLogger.info("auth",
                "Bootstrapped internal jclaw_api token (owner=%s)".formatted(SYSTEM_OWNER));
        return plaintext;
    }
}
