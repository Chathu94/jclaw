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
        // JCLAW-882: the call counter emits from end() too, so it has to be
        // covered by the same guard — a double emit would double the NFR's
        // per-turn call distribution.
        try (var _ = LatencyTrace.bind(trace)) {
            LatencyTrace.countLlmCall();
        }

        trace.end();
        trace.end(); // must be a no-op

        var channelStats = LatencyStats.snapshot().getAsJsonObject(channel);
        assertEquals(1L, channelStats.getAsJsonObject("total").get("count").getAsLong(),
                "a second end() must not re-emit the segments");
        assertEquals(1L, channelStats.getAsJsonObject("llm_call_count").get("count").getAsLong(),
                "a second end() must not re-emit the LLM call count either");
    }
}
