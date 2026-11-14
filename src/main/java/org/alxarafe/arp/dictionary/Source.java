package org.alxarafe.arp.dictionary;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Immutable explicit source snapshot. This class does no filesystem or network I/O. */
public final class Source {
    public enum Encoding { JSON, YAML }
    private final String providerId;
    private final String sourceId;
    private final Encoding encoding;
    private final byte[] content;

    public Source(String providerId, String sourceId, Encoding encoding, byte[] content) {
        this.providerId = Objects.requireNonNull(providerId);
        this.sourceId = Objects.requireNonNull(sourceId);
        this.encoding = Objects.requireNonNull(encoding);
        this.content = Objects.requireNonNull(content).clone();
    }

    public static Source utf8(String providerId, String sourceId, Encoding encoding, String content) {
        Objects.requireNonNull(content);
        if (!Unicode.valid(content)) throw new IllegalArgumentException("Unpaired surrogate in source text; use byte snapshots to test malformed encoding");
        return new Source(providerId, sourceId, encoding, content.getBytes(StandardCharsets.UTF_8));
    }

    public String providerId() { return providerId; }
    public String sourceId() { return sourceId; }
    public Encoding encoding() { return encoding; }
    public byte[] content() { return content.clone(); }
    int byteLength() { return content.length; }
}
