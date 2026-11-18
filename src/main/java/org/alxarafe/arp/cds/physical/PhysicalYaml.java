package org.alxarafe.arp.cds.physical;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.alxarafe.arp.dictionary.Catalog;
import org.alxarafe.arp.dictionary.CatalogBuilder;
import org.alxarafe.arp.dictionary.CdsReadResolver;
import org.alxarafe.arp.dictionary.Source;

/** YAML 1.2 flow document with quoted JSON scalars; exact, deterministic and within existing intake rules. */
public final class PhysicalYaml
{
    private static final CdsReadResolver RESOLVER = new CdsReadResolver();
    private static final Catalog EMPTY = new CatalogBuilder().buildCatalog(List.of()).catalog().orElseThrow();
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private PhysicalYaml() { }

    public static byte[] encode(Map<String, Object> document, PhysicalStoreKey key) throws ObservationException
    {
        validate(document, key);
        try {
            byte[] canonical = JSON.writeValueAsBytes(ordered(document));
            byte[] yaml = ("---\n" + new String(canonical, StandardCharsets.UTF_8) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            if (!Arrays.equals(canonical, JSON.writeValueAsBytes(ordered(decode(yaml, key))))) {
                throw new ObservationException();
            }
            return yaml;
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> decode(byte[] bytes, PhysicalStoreKey key) throws ObservationException
    {
        var parsed = RESOLVER.parse(new Source("physical-store", "stored", Source.Encoding.YAML, bytes));
        if (!(boolean) parsed.get("valid") || !(parsed.get("document") instanceof Map)) {
            throw new ObservationException();
        }
        var document = (Map<String, Object>) parsed.get("document"); validate(document, key); return document;
    }

    @SuppressWarnings("unchecked")
    private static void validate(Map<String, Object> doc, PhysicalStoreKey key) throws ObservationException
    {
        try {
            var intake = RESOLVER.parse(new Source("physical-store", "observed", Source.Encoding.JSON,
                    JSON.writeValueAsBytes(doc)));
            if (!(boolean) intake.get("valid") || !RESOLVER.resolve(Map.of(
                    "physical", Map.of("stored", doc), "logical", Map.of(), "projections", Map.of()), EMPTY).valid()) {
                throw new ObservationException();
            }
            var source = (Map<String, Object>) doc.get("source");
            if (!key.physicalId().equals(doc.get("id")) || !key.provider().equals(source.get("provider"))
                    || !key.dialect().equals(source.get("dialect")) || !key.schema().equals(source.get("schema"))
                    || !key.table().equals(source.get("name"))) throw new ObservationException();
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    private static Object ordered(Object v)
    {
        if (v instanceof Map<?, ?> m) {
            var sorted = new TreeMap<String, Object>((a, b) -> Arrays.compareUnsigned(
                    a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
            m.forEach((k, value) -> sorted.put((String) k, ordered(value))); return sorted;
        }
        if (v instanceof List<?> list) return list.stream().map(PhysicalYaml::ordered).toList();
        if (v instanceof BigDecimal d) return d.toBigIntegerExact(); // Physical schema numeric metadata are integers.
        return v;
    }
}