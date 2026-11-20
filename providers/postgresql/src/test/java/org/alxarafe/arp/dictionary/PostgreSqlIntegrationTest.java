package org.alxarafe.arp.dictionary;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.alxarafe.arp.cds.physical.ObservationException;
import org.alxarafe.arp.cds.physical.PhysicalCatalog;
import org.alxarafe.arp.cds.physical.PhysicalObservationProvider;
import org.alxarafe.arp.cds.physical.PostgreSqlObservationProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real DB contract executions are reported separately from the additional engine checks.
 */
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgreSqlIntegrationTest
{
    private static final String SCOPE = "arp_scope";
    private static final String PASSWORD = System.getenv("ARP_DB_PASSWORD");
    private static final String HOST = System.getenv("ARP_DB_HOST");
    private static final String URL = "jdbc:postgresql://" + HOST + ":5432/arpcds?connectTimeout=3&socketTimeout=5";
    private static Connection admin;
    private static JsonNode engine;
    private static JsonNode vector;
    private static Map<String, Object> definitions;
    private static String original;
    private static Catalog dictionary;
    private static String physicalId;
    private static String setup;
    private static final AtomicBoolean OFFLINE = new AtomicBoolean();
    private static final List<String> CONTRACT = new ArrayList<>();
    private static final List<String> ADDITIONAL = new ArrayList<>();

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void setup() throws Exception
    {
        assertNotNull(PASSWORD, "Run through providers/integration-docker.py; no external DB is accepted");
        admin = DriverManager.getConnection(URL, "arpcds", PASSWORD);
        assertEquals("arpcds", scalar("SELECT current_database()"));
        assertEquals(System.getenv("ARP_CDS_RUN_TOKEN"), scalar("SELECT token FROM public.__arp_cds_test_owner"),
                "Owned run nonce required before DDL");
        exec("CREATE SCHEMA " + SCOPE + "; SET search_path TO " + SCOPE);
        exec("CREATE TABLE sentinel(marker text); INSERT INTO sentinel VALUES ('unchanged')");
        setup = Files.readString(ConformanceTest.ROOT.resolve("examples/demo/sql/postgresql.sql"));
        exec(setup);
        for (var e : ConformanceTest.read(ConformanceTest.ROOT.resolve(
                "fixtures/cds/read/v1/database-cases.json")).get("engines")) {
            if (e.get("engine").asText().equals("postgresql")) {
                engine = e;
            }
        }
        assertNotNull(engine);
        for (var c : ConformanceTest.read(
                ConformanceTest.ROOT.resolve("fixtures/cds/read/v1/cases.json")).get("cases")) {
            if (c.get("id").asText().equals(engine.get("resolutionCase").asText())) {
                vector = c;
            }
        }
        assertNotNull(vector);
        definitions = (Map<String, Object>) ConformanceTest.JSON.convertValue(vector.get("input"), Object.class);
        original = JsonSupport.write(definitions);
        physicalId = vector.at("/input/physical/storage/id").asText();
        dictionary = new CatalogBuilder().buildCatalog(List.of(new Source("fixture", "dictionary", Source.Encoding.JSON,
                Files.readAllBytes(ConformanceTest.ROOT.resolve(
                        "fixtures/dictionary/v1/catalog.json")))))
                .catalog().orElseThrow();
    }

    private static Connection connect() throws SQLException
    {
        return DriverManager.getConnection(OFFLINE.get() ? URL.replace(":5432/", ":1/") : URL, "arpcds", PASSWORD);
    }

    private static void exec(String sql) throws SQLException
    {
        try (var statement = admin.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String scalar(String sql) throws SQLException
    {
        try (var statement = admin.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static JsonNode node(Object value) throws Exception
    {
        return ConformanceTest.JSON.readTree(JsonSupport.write(value));
    }

    private static Counting observer(String table)
    {
        return new Counting(new PostgreSqlObservationProvider(PostgreSqlIntegrationTest::connect,
                "primary", physicalId, SCOPE, table, 5));
    }

    private static PhysicalCatalog session(Counting provider)
    {
        return new PhysicalCatalog(dictionary, definitions, Map.of("storage", provider));
    }

    private static JsonNode names(JsonNode fields)
    {
        var out = ConformanceTest.JSON.createArrayNode();
        fields.forEach(f -> out.add(f.get("name").asText()));
        return out;
    }

    private static void assertions(JsonNode result, JsonNode spec) throws Exception
    {
        if (spec.has("logicalFieldNames")) {
            ConformanceTest.assertJson(spec.get("logicalFieldNames"), names(result.at("/models/0/fields")));
        }
        for (String key : List.of("public", "summary")) {
            if (spec.has(key + "FieldNames")) {
                String id = vector.at("/input/projections/" + key + "/id").asText();
                for (var projection : result.get("projections")) {
                    if (projection.get("id").asText().equals(id)) {
                        ConformanceTest.assertJson(spec.get(key + "FieldNames"), names(projection.get("fields")));
                    }
                }
            }
        }
        assertEquals(original, JsonSupport.write(definitions), "Overlay bytes unchanged");
    }

    @Test
    @Order(1)
    void contractDatabaseScenarios() throws Exception
    {
        var provider = observer("items");
        var catalog = session(provider);
        CdsReadResult first = null;
        for (var c : engine.get("cases")) {
            String id = c.get("id").asText();
            switch (id) {
                case "lazy-generation" -> {
                    assertEquals(0, provider.count);
                    first = catalog.resolve();
                    assertTrue(first.valid());
                    assertEquals(1, provider.count);
                    assertSame(first, catalog.resolve());
                    assertEquals(1, provider.count);
                    var result = node(first.projection());
                    assertions(result, c.get("assert"));
                    ConformanceTest.assertJson(vector.at("/expected/projections"), result.get("projections"));
                    var physical = result.at("/models/0/physical");
                    var facts = engine.get("observedFacts");
                    for (String field : List.of("provider", "dialect", "name")) {
                        assertEquals(facts.get("source." + field).asText(), physical.at("/source/" + field).asText());
                    }
                    assertEquals(SCOPE, physical.at("/source/schema").asText());
                    assertEquals(scalar("SHOW server_version"), physical.at("/source/engineVersion").asText());
                    ConformanceTest.assertJson(facts.get("columnOrder"), names(physical.get("columns")));
                    for (var col : physical.get("columns")) {
                        String name = col.get("name").asText();
                        assertEquals(facts.get("scalarTypes").get(name).asText(), col.get("scalarType").asText());
                        assertEquals(facts.get("nullable").get(name).asBoolean(), col.get("nullable").asBoolean());
                        assertFalse(col.get("nativeType").asText().isEmpty());
                    }
                    ConformanceTest.assertJson(facts.get("primaryKey"), physical.get("primaryKey"));
                    ConformanceTest.assertJson(facts.get("rating.integerRange"),
                            physical.at("/columns/2/integerRange"));
                    ConformanceTest.assertJson(facts.get("code.characterLength"),
                            physical.at("/columns/1/characterLength"));
                    assertEquals("compatible", result.at("/models/0/fields/2/compatibility").asText());
                }
                case "add-column-and-invalidate" -> {
                    exec(c.get("ddl").asText());
                    assertSame(first, catalog.resolve());
                    assertEquals(1, provider.count);
                    catalog.invalidate();
                    assertTrue(catalog.current().isEmpty());
                    assertEquals(1, provider.count);
                    var result = catalog.resolve();
                    assertTrue(result.valid());
                    assertEquals(2, provider.count);
                    assertions(node(result.projection()), c.get("assert"));
                    ConformanceTest.assertJson(node(first.projection()).get("projections"),
                            node(result.projection()).get("projections"));
                }
                case "remove-specialized-column-and-invalidate" -> {
                    exec(c.get("ddl").asText());
                    catalog.invalidate();
                    ConformanceTest.compare(catalog.resolve().projection(), c.get("expected"), false);
                    assertTrue(catalog.current().isEmpty());
                    assertEquals(original, JsonSupport.write(definitions));
                }
                case "observation-fails-after-invalidation" -> {
                    OFFLINE.set(true);
                    catalog.invalidate();
                    ConformanceTest.compare(catalog.resolve().projection(), c.get("expected"), false);
                    assertTrue(catalog.current().isEmpty());
                    OFFLINE.set(false);
                }
                case "invalidation-during-generation" -> {
                    exec("DROP TABLE items");
                    exec(setup);
                    var changing = new PhysicalCatalog[1];
                    var delayed = observer("items");
                    delayed.after = () -> changing[0].invalidate();
                    changing[0] = session(delayed);
                    ConformanceTest.compare(changing[0].resolve().projection(), c.get("expected"), false);
                    assertTrue(changing[0].current().isEmpty());
                    assertEquals(1, delayed.count);
                }
                default -> fail("Unexecuted contractual database scenario: " + id);
            }
            CONTRACT.add(id);
        }
        assertEquals(5, CONTRACT.size());
    }

    @Test
    @Order(2)
    void additionalIntegration() throws Exception
    {
        exec("CREATE TABLE parent(a int,b int,PRIMARY KEY(a,b)); CREATE TABLE keys_table(a int,b int,"
                + "note varchar(12),PRIMARY KEY(a,b),CONSTRAINT fk_pair FOREIGN KEY(a,b) REFERENCES parent(a,b));"
                + "CREATE INDEX ix_note ON keys_table(note)");
        var keys = node(observer("keys_table").observe());
        ConformanceTest.assertJson(ConformanceTest.JSON.readTree("[\"a\",\"b\"]"), keys.get("primaryKey"));
        ConformanceTest.assertJson(keys.get("primaryKey"), keys.at("/foreignKeys/0/columns"));
        ConformanceTest.assertJson(keys.get("primaryKey"), keys.at("/foreignKeys/0/target/columns"));
        assertTrue(keys.get("indexes").findValuesAsText("name").contains("ix_note"));
        ADDITIONAL.add("composite-keys-indexes-foreign-keys");
        exec("CREATE INDEX ix_partial ON keys_table(note) WHERE a > 0");
        assertFalse(node(observer("keys_table").observe()).has("indexes"));
        ADDITIONAL.add("unrepresentable-index-presence");
        exec("CREATE DOMAIN local_int AS integer NOT NULL; CREATE TABLE types_test(opaque jsonb, custom local_int,"
                + "large bigint, generated integer GENERATED ALWAYS AS (1) STORED,"
                + "ident integer GENERATED ALWAYS AS IDENTITY)");
        var types = node(observer("types_test").observe());
        assertFalse(types.at("/columns/0").has("scalarType"));
        assertFalse(types.at("/columns/1").has("scalarType"));
        assertFalse(types.at("/columns/1").has("nullable"));
        assertEquals("9223372036854775807", types.at("/columns/2/integerRange/maximum").asText());
        assertTrue(types.at("/columns/3/generated").asBoolean());
        assertTrue(types.at("/columns/4/identity").asBoolean());
        ADDITIONAL.add("opaque-domain-exact-int64-generated-identity");
        exec("CREATE TABLE checked(value smallint CHECK(value > 0))");
        var checked = node(observer("checked").observe());
        assertFalse(checked.at("/columns/0").has("scalarType"));
        assertFalse(checked.at("/columns/0").has("nullable"));
        var checkedDefinitions = Map.<String, Object>of("physical", Map.of(), "logical", Map.of("model", Map.of(
                "apiVersion", "arp.logical/v1", "id", "demo.checked", "physical", physicalId,
                "fields", Map.of("value", Map.of("dictionary", "test.int16")))), "projections", Map.of());
        var checkedCatalog = new PhysicalCatalog(dictionary, checkedDefinitions, Map.of("storage",
                observer("checked")));
        assertEquals("not-verified", node(checkedCatalog.resolve().projection())
                .at("/models/0/fields/0/compatibility").asText());
        ADDITIONAL.add("native-check-no-false-compatibility");

        exec("CREATE TABLE \"Odd.Table\"(id int)");
        assertEquals(1,
                node(observer("Odd.Table").observe()).get("columns").size());
        ADDITIONAL.add("literal-physical-identifiers");
        exec("CREATE ROLE limited LOGIN PASSWORD '" + PASSWORD + "'; GRANT USAGE ON SCHEMA arp_scope TO limited;"
                + "GRANT SELECT(id) ON items TO limited");
        try (var limited = DriverManager.getConnection(URL, "limited", PASSWORD);
                var s = limited.createStatement();
                var rs = s.executeQuery("SELECT count(*) FROM information_schema.columns WHERE"
                        + " table_schema='arp_scope' AND table_name='items'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "Real column-level metadata visibility");
        }
        var denied = new Counting(new PostgreSqlObservationProvider(
                () -> DriverManager.getConnection(URL, "limited", PASSWORD), "primary", physicalId, SCOPE, "items", 5));
        assertEquals(CdsReadResult.failure("OBSERVATION_FAILED", "/providers/primary",
                "execution-failure").projection(),
                session(denied).resolve().projection());
        ADDITIONAL.add("permissions-incomplete-metadata-no-partial-publication");
        exec("CREATE VIEW view_test AS SELECT id FROM items");
        for (String name : List.of("missing_table", "view_test")) {
            assertThrows(ObservationException.class, () -> observer(name).observe());
        }
        assertEquals("unchanged", scalar("SELECT marker FROM sentinel"));
        assertEquals("0",
                scalar("SELECT count(*) FROM items"));
        ADDITIONAL.add("missing-view-readonly-and-sentinel-preservation");
        var goodObserver = observer("items");
        var atomic = new PhysicalCatalog(dictionary, definitions,
                Map.of("storage", goodObserver, "zz", observer("missing_table")));
        assertEquals(CdsReadResult.failure("OBSERVATION_FAILED", "/providers/primary",
                "execution-failure").projection(),
                atomic.resolve().projection());
        assertTrue(atomic.current().isEmpty());
        assertEquals(1, goodObserver.count);
        ADDITIONAL.add("multi-provider-no-partial-publication");
        var narrowInput = vector.get("input").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) narrowInput.at("/logical/model/fields"))
                .set("id", ConformanceTest.JSON.createObjectNode().put("dictionary", "test.int32"));
        @SuppressWarnings("unchecked")
        var narrowDefinitions = (Map<String, Object>) ConformanceTest.JSON.convertValue(narrowInput, Object.class);
        String authored = JsonSupport.write(narrowDefinitions);
        var narrowed = new PhysicalCatalog(dictionary, narrowDefinitions, Map.of("storage", observer("items")));
        var beforeNarrowing = narrowed.resolve();
        assertTrue(beforeNarrowing.valid());
        exec("DROP VIEW view_test; ALTER TABLE items ALTER COLUMN id TYPE smallint");
        narrowed.invalidate();
        assertEquals(CdsReadResult.failure("DOMAIN_NOT_REPRESENTABLE", "/logical/model/fields/id",
                "physical-incompatible").projection(), narrowed.resolve().projection());
        assertTrue(narrowed.current().isEmpty());
        assertEquals(authored, JsonSupport.write(narrowDefinitions));
        assertEquals(original, JsonSupport.write(definitions));
        assertEquals("INT32", node(beforeNarrowing.projection()).at("/models/0/fields/1/physical/scalarType").asText());
        ADDITIONAL.add("real-narrowing-incompatible");

    }

    @AfterAll
    static void report() throws Exception
    {
        if (admin != null) {
            System.out.println(JsonSupport.write(Map.of("stack",
                    "java",
                    "contractCases",
                    CONTRACT,
                    "additionalCases",
                    ADDITIONAL,
                    "engineVersion",
                    scalar("SHOW server_version"),
                    "contractSha",
                    ConformanceTest.SHA)));
            admin.close();
        }
    }

    private static final class Counting implements PhysicalObservationProvider
    {
        private final PhysicalObservationProvider inner;
        int count;
        Runnable after;

        Counting(PhysicalObservationProvider inner)
        {
            this.inner = inner;
        }

        public String providerId()
        {
            return inner.providerId();
        }

        public Map<String, Object> observe() throws ObservationException
        {
            count++;
            var result = inner.observe();
            if (after != null) {
                after.run();
            }
            return result;
        }
    }
}
