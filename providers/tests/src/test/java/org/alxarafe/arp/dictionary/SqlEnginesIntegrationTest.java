package org.alxarafe.arp.dictionary;

import com.fasterxml.jackson.databind.JsonNode;
import org.alxarafe.arp.cds.physical.PhysicalCatalog;
import org.alxarafe.arp.cds.physical.PhysicalObservationProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared contract products are loaded directly; no local product fixtures or fake row executor.
 */
class SqlEnginesIntegrationTest
{
    private static final String ENGINE = System.getenv("ARP_SQL_ENGINE");

    private static boolean server()
    {
        return "sqlserver".equals(ENGINE);
    }

    private static String readerName()
    {
        return switch (ENGINE) {
            case "mysql" -> "MySql";
            case "mariadb" -> "MariaDb";
            case "sqlserver" -> "SqlServer";
            default -> throw new IllegalStateException("Explicit engine required");
        };
    }

    private static SqlRecordReader reader(SqlRecordReader.Connections connections, int timeout) throws Exception
    {
        return (SqlRecordReader) Class.forName("org.alxarafe.arp.dictionary." + readerName() + "RecordReader")
                .getConstructor(SqlRecordReader.Connections.class, int.class).newInstance(connections, timeout);
    }

    private static PhysicalObservationProvider observer(SqlRecordReader.Connections connections) throws Exception
    {
        return (PhysicalObservationProvider) Class.forName("org.alxarafe.arp.cds.physical."
                + readerName() + "ObservationProvider").getConstructor(
                        SqlRecordReader.Connections.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        int.class)
                .newInstance(connections, "commercial-db", "storage.products", "commerce", "products", 3);
    }

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
        org.junit.jupiter.api.Assumptions.assumeTrue(ENGINE != null && System.getenv("ARP_LISTGET_CONTRACT") != null,
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
            try (var r = s.executeQuery("SELECT token FROM __arp_cds_test_owner")) {
                assertTrue(r.next());
                assertEquals(System.getenv("ARP_CDS_RUN_TOKEN"), r.getString(1));
            }
        }
    }

    private static Connection connect() throws java.sql.SQLException
    {
        String host = System.getenv("ARP_DB_HOST"), password = System.getenv("ARP_DB_PASSWORD");
        String url = switch (ENGINE) {
            case "mysql" -> "jdbc:mysql://" + host + ":3306/arpcds?connectTimeout=3000"
                    + "&socketTimeout=10000&allowPublicKeyRetrieval=true&sslMode=DISABLED&tinyInt1isBit=true";
            case "mariadb" -> "jdbc:mariadb://" + host + ":3306/arpcds?connectTimeout=3000&socketTimeout=10000";
            case "sqlserver" -> "jdbc:sqlserver://" + host
                    + ":1433;databaseName=arpcds;encrypt=true;trustServerCertificate=true;loginTimeout=3";
            default -> throw new IllegalStateException("Explicit engine required");
        };
        return DriverManager.getConnection(url, server() ? "sa" : "root", password);
    }

    private static void exec(String sql) throws Exception
    {
        try (var c = connect(); var s = c.createStatement()) {
            for (String part : sql.split(";")) {
                String text = server() ? part.replace(" ADD COLUMN ", " ADD ") : part;
                if (!text.isBlank()) {
                    s.execute(text);
                }
            }
        }
    }

    private static Object reflect(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable
    {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Connection monitored(Harness harness) throws java.sql.SQLException
    {
        if (harness.offline.get()) {
            throw new java.sql.SQLException("private connection details");
        }
        var connection = connect();
        harness.openConnections.incrementAndGet();
        var selected = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("commit") && selected.get()) {
                        harness.commitAttempts++;
                        if (harness.failCommit) {
                            throw new java.sql.SQLException("private commit failure");
                        }
                        Object result = reflect(connection, method, args);
                        harness.afterCommit.run();
                        return result;
                    }
                    if (name.equals("close")) {
                        Object result = reflect(connection, method, args);
                        harness.openConnections.decrementAndGet();
                        if (selected.get() ? harness.failClose : harness.failGuardClose) {
                            throw new java.sql.SQLException("private close failure");
                        }
                        return result;
                    }
                    if (name.equals("rollback") && !selected.get() && harness.failGuardRollback) {
                        reflect(connection, method, args);
                        throw new java.sql.SQLException(
                                "private mandatory guard rollback failure");
                    }
                    Object value = reflect(connection, method, args);
                    if (name.equals("prepareStatement") && ((String) args[0]).startsWith(
                            "SELECT ")
                            && ((String) args[0]).contains(" ORDER BY ")
                            && ((String) args[0]).contains(server()
                                    ? " FROM [commerce].[products]"
                                    : " FROM `commerce`.`products`")) {
                        selected.set(true);
                        SQL.add((String) args[0]);
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[] { PreparedStatement.class },
                                (p, m, a) -> {
                                    Object result = reflect(
                                            value,
                                            m,
                                            a);
                                    if (m.getName().equals(
                                            "executeQuery")) {
                                        harness.afterQuery.run();
                                    }
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
        Runnable afterQuery = () -> {
        };
        Runnable afterCommit = () -> {
        };
        boolean failCommit, failClose, failGuardRollback, failGuardClose;
        int commitAttempts;
        final java.util.concurrent.atomic.AtomicInteger openConnections = new java.util.concurrent.atomic.AtomicInteger();
        Runnable onAuthorize = () -> {
        };
        final java.util.concurrent.atomic.AtomicReference<String> currentPolicy = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<String> tenant = new java.util.concurrent.atomic.AtomicReference<>(
                "7");
        final java.util.concurrent.atomic.AtomicReference<String> product = new java.util.concurrent.atomic.AtomicReference<>();

        Map<String, Object> restriction()
        {
            var rows = new LinkedHashMap<String, Object>();
            rows.put("tenant_id", tenant.get());
            if (product.get() != null) {
                rows.put("product_id", product.get());
            }
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
            this(name, narrowRating, definitionOverride, 5);
        }

        Harness(String name, boolean narrowRating, JsonNode definitionOverride, int timeoutSeconds) throws Exception
        {
            layout = products.at("/layouts/" + name);
            String pk = name.equals("composite") ? "tenant_id,product_id" : "product_id";
            if (server()) {
                exec("IF SCHEMA_ID('commerce') IS NULL EXEC('CREATE SCHEMA commerce')");
            } else {
                exec("CREATE DATABASE IF NOT EXISTS commerce");
            }
            exec("DROP TABLE IF EXISTS commerce.products");
            String stringType = server() ? "nvarchar" : "varchar";
            String textType = server() ? "nvarchar(max)" : "text";
            String decimalType = server() ? "sql_variant" : "decimal(65,18)";
            exec("CREATE TABLE commerce.products(tenant_id integer NOT NULL,product_id bigint NOT NULL,"
                    + "sku " + stringType + "(40) NOT NULL,name " + stringType + "(200) NOT NULL,price "
                    + decimalType + " NOT NULL,rating smallint NOT NULL,description " + textType
                    + ",available " + (server() ? "bit" : "tinyint(1)") + " NOT NULL,released date NOT NULL,"
                    + "cost " + decimalType + " NOT NULL,internal_note " + textType + ",PRIMARY KEY(" + pk + "))");
            try (var c = connect()) {
                for (var row : layout.get("rows")) {
                    var v = row.get("values");
                    var expressions = new ArrayList<String>();
                    for (String col : List.of("tenant_id", "product_id", "sku", "name", "price", "rating",
                            "description", "available", "released", "cost", "internal_note")) {
                        var n = v.get(col);
                        if (server() && (col.equals("price") || col.equals("cost"))) {
                            var decimal = new java.math.BigDecimal(n.asText());
                            int scale = Math.max(0, decimal.scale());
                            expressions.add("CONVERT(sql_variant,CAST(? AS decimal("
                                    + Math.max(decimal.precision(), scale) + "," + scale + ")))");
                        } else {
                            expressions.add("?");
                        }
                    }
                    try (var insert = c.prepareStatement("INSERT INTO commerce.products VALUES ("
                            + String.join(",", expressions) + ")")) {
                        int i = 1;
                        for (String col : List.of("tenant_id", "product_id", "sku", "name", "price", "rating",
                                "description", "available", "released", "cost", "internal_note")) {
                            var n = v.get(col);
                            Object value = n.isNull() ? null : switch (col) {
                                case "tenant_id", "product_id", "price", "rating", "cost" ->
                                    new java.math.BigDecimal(n.asText());
                                case "available" -> n.asBoolean();
                                case "released" -> java.sql.Date.valueOf(n.asText());
                                default -> n.asText();
                            };
                            insert.setObject(i++, value);
                        }
                        assertEquals(1, insert.executeUpdate());
                    }
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
                    observer(SqlEnginesIntegrationTest::connect)));
            var executor = reader(() -> monitored(this), timeoutSeconds);
            host = new ListGetHost(physical, executor,
                    ACTIVE, (target, operation, context) -> {
                        onAuthorize.run();
                        String revision = currentPolicy.get();
                        return new ListGetHost.Access(
                                (operation.equals("GET") ? allowGet.get() : allowList.get())
                                        && (revision == null || revision.equals(context.policyRevision())),
                                allowFields.get()
                                        ? Set.of("tenant_id",
                                                "product_id",
                                                "sku",
                                                "name",
                                                "price",
                                                "rating",
                                                "description",
                                                "available",
                                                "released",
                                                "cost",
                                                "internal_note")
                                        : Set.of("sku"),
                                capability.get() ? restriction() : null);
                    });
            assertTrue(physical.resolve().valid(), physical.resolve().toJson());
            SQL.clear();
        }

        JsonNode call(
                String target, String op, Map<String, Object> inputs,
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
        assertTrue(response.path("diagnostics").isEmpty());
        return response.get("output");
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
                        assertTrue(output.at("/page/next").isNull());
                        break;
                    }
                    inputs = Map.of("continuation", output.at("/page/next").asText());
                } while (true);
                assertEquals(5, seen.size());
                assertEquals(5, new HashSet<>(seen).size());
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
                    + " absent and inaccessible indistinguishable; reports derived",
                    () -> {
                        var h = new Harness(layout);
                        var row = h.layout.get("rows").get(0).deepCopy();
                        ((com.fasterxml.jackson.databind.node.ObjectNode) row.get("values")).put(
                                "product_id",
                                "999");
                        var missing = ok(h.call("identified",
                                "GET",
                                Map.of("key", h.publicKey(row))));
                        var denied = ok(h.call("identified",
                                "GET",
                                Map.of("key",
                                        h.publicKey(h.layout.get("rows").get(5)))));
                        assertEquals(missing, denied);
                        assertFalse(missing.get("found").asBoolean());
                        var summary = ok(h.call("reports", "LIST", Map.of()));
                        assertEquals(Set.of("sku", "name"), ConformanceTest.JSON.convertValue(
                                summary.at("/records/0/values"), Map.class).keySet());
                        int queries = SQL.size();
                        error(h.call("reports", "GET", Map.of()), "UNAVAILABLE");
                        assertEquals(queries, SQL.size());
                    }));
            tests.add(DynamicTest.dynamicTest(layout
                    + " authorization, inactive modules and unsupported policy fail closed",
                    () -> {
                        var h = new Harness(layout);
                        h.allowGet.set(false);
                        error(h.call("catalog", "GET", Map.of()), "UNAVAILABLE");
                        assertTrue(SQL.isEmpty());
                        ok(h.call("catalog", "LIST", Map.of()));
                        h.allowGet.set(true);
                        h.allowList.set(false);
                        error(h.call("catalog", "LIST", Map.of()), "UNAVAILABLE");
                        h.allowList.set(true);
                        h.allowFields.set(false);
                        SQL.clear();
                        error(h.call("catalog", "LIST", Map.of()), "UNAVAILABLE");
                        assertTrue(SQL.isEmpty());
                        h.allowFields.set(true);
                        h.capability.set(false);
                        error(h.call("catalog", "LIST", Map.of()), "CAPABILITY_UNAVAILABLE");
                        assertTrue(SQL.isEmpty());
                        h.capability.set(true);
                        h.host.changeAccess(Map.of("catalog", ACTIVE.get("catalog")), () -> {
                        });
                        error(h.call("reports", "LIST", Map.of()), "UNAVAILABLE");
                        assertTrue(SQL.isEmpty());
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
                    + " no output on inflight invalidation, module or policy change",
                    () -> {
                        var h = new Harness(layout);
                        h.afterQuery = h.physical::invalidate;
                        error(h.call("catalog", "LIST", Map.of()), "GENERATION_CHANGED");
                        assertFalse(SQL.isEmpty());
                        var missing = h.layout.get("rows").get(0).deepCopy();
                        ((com.fasterxml.jackson.databind.node.ObjectNode) missing.get("values")).put(
                                "product_id",
                                "999");
                        error(h.call("identified",
                                "GET",
                                Map.of("key", h.publicKey(missing))),
                                "GENERATION_CHANGED");
                        h.afterQuery = () -> {
                        };
                        ok(h.call("catalog", "LIST", Map.of()));
                        h.afterQuery = () -> h.host.changeAccess(Map.of("catalog",
                                ACTIVE.get("catalog")),
                                () -> {
                                });
                        error(h.call("reports", "LIST", Map.of()), "GENERATION_CHANGED");
                        h.host.changeAccess(ACTIVE, () -> {
                        });
                        h.afterQuery = () -> h.host.changeAccess(ACTIVE,
                                () -> h.allowList.set(false));
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
                exec("ALTER TABLE commerce.products DROP COLUMN rating");
                h.physical.invalidate();
                error(h.call("catalog", "LIST", Map.of()), "INVALID_DEFINITION");
            }));
            tests.add(DynamicTest.dynamicTest(layout + " invalid stored scalar discards complete page", () -> {
                var h = new Harness(layout, true);
                exec("UPDATE commerce.products SET rating = 128 WHERE tenant_id = 7 AND product_id = 10");
                error(h.call("catalog", "LIST", Map.of()), "ROW_DECODE_FAILED");
                // Hidden rating is not decoded or exposed by the reports projection.
                ok(h.call("reports", "LIST", Map.of()));
                if (!server()) {
                    exec("UPDATE commerce.products SET rating=8,available=2 WHERE product_id=10");
                    error(h.call("catalog", "LIST", Map.of()), "ROW_DECODE_FAILED");
                } else {
                    exec("UPDATE commerce.products SET rating=8,price=N'1.23' WHERE product_id=10");
                    error(h.call("catalog", "LIST", Map.of()), "ROW_DECODE_FAILED");
                }
                ok(h.call("reports", "LIST", Map.of()));
            }));
            tests.add(DynamicTest.dynamicTest(layout + " strict input and SQL bounds", () -> {
                var h = new Harness(layout);
                for (var input : List.of(Map.of("offset", 1),
                        Map.of("limit", false),
                        Map.of("limit", 0),
                        Map.of("limit", 101),
                        Map.of("limit", 1.5),
                        Map.of("sql", "DROP TABLE commerce.products"))) {
                    error(h.call("catalog", "LIST", (Map) input), "INPUT_SHAPE");
                }
                assertTrue(SQL.isEmpty());
                var malformed = h.host.invoke(("{\"target\":\"catalog\",\"operation\":\"LIST\","
                        + "\"inputs\":{\"limit\":1,\"limit\":2}}").getBytes(), CONTEXT);
                error(ConformanceTest.JSON.valueToTree(malformed), "INPUT_SHAPE");
                ok(h.call("catalog", "LIST", Map.of()));
                assertTrue(SQL.getLast().contains(" = ? ORDER BY"));
                assertTrue(SQL.getLast().endsWith(server() ? "OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY" : "LIMIT ?"));
                assertFalse(SQL.getLast().contains("cost"));
                assertFalse(SQL.getLast().contains("internal_note"));
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
            for (int i = 14; i <= 21; i++) {
                selected.add(i);
            }
            for (int i = 29; i <= 40; i++) {
                selected.add(i);
            }
            selected.addAll(Set.of(49, 50, 51, 85));
            for (int i = 0; i < vectors.size(); i++) {
                if (!selected.contains(i)) {
                    continue;
                }
                var vector = vectors.get(i);
                var request = vector.get("request").deepCopy();
                String target = vector.at("/host/projection").asText().equals("products.identified")
                        ? "identified"
                        : "catalog";
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
            exec("ALTER TABLE commerce.products DROP COLUMN rating");
            h.physical.invalidate();
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
            assertTrue(empty.path("records").isEmpty());
            assertFalse(empty.at("/page/hasMore").asBoolean());
            assertTrue(empty.at("/page/next").isNull());
            assertFalse(SQL.isEmpty());
            h.host.changeAccess(ACTIVE, () -> {
                h.tenant.set("7");
                h.product.set("2");
            });
            var summary = ok(h.call("reports", "LIST", Map.of()));
            assertEquals(1, summary.path("records").size());
            assertFalse(summary.at("/page/hasMore").asBoolean());
            assertTrue(SQL.getLast().contains(" AND "));
            h.host.changeAccess(ACTIVE, () -> h.product.set(null));
            var page = ok(h.call("catalog", "LIST", Map.of()));
            exec("DELETE FROM commerce.products WHERE tenant_id = 7 AND product_id > 10");
            var tail = ok(h.call("catalog", "LIST", Map.of("continuation", page.at("/page/next").asText())));
            assertTrue(tail.path("records").isEmpty());
            assertTrue(tail.at("/page/next").isNull());
            assertFalse(tail.at("/page/hasMore").asBoolean());
            var authorizations = new java.util.concurrent.atomic.AtomicInteger();
            h.onAuthorize = () -> {
                if (authorizations.incrementAndGet() == 2) {
                    h.physical.invalidate();
                }
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
            h.afterQuery = () -> {
            };
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
        return tests;
    }

    @TestFactory
    List<DynamicTest> publicationLifecycle()
    {
        var tests = new ArrayList<DynamicTest>();
        for (String layout : List.of("simple", "composite")) {
            tests.add(DynamicTest.dynamicTest(layout + " DDL remains blocked after DATA commit", () -> {
                var h = new Harness(layout);
                var blocked = new java.util.concurrent.atomic.AtomicInteger();
                h.afterCommit = () -> {
                    try (var c = connect(); var statement = c.createStatement()) {
                        statement.execute(server() ? "SET LOCK_TIMEOUT 150" : "SET SESSION lock_wait_timeout=1");
                        var error = assertThrows(java.sql.SQLException.class, () -> statement.execute(
                                server()
                                        ? "ALTER TABLE commerce.products ADD publication_gap nvarchar(max)"
                                        : "ALTER TABLE commerce.products ADD COLUMN publication_gap text"));
                        assertEquals(server() ? 1222 : (ENGINE.equals("mysql") ? 1205 : 1205), error.getErrorCode());
                        blocked.incrementAndGet();
                    } catch (java.sql.SQLException error) {
                        throw new AssertionError(error);
                    }
                };
                ok(h.call("catalog", "LIST", Map.of()));
                ok(h.call("identified", "GET", Map.of("key", h.publicKey(h.layout.get("rows").get(0)))));
                h.tenant.set("999");
                ok(h.call("catalog", "LIST", Map.of()));
                assertEquals(3, blocked.get(), "LIST/GET/empty SQL page keep publication guard");
                assertEquals(0, h.openConnections.get(), "all owned connections closed");
                h.afterCommit = () -> {
                };
                exec(server()
                        ? "ALTER TABLE commerce.products ADD publication_gap nvarchar(max)"
                        : "ALTER TABLE commerce.products ADD COLUMN publication_gap text");
                h.physical.invalidate();
                h.tenant.set("7");
                ok(h.call("catalog", "LIST", Map.of()));
            }));
            tests.add(DynamicTest.dynamicTest(layout + " mandatory commit and close failures discard output", () -> {
                var h = new Harness(layout);
                h.failCommit = true;
                error(h.call("catalog", "LIST", Map.of()), "READ_FAILED");
                error(h.call("identified", "GET", Map.of("key", h.publicKey(h.layout.get("rows").get(0)))),
                        "READ_FAILED");
                h.tenant.set("999");
                error(h.call("catalog", "LIST", Map.of()), "READ_FAILED");
                assertEquals(3, h.commitAttempts);
                assertEquals(0, h.openConnections.get());
                h.failCommit = false;
                h.tenant.set("7");
                ok(h.call("catalog", "LIST", Map.of()));
                h.failClose = true;
                error(h.call("catalog", "LIST", Map.of()), "READ_FAILED");
                assertEquals(0, h.openConnections.get());
                h.failClose = false;
                h.afterCommit = () -> h.failGuardRollback = true;
                error(h.call("catalog", "LIST", Map.of()), "READ_FAILED");
                assertEquals(0, h.openConnections.get());
                h.failGuardRollback = false;
                h.afterCommit = () -> h.failGuardClose = true;
                error(h.call("catalog", "LIST", Map.of()), "READ_FAILED");
                assertEquals(0, h.openConnections.get());
                h.failGuardClose = false;
                h.afterCommit = () -> h.physical.invalidate();
                error(h.call("catalog", "LIST", Map.of()), "GENERATION_CHANGED");
                h.afterCommit = () -> h.host.changeAccess(ACTIVE, () -> h.allowList.set(false));
                error(h.call("catalog", "LIST", Map.of()), "UNAVAILABLE");
                h.afterCommit = () -> {
                };
                h.allowList.set(true);
                ok(h.call("catalog", "LIST", Map.of()));
                assertEquals(0, h.openConnections.get());
            }));
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> engineSafety()
    {
        var tests = new ArrayList<DynamicTest>();
        tests.add(DynamicTest.dynamicTest("data lock excludes DDL before guard handoff", () -> {
            var h = new Harness("composite");
            h.afterQuery = () -> {
                try (var c = connect(); var statement = c.createStatement()) {
                    statement.execute(server() ? "SET LOCK_TIMEOUT 150" : "SET SESSION lock_wait_timeout=1");
                    var error = assertThrows(java.sql.SQLException.class, () -> statement.execute(server()
                            ? "ALTER TABLE commerce.products ADD selected_gap int"
                            : "ALTER TABLE commerce.products ADD COLUMN selected_gap int"));
                    assertEquals(server() ? 1222 : 1205, error.getErrorCode());
                } catch (java.sql.SQLException error) {
                    throw new AssertionError(error);
                }
            };
            ok(h.call("catalog", "LIST", Map.of()));
            h.afterQuery = () -> {
            };
            exec("ALTER TABLE commerce.products ADD COLUMN selected_gap int");
            error(h.call("catalog", "LIST", Map.of()), "GENERATION_CHANGED");
        }));
        tests.add(DynamicTest.dynamicTest("metadata authority is complete or observation fails", () -> {
            new Harness("composite");
            String password = System.getenv("ARP_DB_PASSWORD");
            SqlRecordReader.Connections limited;
            if (!server()) {
                exec("ALTER TABLE commerce.products ADD COLUMN invisible_fact int INVISIBLE");
                exec("CREATE USER 'arp_columns'@'%' IDENTIFIED BY '" + password + "'");
                exec("GRANT SELECT(tenant_id,product_id,sku,name,price,rating,description,available,"
                        + "released,cost,internal_note) ON commerce.products TO 'arp_columns'@'%'");
                limited = () -> DriverManager.getConnection((ENGINE.equals("mysql")
                        ? "jdbc:mysql://"
                        : "jdbc:mariadb://") + System.getenv("ARP_DB_HOST") + ":3306/commerce"
                        + (ENGINE.equals("mysql") ? "?allowPublicKeyRetrieval=true&sslMode=DISABLED" : ""),
                        "arp_columns", password);
            } else {
                exec("CREATE USER arp_columns WITHOUT LOGIN");
                exec("GRANT SELECT ON commerce.products TO arp_columns");
                limited = () -> {
                    var connection = connect();
                    try (var statement = connection.createStatement()) {
                        statement.execute("EXECUTE AS USER='arp_columns'");
                    }
                    return connection;
                };
            }
            var restricted = observer(limited);
            assertThrows(org.alxarafe.arp.cds.physical.ObservationException.class, restricted::observe);
            exec(server()
                    ? "GRANT VIEW DEFINITION TO arp_columns"
                    : "GRANT SELECT ON commerce.products TO 'arp_columns'@'%'");
            var facts = restricted.observe();
            assertEquals(server() ? 11 : 12, ((List<?>) facts.get("columns")).size());
            if (server()) {
                exec("CREATE FUNCTION commerce.arp_filter(@tenant int) RETURNS TABLE WITH SCHEMABINDING "
                        + "AS RETURN SELECT 1 AS allowed WHERE @tenant=7");
                exec("CREATE SECURITY POLICY commerce.arp_policy ADD FILTER PREDICATE "
                        + "commerce.arp_filter(tenant_id) ON commerce.products WITH (STATE=ON)");
                try {
                    assertThrows(org.alxarafe.arp.cds.physical.ObservationException.class,
                            () -> observer(SqlEnginesIntegrationTest::connect).observe());
                    assertThrows(org.alxarafe.arp.cds.physical.ObservationException.class, restricted::observe);
                } finally {
                    exec("DROP SECURITY POLICY commerce.arp_policy");
                    exec("DROP FUNCTION commerce.arp_filter");
                }
                exec("DROP USER arp_columns");
            } else {
                exec("DROP USER 'arp_columns'@'%'");
            }
        }));
        tests.add(DynamicTest.dynamicTest("ordinary decimal storage and exact boundary codec", () -> {
            var h = new Harness("composite");
            if (server()) {
                exec("ALTER TABLE commerce.products DROP COLUMN price");
                exec("ALTER TABLE commerce.products ADD price decimal(38,18) NOT NULL "
                        + "DEFAULT(0.123456789012345678)");
                h.physical.invalidate();
                var page = ok(h.call("catalog", "LIST", Map.of()));
                assertEquals("0.123456789012345678", page.at("/records/0/values/price").asText());
                String reference = page.at("/records/0/key/reference").asText();
                assertEquals("0.123456789012345678", ok(h.call("catalog", "GET",
                        Map.of("key", Map.of("reference", reference)))).at(
                                "/record/values/price")
                        .asText());
            } else {
                try (var c = connect()) {
                    reader(() -> c, 3).beginTransaction(c);
                    try (var statement = c.createStatement()) {
                        assertThrows(java.sql.SQLException.class,
                                () -> statement.executeUpdate("UPDATE commerce.products SET rating=1"));
                    }
                    c.rollback();
                }
                var page = ok(h.call("catalog", "LIST", Map.of()));
                assertTrue(page.has("records"));
            }
        }));
        return tests;
    }
}
