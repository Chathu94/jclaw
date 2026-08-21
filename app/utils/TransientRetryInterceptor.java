package utils;

import okhttp3.Interceptor;
import okhttp3.Response;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * Retries genuinely transient HTTP failures on the scrape/fetch lane (JCLAW-1099).
 *
 * <p>Scoped deliberately, from measurement rather than instinct. Of 45 corpus failures,
 * 30 were {@code 403} and 4 were {@code 401} — refusals, not blips. A blanket three-times
 * retry would have issued about 135 extra requests to gain at most three pages, and most
 * of that volume would have gone to origins that had already said no, which is precisely
 * the pattern a WAF escalates on. Retrying only {@code 429}/{@code 503}/{@code 504} and
 * connection failures costs about nine requests for the same three pages.
 *
 * <p>{@code Retry-After} is honoured where the origin sends it, capped: a {@code 429}
 * means we were going too fast, so retrying immediately is worse than not retrying at
 * all, and this codebase already paces per host and honours robots.txt — a retry loop
 * that ignored that would undercut it. An origin asking for an hour gets
 * {@link #MAX_BACKOFF} instead, because a crawl cannot stall on one host.
 *
 * <p>Deliberately NOT part of the escalation ladder. Retry is about a transient failure;
 * the ladder is about transport capability. Keeping them apart means every caller of
 * {@link SsrfGuard#buildGuardedClient} inherits this uniformly, and the ladder simply
 * sees fewer errors rather than growing a second concern.
 */
public final class TransientRetryInterceptor implements Interceptor {

    /** Statuses worth a second attempt: rate-limiting and upstream unavailability.
     *  {@code 403}/{@code 401} are absent on purpose — those are decisions, not blips. */
    private static final Set<Integer> RETRYABLE = Set.of(429, 503, 504);

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration BASE_BACKOFF = Duration.ofMillis(500);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);

    @Override
    public @NonNull Response intercept(Chain chain) throws IOException {
        var request = chain.request();
        // Only idempotent methods. Everything on this lane is a GET today; the guard is
        // here so that stays true if it ever is not.
        if (!"GET".equalsIgnoreCase(request.method())) {
            return chain.proceed(request);
        }

        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Response response = null;
            try {
                response = chain.proceed(request);
                if (!RETRYABLE.contains(response.code()) || attempt == MAX_ATTEMPTS) {
                    return response;
                }
            } catch (IOException e) {
                lastFailure = e;
                if (attempt == MAX_ATTEMPTS) throw e;
            }

            var wait = backoff(attempt, response);
            // The body must be closed before another attempt or the connection leaks;
            // the response is being discarded either way.
            if (response != null) response.close();
            if (!sleep(wait)) {
                // Interrupted mid-backoff: the caller is shutting down, so stop rather
                // than issue another request into a crawl that is being torn down.
                throw lastFailure != null ? lastFailure : new IOException("interrupted during retry backoff");
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("retries exhausted");
    }

    /** {@code Retry-After} when the origin sends a usable one, else exponential. */
    private static Duration backoff(int attempt, Response response) {
        if (response != null) {
            var header = response.header("Retry-After");
            if (header != null && !header.isBlank()) {
                try {
                    var seconds = Long.parseLong(header.strip());
                    if (seconds >= 0) {
                        return Duration.ofSeconds(seconds).compareTo(MAX_BACKOFF) > 0
                                ? MAX_BACKOFF : Duration.ofSeconds(seconds);
                    }
                } catch (NumberFormatException _) {
                    // An HTTP-date form is legal and rare; fall through to exponential
                    // rather than parse a second grammar for a value we cap anyway.
                }
            }
        }
        var exponential = BASE_BACKOFF.multipliedBy(1L << (attempt - 1));
        return exponential.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : exponential;
    }

    /** False when the wait was interrupted. */
    private static boolean sleep(Duration wait) {
        try {
            Thread.sleep(wait.toMillis());
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
