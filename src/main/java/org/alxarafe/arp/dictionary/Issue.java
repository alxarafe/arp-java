package org.alxarafe.arp.dictionary;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record Issue(Code code, String elementId, String constraint, Provenance provenance) {
    public Issue { Objects.requireNonNull(code); }

    /** Portable fields only; diagnostic provenance can be obtained separately. */
    public Map<String, Object> projection() {
        var result = new LinkedHashMap<String, Object>();
        result.put("code", code.name());
        if (elementId != null) result.put("elementId", elementId);
        if (constraint != null) result.put("constraint", constraint);
        return Map.copyOf(result);
    }
}
