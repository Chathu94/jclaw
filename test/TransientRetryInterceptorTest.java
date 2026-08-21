import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.TransientRetryInterceptor;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Transient-failure retry on the scrape/fetch lane (JCLAW-1099).
 *
 * <p>Follows the {@code WebFetchToolTest} idiom: a terminal interceptor returns
 * pre-enqueued responses without opening a socket, so no MockWebServer and no port.
 *
 * <p>What matters here is the scope, not the mechanism. The corpus said 30 of 45
 * failures were {@code 403} and 4 were {@code 401}; retrying those would have tripled
 * our request volume against origins that had already refused, to gain nothing.
 */
class TransientRetryInterceptorTest extends UnitTest {

    private final AtomicInteger attempts = new AtomicInteger();
    private final Deque<Object> canned = new ArrayDeque<>();

    private OkHttpClient client() {
        return new OkHttpClient.Builder()
                .addInterceptor(new TransientRetryInterceptor())
                .addInterceptor(chain -> {
                    attempts.incrementAndGet();
                    var next = canned.poll();
                    if (next instanceof IOException e) throw e;
                    int code = next == null ? 200 : (int) next;
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(code)
                            .message("stub " + code)
                            .body(ResponseBody.create("body", MediaType.get("text/plain")))
                            .build();
                })
                .build();
    }

    private Response get() throws IOException {
        return client().newCall(
                new Request.Builder().url("https://example.test/").get().build()).execute();
    }

    @Test
    void aTransientStatusIsRetriedAndTheRetrySucceeds() throws IOException {
        canned.add(503);
        canned.add(200);
        try (var r = get()) {
            assertEquals(200, r.code());
        }
        assertEquals(2, attempts.get(), "503 should have been retried exactly once here");
    }

    @Test
    void aRefusalIsNotRetried() throws IOException {
        // The whole point of the scoping. 30 of 45 corpus failures were 403; retrying
        // them is volume aimed at origins that already said no.
        canned.add(403);
        try (var r = get()) {
            assertEquals(403, r.code());
        }
        assertEquals(1, attempts.get(), "403 must not be retried");
    }

    @Test
    void anAuthRefusalIsNotRetried() throws IOException {
        canned.add(401);
        try (var r = get()) {
            assertEquals(401, r.code());
        }
        assertEquals(1, attempts.get(), "401 must not be retried");
    }

    @Test
    void retriesAreBoundedAndTheLastResponseIsReturned() throws IOException {
        canned.add(503);
        canned.add(503);
        canned.add(503);
        try (var r = get()) {
            assertEquals(503, r.code(), "the caller sees the origin's own status, not an error");
        }
        assertEquals(3, attempts.get(), "three attempts total, not unbounded");
    }

    @Test
    void aConnectionFailureIsRetriedAndThenSurfaced() {
        canned.add(new IOException("connect failed 1"));
        canned.add(new IOException("connect failed 2"));
        canned.add(new IOException("connect failed 3"));
        var boom = assertThrows(IOException.class, this::get);
        assertEquals("connect failed 3", boom.getMessage(), "the last failure is what surfaces");
        assertEquals(3, attempts.get());
    }

    @Test
    void aTransientFailureFollowedByASuccessRecovers() throws IOException {
        canned.add(new IOException("connect failed"));
        canned.add(200);
        try (var r = get()) {
            assertEquals(200, r.code());
        }
        assertEquals(2, attempts.get());
    }
}
