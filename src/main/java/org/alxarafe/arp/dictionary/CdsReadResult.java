package org.alxarafe.arp.dictionary;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable complete outcome; nested maps/lists are immutable, numeric values are exact. */
public final class CdsReadResult
{
    private final Map<String, Object> projection;
    @SuppressWarnings("unchecked")
    CdsReadResult(Map<String, Object> value)
    {
        projection = (Map<String, Object>) freeze(value);
    }
    public boolean valid()
    {
        return (boolean) projection.get("valid");
    }
    public Map<String, Object> projection()
    {
        return projection;
    }
    public String toJson()
    {
        return JsonSupport.write(projection);
    }
    static Object freeze(Object value)
    {
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            map.forEach((k, v) -> copy.put((String) k, freeze(v)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return Collections.unmodifiableList(list.stream().map(CdsReadResult::freeze).toList());
        }
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal
                || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            return value;
        }
        throw new IllegalArgumentException("Decoded sources must use exact portable values");
    }
    public static CdsReadResult failure(String code, String at, String family)
    {
        return new CdsReadResult(Map.of("valid", false, "issues", List.of(
                Map.of("code", "CDS_READ_" + code, "at", at, "family", family))));
    }
}