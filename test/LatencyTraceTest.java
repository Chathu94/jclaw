import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.LatencyStats;
import utils.LatencyTrace;

class LatencyTraceTest extends UnitTest {

    @Test
    void doubleEndEmitsEachSegmentOnce() {
        // JCLAW-822: end() is fired from racing terminal callbacks, so its
        // single-shot guard must be an atomic CAS — a second end() must be a
        // no-op, not a second emit. Use a UNIQUE channel so this assertion is
        // isolated from the JVM-global LatencyStats singleton the concurrently
        // running latency tests share (we must not reset() it or reuse a
        // shared channel name — see the play1 concurrent-TestEngine constraint).
        var channel = "lt-double-end-" + System.nanoTime();
        var trace = LatencyTrace.forTurn(channel, null);
        // PROLOGUE_DONE is required for end() to emit (early-exit traces skip).
        trace.mark(LatencyTrace.PROLOGUE_DONE);

        trace.end();
        trace.end(); // must be a no-op

        var total = LatencyStats.snapshot()
                .getAsJsonObject(channel)
                .getAsJsonObject("total");
        assertEquals(1L, total.get("count").getAsLong(),
                "a second end() must not re-emit the segments");
    }

    @Test
    void prefillEmittedBetweenRequestSentAndFirstOutput() {
        // Unique channel per test — LatencyStats is a JVM-global singleton and
        // play1 runs test classes concurrently, so a shared name would race.
        var channel = "lt-prefill-" + System.nanoTime();
        var trace = LatencyTrace.forTurn(channel, null);
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        trace.mark(LatencyTrace.LLM_REQUEST_SENT);
        trace.mark(LatencyTrace.LLM_FIRST_OUTPUT);
        trace.end();

        var prefill = LatencyStats.snapshot().getAsJsonObject(channel).getAsJsonObject("prefill");
        assertNotNull(prefill, "prefill must be emitted when both marks are present");
        assertEquals(1L, prefill.get("count").getAsLong());
    }

    @Test
    void prefillOmittedWhenNoOutputStreamed() {
        // Tool-call fragments never reach a streaming callback, so a round that
        // opens with a tool call sets no LLM_FIRST_OUTPUT. The segment must be
        // absent rather than silently backfilled from a later mark — a fabricated
        // sample would read as a cache hit and hide the very thing it measures.
        var channel = "lt-prefill-absent-" + System.nanoTime();
        var trace = LatencyTrace.forTurn(channel, null);
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        trace.mark(LatencyTrace.LLM_REQUEST_SENT);
        trace.end();

        assertNull(LatencyStats.snapshot().getAsJsonObject(channel).getAsJsonObject("prefill"),
                "no first-output mark must mean no prefill sample");
    }
}
