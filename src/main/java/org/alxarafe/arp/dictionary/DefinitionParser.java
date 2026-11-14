package org.alxarafe.arp.dictionary;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DefinitionParser {
    private static final Set<String> KEYS = Set.of("id", "type", "constraints", "enumValues", "label", "description");
    private static final Set<String> NUMERIC = Set.of("minimum", "maximum", "multipleOf", "exclusiveMinimum", "exclusiveMaximum");
    private DefinitionParser() {}

    static Descriptor parse(Map<?, ?> input) {
        if (!KEYS.containsAll(input.keySet())) throw Rejected.element();
        if (!(input.get("id") instanceof String id) || id.length() > 128
                || !id.matches("[A-Za-z][A-Za-z0-9_-]*(\\.[A-Za-z][A-Za-z0-9_-]*)+")) throw Rejected.element();
        ScalarType type;
        if (!(input.get("type") instanceof String name)) throw Rejected.element();
        try { type = ScalarType.valueOf(name); } catch (IllegalArgumentException e) { throw Rejected.element(); }
        String label = optionalText(input, "label"), description = optionalText(input, "description");
        if (input.containsKey("constraints") && !(input.get("constraints") instanceof Map<?, ?>)) throw Rejected.element();
        Map<?, ?> raw = input.containsKey("constraints") ? (Map<?, ?>) input.get("constraints") : Map.of();
        var allowed = new HashSet<String>();
        if (type.numeric()) allowed.addAll(NUMERIC);
        if (type == ScalarType.DECIMAL) allowed.addAll(Set.of("precision", "scale"));
        if (type.text() || type == ScalarType.BINARY) allowed.addAll(Set.of("minimumLength", "maximumLength"));
        if (type.text()) allowed.add("pattern");
        if (!allowed.containsAll(raw.keySet())) throw Rejected.element();
        var constraints = new LinkedHashMap<String, Object>();
        PortablePattern pattern = null;
        for (var entry : raw.entrySet()) {
            String key = (String) entry.getKey();
            Object value = entry.getValue();
            switch (key) {
                case "minimum", "maximum", "multipleOf" -> {
                    BigDecimal number;
                    try { number = Numbers.parse(value, type); }
                    catch (Rejected e) { if (e.code == Code.DICTIONARY_LIMIT_EXCEEDED) throw e; throw Rejected.element(); }
                    if (!Numbers.inRange(number, type) || key.equals("multipleOf") && number.signum() <= 0) throw Rejected.element();
                }
                case "exclusiveMinimum", "exclusiveMaximum" -> { if (!(value instanceof Boolean)) throw Rejected.element(); }
                case "pattern" -> {
                    if (!(value instanceof String text)) throw Rejected.element();
                    pattern = new PortablePattern(text);
                }
                default -> {
                    if (!Numbers.integral(value) || Numbers.metadata(value).signum() < 0) throw Rejected.element();
                }
            }
            constraints.put(key, value);
        }
        if (constraints.containsKey("precision") != constraints.containsKey("scale")) throw Rejected.element();
        if (constraints.containsKey("precision")) {
            BigDecimal precision = Numbers.metadata(constraints.get("precision")), scale = Numbers.metadata(constraints.get("scale"));
            if (precision.compareTo(BigDecimal.ONE) < 0 || precision.compareTo(BigDecimal.valueOf(4096)) > 0 || scale.compareTo(precision) > 0)
                throw Rejected.element();
        }
        if (constraints.containsKey("minimumLength") && constraints.containsKey("maximumLength")
                && Numbers.metadata(constraints.get("minimumLength")).compareTo(Numbers.metadata(constraints.get("maximumLength"))) > 0)
            throw Rejected.element();
        for (String endpoint : List.of("Minimum", "Maximum")) {
            if (Boolean.TRUE.equals(constraints.get("exclusive" + endpoint))
                    && !constraints.containsKey(endpoint.toLowerCase(java.util.Locale.ROOT))) throw Rejected.element();
        }
        if (constraints.containsKey("minimum") && constraints.containsKey("maximum")) {
            int comparison = new BigDecimal((String) constraints.get("minimum")).compareTo(new BigDecimal((String) constraints.get("maximum")));
            if (comparison > 0 || comparison == 0 && (Boolean.TRUE.equals(constraints.get("exclusiveMinimum")) || Boolean.TRUE.equals(constraints.get("exclusiveMaximum"))))
                throw Rejected.element();
        }
        var literals = new ArrayList<String>();
        if (type == ScalarType.ENUM) {
            if (!(input.get("enumValues") instanceof List<?> values) || values.isEmpty()) throw Rejected.element();
            var unique = new HashSet<String>();
            for (Object value : values) {
                if (!(value instanceof String text) || text.isEmpty() || !unique.add(text)) throw Rejected.element();
                literals.add(text);
            }
        } else if (input.containsKey("enumValues")) throw Rejected.element();
        var descriptor = new Descriptor(id, type, constraints, literals, label, description, pattern);
        for (String literal : literals) {
            var result = ScalarValidator.validate(descriptor, literal);
            if (!result.valid()) {
                if (result.issues().stream().anyMatch(i -> i.code() == Code.DICTIONARY_LIMIT_EXCEEDED)) throw Rejected.limit();
                throw Rejected.element();
            }
        }
        return descriptor;
    }

    private static String optionalText(Map<?, ?> input, String key) {
        if (!input.containsKey(key)) return null;
        if (!(input.get(key) instanceof String text) || text.isEmpty()) throw Rejected.element();
        return text;
    }
}
