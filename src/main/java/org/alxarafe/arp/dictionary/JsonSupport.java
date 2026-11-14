package org.alxarafe.arp.dictionary;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

final class JsonSupport {
    private static final ObjectMapper WRITER = new ObjectMapper();
    private JsonSupport() {}

    static ObjectMapper reader(Limits limits) {
        var factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(limits.maxNestingDepth())
                        .maxStringLength(limits.maxDocumentBytes())
                        .maxNumberLength(limits.maxDocumentBytes()).build()).build();
        return new ObjectMapper(factory)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    static String write(Object value) {
        try { return WRITER.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot export validated projection", e); }
    }

    static void unicode(Object value) {
        if (value instanceof String s && !Unicode.valid(s)) throw Rejected.document();
        if (value instanceof Map<?, ?> m) m.forEach((k, v) -> { unicode(k); unicode(v); });
        if (value instanceof List<?> l) l.forEach(JsonSupport::unicode);
    }
}
