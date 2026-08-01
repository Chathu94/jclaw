import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import models.MessageRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Exercises {@code agents.VisionAudioAssembler.userMessageFor} — the content-part assembler
 * that lifts a stored user turn into an OpenAI-compatible
 * {@code content: [...]} array when attachments are present (JCLAW-25 for
 * images, JCLAW-132 for audio). Play 1.x pins tests to the default package,
 * which is why the assembler is exposed as {@code public} rather than
 * package-private.
 */
class VisionAudioAssemblyTest extends UnitTest {

    private Agent agent;
    private Conversation conversation;
    private Message message;

    @BeforeEach
    void setUp() {
        Fixtures.deleteDatabase();
        agent = new Agent();
        agent.name = "assembly-agent-" + UUID.randomUUID().toString().substring(0, 6);
        agent.modelProvider = "openrouter";
        agent.modelId = "google/gemini-3-flash-preview";
        agent.save();

        conversation = new Conversation();
        conversation.agent = agent;
        conversation.channelType = "web";
        conversation.peerId = "tester";
        conversation.save();

        message = new Message();
        message.conversation = conversation;
        message.role = MessageRole.USER.value;
        message.content = "what's in these?";
        message.save();
    }

    @Test
    void audioAttachmentBecomesInputAudioContentPart() throws Exception {
        persistAttachment("clip.mp3", "audio/mpeg",
                MessageAttachment.KIND_AUDIO, new byte[]{1, 2, 3, 4, 5});

        // Re-fetch through Hibernate so the assembler exercises the same
        // code path production uses (buildMessages → Message.findRecent →
        // userMessageFor). The null-guard inside userMessageFor handles
        // Play's enhancer quirk where attachments lands as null rather than
        // a lazy PersistentList proxy; explicitly populating the collection
        // here would mask that production path.
        var fresh = Message.<Message>findById(message.id);

        var chatMsg = agents.VisionAudioAssembler.userMessageFor(fresh);
        assertTrue(chatMsg.content() instanceof List,
                "multimodal turn must emit a content-parts list, not a string");
        var parts = (List<?>) chatMsg.content();

        var audioPart = firstPartOfType(parts, "input_audio");
        assertNotNull(audioPart, "an input_audio content part must be emitted for AUDIO kind");
        var inner = (Map<?, ?>) audioPart.get("input_audio");
        assertEquals("AQIDBAU=", inner.get("data"),
                "data field should be bare base64 of the five bytes on disk");
        assertEquals("mp3", inner.get("format"),
                "format should derive from audio/mpeg via Play's mime-types properties");

        // Was byte-equality, which held only because this branch appended nothing —
        // the !supportsAudio branch has always injected a transcript block with a path.
        // The real invariant is that the audio's own filename stays out of the text.
        var textPart = firstPartOfType(parts, "text");
        assertNotNull(textPart);
        var text = (String) textPart.get("text");
        assertTrue(text.startsWith("what's in these?"), text);
        assertFalse(text.contains("clip.mp3"),
                "audio attachments must NOT inject filename references into the text: " + text);
        // The workspace path is deliberately present: without it an audio-capable
        // model can hear the recording and cannot name the file for any tool.
        assertTrue(text.contains("The audio above is in your workspace at"), text);
    }

    @Test
    void audioFormatDerivesFromSniffedMime() throws Exception {
        // audio/wav -> wav; tests that the reverse-lookup delegates to
        // play.libs.MimeTypes + application.conf rather than a hardcoded
        // MIME string match.
        persistAttachment("voice.wav", "audio/wav",
                MessageAttachment.KIND_AUDIO, new byte[]{9, 8, 7});

        var fresh = Message.<Message>findById(message.id);

        var chatMsg = agents.VisionAudioAssembler.userMessageFor(fresh);
        var parts = (List<?>) chatMsg.content();
        var audioPart = firstPartOfType(parts, "input_audio");
        assertNotNull(audioPart);
        var inner = (Map<?, ?>) audioPart.get("input_audio");
        assertEquals("wav", inner.get("format"));
    }

    @Test
    void audioFormatResolvesTikaRegisteredWavAliases() throws Exception {
        // Tika sniffs WAV files as audio/vnd.wave (RFC 2361) or the legacy
        // audio/x-wav, never the de-facto audio/wav that Play's bundled
        // mime-types.properties uses. MimeExtensions.forMime must resolve
        // both Tika variants to the same "wav" format hint, otherwise the
        // OpenAI input_audio content part goes out without a format and
        // upstream providers silently drop the audio (JCLAW-132 live-bug
        // regression).
        persistAttachment("rfc.wav", "audio/vnd.wave",
                MessageAttachment.KIND_AUDIO, new byte[]{1, 2, 3});
        var fresh1 = Message.<Message>findById(message.id);
        var inner1 = (Map<?, ?>) firstPartOfType((List<?>) agents.VisionAudioAssembler.userMessageFor(fresh1)
                .content(), "input_audio").get("input_audio");
        assertEquals("wav", inner1.get("format"),
                "audio/vnd.wave must alias to 'wav' format hint");

        // Reset the message's attachments for the legacy-x-wav case.
        setUp();
        persistAttachment("legacy.wav", "audio/x-wav",
                MessageAttachment.KIND_AUDIO, new byte[]{4, 5, 6});
        var fresh2 = Message.<Message>findById(message.id);
        var inner2 = (Map<?, ?>) firstPartOfType((List<?>) agents.VisionAudioAssembler.userMessageFor(fresh2)
                .content(), "input_audio").get("input_audio");
        assertEquals("wav", inner2.get("format"),
                "audio/x-wav must alias to 'wav' format hint");
    }

    @Test
    void fileAttachmentStillRidesAsFilenameReference() throws Exception {
        persistAttachment("doc.pdf", "application/pdf",
                MessageAttachment.KIND_FILE, new byte[]{'p', 'd', 'f'});

        var fresh = Message.<Message>findById(message.id);

        var chatMsg = agents.VisionAudioAssembler.userMessageFor(fresh);
        var parts = (List<?>) chatMsg.content();

        // No image_url or input_audio parts for a plain document.
        assertNull(firstPartOfType(parts, "image_url"));
        assertNull(firstPartOfType(parts, "input_audio"));

        var textPart = firstPartOfType(parts, "text");
        assertNotNull(textPart);
        var text = (String) textPart.get("text");
        assertTrue(text.contains("doc.pdf"),
                "FILE kind must surface the original filename in the text part: " + text);
        assertTrue(text.contains("[Attached file:"),
                "FILE kind must use the bracketed filename marker: " + text);
    }

    @Test
    void nativelyCarriedMediaStillGetsItsWorkspacePath() throws Exception {
        // The caption and transcript blocks carry the path, and neither runs when the
        // model consumes the medium natively — leaving it unable to name either file.
        persistAttachment("shot.png", "image/png",
                MessageAttachment.KIND_IMAGE, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        persistAttachment("voice.mp3", "audio/mpeg",
                MessageAttachment.KIND_AUDIO, new byte[]{1, 2});

        var fresh = Message.<Message>findById(message.id);
        var parts = (List<?>) agents.VisionAudioAssembler.userMessageFor(fresh).content();
        var text = (String) firstPartOfType(parts, "text").get("text");

        assertTrue(text.contains("The image above is in your workspace at"), text);
        assertTrue(text.contains("The audio above is in your workspace at"), text);
        // Workspace-root-relative: no {agentName}/ prefix for a tool to trip on.
        assertTrue(text.contains("attachments/" + conversation.id + "/"), text);
        // UUID leaf — what lets the path coexist with the filename invariant below.
        assertFalse(text.contains("shot.png"), text);
        assertFalse(text.contains("voice.mp3"), text);
    }

    @Test
    void aSpokenTurnGetsNoAudioPathBecauseItsFileIsSessionScoped() throws Exception {
        // Blank content beside audio marks a spoken turn; its WAV dies with the session.
        message.content = "";
        message.save();
        persistAttachment("voice.wav", "audio/wav",
                MessageAttachment.KIND_AUDIO, new byte[]{1, 2});
        // The gate is about spoken-audio lifetime, not paths wholesale — images still get one.
        persistAttachment("shot.png", "image/png",
                MessageAttachment.KIND_IMAGE, new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        var fresh = Message.<Message>findById(message.id);
        var parts = (List<?>) agents.VisionAudioAssembler.userMessageFor(fresh).content();
        var text = (String) firstPartOfType(parts, "text").get("text");

        assertFalse(text.contains("The audio above is in your workspace at"), text);
        assertTrue(text.contains("The image above is in your workspace at"), text);
        // The audio itself still reaches the model — only its path is withheld.
        assertNotNull(firstPartOfType(parts, "input_audio"),
                "the spoken audio must still ride as an input_audio part");
    }

    @Test
    void mixedAttachmentsEmitOrderedContentParts() throws Exception {
        // One of each kind. The text part must come first and must carry
        // the filename marker for the FILE-kind attachment but NOT for the
        // image or audio (those ride as their own structured parts).
        persistAttachment("shot.png", "image/png",
                MessageAttachment.KIND_IMAGE, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        persistAttachment("voice.mp3", "audio/mpeg",
                MessageAttachment.KIND_AUDIO, new byte[]{1, 2});
        persistAttachment("notes.pdf", "application/pdf",
                MessageAttachment.KIND_FILE, new byte[]{'p', 'd', 'f'});

        var fresh = Message.<Message>findById(message.id);

        var chatMsg = agents.VisionAudioAssembler.userMessageFor(fresh);
        var parts = (List<?>) chatMsg.content();

        // Exactly one of each expected structured kind, plus one text part.
        var textCount = parts.stream()
                .filter(p -> p instanceof Map && "text".equals(((Map<?, ?>) p).get("type")))
                .count();
        var imageCount = parts.stream()
                .filter(p -> p instanceof Map && "image_url".equals(((Map<?, ?>) p).get("type")))
                .count();
        var audioCount = parts.stream()
                .filter(p -> p instanceof Map && "input_audio".equals(((Map<?, ?>) p).get("type")))
                .count();
        assertEquals(1, textCount);
        assertEquals(1, imageCount);
        assertEquals(1, audioCount);

        // Text part references notes.pdf but not the image or audio filenames.
        var textPart = firstPartOfType(parts, "text");
        var text = (String) textPart.get("text");
        assertTrue(text.contains("notes.pdf"), "FILE filename belongs in text: " + text);
        assertFalse(text.contains("shot.png"),
                "image filename must NOT appear in text — it rides as image_url: " + text);
        assertFalse(text.contains("voice.mp3"),
                "audio filename must NOT appear in text — it rides as input_audio: " + text);

        // Text part is the first element so the LLM reads user intent before
        // the media parts. image and audio may appear in either order.
        assertEquals("text", ((Map<?, ?>) parts.get(0)).get("type"),
                "text part must lead the content-parts array");
    }

    // --- helpers ---

    private MessageAttachment persistAttachment(String filename, String mimeType,
                                                 String kind, byte[] bytes) throws Exception {
        var uuid = UUID.randomUUID().toString();
        var ext = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1)
                : "";
        var relPath = "attachments/" + conversation.id + "/" + uuid
                + (ext.isEmpty() ? "" : "." + ext);
        var full = AgentService.acquireWorkspacePath(agent.name, relPath);
        Files.createDirectories(full.getParent());
        Files.write(full, bytes);

        var att = new MessageAttachment();
        att.message = message;
        att.uuid = uuid;
        att.originalFilename = filename;
        att.storagePath = agent.name + "/" + relPath;
        att.mimeType = mimeType;
        att.sizeBytes = (long) bytes.length;
        att.kind = kind;
        att.save();
        return att;
    }

    private static Map<?, ?> firstPartOfType(List<?> parts, String type) {
        return parts.stream()
                .filter(p -> p instanceof Map && type.equals(((Map<?, ?>) p).get("type")))
                .map(p -> (Map<?, ?>) p)
                .findFirst()
                .orElse(null);
    }
}
