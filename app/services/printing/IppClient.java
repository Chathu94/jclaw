package services.printing;

import com.hp.jipp.encoding.AttributeGroup;
import com.hp.jipp.encoding.IppInputStream;
import com.hp.jipp.encoding.IppPacket;
import com.hp.jipp.encoding.Tag;
import com.hp.jipp.model.Operation;
import com.hp.jipp.model.Types;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;
import utils.HttpFactories;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IPP print backend (JCLAW-911) — the primary one, and the only one that can
 * tell us what happened.
 *
 * <p>RFC 8010 frames an IPP request as a binary attribute packet followed
 * immediately by the document bytes, POSTed as {@code application/ipp}. HP JIPP
 * encodes and decodes the packet; the HTTP hop rides JClaw's shared OkHttp
 * client, per the tree-wide rule that outbound HTTP goes through
 * {@link HttpFactories} (pinned by ArchitectureTest).
 *
 * <p>The reason this is tried first is not speed — it is that IPP answers. A
 * successful Print-Job returns a job id and a status code, so the tool can report
 * "queued as job 42" rather than "the bytes went somewhere". The other two
 * backends cannot make that statement.
 */
public final class IppClient {

    private static final MediaType IPP = MediaType.get("application/ipp");

    /**
     * IPP request ids need only be unique within a connection, but a monotonic
     * counter makes a packet capture readable across jobs when several are in
     * flight. Wrapping at overflow is harmless.
     */
    private static final AtomicInteger REQUEST_ID = new AtomicInteger(1);

    /** Outcome of a Print-Job. */
    public record PrintResult(boolean accepted, Integer jobId, String state, String message) {}

    private IppClient() {}

    /**
     * Submit a Print-Job.
     *
     * @param printerUri     {@code ipp://host:port/path} of the target printer
     * @param jobName        job name, shown on the printer's display
     * @param user           requesting user recorded on the job
     * @param documentFormat MIME type of {@code document} (e.g. {@code application/pdf});
     *                       when null the printer is left to sniff it, which is what
     *                       {@code application/octet-stream} means to a conforming printer
     * @param document       the bytes to print
     */
    public static PrintResult print(String printerUri, String jobName, String user,
                                    String documentFormat, byte[] document,
                                    JobAttributes job) throws IOException {
        var operation = new java.util.ArrayList<com.hp.jipp.encoding.Attribute<?>>();
        operation.add(Types.attributesCharset.of("utf-8"));
        operation.add(Types.attributesNaturalLanguage.of("en"));
        operation.add(Types.printerUri.of(URI.create(printerUri)));
        operation.add(Types.requestingUserName.of(user));
        operation.add(Types.jobName.of(jobName));
        if (documentFormat != null && !documentFormat.isBlank()) {
            operation.add(Types.documentFormat.of(documentFormat));
        }

        // Job-template attributes go in their OWN group, not alongside the
        // operation attributes. RFC 8011 §4.2 separates "how to address this
        // request" from "how to print this job", and a printer that finds `sides`
        // in the operation group is entitled to reject the whole request.
        var groups = new java.util.ArrayList<AttributeGroup>();
        groups.add(AttributeGroup.groupOf(Tag.operationAttributes, operation));
        if (job != null && !job.isEmpty()) {
            var template = new java.util.ArrayList<com.hp.jipp.encoding.Attribute<?>>();
            if (job.sides() != null) {
                template.add(Types.sides.of(job.sides()));
            }
            if (job.colorMode() != null) {
                template.add(Types.printColorMode.of(job.colorMode()));
            }
            if (job.media() != null) {
                template.add(Types.media.of(job.media()));
            }
            groups.add(AttributeGroup.groupOf(Tag.jobAttributes, template));
        }

        var packet = new IppPacket(Operation.printJob, REQUEST_ID.getAndIncrement(),
                groups.toArray(new AttributeGroup[0]));

        var response = exchange(printerUri, packet, document);
        var status = response.getStatus();
        var jobId = response.getValue(Tag.jobAttributes, Types.jobId);
        var jobState = response.getValue(Tag.jobAttributes, Types.jobState);
        // IPP status codes below 0x0100 are the successful family (successful-ok and
        // its warning variants). Anything at or above is a genuine rejection —
        // treating a warning as failure would fail jobs that actually printed.
        var accepted = status != null && status.getCode() < 0x0100;
        return new PrintResult(accepted, jobId,
                jobState == null ? null : jobState.getName(),
                status == null ? "no status returned" : status.getName());
    }

    /**
     * The document formats this printer says it accepts, lowercased.
     *
     * <p>Asked over IPP rather than read from the mDNS {@code pdl} TXT record
     * because the two disagree: the Canon's TXT record and its
     * {@code document-format-supported} attribute list different sets, and the
     * IPP attribute is the one the Print-Job operation is actually validated
     * against. Empty when the printer will not say.
     */
    public static java.util.Set<String> supportedFormats(String printerUri) throws IOException {
        var packet = new IppPacket(Operation.getPrinterAttributes, REQUEST_ID.getAndIncrement(),
                AttributeGroup.groupOf(Tag.operationAttributes,
                        Types.attributesCharset.of("utf-8"),
                        Types.attributesNaturalLanguage.of("en"),
                        Types.printerUri.of(URI.create(printerUri))));
        var response = exchange(printerUri, packet, null);
        var formats = response.getStrings(Tag.printerAttributes, Types.documentFormatSupported);
        if (formats == null || formats.isEmpty()) {
            return java.util.Set.of();
        }
        return formats.stream().map(f -> f.toLowerCase().trim())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Query a printer's own state (Get-Printer-Attributes). */
    public static String printerState(String printerUri) throws IOException {
        var packet = new IppPacket(Operation.getPrinterAttributes, REQUEST_ID.getAndIncrement(),
                AttributeGroup.groupOf(Tag.operationAttributes,
                        Types.attributesCharset.of("utf-8"),
                        Types.attributesNaturalLanguage.of("en"),
                        Types.printerUri.of(URI.create(printerUri))));
        var response = exchange(printerUri, packet, null);
        var state = response.getValue(Tag.printerAttributes, Types.printerState);
        var reasons = response.getStrings(Tag.printerAttributes, Types.printerStateReasons);
        var text = state == null ? "unknown" : state.getName();
        return reasons == null || reasons.isEmpty() ? text : text + " (" + String.join(", ", reasons) + ")";
    }

    /** Query one job's state (Get-Job-Attributes). */
    public static String jobState(String printerUri, int jobId) throws IOException {
        var packet = new IppPacket(Operation.getJobAttributes, REQUEST_ID.getAndIncrement(),
                AttributeGroup.groupOf(Tag.operationAttributes,
                        Types.attributesCharset.of("utf-8"),
                        Types.attributesNaturalLanguage.of("en"),
                        Types.printerUri.of(URI.create(printerUri)),
                        Types.jobId.of(jobId)));
        var response = exchange(printerUri, packet, null);
        var state = response.getValue(Tag.jobAttributes, Types.jobState);
        return state == null ? "unknown" : state.getName();
    }

    /** Cancel a job (Cancel-Job). Returns the IPP status name. */
    public static String cancel(String printerUri, int jobId, String user) throws IOException {
        var packet = new IppPacket(Operation.cancelJob, REQUEST_ID.getAndIncrement(),
                AttributeGroup.groupOf(Tag.operationAttributes,
                        Types.attributesCharset.of("utf-8"),
                        Types.attributesNaturalLanguage.of("en"),
                        Types.printerUri.of(URI.create(printerUri)),
                        Types.jobId.of(jobId),
                        Types.requestingUserName.of(user)));
        var response = exchange(printerUri, packet, null);
        var status = response.getStatus();
        return status == null ? "no status returned" : status.getName();
    }

    /**
     * POST an IPP packet (optionally followed by document bytes) and decode the reply.
     *
     * <p>The {@code ipp://} scheme is an IPP-level addressing convention, not a
     * transport — the actual hop is HTTP(S), so the scheme is rewritten here. Getting
     * this wrong produces an OkHttp "unexpected url" that reads like a malformed
     * printer address rather than a client bug.
     */
    private static IppPacket exchange(String printerUri, IppPacket packet, byte[] document)
            throws IOException {
        var httpUrl = printerUri.replaceFirst("^ipps://", "https://").replaceFirst("^ipp://", "http://");

        var body = new RequestBody() {
            @Override
            public MediaType contentType() {
                return IPP;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                // Stream straight into the sink rather than buffering the whole
                // job: a print document is arbitrarily large and this path is the
                // one that would OOM on a 200 MB PDF.
                packet.write(sink.outputStream());
                if (document != null) {
                    sink.write(document);
                }
            }
        };

        var request = new Request.Builder().url(httpUrl).post(body).build();
        try (var response = HttpFactories.general().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("IPP request failed: HTTP " + response.code());
            }
            var responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("IPP response had no body");
            }
            try (var in = new IppInputStream(responseBody.byteStream())) {
                return in.readPacket();
            }
        }
    }

    /** Encode a packet to bytes. Exposed for tests, which assert the wire form without a printer. */
    static byte[] encode(IppPacket packet) throws IOException {
        var out = new ByteArrayOutputStream();
        packet.write(out);
        return out.toByteArray();
    }
}
