import agents.CurrentTimeInjector;
import llm.LlmTypes.ChatMessage;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.TimezoneResolver;

import java.util.List;
import java.util.Map;

/**
 * Tests for {@link CurrentTimeInjector}, which moved the per-turn clock out of
 * the system prompt and onto the last user message.
 *
 * <p>The invariant under test throughout: everything <em>before</em> the last
 * user message must come out byte-identical, because that region is what an LLM
 * prefix cache serves without re-processing. A test that only checked "the clock
 * is present somewhere" would pass on the old broken placement too.
 */
class CurrentTimeInjectorTest extends UnitTest {

    @Test
    void blockCarriesHeadingAndConfiguredTimezone() {
        ConfigService.set(TimezoneResolver.APP_CONFIG_KEY, "Asia/Kuala_Lumpur");
        try {
            var block = CurrentTimeInjector.block();
            assertTrue(block.contains(CurrentTimeInjector.HEADING));
            assertTrue(block.contains("- Timezone: Asia/Kuala_Lumpur"),
                    "clock must reflect the configured app.timezone");
        } finally {
            ConfigService.delete(TimezoneResolver.APP_CONFIG_KEY);
            ConfigService.clearCache();
        }
    }

    @Test
    void prependsClockToLastUserMessage() {
        var out = CurrentTimeInjector.inject(List.of(
                ChatMessage.system("static prefix"),
                ChatMessage.user("hello")));

        assertEquals(2, out.size());
        assertEquals("static prefix", out.getFirst().content(),
                "system message must stay byte-identical — that is the whole point");
        var user = (String) out.get(1).content();
        assertTrue(user.contains(CurrentTimeInjector.HEADING), "clock must reach the user turn");
        assertTrue(user.endsWith("hello"), "original user text must survive intact");
    }

    @Test
    void targetsTheLastUserMessageNotTheFirst() {
        var out = CurrentTimeInjector.inject(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("turn one"),
                ChatMessage.assistant("reply"),
                ChatMessage.user("turn two")));

        assertEquals("turn one", out.get(1).content(),
                "prior history must stay byte-identical or the prefix cache misses on it");
        assertEquals("reply", out.get(2).content());
        assertTrue(((String) out.get(3).content()).contains(CurrentTimeInjector.HEADING));
    }

    @Test
    void prependsTextPartForMultimodalContent() {
        // Vision/audio turns carry a List of OpenAI-style parts rather than a
        // String (see VisionAudioAssembler), so the clock rides as an extra part.
        var parts = List.<Object>of(Map.of("type", "text", "text", "describe this"));
        var multimodal = new ChatMessage("user", parts, null, null, null);

        var out = CurrentTimeInjector.inject(List.of(multimodal));

        var merged = (List<?>) out.getFirst().content();
        assertEquals(2, merged.size(), "clock must be added as one extra text part");
        var first = (Map<?, ?>) merged.getFirst();
        assertEquals("text", first.get("type"));
        assertTrue(((String) first.get("text")).contains(CurrentTimeInjector.HEADING));
    }

    @Test
    void noOpsWhenThereIsNoUserMessage() {
        var input = List.of(ChatMessage.system("sys"));
        assertSame(input, CurrentTimeInjector.inject(input),
                "nothing sensible to attach to; must not invent a message");
    }

    @Test
    void doesNotMutateTheInputList() {
        var input = List.of(ChatMessage.system("sys"), ChatMessage.user("hi"));
        CurrentTimeInjector.inject(input);
        assertEquals("hi", input.get(1).content(),
                "callers reuse the pre-injection list; injection must return a copy");
    }
}
