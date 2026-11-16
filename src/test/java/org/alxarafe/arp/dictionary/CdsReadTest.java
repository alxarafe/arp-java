package org.alxarafe.arp.dictionary;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class CdsReadTest
{
    private final CdsReadResolver resolver = new CdsReadResolver();

    @TestFactory Stream<DynamicTest> resolution() throws Exception
    {
        var cases = ConformanceTest.read(ConformanceTest.ROOT.resolve("fixtures/cds/read/v1/cases.json"));
        var tests = new ArrayList<DynamicTest>();
        for (var c : cases.get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("id").asText(), () -> {
                var path = ConformanceTest.ROOT.resolve(c.get("dictionary").asText());
                var built = new CatalogBuilder().buildCatalog(List.of(new Source("fixture", "dict",
                    path.toString().endsWith("yaml") ? Source.Encoding.YAML : Source.Encoding.JSON,
                    Files.readAllBytes(path))));
                assertTrue(built.valid());
                var bundle = (Map<String, Object>) ConformanceTest.JSON.convertValue(c.get("input"), Object.class);
                String before = JsonSupport.write(bundle);
                var result = resolver.resolve(bundle, built.catalog().orElseThrow());
                ConformanceTest.compare(result.projection(), c.get("expected"), false);
                ConformanceTest.compare(resolver.resolve((Map<String, Object>) reverse(bundle),
                    built.catalog().orElseThrow()).projection(), c.get("expected"), false);
                assertEquals(before, JsonSupport.write(bundle));
                assertThrows(UnsupportedOperationException.class, () -> result.projection().put("valid", false));
                assertFrozen(result.projection());
                bundle.clear();
                ConformanceTest.compare(result.projection(), c.get("expected"), false);
            }));
        }
        assertEquals(80, tests.size());
        return tests.stream();
    }

    @TestFactory Stream<DynamicTest> intake() throws Exception
    {
        var cases = ConformanceTest.read(ConformanceTest.ROOT.resolve("fixtures/cds/read/v1/source-cases.json"));
        var tests = new ArrayList<DynamicTest>();
        for (var c : cases.get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("id").asText(), () -> {
                byte[] bytes = c.has("bytesHex") ? HexFormat.of().parseHex(c.get("bytesHex").asText())
                    : c.get("text").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                var actual = resolver.parse(new Source("fixture", "s", c.get("encoding").asText().equals("json")
                    ? Source.Encoding.JSON : Source.Encoding.YAML, bytes));
                if (c.has("expectedError")) {
                    ConformanceTest.compare(
                    (Map<String, Object>) ((List<?>) actual.get("issues")).getFirst(), c.get("expectedError"), false);
                }
                else {
                    ConformanceTest.assertJson(c.get("expected"),
                    ConformanceTest.JSON.readTree(JsonSupport.write(actual.get("document"))));
                }
            }));
        }
        assertEquals(11, tests.size());
        return tests.stream();
    }

    private static Object reverse(Object v)
    {
        if (v instanceof Map<?, ?> m) {
            var entries = new ArrayList<>(m.entrySet()); Collections.reverse(entries);
            var out = new LinkedHashMap<String, Object>();
            entries.forEach(e -> out.put((String) e.getKey(), reverse(e.getValue())));
            return out;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(CdsReadTest::reverse).toList();
        }
        return v;
    }

    private static void assertFrozen(Object v)
    {
        if (v instanceof Map<?, ?> m) {
            assertThrows(UnsupportedOperationException.class, () -> ((Map<Object, Object>) m).put("__", false));
            m.values().forEach(CdsReadTest::assertFrozen);
        }
        if (v instanceof List<?> l) {
            assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) l).add(false));
            l.forEach(CdsReadTest::assertFrozen);
        }
    }

    @Test void generations()
    {
        var store = new CdsReadPublication(); var other = new CdsReadPublication();
        var good = new CdsReadResult(Map.of("valid", true, "issues", List.of(),
                "models", List.of(), "projections", List.of()));
        var bad = CdsReadResult.failure("OBSERVATION_FAILED", "/snapshot", "execution-failure");
        long ticket = store.beginGeneration();
        assertSame(good, store.publish(ticket, good)); assertSame(good, store.current().orElseThrow());
        assertTrue(other.current().isEmpty());
        store.invalidate(); assertTrue(store.current().isEmpty());
        assertEquals(CdsReadResult.failure("SNAPSHOT_CHANGED", "/snapshot", "execution-failure").projection(),
                store.publish(ticket, good).projection());
        store.publish(store.beginGeneration(), bad); assertTrue(store.current().isEmpty());
    }

    @Test void intakeBudgetBeforeSyntax()
    {
        var outcome = resolver.parse(new Source("fixture", "s", Source.Encoding.JSON, new byte[1048577]));
        assertEquals(CdsReadResult.failure("LIMIT", "/sources/s", "definition-invalid").projection(), outcome);
    }
    @Test void schemasAndBudgets() throws Exception
    {
        for (String name : List.of("physical", "logical", "projection")) {
            try (var stream = getClass().getResourceAsStream("/cds/" + name + ".schema.json")) {
                assertNotNull(stream);
                assertArrayEquals(Files.readAllBytes(ConformanceTest.ROOT.resolve(
                        "schemas/cds/v1/" + name + ".schema.json")), stream.readAllBytes());
            }
        }
        var dict = new CatalogBuilder().buildCatalog(List.of()).catalog().orElseThrow();
        for (int size : List.of(256, 257)) {
            var physical = new LinkedHashMap<String, Object>();
            for (int i = 0; i < size; i++) {
                physical.put("s" + i, Map.of(
                    "apiVersion", "arp.physical/v1", "id", "storage.t" + i, "kind", "table",
                    "source", Map.of("provider", "host", "dialect", "test", "name", "t"),
                    "columns", List.of(Map.of("name", "id", "nativeType", "opaque"))));
            }
            assertEquals(size == 256, resolver.resolve(Map.of("physical", physical,
                    "logical", Map.of(), "projections", Map.of()), dict).valid());
        }
        var batch = Map.of("physical", Map.of("first", Source.utf8("host", "ignored", Source.Encoding.JSON, "{}")),
                "logical", Map.of("later", Source.utf8("host", "ignored", Source.Encoding.JSON, "{")));
        assertEquals(CdsReadResult.failure("SOURCE_INVALID", "/sources/later", "definition-invalid").projection(),
                resolver.resolveSources(batch, dict).projection());
    }

    @Test void invalidationWhileComputing() throws Exception
    {
        var store = new CdsReadPublication();
        long ticket = store.beginGeneration();
        var started = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var task = executor.submit(() -> {
                started.countDown();
                if (!finish.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new AssertionError("Timeout");
                }
                return store.publish(ticket, new CdsReadResult(Map.of("valid", true, "issues", List.of(),
                        "models", List.of(), "projections", List.of())));
            });
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            store.invalidate();
            finish.countDown();
            assertEquals(CdsReadResult.failure("SNAPSHOT_CHANGED", "/snapshot", "execution-failure").projection(),
                    task.get(5, java.util.concurrent.TimeUnit.SECONDS).projection());
            assertTrue(store.current().isEmpty());
        } finally {
            finish.countDown();
        }
    }
    @Test void structuralBoundsAndDeterministicLimit()
    {
        var dict = new CatalogBuilder().buildCatalog(List.of()).catalog().orElseThrow();
        for (int size : List.of(2048, 2049)) {
            var columns = new ArrayList<Object>();
            for (int i = 0; i < size; i++) {
                columns.add(Map.of("name", "c" + i, "nativeType", "opaque"));
            }
            assertEquals(size == 2048, resolver.resolve(minimal(columns, Map.of()), dict).valid());
        }
        for (int size : List.of(64, 65)) {
            var projections = new LinkedHashMap<String, Object>();
            for (int i = 0; i < size; i++) {
                projections.put("s" + i, Map.of(
                    "apiVersion", "arp.projection/v1", "id", "projection.p" + i,
                    "source", i == 0 ? Map.of("entity", "logical.table")
                            : Map.of("projection", "projection.p" + (i - 1)),
                    "fields", List.of("id"), "operations", List.of("GET")));
            }
            assertEquals(size == 64, resolver.resolve(minimal(
                    List.of(Map.of("name", "id", "nativeType", "opaque")), projections), dict).valid());
        }
        String longBound = "1".repeat(4097);
        for (boolean reverse : List.of(false, true)) {
            var extent = new LinkedHashMap<String, Object>();
            extent.put(reverse ? "maximum" : "minimum", longBound);
            extent.put(reverse ? "minimum" : "maximum", longBound);
            var bundle = minimal(List.of(Map.of("name", "id", "nativeType", "opaque", "integerRange", extent)),
                    Map.of());
            assertEquals(CdsReadResult.failure("LIMIT", "/physical/s/columns/0/integerRange/minimum",
                    "definition-invalid").projection(), resolver.resolve(bundle, dict).projection());
        }
    }

    private static Map<String, Object> minimal(List<Object> columns, Map<String, Object> projections)
    {
        return Map.of("physical", Map.of("s", Map.of("apiVersion", "arp.physical/v1", "id", "storage.table",
                "kind", "table", "source", Map.of("provider", "host", "dialect", "test", "name", "t"),
                "columns", columns)), "logical", Map.of("s", Map.of("apiVersion", "arp.logical/v1",
                "id", "logical.table", "physical", "storage.table", "fields", Map.of())), "projections", projections);
    }
}