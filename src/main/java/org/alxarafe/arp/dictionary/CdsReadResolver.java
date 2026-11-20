package org.alxarafe.arp.dictionary;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure CDS-READ-001 metadata compiler; no discovery, SQL, row access or scalar validation copy.
 */
@SuppressWarnings("unchecked")
public final class CdsReadResolver
{
    private static final List<String> KINDS = List.of("physical", "logical", "projections");
    static final Comparator<String> ORDER = (a, b) -> Arrays.compareUnsigned(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    private static final Map<String, BigInteger[]> RANGES = integerRanges();

    private static Map<String, BigInteger[]> integerRanges()
    {
        var result = new HashMap<String, BigInteger[]>();
        for (var type : ScalarType.values()) {
            if (type.integer()) {
                String[] bounds = Numbers.integerRange(type);
                result.put(type.name(), range(bounds[0], bounds[1]));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private final Map<String, Map<String, Object>> schemas = new HashMap<>();

    public CdsReadResolver()
    {
        for (int i = 0; i < KINDS.size(); i++) {
            String name = List.of("physical", "logical", "projection").get(i);
            try (var resource = getClass().getResourceAsStream("/cds/" + name + ".schema.json")) {
                if (resource == null) {
                    throw new IllegalStateException("Missing pinned CDS schema");
                }
                schemas.put(KINDS.get(i), new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(resource, Map.class));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot load pinned CDS schema", e);
            }
        }
    }

    private static BigInteger[] range(String lo, String hi)
    {
        return new BigInteger[] { new BigInteger(lo), new BigInteger(hi) };
    }

    static String pointer(String... parts)
    {
        return "/" + String.join("/", Arrays.stream(parts)
                .map(s -> s.replace("~", "~0").replace("/", "~1")).toList());
    }

    private static final class Failure extends RuntimeException
    {
        final Map<String, Object> issue;

        Failure(String code, String at, String family)
        {
            issue = Map.of("family", family, "code", "CDS_READ_" + code, "at", at);
        }
    }

    private static void need(boolean ok, String code, String at)
    {
        need(ok, code, at, "definition-invalid");
    }

    private static void need(boolean ok, String code, String at, String family)
    {
        if (!ok) {
            throw new Failure(code, at, family);
        }
    }

    /**
     * Independent intake; its document may be null. Expected input errors have one contractual issue.
     */
    public Map<String, Object> parse(Source source)
    {
        try {
            var output = new LinkedHashMap<String, Object>();
            output.put("valid", true);
            output.put("issues", List.of());
            output.put("document", new SourceParser(Limits.DEFAULT).parse(source));
            return (Map<String, Object>) CdsReadResult.freeze(output);
        } catch (Rejected e) {
            return CdsReadResult.failure(e.code == Code.DICTIONARY_LIMIT_EXCEEDED ? "LIMIT" : "SOURCE_INVALID",
                    pointer("sources", source.sourceId()), "definition-invalid").projection();
        }
    }

    /**
     * Trusted supplied handles; decode the complete batch before schema resolution.
     */
    public CdsReadResult resolveSources(Map<String, Map<String, Source>> sources, Catalog dictionary)
    {
        if (sources.values().stream().mapToInt(Map::size).sum() > 256) {
            return CdsReadResult.failure("LIMIT", "/", "definition-invalid");
        }
        var bundle = new LinkedHashMap<String, Object>();
        for (String kind : KINDS) {
            var docs = new LinkedHashMap<String, Object>();
            for (var entry : sorted(sources.getOrDefault(kind, Map.of())).entrySet()) {
                Source s = entry.getValue();
                var parsed = parse(new Source(s.providerId(), entry.getKey(), s.encoding(), s.content()));
                if (!(boolean) parsed.get("valid")) {
                    return new CdsReadResult(parsed);
                }
                docs.put(entry.getKey(), parsed.get("document"));
            }
            bundle.put(kind, docs);
        }
        return resolve(bundle, dictionary);
    }

    /**
     * Decoded JSON object graph. Retained results never share mutable caller state.
     */
    public CdsReadResult resolve(Map<String, Object> supplied, Catalog dictionary)
    {
        try {
            int count = KINDS.stream().mapToInt(k -> map(supplied.get(k)).size()).sum();
            need(count <= 256, "LIMIT", "/");
            var bundle = map(CdsReadResult.freeze(supplied));
            var indexes = new HashMap<String, Map<String, Entry>>();
            for (String kind : KINDS) {
                var entries = new LinkedHashMap<String, Entry>();
                for (var item : sorted(map(bundle.get(kind))).entrySet()) {
                    String at = pointer(kind, item.getKey());
                    var schema = schemas.get(kind);
                    need(schema(item.getValue(), schema, schema), "SCHEMA_INVALID", at);
                    var doc = map(item.getValue());
                    String id = text(doc, "id");
                    need(!entries.containsKey(id), "DUPLICATE_ID", at);
                    entries.put(id, new Entry(doc, at));
                }
                indexes.put(kind, entries);
            }
            for (var e : indexes.get("physical").values()) {
                physical(e.doc, e.at);
            }
            var models = new TreeMap<String, Object>(ORDER);
            for (var item : sorted(indexes.get("logical")).entrySet()) {
                var logical = item.getValue().doc;
                String at = item.getValue().at;
                need(indexes.get("physical").containsKey(text(logical, "physical")),
                        "REFERENCE_NOT_FOUND", at + "/physical");
                var physical = indexes.get("physical").get(text(logical, "physical")).doc;
                var columns = new TreeMap<String, Object>(ORDER);
                for (Object col : list(physical.get("columns"))) {
                    columns.put(text(map(col), "name"), col);
                }
                var overlays = map(logical.get("fields"));
                need(columns.keySet().containsAll(overlays.keySet()), "UNKNOWN_FIELD", at + "/fields");
                var fields = new ArrayList<Object>();
                for (var column : columns.entrySet()) {
                    fields.add(field(map(column.getValue()),
                            map(overlays.getOrDefault(column.getKey(), Map.of())), dictionary,
                            at + pointer("fields", column.getKey())));
                }
                models.put(item.getKey(), Map.of("id", item.getKey(), "physical", physical, "fields", fields));
            }
            var projected = new TreeMap<String, Object>(ORDER);
            for (String id : sorted(indexes.get("projections")).keySet()) {
                projection(id, List.of(), indexes.get("projections"), models, projected);
            }
            return new CdsReadResult(Map.of("valid",
                    true,
                    "issues",
                    List.of(),
                    "models",
                    new ArrayList<>(models.values()),
                    "projections",
                    new ArrayList<>(projected.values())));
        } catch (Failure e) {
            return new CdsReadResult(Map.of("valid", false, "issues", List.of(e.issue)));
        }
    }

    private record Entry(Map<String, Object> doc, String at) {
    }

    private static Map<String, Object> map(Object v)
    {
        return (Map<String, Object>) v;
    }

    private static List<Object> list(Object v)
    {
        return (List<Object>) v;
    }

    private static String text(Map<String, Object> v, String key)
    {
        return (String) v.get(key);
    }

    private static Map<String, Object> object(Map<String, Object> v, String key)
    {
        return map(v.getOrDefault(key, Map.of()));
    }

    private static <T> Map<String, T> sorted(Map<String, T> v)
    {
        var sorted = new TreeMap<String, T>(ORDER);
        sorted.putAll(v);
        return sorted;
    }

    private static BigInteger bound(Object value, String at)
    {
        need(value instanceof String && ((String) value).length() <= 4096, "LIMIT", at);
        String s = (String) value;
        need(s.matches("-?(?:0|[1-9][0-9]*)"), "PROFILE_UNSUPPORTED", at);
        return new BigInteger(s);
    }

    private static boolean interval(Map<String, Object> constraints)
    {
        return Set.of("minimum", "maximum").containsAll(constraints.keySet())
                && constraints.values().stream().allMatch(v -> v instanceof String s
                        && s.length() <= 4096 && s.matches("-?(?:0|[1-9][0-9]*)"));
    }

    private static void physical(Map<String, Object> doc, String at)
    {
        need(doc.get("kind").equals("table"), "PROFILE_UNSUPPORTED", at + "/kind");
        var columns = list(doc.get("columns"));
        need(columns.size() <= 2048, "LIMIT", at + "/columns");
        var names = new HashSet<String>();
        var ordinals = new HashSet<BigInteger>();
        boolean unique = true;
        for (Object item : columns) {
            var col = map(item);
            unique &= names.add(text(col, "name"));
            if (col.containsKey("ordinal")) {
                unique &= ordinals.add(decimal(col.get("ordinal")).toBigIntegerExact());
            }
        }
        need(unique, "PHYSICAL_INVALID", at + "/columns");
        for (int i = 0; i < columns.size(); i++) {
            var col = map(columns.get(i));
            String point = at + "/columns/" + i;
            var values = new HashMap<String, BigInteger>();
            var extent = object(col, "integerRange");
            for (String key : List.of("minimum", "maximum")) {
                if (extent.containsKey(key)) {
                    values.put(key, bound(extent.get(key), point + "/integerRange/" + key));
                }
            }
            need(!values.keySet().containsAll(List.of("minimum", "maximum"))
                    || values.get("minimum").compareTo(values.get("maximum")) <= 0, "PHYSICAL_INVALID", point);
            var range = col.containsKey("scalarType") ? RANGES.get(text(col, "scalarType")) : null;
            if (range != null) {
                for (var v : values.values()) {
                    need(range[0].compareTo(v) <= 0 && v.compareTo(range[1]) <= 0, "PHYSICAL_INVALID", point);
                }
            }
            if (col.containsKey("decimalGeometry")) {
                var g = object(col, "decimalGeometry");
                need(decimal(g.get("scale")).compareTo(decimal(g.get("precision"))) <= 0,
                        "PHYSICAL_INVALID", point);
            }
        }
        need(names.containsAll(list(doc.getOrDefault("primaryKey", List.of()))), "PHYSICAL_INVALID", at);
        for (Object item : list(doc.getOrDefault("indexes", List.of()))) {
            need(names.containsAll(list(map(item).get("columns"))), "PHYSICAL_INVALID", at);
        }
        for (Object item : list(doc.getOrDefault("foreignKeys", List.of()))) {
            var key = map(item);
            need(names.containsAll(list(key.get("columns")))
                    && list(key.get("columns")).size() == list(object(key, "target").get("columns")).size(),
                    "PHYSICAL_INVALID", at);
        }
        for (String key : List.of("indexes", "foreignKeys")) {
            var ids = new HashSet<String>();
            boolean distinct = true;
            for (Object item : list(doc.getOrDefault(key, List.of()))) {
                distinct &= ids.add(text(map(item), "name"));
            }
            need(distinct, "PHYSICAL_INVALID", at + "/" + key);
        }
    }

    private static Map<String, Object> field(
            Map<String, Object> col, Map<String, Object> overlay,
            Catalog dictionary, String at)
    {
        var out = new LinkedHashMap<String, Object>();
        out.put("name", col.get("name"));
        out.put("physical", col);
        out.put("compatibility", "not-verified");
        String type = text(col, "scalarType");
        var intrinsic = new LinkedHashMap<String, Object>();
        if (overlay.containsKey("dictionary")) {
            String id = text(overlay, "dictionary");
            var resolved = dictionary.resolve(id).descriptor();
            need(resolved.isPresent(), "REFERENCE_NOT_FOUND", at + "/dictionary");
            var descriptor = resolved.orElseThrow().projection();
            type = text(descriptor, "type");
            intrinsic.putAll(object(descriptor, "constraints"));
            out.put("dictionary", id);
            if (descriptor.containsKey("enumValues")) {
                out.put("enumValues", descriptor.get("enumValues"));
            }
        } else if (type != null && RANGES.containsKey(type)) {
            var range = RANGES.get(type);
            for (var e : object(col, "integerRange").entrySet()) {
                var n = new BigInteger((String) e.getValue());
                if (!n.equals(range[e.getKey().equals("minimum") ? 0 : 1])) {
                    intrinsic.put(e.getKey(), n.toString());
                }
            }
        }
        var effective = new LinkedHashMap<>(intrinsic);
        if (type != null) {
            out.put("type", type);
            out.put("intrinsicConstraints", intrinsic);
            out.put("constraints", effective);
        }
        var context = object(overlay, "constraints");
        if (overlay.containsKey("constraints")) {
            out.put("contextualConstraints", context);
        }
        if (!context.isEmpty()) {
            need(type != null && RANGES.containsKey(type) && interval(intrinsic),
                    "PROFILE_UNSUPPORTED", at + "/constraints");
            need(Set.of("minimum", "maximum").containsAll(context.keySet()),
                    "PROFILE_UNSUPPORTED", at + "/constraints");
        }
        if (type != null && RANGES.containsKey(type) && interval(intrinsic)) {
            var range = RANGES.get(type);
            var lo = range[0].max(new BigInteger((String) intrinsic.getOrDefault("minimum", range[0].toString())));
            var hi = range[1].min(new BigInteger((String) intrinsic.getOrDefault("maximum", range[1].toString())));
            for (String key : List.of("minimum", "maximum")) {
                if (context.containsKey(key)) {
                    var v = bound(context.get(key), at + "/constraints/" + key);
                    need(key.equals("minimum") ? v.compareTo(lo) >= 0 : v.compareTo(hi) <= 0,
                            "CONSTRAINT_WEAKENING", at + "/constraints/" + key);
                    if (key.equals("minimum")) {
                        lo = v;
                    } else {
                        hi = v;
                    }
                    effective.put(key, v.toString());
                }
            }
            need(lo.compareTo(hi) <= 0, "CONSTRAINT_CONTRADICTION", at + "/constraints");
            String physicalType = text(col, "scalarType");
            if (physicalType != null && RANGES.containsKey(physicalType)) {
                var p = RANGES.get(physicalType);
                var extent = object(col, "integerRange");
                var plo = p[0].max(new BigInteger((String) extent.getOrDefault("minimum", p[0].toString())));
                var phi = p[1].min(new BigInteger((String) extent.getOrDefault("maximum", p[1].toString())));
                need(plo.compareTo(lo) <= 0 && hi.compareTo(phi) <= 0,
                        "DOMAIN_NOT_REPRESENTABLE", at, "physical-incompatible");
                out.put("compatibility", "compatible");
            }
        }
        for (String key : List.of("label", "description", "hint")) {
            if (overlay.containsKey(key)) {
                out.put(key, overlay.get(key));
            }
        }
        return out;
    }

    private Map<String, Object> projection(
            String id, List<String> stack, Map<String, Entry> index,
            Map<String, Object> models, Map<String, Object> out)
    {
        var doc = index.get(id).doc;
        String at = index.get(id).at;
        need(!stack.contains(id), "REFERENCE_CYCLE", at + "/source");
        need(stack.size() < 64, "LIMIT", at + "/source");
        var operations = list(doc.get("operations"));
        need(List.of("LIST", "GET").containsAll(operations), "OPERATION_UNSUPPORTED",
                at + "/operations", "operation-not-allowed");
        var source = object(doc, "source");
        String entity;
        List<Object> fields;
        if (source.containsKey("entity")) {
            entity = text(source, "entity");
            need(models.containsKey(entity), "REFERENCE_NOT_FOUND", at + "/source");
            fields = list(map(models.get(entity)).get("fields"));
        } else {
            String parentId = text(source, "projection");
            need(index.containsKey(parentId), "REFERENCE_NOT_FOUND", at + "/source");
            var next = new ArrayList<>(stack);
            next.add(id);
            var parent = projection(parentId, next, index, models, out);
            entity = text(parent, "entity");
            fields = list(parent.get("fields"));
            need(list(parent.get("operations")).containsAll(operations), "OPERATION_EXPANSION", at + "/operations");
        }
        var available = new HashMap<String, Map<String, Object>>();
        for (Object f : fields) {
            available.put(text(map(f), "name"), map(f));
        }
        need(available.keySet().containsAll(list(doc.get("fields"))), "FIELD_NOT_EXPOSED", at + "/fields");
        var selected = new ArrayList<Object>();
        for (Object name : list(doc.get("fields"))) {
            var f = available.get(name);
            var publicField = new LinkedHashMap<>(f);
            for (String key : List.of("physical", "intrinsicConstraints", "contextualConstraints")) {
                publicField.remove(key);
            }
            if (f.containsKey("physical") && object(f, "physical").containsKey("nullable")) {
                publicField.put("nullable", object(f, "physical").get("nullable"));
            }
            selected.add(publicField);
        }
        var result = Map.<String, Object>of("id", id, "source", source, "entity", entity,
                "fields", selected, "operations", operations);
        out.put(id, result);
        return result;
    }

    private static BigDecimal decimal(Object v)
    {
        return new BigDecimal(v.toString());
    }

    /**
     * Only the keywords present in the pinned authoring schemas; never validates scalar values.
     */
    private static boolean schema(Object v, Map<String, Object> s, Map<String, Object> root)
    {
        if (s.containsKey("$ref")) {
            Object ref = root;
            for (String part : text(s, "$ref").substring(2).split("/")) {
                ref = map(ref).get(part);
            }
            return schema(v, map(ref), root);
        }
        if (s.containsKey("oneOf")) {
            long n = list(s.get("oneOf")).stream().filter(o -> schema(v, map(o), root)).count();
            if (n != 1) {
                return false;
            }
        }
        if (s.containsKey("const") && !Objects.equals(v, s.get("const"))) {
            return false;
        }
        if (s.containsKey("enum") && !list(s.get("enum")).contains(v)) {
            return false;
        }
        if (s.containsKey("type")) {
            boolean ok = switch (text(s, "type")) {
                case "object" -> v instanceof Map;
                case "array" -> v instanceof List;
                case "string" -> v instanceof String;
                case "boolean" -> v instanceof Boolean;
                case "integer" -> v instanceof Number && decimal(v).stripTrailingZeros().scale() <= 0;
                default -> throw new IllegalStateException("Unknown pinned schema type");
            };
            if (!ok) {
                return false;
            }
        }
        if (v instanceof String string) {
            int length = string.codePointCount(0, string.length());
            if (s.containsKey("minLength") && length < ((Number) s.get("minLength")).intValue()) {
                return false;
            }
            if (s.containsKey("maxLength") && length > ((Number) s.get("maxLength")).intValue()) {
                return false;
            }
            if (s.containsKey("pattern") && !java.util.regex.Pattern.compile(text(s, "pattern"),
                    java.util.regex.Pattern.UNICODE_CHARACTER_CLASS).matcher(
                            string)
                    .find()) {
                return false;
            }
        }
        if (v instanceof Number && s.containsKey("minimum") && decimal(v).compareTo(decimal(s.get("minimum"))) < 0) {
            return false;
        }
        if (v instanceof Map) {
            var object = map(v);
            if (!object.keySet().containsAll(list(s.getOrDefault("required", List.of())))) {
                return false;
            }
            if (s.containsKey("minProperties") && object.size() < ((Number) s.get("minProperties")).intValue()) {
                return false;
            }
            for (var e : object.entrySet()) {
                if (s.containsKey("propertyNames") && !schema(e.getKey(), object(s, "propertyNames"), root)) {
                    return false;
                }
                Object child = object(s, "properties").getOrDefault(e.getKey(), s.get("additionalProperties"));
                if (Boolean.FALSE.equals(child)) {
                    return false;
                }
                if (child instanceof Map && !schema(e.getValue(), map(child), root)) {
                    return false;
                }
            }
        }
        if (v instanceof List<?> values) {
            if (s.containsKey("minItems") && values.size() < ((Number) s.get("minItems")).intValue()) {
                return false;
            }
            if (Boolean.TRUE.equals(s.get("uniqueItems")) && new HashSet<>(values).size() != values.size()) {
                return false;
            }
            for (Object item : values) {
                if (s.containsKey("items") && !schema(item, object(s, "items"), root)) {
                    return false;
                }
            }
        }
        return true;
    }
}
