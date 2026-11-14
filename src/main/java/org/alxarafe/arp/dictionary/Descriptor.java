package org.alxarafe.arp.dictionary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Built and validated by CatalogBuilder; all reachable state is immutable. */
public final class Descriptor {
    private final String id;
    private final ScalarType type;
    private final Map<String, Object> constraints;
    private final List<String> enumValues;
    private final String label;
    private final String description;
    private final PortablePattern pattern;

    Descriptor(String id, ScalarType type, Map<String, Object> constraints,
               List<String> enumValues, String label, String description, PortablePattern pattern) {
        this.id = id;
        this.type = type;
        this.constraints = Map.copyOf(constraints);
        this.enumValues = List.copyOf(enumValues);
        this.label = label;
        this.description = description;
        this.pattern = pattern;
    }

    public String id() { return id; }
    public ScalarType type() { return type; }
    public Map<String, Object> constraints() { return constraints; }
    public List<String> enumValues() { return enumValues; }
    public Optional<String> label() { return Optional.ofNullable(label); }
    public Optional<String> description() { return Optional.ofNullable(description); }
    PortablePattern pattern() { return pattern; }

    public Map<String, Object> projection() {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("type", type.name());
        result.put("constraints", constraints);
        if (type == ScalarType.ENUM) result.put("enumValues", enumValues);
        if (label != null) result.put("label", label);
        if (description != null) result.put("description", description);
        return Map.copyOf(result);
    }

    /** Exact numeric metadata is exported as JSON numbers, not floating-point approximations. */
    public String toJson() { return JsonSupport.write(projection()); }
}
