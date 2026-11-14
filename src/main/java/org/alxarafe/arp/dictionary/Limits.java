package org.alxarafe.arp.dictionary;

/** Per-source configurable intake budgets. Fixed numeric/pattern ceilings belong to ARP. */
public record Limits(int maxDocumentBytes, int maxElementsPerSource, int maxNestingDepth) {
    public static final Limits DEFAULT = new Limits(1_048_576, 2048, 128);
    public Limits {
        if (maxDocumentBytes < 1 || maxElementsPerSource < 1 || maxNestingDepth < 1)
            throw new IllegalArgumentException("Limits must be positive");
    }
}
