package org.alxarafe.arp.dictionary;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.alxarafe.arp.cds.physical.PhysicalCatalog;

/** Application-owned active registry and authorization boundary, independent of JDBC execution. */
public final class ListGetHost
{
    /** Binding includes principal/tenant and policy revision; the caller supplies it, never the request. */
    public record Context(String identity, String policyRevision) {}
    /** Complete field grant plus SQL equality restrictions; null means an unsupported policy, not unrestricted. */
    public record Access(boolean allowed, Set<String> fields, Map<String, Object> equalities)
    {
        public Access
        {
            fields = Set.copyOf(fields);
            equalities = equalities == null ? null : Map.copyOf(equalities);
        }
    }
    @FunctionalInterface
    public interface Authorizer
    {
        Access authorize(String target, String operation, Context context);
    }
    public record Target(String projection, int defaultLimit, int maxLimit, Set<String> fields)
    {
        public Target
        {
            fields = Set.copyOf(fields);
            if (defaultLimit < 1 || defaultLimit > maxLimit || maxLimit > 100) {
                throw new IllegalArgumentException("Invalid server limits");
            }
        }
    }
    private static final class Fault extends RuntimeException
    {
        final String code;
        Fault(String code) { this.code = code; }
    }
    private final PhysicalCatalog catalog;
    private final PostgreSqlReadExecutor executor;
    private final Authorizer authorizer;
    private final Object gate = new Object();
    private Map<String, Target> active;
    private long policyGeneration;
    private final SecureRandom random = new SecureRandom();
    private final byte[] secret = new byte[32];
    private final ObjectMapper json = JsonSupport.reader(new Limits(1_048_576, 2048, 64));

    public ListGetHost(PhysicalCatalog catalog, PostgreSqlReadExecutor executor,
                       Map<String, Target> active, Authorizer authorizer)
    {
        this.catalog = catalog;
        this.executor = executor;
        this.active = Map.copyOf(active);
        this.authorizer = authorizer;
        random.nextBytes(secret);
    }
    /** The application must serialize every policy/composition mutation here, including ABA changes. */
    public void changeAccess(Map<String, Target> replacement, Runnable policyMutation)
    {
        synchronized (gate) {
            policyGeneration++;
            active = Map.copyOf(replacement);
            policyMutation.run();
        }
    }
    private static void need(boolean condition, String code)
    {
        if (!condition) { throw new Fault(code); }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value)
    {
        need(value instanceof Map<?, ?>, "INPUT_SHAPE");
        return (Map<String, Object>) value;
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value)
    {
        return (List<Map<String, Object>>) value;
    }
    private static Map<String, Object> find(Object list, String id)
    {
        return objects(list).stream().filter(v -> id.equals(v.get("id"))).findFirst()
                .orElseThrow(() -> new Fault("CAPABILITY_UNAVAILABLE"));
    }
    private static ScalarType type(Map<String, Object> field)
    {
        try { return ScalarType.valueOf((String) field.get("type")); }
        catch (RuntimeException e) { throw new Fault("CAPABILITY_UNAVAILABLE"); }
    }
    private Access access(String target, String operation, Context context, List<String> fields)
    {
        var decision = authorizer.authorize(target, operation, context);
        need(decision != null && decision.allowed() && decision.fields().containsAll(fields), "UNAVAILABLE");
        return decision;
    }
    /** Strict portable JSON decoding (duplicate members, Unicode, exact numbers and budgets included). */
    public Map<String, Object> invoke(byte[] request, Context context)
    {
        try {
            if (request.length > 1_048_576) { throw new Fault("INPUT_SHAPE"); }
            var decoded = json.readValue(request, Object.class);
            JsonSupport.unicode(decoded);
            return invokeDecoded(map(decoded), context);
        } catch (Fault e) { return failure(e.code); }
        catch (Exception e) { return failure("INPUT_SHAPE"); }
    }
    private Map<String, Object> invokeDecoded(Map<String, Object> request, Context context)
    {
        try {
            String target = request.get("target") instanceof String s ? s : "";
            String operation = request.get("operation") instanceof String s ? s : "";
            Target registration;
            Access initial;
            long epoch;
            synchronized (gate) {
                registration = active.get(target);
                need(registration != null && Set.of("LIST", "GET").contains(operation), "UNAVAILABLE");
                initial = access(target, operation, context, List.copyOf(registration.fields()));
                epoch = policyGeneration;
            }
            need(Set.of("target", "operation", "inputs").containsAll(request.keySet()), "INPUT_SHAPE");
            var snapshot = catalog.resolve();
            if (!snapshot.valid()) {
                var issue = objects(snapshot.projection().get("issues")).getFirst();
                throw new Fault(switch ((String) issue.get("family")) {
                    case "execution-failure" -> "READ_FAILED";
                    case "physical-incompatible" -> "INCOMPATIBLE_STORAGE";
                    default -> "INVALID_DEFINITION";
                });
            }
            var projection = find(snapshot.projection().get("projections"), registration.projection());
            need(((List<?>) projection.get("operations")).contains(operation), "UNAVAILABLE");
            var fields = objects(projection.get("fields"));
            var fieldNames = fields.stream().map(f -> (String) f.get("name")).toList();
            need(registration.fields().equals(Set.copyOf(fieldNames)), "INVALID_DEFINITION");
            need(initial.fields().containsAll(fieldNames), "UNAVAILABLE");
            need(initial.equalities() != null, "CAPABILITY_UNAVAILABLE");
            var model = find(snapshot.projection().get("models"), (String) projection.get("entity"));
            var physical = map(model.get("physical"));
            need("table".equals(physical.get("kind")), "CAPABILITY_UNAVAILABLE");
            var modelFields = objects(model.get("fields"));
            var columns = new LinkedHashMap<String, Map<String, Object>>();
            modelFields.forEach(f -> columns.put((String) f.get("name"), f));
            @SuppressWarnings("unchecked") var keys = (List<String>) physical.get("primaryKey");
            need(keys != null && !keys.isEmpty() && keys.size() <= 7, "CAPABILITY_UNAVAILABLE");
            var keyTypes = new ArrayList<ScalarType>();
            for (String key : keys) {
                var f = columns.get(key);
                ScalarType t = type(f);
                var keyPhysical = map(f.get("physical"));
                need(t.integer() && keyPhysical.get("scalarType") instanceof String nativeKey
                        && ScalarType.valueOf(nativeKey).integer()
                        && Boolean.FALSE.equals(keyPhysical.get("nullable")),
                        "CAPABILITY_UNAVAILABLE");
                keyTypes.add(t);
            }
            var selected = new ArrayList<PostgreSqlReadExecutor.Column>();
            for (var f : fields) {
                String name = (String) f.get("name");
                var stored = map(columns.get(name).get("physical"));
                ScalarType t = type(f);
                need(Set.of(ScalarType.INT8, ScalarType.UINT8, ScalarType.INT16, ScalarType.UINT16,
                        ScalarType.INT32, ScalarType.UINT32, ScalarType.INT64, ScalarType.DECIMAL,
                        ScalarType.STRING, ScalarType.TEXT, ScalarType.BOOLEAN, ScalarType.DATE).contains(t)
                        && stored.containsKey("nullable") && stored.containsKey("scalarType"),
                        "CAPABILITY_UNAVAILABLE");
                // PostgreSQL fixed CHAR has padding semantics outside the demonstrated lossless text profile.
                need(!((String) stored.get("nativeType")).startsWith("character("), "CAPABILITY_UNAVAILABLE");
                String nativeScalar = (String) stored.get("scalarType");
                need(t.name().equals(nativeScalar) || t.integer() && ScalarType.valueOf(nativeScalar).integer(),
                        "CAPABILITY_UNAVAILABLE");
                selected.add(new PostgreSqlReadExecutor.Column(name, t, (boolean) stored.get("nullable")));
            }
            var restriction = new LinkedHashMap<String, Object>();
            initial.equalities().forEach((name, value) -> {
                need(columns.containsKey(name), "CAPABILITY_UNAVAILABLE");
                ScalarType t = type(columns.get(name));
                need(t.integer(), "CAPABILITY_UNAVAILABLE");
                var normalized = ScalarValidator.read(t, value);
                need(normalized.valid(), "CAPABILITY_UNAVAILABLE");
                restriction.put(name, new BigDecimal((String) normalized.normalizedValue().orElseThrow()));
            });
            long generation;
            synchronized (catalog) {
                need(catalog.current().orElse(null) == snapshot, "GENERATION_CHANGED");
                generation = catalog.generation();
            }
            var binding = Map.<String, Object>of("target", target, "projection", registration.projection(),
                    "identity", context.identity(), "policy", context.policyRevision(),
                    "accessEpoch", Long.toString(epoch), "generation", Long.toString(generation));
            var inputs = map(request.get("inputs"));
            boolean get = operation.equals("GET"), opaque = !fieldNames.containsAll(keys);
            int limit = registration.defaultLimit();
            List<String> position = null;
            boolean absent = false;
            if (get) {
                need(inputs.keySet().equals(Set.of("key")), "INPUT_SHAPE");
                need(inputs.get("key") instanceof Map<?, ?>, "INVALID_KEY");
                var key = map(inputs.get("key"));
                if (opaque) {
                    need(key.keySet().equals(Set.of("reference")) && key.get("reference") instanceof String,
                            "INVALID_KEY");
                    String reference = (String) key.get("reference");
                    need(!reference.isEmpty() && reference.length() <= 128, "INVALID_KEY");
                    var decoded = open(reference, binding, "GET");
                    if (decoded == null) { absent = true; }
                    else { position = tokenPosition(decoded); }
                } else {
                    need(key.keySet().equals(Set.of("components")) && key.get("components") instanceof List<?>,
                            "INVALID_KEY");
                    var components = (List<?>) key.get("components");
                    need(components.size() == keys.size(), "INVALID_KEY");
                    position = new ArrayList<>();
                    for (int i = 0; i < keys.size(); i++) {
                        need(components.get(i) instanceof Map<?, ?>, "INVALID_KEY");
                        var c = map(components.get(i));
                        need(c.keySet().equals(Set.of("field", "type", "value"))
                                && keys.get(i).equals(c.get("field"))
                                && keyTypes.get(i).name().equals(c.get("type")), "INVALID_KEY");
                        var decoded = ScalarValidator.read(keyTypes.get(i), c.get("value"));
                        need(decoded.valid(), "INVALID_KEY");
                        position.add((String) decoded.normalizedValue().orElseThrow());
                    }
                }
            } else {
                need(Set.of("limit", "continuation").containsAll(inputs.keySet()), "INPUT_SHAPE");
                if (inputs.containsKey("limit")) {
                    Object value = inputs.get("limit");
                    need(Numbers.integral(value), "INPUT_SHAPE");
                    var n = Numbers.metadata(value);
                    need(n.compareTo(BigDecimal.ONE) >= 0 && n.compareTo(BigDecimal.valueOf(100)) <= 0,
                            "INPUT_SHAPE");
                    limit = Math.min(n.intValueExact(), registration.maxLimit());
                }
                if (inputs.containsKey("continuation")) {
                    need(inputs.get("continuation") instanceof String, "INVALID_CURSOR");
                    var decoded = open((String) inputs.get("continuation"), binding, "LIST");
                    need(decoded != null && Integer.toString(limit).equals(decoded.get("limit")), "INVALID_CURSOR");
                    position = tokenPosition(decoded);
                }
            }
            final int effectiveLimit = limit;
            java.util.function.Function<List<PostgreSqlReadExecutor.Row>, Map<String, Object>> publish = rows -> {
                // Validate even internal/hidden PKs before any row or token may escape.
                for (var row : rows) {
                    for (int i = 0; i < keys.size(); i++) {
                        need(ScalarValidator.read(keyTypes.get(i), row.key().get(i)).valid(), "ROW_IDENTITY_INVALID");
                    }
                }
                synchronized (gate) {
                    need(registration.equals(active.get(target)), "GENERATION_CHANGED");
                    var finalAccess = access(target, operation, context, fieldNames);
                    need(initial.equals(finalAccess), "UNAVAILABLE");
                    need(policyGeneration == epoch, "GENERATION_CHANGED");
                    return catalog.publishRead(snapshot, () -> {
                        if (get) {
                            need(rows.size() <= 1, "ROW_IDENTITY_INVALID");
                            return success(rows.isEmpty() ? Map.of("found", false)
                                    : Map.of("found", true, "record",
                                            record(rows.getFirst(), keys, keyTypes, opaque, binding)));
                        }
                        boolean more = rows.size() > effectiveLimit;
                        var visible = rows.subList(0, Math.min(effectiveLimit, rows.size()));
                        var records = visible.stream().map(r -> record(r, keys, keyTypes, opaque, binding)).toList();
                        var page = new LinkedHashMap<String, Object>();
                        page.put("limit", effectiveLimit); page.put("hasMore", more);
                        page.put("next", more ? seal(binding, "LIST", visible.getLast().key(), effectiveLimit) : null);
                        return success(Map.of("records", records, "page", page));
                    });
                }
            };
            java.util.function.BooleanSupplier current = () -> {
                synchronized (gate) {
                    return policyGeneration == epoch && registration.equals(active.get(target))
                            && initial.equals(authorizer.authorize(target, operation, context))
                            && catalog.current().orElse(null) == snapshot;
                }
            };
            return absent ? publish.apply(List.of()) : executor.read(new PostgreSqlReadExecutor.Plan(
                    physical, selected, keys, restriction, position, get, limit), current, publish);
        } catch (Fault e) { return failure(e.code); }
        catch (PostgreSqlReadExecutor.ReadFailure e) { return failure(e.code); }
        catch (IllegalStateException e) { return failure("GENERATION_CHANGED"); }
        catch (RuntimeException e) { return failure("READ_FAILED"); }
    }
    private Map<String, Object> record(PostgreSqlReadExecutor.Row row, List<String> keys,
                                       List<ScalarType> types, boolean opaque, Map<String, Object> binding)
    {
        Object key;
        if (opaque) { key = Map.of("reference", seal(binding, "GET", row.key(), 0)); }
        else {
            var components = new ArrayList<Object>();
            for (int i = 0; i < keys.size(); i++) {
                components.add(Map.of("field", keys.get(i), "type", types.get(i).name(), "value", row.key().get(i)));
            }
            key = Map.of("components", components);
        }
        return Map.of("key", key, "values", row.values());
    }
    private byte[] bindingBytes(Map<String, Object> binding, String purpose) throws java.io.IOException
    {
        var associated = new java.util.TreeMap<>(binding);
        associated.remove("generation");
        associated.put("purpose", purpose);
        return json.writeValueAsBytes(associated);
    }
    private String seal(Map<String, Object> binding, String purpose, List<String> position, int limit)
    {
        try {
            // All supported integer codecs fit signed 64 bits; seven components keep the token <= 128 chars.
            var payload = java.nio.ByteBuffer.allocate(9 + 8 * position.size());
            payload.putLong(Long.parseLong((String) binding.get("generation")));
            payload.put((byte) limit);
            for (String component : position) { payload.putLong(Long.parseLong(component)); }
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(bindingBytes(binding, purpose));
            byte[] encrypted = cipher.doFinal(payload.array());
            byte[] token = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, token, 0, nonce.length);
            System.arraycopy(encrypted, 0, token, nonce.length, encrypted.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } catch (Exception e) { throw new Fault("READ_FAILED"); }
    }
    private Map<String, Object> open(String token, Map<String, Object> binding, String purpose)
    {
        java.nio.ByteBuffer payload;
        try {
            if (token.length() > 128) { return null; }
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"),
                    new GCMParameterSpec(128, bytes, 0, 12));
            cipher.updateAAD(bindingBytes(binding, purpose));
            payload = java.nio.ByteBuffer.wrap(cipher.doFinal(bytes, 12, bytes.length - 12));
            if (payload.remaining() < 17 || (payload.remaining() - 9) % 8 != 0) { return null; }
        } catch (Exception e) { return null; }
        need(binding.get("generation").equals(Long.toString(payload.getLong())), "GENERATION_EXPIRED");
        String limit = Integer.toString(Byte.toUnsignedInt(payload.get()));
        var position = new ArrayList<String>();
        while (payload.hasRemaining()) { position.add(Long.toString(payload.getLong())); }
        return Map.of("limit", limit, "position", List.copyOf(position));
    }
    @SuppressWarnings("unchecked")
    private static List<String> tokenPosition(Map<String, Object> token)
    {
        return (List<String>) token.get("position");
    }
    private static Map<String, Object> success(Map<String, Object> output)
    {
        return java.util.Collections.unmodifiableMap(Map.of("status", "success", "diagnostics", List.of(),
                "output", CdsReadResult.freeze(output)));
    }
    private static Map<String, Object> failure(String code)
    {
        boolean failed = Set.of("READ_FAILED", "ROW_DECODE_FAILED", "ROW_IDENTITY_INVALID", "GENERATION_CHANGED")
                .contains(code);
        String family = failed ? "execution-failure" : switch (code) {
            case "INPUT_SHAPE", "INVALID_KEY", "INVALID_CURSOR" -> "invalid-data";
            case "INVALID_DEFINITION" -> "definition-invalid";
            case "INCOMPATIBLE_STORAGE" -> "physical-incompatible";
            default -> "operation-not-allowed";
        };
        return Map.of("status", failed ? "failed" : "rejected", "diagnostics", List.of(
                Map.of("code", "CDS_LISTGET_" + code, "family", family)));
    }
}