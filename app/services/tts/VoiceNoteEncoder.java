package services.tts;

import play.Logger;
import services.transcription.FfmpegProbe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Turns the WAV {@link TtsRouter} produces into something worth sending over a
 * chat channel (JCLAW-876).
 *
 * <p>WAV is the wrong wire format for a spoken reply. Telegram's {@code sendVoice}
 * wants OGG/Opus specifically and renders it as a voice note — the natural mirror
 * when the operator sent one — and WhatsApp behaves the same way; hand either of
 * them a WAV and it arrives as a file to download instead. Size compounds it: at
 * 48&nbsp;kHz mono PCM a one-minute reply is ~5.8&nbsp;MB, against roughly a tenth
 * of that as Opus. Browsers and Slack play Opus natively, so nothing regresses on
 * the web.
 *
 * <p>Falls back to the original WAV when ffmpeg is missing or the transcode fails.
 * A larger reply that plays is better than no reply, and the web player handles
 * WAV today. The returned {@link Encoded} always describes the bytes actually
 * produced — the caller must not assume it asked for OGG and got it.
 */
public final class VoiceNoteEncoder {

    private VoiceNoteEncoder() {}

    /** Opus bitrate. 32&nbsp;kbps mono is the voice-note range and is transparent
     *  enough for speech; the reference-clip cloning work is audible well below
     *  this, and the point of transcoding is to stop shipping megabytes. */
    private static final String OPUS_BITRATE = "32k";

    /** Tail of ffmpeg's output kept when it fails — enough to name the cause
     *  without dumping its banner into the log. */
    private static final int FFMPEG_ERROR_TAIL_CHARS = 400;

    /** Audio bytes plus what they actually are. */
    public record Encoded(byte[] bytes, String mimeType, String extension) {}

    private static Encoded wav(byte[] bytes) {
        return new Encoded(bytes, "audio/wav", "wav");
    }

    /**
     * Encode {@code wavBytes} as OGG/Opus, or return it unchanged when that is not
     * possible. Never throws for a transcode problem — the caller has working audio
     * either way and losing the reply over its container would be the worse outcome.
     */
    public static Encoded toVoiceNote(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length == 0) return wav(wavBytes);
        if (!FfmpegProbe.isAvailable()) {
            Logger.debug("VoiceNoteEncoder: ffmpeg unavailable (%s) — sending WAV",
                    FfmpegProbe.lastResult().reason());
            return wav(wavBytes);
        }
        Path in = null;
        Path out = null;
        try {
            in = Files.createTempFile("jclaw-tts-", ".wav");
            out = Files.createTempFile("jclaw-tts-", ".ogg");
            Files.write(in, wavBytes);
            // -y so the pre-created temp output is overwritten rather than prompting.
            var proc = new ProcessBuilder("ffmpeg", "-y", "-i", in.toString(),
                    "-c:a", "libopus", "-b:a", OPUS_BITRATE, "-ac", "1", out.toString())
                    .redirectErrorStream(true).start();
            var output = new String(proc.getInputStream().readAllBytes());
            if (proc.waitFor() != 0) {
                Logger.warn("VoiceNoteEncoder: ffmpeg failed, sending WAV instead: %s",
                        output.substring(Math.max(0, output.length() - FFMPEG_ERROR_TAIL_CHARS)));
                return wav(wavBytes);
            }
            var encoded = Files.readAllBytes(out);
            // A zero-length success is still a failure from the caller's point of
            // view; libopus can exit 0 having written nothing on odd inputs.
            if (encoded.length == 0) return wav(wavBytes);
            return new Encoded(encoded, "audio/ogg", "ogg");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return wav(wavBytes);
        } catch (IOException | RuntimeException e) {
            Logger.warn("VoiceNoteEncoder: transcode failed, sending WAV instead: %s", e.getMessage());
            return wav(wavBytes);
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            Logger.debug("VoiceNoteEncoder: could not remove temp file %s: %s", p, e.getMessage());
        }
    }
}
