package org.alxarafe.arp.dictionary;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.alxarafe.arp.cds.physical.PhysicalCatalog;
import org.alxarafe.arp.cds.physical.PostgreSqlObservationProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.*;

/** Shared contract products are loaded directly; no local product fixtures or fake row executor. */
class ListGetIntegrationTest
{
    private static JsonNode products;
    private static Path root;
    private static final List<String> SQL = new ArrayList<>();
    private static final ListGetHost.Context CONTEXT = new ListGetHost.Context("reader/company-7", "policy-1");
    private static Map<String, ListGetHost.Target> ACTIVE;
    private static ListGetHost.Target target(String projection)
    {
        var fields = new HashSet<String>();
        products.at("/layouts/composite/definitions/projections/" + projection + "/fields")
                .forEach(f -> fields.add(f.asText()));
        return new ListGetHost.Target("products." + projection, 2, 3, fields);
    }

    @BeforeAll
    static void contract() throws Exception
    {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("ARP_LISTGET_CONTRACT") != null,
                "Use integration-docker.py --listget-contract for native LISTGET tests");
        root = Path.of(System.getenv("ARP_LISTGET_CONTRACT"));
        String pin = Files.readString(Path.of("listget-contract.ref")).trim();
        var process = new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "HEAD").start();
        assertEquals(pin, new String(process.getInputStream().readAllBytes()).trim());
        assertEquals(0, process.waitFor());
        products = ConformanceTest.read(root.resolve("fixtures/cds/listget/v1/products.json"));
        ACTIVE = Map.of("catalog", target("catalog"), "reports", target("summary"),
                "identified", target("identified"));
        try (var c = connect(); var s = c.createStatement()) {
            try (var r = s.executeQuery("SHOW server_version")) {
                assertTrue(r.next()); assertTrue(r.getString(1).startsWith("18.6"), r.getString(1));
            }
            try (var r = s.executeQuery("SELECT token FROM public.__arp_cds_test_owner")) {
                assertTrue(r.next()); assertEquals(System.getenv("ARP_CDS_RUN_TOKEN"), r.getString(1));
            }
        }
    }
    private static Connection connect() throws java.sql.SQLException
    {
        return DriverManager.getConnection("jdbc:postgresql://" + System.getenv("ARP_DB_HOST")
                + ":5432/arpcds?connectTimeout=3&socketTimeout=10", "arpcds", System.getenv("ARP_DB_PASSWORD"));
    }
    private static void exec(String sql) throws Exception
    {
        try (var c = connect(); var s = c.createStatement()) { s.execute(sql); }
    }
    private static Object reflect(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable
    {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
    private static Connection monitored(Runnable afterQuery, AtomicBoolean offline) throws java.sql.SQLException
    {
        if (offline.get()) { throw new java.sql.SQLException("private connection details"); }
        var c = connect();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object value = reflect(c, method, args);
                    if (method.getName().equals("prepareStatement") && ((String) args[0]).contains(" FROM ONLY ")) {
                        SQL.add((String) args[0]);
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                                    Object result = reflect(value, m, a);
                                    if (m.getName().equals("executeQuery")) { afterQuery.run(); }
                                    return result;
                                });
                    }
                    return value;
                });
    }
    @SuppressWarnings("unchecked")
    private static final class Harness
    {
        final JsonNode layout;
        final PhysicalCatalog physical;
        final ListGetHost host;
        final AtomicBoolean offline = new AtomicBoolean();
        final AtomicBoolean allowGet = new AtomicBoolean(true);
        final AtomicBoolean allowList = new AtomicBoolean(true);
        final AtomicBoolean allowFields = new AtomicBoolean(true);
        final AtomicBoolean capability = new AtomicBoolean(true);
        Runnable afterQuery = () -> {};
        Runnable onAuthorize = () -> {};
        final java.util.concurrent.atomic.AtomicReference<String> currentPolicy =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<String> tenant =
                new java.util.concurrent.atomic.AtomicReference<>("7");
        final java.util.concurrent.atomic.AtomicReference<String> product =
                new java.util.concurrent.atomic.AtomicReference<>();
        Map<String, Object> restriction()
        {
            var rows = new LinkedHashMap<String, Object>();
            rows.put("tenant_id", tenant.get());
            if (product.get() != null) { rows.put("product_id", product.get()); }
            return rows;
        }
        final Map<String, Object> definitions;
        Harness(String name) throws Exception
        {
            this(name, false);
        }
        Harness(String name, boolean narrowRating) throws Exception
        {
            this(name, narrowRating, null);
        }
        Harness(String name, boolean narrowRating, JsonNode definitionOverride) throws Exception
        {
            layout = products.at("/layouts/" + name);
            String pk = name.equals("composite") ? "tenant_id,product_id" : "product_id";
            exec("CREATE SCHEMA IF NOT EXISTS commerce; DROP TABLE IF EXISTS commerce.products; "
                    + "CREATE TABLE commerce.products(tenant_id integer NOT NULL,product_id bigint NOT NULL,"
                    + "sku varchar(40) NOT NULL,name varchar(200) NOT NULL,price numeric NOT NULL,"
                    + "rating smallint NOT NULL,description text,available boolean NOT NULL,released date NOT NULL,"
                    + "cost numeric NOT NULL,internal_note text, PRIMARY KEY(" + pk + "))");
            try (var c = connect(); var insert = c.prepareStatement("INSERT INTO commerce.products VALUES "
                    + "(?,?,?, ?,?,?, ?,?,?, ?,?)")) {
                for (var row : layout.get("rows")) {
                    var v = row.get("values");
                    int i = 1;
                    for (String col : List.of("tenant_id", "product_id", "sku", "name", "price", "rating",
                            "description", "available", "released", "cost", "internal_note")) {
                        var n = v.get(col);
                        Object value = n.isNull() ? null : switch (col) {
                            case "tenant_id", "product_id", "price", "rating", "cost" ->
                                new java.math.BigDecimal(n.asText());
                            case "available" -> n.asBoolean();
                            case "released" -> java.time.LocalDate.parse(n.asText());
                            default -> n.asText();
                        };
                        insert.setObject(i++, value);
                    }
                    assertEquals(1, insert.executeUpdate());
                }
            }
            definitions = (Map<String, Object>) ConformanceTest.JSON.convertValue(
                    definitionOverride == null ? layout.get("definitions") : definitionOverride, Map.class);
            var dictionaryDocument = products.get("dictionary").deepCopy();
            if (narrowRating) {
                for (var e : dictionaryDocument.get("elements")) {
                    if (e.path("id").asText().equals("products.rating")) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) e).put("type", "INT8");
                    }
                }
            }
            Catalog dictionary = new CatalogBuilder().buildCatalog(List.of(ConformanceTest.source(
                    dictionaryDocument))).catalog().orElseThrow();
            physical = new PhysicalCatalog(dictionary, definitions, Map.of("observed",
                    new PostgreSqlObservationProvider(ListGetIntegrationTest::connect, "commercial-db",
                            "storage.products", "commerce", "products", 5)));
            var executor = new PostgreSqlReadExecutor(() -> monitored(() -> afterQuery.run(), offline));
            host = new ListGetHost(physical, executor,
                    ACTIVE, (target, operation, context) -> {
                        onAuthorize.run();
                        String revision = currentPolicy.get();
                        return new ListGetHost.Access(
                            (operation.equals("GET") ? allowGet.get() : allowList.get())
                                    && (revision == null || revision.equals(context.policyRevision())),
                            allowFields.get() ? Set.of("tenant_id", "product_id", "sku", "name", "price", "rating",
                                    "description", "available", "released", "cost", "internal_note") : Set.of("sku"),
                            capability.get() ? restriction() : null);
                    });
            assertTrue(physical.resolve().valid(), physical.resolve().toJson());
            SQL.clear();
        }
        JsonNode call(String target, String op, Map<String, Object> inputs,
                      ListGetHost.Context context) throws Exception
        {
            var response = host.invoke(ConformanceTest.JSON.writeValueAsBytes(
                    Map.of("target", target, "operation", op, "inputs", inputs)), context);
            System.out.println("LISTGET_EVIDENCE:" + JsonSupport.write(Map.of(
                    "target", target, "operation", op, "response", response)));
            return ConformanceTest.JSON.valueToTree(response);
        }
        JsonNode call(String target, String op, Map<String, Object> inputs) throws Exception
        {
            return call(target, op, inputs, CONTEXT);
        }
        Map<String, Object> publicKey(JsonNode row)
        {
            var out = new ArrayList<Object>();
            for (var k : layout.at("/definitions/physical/observed/primaryKey")) {
                String key = k.asText();
                out.add(Map.of("field", key, "type", key.equals("tenant_id") ? "INT32" : "INT64",
                        "value", row.get("values").get(key).asText()));
            }
            return Map.of("components", out);
        }
    }
    private static JsonNode ok(JsonNode response)
    {
        assertEquals("success", response.path("status").asText(), response.toPrettyString());
        assertTrue(response.path("diagnostics").isEmpty()); return response.get("output");
    }
    private static void error(JsonNode response, String code)
    {
        assertFalse(response.has("output"), response.toPrettyString());
        assertEquals("CDS_LISTGET_" + code, response.at("/diagnostics/0/code").asText(), response.toPrettyString());
        assertEquals(1, response.path("diagnostics").size());
    }
    @TestFactory
    List<DynamicTest> databaseReads() throws Exception
    {
        var tests = new ArrayList<DynamicTest>();
        for (String layout : List.of("composite", "simple")) {
            tests.add(DynamicTest.dynamicTest(layout + " GET reference length and invalid evidence", () -> {
                var h = new Harness(layout);
                var page = ok(h.call("catalog", "LIST", Map.of()));
                String reference = page.at("/records/0/key/reference").asText();
                for (String invalid : List.of("", "x".repeat(129))) {
                    SQL.clear();
                    var response = h.call("catalog", "GET", Map.of("key", Map.of("reference", invalid)));
                    error(response, "INVALID_KEY");
                    assertEquals("rejected", response.path("status").asText());
                    assertEquals("invalid-data", response.at("/diagnostics/0/family").asText());
                    assertTrue(SQL.isEmpty());
                }
                String tampered = (reference.startsWith("A") ? "B" : "A") + reference.substring(1);
                for (String unknown : List.of("x", "x".repeat(128), "rec-unknown-unissued", tampered)) {
                    SQL.clear();
                    var output = ok(h.call("catalog", "GET", Map.of("key", Map.of("reference", unknown))));
                    assertEquals(ConformanceTest.JSON.readTree("{\"found\":false}"), output);
                    assertTrue(SQL.isEmpty());
                }
                SQL.clear();
                var output = ok(h.call("catalog", "GET", Map.of("key", Map.of("reference", reference))));
                assertTrue(output.path("found").asBoolean());
                assertEquals(page.at("/records/0/values"), output.at("/record/values"));
                assertEquals(1, SQL.size());
            }));
            tests.add(DynamicTest.dynamicTest(layout + " products LIST/GET exact shared values and paging", () -> {
                var h = new Harness(layout);
                var seen = new ArrayList<JsonNode>();
                Map<String, Object> inputs = Map.of();
                do {
                    var output = ok(h.call("catalog", "LIST", inputs));
                    assertEquals(2, output.at("/page/limit").asInt());
                    for (var record : output.get("records")) {
                        seen.add(record.get("values"));
                        Map<String, Object> key = ConformanceTest.JSON.convertValue(record.get("key"), Map.class);
                        var found = ok(h.call("catalog", "GET", Map.of("key", key)));
                        assertTrue(found.get("found").asBoolean());
                        assertEquals(record.get("values"), found.at("/record/values"));
                        assertTrue(key.containsKey("reference"));
                        assertFalse(record.get("values").has("tenant_id"));
                        assertFalse(record.get("values").has("cost"));
                    }
                    if (!output.at("/page/hasMore").asBoolean()) {
                        assertTrue(output.at("/page/next").isNull()); break;
                    }
                    inputs = Map.of("continuation", output.at("/page/next").asText());
                } while (true);
                assertEquals(5, seen.size()); assertEquals(5, new HashSet<>(seen).size());
                for (int i = 0; i < 5; i++) {
                    var row = h.layout.get("rows").get(i);
                    var found = ok(h.call("identified", "GET", Map.of("key", h.publicKey(row))));
                    assertEquals(row.at("/values/product_id"), found.at("/record/values/product_id"));
                }
                assertTrue(ok(h.call("identified", "GET", Map.of("key", h.publicKey(h.layout.get("rows").get(0))),
                        new ListGetHost.Context("public", "public-1"))).path("found").asBoolean());
                for (int i = 0; i < seen.size(); i++) {
                    var expected = ConformanceTest.JSON.createObjectNode();
                    var row = h.layout.get("rows").get(i).get("values");
                    for (var name : h.layout.at("/definitions/projections/catalog/fields")) {
                        expected.set(name.asText(), row.get(name.asText()));
                    }
                    assertEquals(expected, seen.get(i));
                }
                // Shared oracle expectation, replacing only the application-issued protected reference.
                var cases = ConformanceTest.read(root.resolve("fixtures/cds/listget/v1/cases.json"));
                for (var vector : cases.get("cases")) {
                    if (vector.path("id").asText().equals("get-authorized-hidden-key") && layout.equals("composite")) {
                        assertEquals(vector.at("/expected/response/output/record/values"), seen.getFirst());
                    }
                }
                var identified = ok(h.call("identified", "LIST", Map.of("limit", 100)));
                assertEquals(3, identified.at("/page/limit").asInt());
                assertEquals(h.layout.at("/rows/0/values/product_id").asText(),
                        identified.at("/records/0/values/product_id").asText());
            }));
            tests.add(DynamicTest.dynamicTest(layout
                    + " absent and inaccessible indistinguishable; reports derived", () -> {
                var h = new Harness(layout);
                var row = h.layout.get("rows").get(0).deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) row.get("values")).put("product_id", "999");
                var missing = ok(h.call("identified", "GET", Map.of("key", h.publicKey(row))));
                var denied = ok(h.call("identified", "GET", Map.of("key", h.publicKey(h.layout.get("rows").get(5)))));
                assertEquals(missing, denied); assertFalse(missing.get("found").asBoolean());
                var summary = ok(h.call("reports", "LIST", Map.of()));
                assertEquals(Set.of("sku", "name"), ConformanceTest.JSON.convertValue(
                        summary.at("/records/0/values"), Map.class).keySet());
                int queries = SQL.size(); error(h.call("reports", "GET", Map.of()), "UNAVAILABLE");
                assertEquals(queries, SQL.size());
            }));
            tests.add(DynamicTest.dynamicTest(layout
                    + " authorization, inactive modules and unsupported policy fail closed", () -> {
                var h = new Harness(layout);
                h.allowGet.set(false);
                error(h.call("catalog", "GET", Map.of()), "UNAVAILABLE"); assertTrue(SQL.isEmpty());
                ok(h.call("catalog", "LIST", Map.of()));
                h.allowGet.set(true); h.allowList.set(false);
                error(h.call("catalog", "LIST", Map.of()), "UNAVAILABLE");
                h.allowList.set(true); h.allowFields.set(false);
                SQL.clear(); error(h.call("catalog", "LIST", Map.of()), "UNAVAILABLE"); assertTrue(SQL.isEmpty());
                h.allowFields.set(true); h.capability.set(false);
                error(h.call("catalog", "LIST", Map.of()), "CAPABILITY_UNAVAILABLE"); assertTrue(SQL.isEmpty());
                h.capability.set(true);
                h.host.changeAccess(Map.of("catalog", ACTIVE.get("catalog")), () -> {});
                error(h.call("reports", "LIST", Map.of()), "UNAVAILABLE"); assertTrue(SQL.isEmpty());
                ok(h.call("catalog", "LIST", Map.of()));
            }));
            tests.add(DynamicTest.dynamicTest(layout + " opaque bindings, tampering, limit and generation", () -> {
                var h = new Harness(layout);
                var page = ok(h.call("catalog", "LIST", Map.of()));
                String ref = page.at("/records/0/key/reference").asText();
                String cursor = page.at("/page/next").asText();
                var other = new ListGetHost.Context("other/company-8", "policy-1");
                assertFalse(ok(h.call("catalog", "GET", Map.of("key", Map.of("reference", ref)), other))
                        .get("found").asBoolean());
                error(h.call("catalog", "LIST", Map.of("continuation", cursor), other), "INVALID_CURSOR");
                error(h.call("reports", "LIST", Map.of("continuation", cursor)), "INVALID_CURSOR");
                error(h.call("catalog", "LIST", Map.of("continuation", ref)), "INVALID_CURSOR");
                assertFalse(ok(h.call("catalog", "GET", Map.of("key", Map.of("reference", cursor))))
                        .get("found").asBoolean());
                error(h.call("catalog", "LIST", Map.of("continuation", cursor, "limit", 3)), "INVALID_CURSOR");
                assertFalse(ok(h.call("catalog", "GET", Map.of("key", Map.of("reference", ref + "x"))))
                        .get("found").asBoolean());
                error(h.call("catalog", "GET", Map.of("key", h.publicKey(h.layout.get("rows").get(0)))), "INVALID_KEY");
                h.physical.invalidate();
                error(h.call("catalog", "GET", Map.of("key", Map.of("reference", ref))), "GENERATION_EXPIRED");
                error(h.call("catalog", "LIST", Map.of("continuation", cursor)), "GENERATION_EXPIRED");
                ok(h.call("catalog", "LIST", Map.of()));
            }));
            tests.add(DynamicTest.dynamicTest(layout
                    + " no output on inflight invalidation, module or policy change", () -> {
                var h = new Harness(layout);
                h.afterQuery = h.physical::invalidate;
                error(h.call("catalog", "LIST", Map.of()), "GENERATION_CHANGED"); assertFalse(SQL.isEmpty());
                var missing = h.layout.get("rows").get(0).deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) missing.get("values")).put("product_id", "999");
                error(h.call("identified", "GET", Map.of("key", h.publicKey(missing))), "GENERATION_CHANGED");
                h.afterQuery = () -> {};
                ok(h.call("catalog", "LIST", Map.of()));
                h.afterQuery = () -> h.host.changeAccess(Map.of("catalog", ACTIVE.get("catalog")), () -> {});
                error(h.call("reports", "LIST", Map.of()), "GENERATION_CHANGED");
                h.host.changeAccess(ACTIVE, () -> {});
                h.afterQuery = () -> h.host.changeAccess(ACTIVE, () -> h.allowList.set(false));
                error(h.call("catalog", "LIST", Map.of()), "UNAVAILABLE");
            }));
            tests.add(DynamicTest.dynamicTest(layout + " metadata rebuild, external DDL and provider failure", () -> {
                var h = new Harness(layout);
                String before = JsonSupport.write(h.definitions);
                exec("ALTER TABLE commerce.products ADD COLUMN future_private text");
                error(h.call("catalog", "LIST", Map.of()), "GENERATION_CHANGED");
                h.physical.invalidate();
                assertFalse(ok(h.call("catalog", "LIST", Map.of())).at("/records/0/values").has("future_private"));
                assertEquals(before, JsonSupport.write(h.definitions));
                h.offline.set(true);
                error(h.call("catalog", "LIST", Map.of()), "READ_FAILED");
                h.offline.set(false);
                exec("ALTER TABLE commerce.products DROP COLUMN rating"); h.physical.invalidate();
                error(h.call("catalog", "LIST", Map.of()), "INVALID_DEFINITION");
            }));
            tests.add(DynamicTest.dynamicTest(layout + " invalid stored scalar discards complete page", () -> {
                var h = new Harness(layout, true);
                exec("UPDATE commerce.products SET rating = 128 WHERE tenant_id = 7 AND product_id = 10");
                error(h.call("catalog", "LIST", Map.of()), "ROW_DECODE_FAILED");
                // Hidden rating is not decoded or exposed by the reports projection.
                ok(h.call("reports", "LIST", Map.of()));
                exec("UPDATE commerce.products SET rating = 8,price = 'NaN' WHERE product_id = 10");
                error(h.call("catalog", "LIST", Map.of()), "ROW_DECODE_FAILED");
                exec("UPDATE commerce.products SET price = 1,released = 'infinity' WHERE product_id = 10");
                error(h.call("catalog", "LIST", Map.of()), "ROW_DECODE_FAILED");
                ok(h.call("reports", "LIST", Map.of()));
            }));
            tests.add(DynamicTest.dynamicTest(layout + " strict input and SQL bounds", () -> {
                var h = new Harness(layout);
                for (var input : List.of(Map.of("offset", 1), Map.of("limit", false), Map.of("limit", 0),
                        Map.of("limit", 101), Map.of("limit", 1.5), Map.of("sql", "DROP TABLE commerce.products"))) {
                    error(h.call("catalog", "LIST", (Map) input), "INPUT_SHAPE");
                }
                assertTrue(SQL.isEmpty());
                var malformed = h.host.invoke(("{\"target\":\"catalog\",\"operation\":\"LIST\","
                        + "\"inputs\":{\"limit\":1,\"limit\":2}}").getBytes(), CONTEXT);
                error(ConformanceTest.JSON.valueToTree(malformed), "INPUT_SHAPE");
                ok(h.call("catalog", "LIST", Map.of()));
                assertTrue(SQL.getLast().contains("WHERE \"tenant_id\" = ? ORDER BY"));
                assertTrue(SQL.getLast().endsWith("LIMIT ?")); assertFalse(SQL.getLast().contains("OFFSET"));
                assertFalse(SQL.getLast().contains("cost")); assertFalse(SQL.getLast().contains("internal_note"));
            }));
        }
        return tests;
    }
    @TestFactory
    List<DynamicTest> finalReview() throws Exception
    {
        var tests = new ArrayList<DynamicTest>();
        tests.add(DynamicTest.dynamicTest("review shared input vectors", () -> {
            var h = new Harness("composite");
            var vectors = ConformanceTest.read(root.resolve("fixtures/cds/listget/v1/cases.json")).get("cases");
            var selected = new HashSet<Integer>();
            for (int i = 14; i <= 21; i++) { selected.add(i); }
            for (int i = 29; i <= 40; i++) { selected.add(i); }
            selected.addAll(Set.of(49, 50, 51, 85));
            for (int i = 0; i < vectors.size(); i++) {
                if (!selected.contains(i)) { continue; }
                var vector = vectors.get(i);
                var request = vector.get("request").deepCopy();
                String target = vector.at("/host/projection").asText().equals("products.identified")
                        ? "identified" : "catalog";
                ((com.fasterxml.jackson.databind.node.ObjectNode) request).put("target", target);
                h.allowList.set(i != 50);
                SQL.clear();
                var actual = h.host.invoke(ConformanceTest.JSON.writeValueAsBytes(request), CONTEXT);
                assertEquals(vector.at("/expected/response"), ConformanceTest.JSON.valueToTree(actual),
                        vector.path("id").asText());
                assertTrue(SQL.isEmpty(), vector.path("id").asText());
                System.out.println("LISTGET_NATIVE_VECTOR:" + vector.path("id").asText());
            }
            // Malformed component objects must also be client errors, never provider failures.
            for (String key : List.of("{\"components\":[null,null]}",
                    "{\"components\":[1,2]}", "{\"components\":[[],{}]}")) {
                var request = "{\"target\":\"identified\",\"operation\":\"GET\",\"inputs\":{\"key\":" + key + "}}";
                error(ConformanceTest.JSON.valueToTree(h.host.invoke(request.getBytes(), CONTEXT)), "INVALID_KEY");
            }
        }));
        tests.add(DynamicTest.dynamicTest("review full authorization precedes resolution errors", () -> {
            var h = new Harness("composite");
            exec("ALTER TABLE commerce.products DROP COLUMN rating"); h.physical.invalidate();
            h.allowFields.set(false);
            error(h.call("catalog", "LIST", Map.of()), "UNAVAILABLE");
            assertTrue(SQL.isEmpty());
            h.allowFields.set(true);
            error(h.call("catalog", "LIST", Map.of()), "INVALID_DEFINITION");
        }));
        tests.add(DynamicTest.dynamicTest("review empty SQL page and empty GET publication fence", () -> {
            var h = new Harness("composite");
            h.host.changeAccess(ACTIVE, () -> h.tenant.set("99"));
            var empty = ok(h.call("catalog", "LIST", Map.of()));
            assertTrue(empty.path("records").isEmpty()); assertFalse(empty.at("/page/hasMore").asBoolean());
            assertTrue(empty.at("/page/next").isNull()); assertFalse(SQL.isEmpty());
            h.host.changeAccess(ACTIVE, () -> { h.tenant.set("7"); h.product.set("2"); });
            var summary = ok(h.call("reports", "LIST", Map.of()));
            assertEquals(1, summary.path("records").size());
            assertFalse(summary.at("/page/hasMore").asBoolean());
            assertTrue(SQL.getLast().contains(" AND "));
            h.host.changeAccess(ACTIVE, () -> h.product.set(null));
            var page = ok(h.call("catalog", "LIST", Map.of()));
            exec("DELETE FROM commerce.products WHERE tenant_id = 7 AND product_id > 10");
            var tail = ok(h.call("catalog", "LIST", Map.of("continuation", page.at("/page/next").asText())));
            assertTrue(tail.path("records").isEmpty()); assertTrue(tail.at("/page/next").isNull());
            assertFalse(tail.at("/page/hasMore").asBoolean());
            var authorizations = new java.util.concurrent.atomic.AtomicInteger();
            h.onAuthorize = () -> {
                if (authorizations.incrementAndGet() == 2) { h.physical.invalidate(); }
            };
            SQL.clear();
            error(h.call("catalog", "GET", Map.of("key", Map.of("reference", "rec-unknown-unissued"))),
                    "GENERATION_CHANGED");
            assertTrue(SQL.isEmpty());
        }));
        tests.add(DynamicTest.dynamicTest("review independent token bindings and access revocation", () -> {
            var h = new Harness("composite");
            var page = ok(h.call("catalog", "LIST", Map.of("limit", 1)));
            assertEquals(1, page.path("records").size());
            String reference = page.at("/records/0/key/reference").asText();
            String cursor = page.at("/page/next").asText();
            assertTrue(reference.length() <= 128 && cursor.length() <= 128);
            for (var context : List.of(new ListGetHost.Context("different-reader/company-7", "policy-1"),
                    new ListGetHost.Context("reader/company-8", "policy-1"),
                    new ListGetHost.Context("reader/company-7", "policy-2"))) {
                assertFalse(ok(h.call("catalog", "GET", Map.of("key", Map.of("reference", reference)), context))
                        .path("found").asBoolean());
                error(h.call("catalog", "LIST", Map.of("limit", 1, "continuation", cursor), context),
                        "INVALID_CURSOR");
            }
            h.afterQuery = () -> h.host.changeAccess(ACTIVE, () -> h.allowGet.set(false));
            error(h.call("catalog", "GET", Map.of("key", Map.of("reference", reference))), "UNAVAILABLE");
            h.afterQuery = () -> {};
            h.host.changeAccess(ACTIVE, () -> h.allowGet.set(true));
            var fresh = ok(h.call("catalog", "LIST", Map.of()));
            String bound = fresh.at("/records/0/key/reference").asText();
            h.afterQuery = () -> h.host.changeAccess(ACTIVE, () -> h.currentPolicy.set("policy-2"));
            error(h.call("catalog", "GET", Map.of("key", Map.of("reference", bound))), "UNAVAILABLE");
            // The contract's alternate derived projection exposes GET, so this exercises binding, not exposure denial.
            var cases = ConformanceTest.read(root.resolve("fixtures/cds/listget/v1/cases.json")).get("cases");
            JsonNode alternate = null;
            for (var vector : cases) {
                if (vector.path("id").asText().equals("get-projection-binding-denied")) {
                    alternate = vector.at("/host/definitions");
                }
            }
            assertNotNull(alternate);
            var projections = new Harness("composite", false, alternate);
            var catalogPage = ok(projections.call("catalog", "LIST", Map.of()));
            String catalogRef = catalogPage.at("/records/0/key/reference").asText();
            assertFalse(ok(projections.call("reports", "GET", Map.of("key", Map.of("reference", catalogRef))))
                    .path("found").asBoolean());
            var reportsPage = ok(projections.call("reports", "LIST", Map.of()));
            String reportsRef = reportsPage.at("/records/0/key/reference").asText();
            assertTrue(ok(projections.call("reports", "GET", Map.of("key", Map.of("reference", reportsRef))))
                    .path("found").asBoolean());
        }));
        tests.add(DynamicTest.dynamicTest("review concurrent invalidation and real DDL exclusion", () -> {
            var h = new Harness("composite");
            var snapshot = h.physical.current().orElseThrow();
            long generation = h.physical.generation();
            assertThrows(IllegalStateException.class, () -> h.physical.publishRead(null, () -> "invalid"));
            var selected = new java.util.concurrent.CountDownLatch(1);
            var resume = new java.util.concurrent.CountDownLatch(1);
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            h.afterQuery = () -> {
                selected.countDown();
                try {
                    assertTrue(resume.await(5, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException e) { throw new AssertionError(e); }
            };
            try {
                var pending = pool.submit(() -> h.call("catalog", "LIST", Map.of()));
                assertTrue(selected.await(5, java.util.concurrent.TimeUnit.SECONDS));
                try (var c = connect(); var statement = c.createStatement()) {
                    statement.execute("SET lock_timeout = '150ms'");
                    var blocked = assertThrows(java.sql.SQLException.class, () -> statement.execute(
                            "ALTER TABLE commerce.products ADD COLUMN blocked_during_read text"));
                    assertEquals("55P03", blocked.getSQLState());
                }
                h.physical.invalidate();
                assertTrue(h.physical.generation() > generation);
                var published = new AtomicBoolean();
                assertThrows(IllegalStateException.class, () -> h.physical.publishRead(snapshot, () -> {
                    published.set(true); return "stale";
                }));
                assertFalse(published.get());
                resume.countDown();
                error(pending.get(5, java.util.concurrent.TimeUnit.SECONDS), "GENERATION_CHANGED");
                h.afterQuery = () -> {};
                exec("ALTER TABLE commerce.products ADD COLUMN blocked_during_read text");
                h.physical.invalidate();
                ok(h.call("catalog", "LIST", Map.of()));
            } finally {
                resume.countDown(); pool.shutdownNow();
                assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            }
        }));
        tests.add(DynamicTest.dynamicTest("review PK capacity and incompatible storage boundaries", () -> {
            var h = new Harness("composite");
            assertThrows(IllegalArgumentException.class, () ->
                    new ListGetHost.Target("products.catalog", 2, 0, ACTIVE.get("catalog").fields()));
            var keys = new ArrayList<>(List.of("tenant_id", "product_id"));
            for (int i = 3; i <= 7; i++) {
                exec("ALTER TABLE commerce.products ADD COLUMN key_" + i + " integer NOT NULL DEFAULT 0");
                keys.add("key_" + i);
            }
            exec("ALTER TABLE commerce.products DROP CONSTRAINT products_pkey, ADD PRIMARY KEY("
                    + String.join(",", keys) + ")");
            h.physical.invalidate();
            var page = ok(h.call("catalog", "LIST", Map.of()));
            String reference = page.at("/records/0/key/reference").asText();
            String cursor = page.at("/page/next").asText();
            assertTrue(reference.length() <= 128 && cursor.length() <= 128);
            assertTrue(ok(h.call("catalog", "GET", Map.of("key", Map.of("reference", reference))))
                    .path("found").asBoolean());
            ok(h.call("catalog", "LIST", Map.of("continuation", cursor)));
            exec("ALTER TABLE commerce.products ADD COLUMN key_8 integer NOT NULL DEFAULT 0");
            keys.add("key_8");
            exec("ALTER TABLE commerce.products DROP CONSTRAINT products_pkey, ADD PRIMARY KEY("
                    + String.join(",", keys) + ")");
            h.physical.invalidate(); SQL.clear();
            error(h.call("catalog", "LIST", Map.of()), "CAPABILITY_UNAVAILABLE"); assertTrue(SQL.isEmpty());
            exec("ALTER TABLE commerce.products DROP CONSTRAINT products_pkey"); h.physical.invalidate();
            error(h.call("catalog", "LIST", Map.of()), "CAPABILITY_UNAVAILABLE"); assertTrue(SQL.isEmpty());
            exec("DELETE FROM commerce.products WHERE product_id > 32767; "
                    + "ALTER TABLE commerce.products ALTER COLUMN product_id TYPE smallint");
            h.physical.invalidate();
            error(h.call("catalog", "LIST", Map.of()), "INCOMPATIBLE_STORAGE"); assertTrue(SQL.isEmpty());
        }));
        return tests;
    }

}