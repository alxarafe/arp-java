package org.alxarafe.arp.dictionary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.alxarafe.arp.cds.physical.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit seed/recover runs use different processes and containers with the same external volume.
 */
class PersistenceIntegrationTest
{
    private static class Counted implements PhysicalObservationProvider
    {
        final PhysicalObservationProvider inner;
        int count;
        Runnable after;

        Counted(PhysicalObservationProvider inner)
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
            var value = inner.observe();
            if (after != null) {
                after.run();
            }
            return value;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistence() throws Exception
    {
        String phase = System.getenv("ARP_PERSIST_PHASE");
        assertNotNull(phase, "Use the Docker runner with explicit seed/recover phase");
        Path root = ConformanceTest.ROOT;
        Path directory = Path.of(System.getenv("ARP_PHYSICAL_STORE"));
        String password = System.getenv("ARP_DB_PASSWORD");
        String url = "jdbc:postgresql://db:5432/arpcds?connectTimeout=3&socketTimeout=5";
        try (var admin = DriverManager.getConnection(url, "arpcds", password)) {
            try (var c = admin.createStatement();
                 var r = c.executeQuery("SELECT token FROM public.__arp_cds_test_owner"))
            {
                assertTrue(r.next());
                assertEquals(System.getenv("ARP_CDS_RUN_TOKEN"), r.getString(1));
            }
            if (phase.equals("seed")) {
                try (var c = admin.createStatement()) {
                    c.execute("CREATE SCHEMA persist_scope; CREATE TABLE persist_scope.persisted_items "
                                      + "(id INTEGER PRIMARY KEY, code VARCHAR(12) NOT NULL, rating SMALLINT NOT NULL)");
                }
            }
            var key = new PhysicalStoreKey("tests", "db-main", "primary", "storage.items", "postgresql",
                                           "persist_scope", "persisted_items");
            var store = new FilePhysicalStore(directory, List.of(Path.of("/work"), root));
            var offline = new java.util.concurrent.atomic.AtomicBoolean();
            var counted = new Counted(new PostgreSqlObservationProvider(
                    () -> DriverManager.getConnection(offline.get() ? url.replace(":5432/", ":1/") : url,
                                                      "arpcds", password), "primary", key.physicalId(),
                    key.schema(), key.table(), 10));
            var cases = ConformanceTest.read(root.resolve("fixtures/cds/read/v1/cases.json")).get("cases");
            Map<String, Object> definitions = null;
            for (var v : cases) {
                if (v.get("id").asText().equals("postgresql-same-logical-projections")) {
                    definitions = (Map<String, Object>) ConformanceTest.JSON.convertValue(v.get("input"), Object.class);
                }
            }
            assertNotNull(definitions);
            String original = JsonSupport.write(definitions);
            var dictionary = new CatalogBuilder().buildCatalog(List.of(new Source("fixture",
                                                                                  "dictionary",
                                                                                  Source.Encoding.JSON,
                                                                                  Files.readAllBytes(root.resolve(
                                                                                          "fixtures/dictionary/v1/catalog.json")))))
                    .catalog().orElseThrow();
            var defs = definitions;
            java.util.function.Function<PhysicalStoreKey, PhysicalCatalog> catalogFor = k ->
                    new PhysicalCatalog(dictionary, defs,
                                        Map.of("storage", new PersistedPhysicalProvider(counted, store, k)));
            var catalog = catalogFor.apply(key);
            Path path = store.fileFor(key);
            assertEquals(0, counted.count);
            var result = catalog.resolve();
            assertTrue(result.valid());
            if (phase.equals("recover")) {
                assertEquals(0, counted.count, "zero introspections in new process");
                assertEquals(Files.readString(directory.resolve("proof.json")), digest(Files.readAllBytes(path)));
                System.out.println("PERSISTENCE_RECOVERED: process/container restart, same YAML, zero observations");
                return;
            }
            var groups = new ArrayList<String>();
            assertEquals(1, counted.count);
            assertTrue(Files.isRegularFile(path));
            assertSame(result, catalog.resolve());
            assertEquals(1, counted.count);
            var reopened = new FilePhysicalStore(directory, List.of(Path.of("/work"), root));
            assertTrue(new PhysicalCatalog(dictionary, defs,
                                           Map.of("storage",
                                                  new PersistedPhysicalProvider(counted,
                                                                                reopened,
                                                                                key))).resolve().valid());
            assertEquals(1, counted.count);
            groups.add("lazy-cache-and-restart");
            byte[] first = Files.readAllBytes(path);
            var doc = PhysicalYaml.decode(first, key);
            assertArrayEquals(first, PhysicalYaml.encode(doc, key));
            assertArrayEquals(first, PhysicalYaml.encode(counted.observe(), key));
            groups.add("deterministic-semantic-roundtrip");
            for (var k : List.of(new PhysicalStoreKey("other", "db-main", "primary", key.physicalId(), key.dialect(),
                                                      key.schema(), key.table()),
                                 new PhysicalStoreKey("tests", "other-db", "primary", key.physicalId(),
                                                      key.dialect(), key.schema(), key.table()))) {
                assertNotEquals(path, store.fileFor(k));
                assertTrue(catalogFor.apply(k).resolve().valid());
            }
            assertNotEquals(key.hash(), new PhysicalStoreKey("tests", "db-main", "primary", key.physicalId(),
                                                             key.dialect(), "other_schema", key.table()).hash());
            assertNotEquals(key.hash(), new PhysicalStoreKey("tests", "db-main", "primary", key.physicalId(),
                                                             key.dialect(), key.schema(), "other_table").hash());
            groups.add("identity-isolation");
            try (var c = admin.createStatement()) {
                c.execute("ALTER TABLE persist_scope.persisted_items ADD COLUMN new_column BIGINT");
            }
            assertFalse(JsonSupport.write(catalog.resolve().projection()).contains("new_column"));
            catalog.invalidate();
            assertFalse(Files.exists(path));
            assertTrue(catalog.current().isEmpty());
            result = catalog.resolve();
            assertTrue(result.valid());
            var projection = ConformanceTest.JSON.valueToTree(result.projection());
            assertTrue(projection.get("models").toString().contains("new_column"));
            assertFalse(projection.get("projections").toString().contains("new_column"));
            assertEquals(original, JsonSupport.write(defs));
            groups.add("ddl-invalidation");
            Files.writeString(path, "---\n{broken");
            var corrupt = catalogFor.apply(key);
            assertFalse(corrupt.resolve().valid());
            assertTrue(corrupt.current().isEmpty());
            assertTrue(corrupt.resolve().valid());
            var edited = (Map<String, Object>) ConformanceTest.JSON.readValue(
                    ConformanceTest.JSON.writeValueAsBytes(PhysicalYaml.decode(Files.readAllBytes(path), key)),
                    Map.class);
            ((Map<String, Object>) edited.get("source")).put("engineVersion", "tampered");
            Files.write(path, PhysicalYaml.encode(edited, key));
            var tampered = catalogFor.apply(key);
            assertFalse(tampered.resolve().valid());
            assertTrue(tampered.resolve().valid());
            groups.add("corruption-and-checksum");
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("---------"));
            var unreadable = catalogFor.apply(key);
            assertFalse(unreadable.resolve().valid());
            assertTrue(unreadable.current().isEmpty());
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            assertTrue(unreadable.resolve().valid());
            groups.add("read-permission-recovery");
            catalog.invalidate();
            offline.set(true);
            assertFalse(catalog.resolve().valid());
            assertTrue(catalog.current().isEmpty());
            assertFalse(Files.exists(path));
            offline.set(false);
            assertTrue(catalog.resolve().valid());
            groups.add("failed-regeneration-no-stale-cache");
            catalog.invalidate();
            var permissions = Files.getPosixFilePermissions(directory);
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
            assertFalse(catalog.resolve().valid());
            assertTrue(catalog.current().isEmpty());
            Files.setPosixFilePermissions(directory, permissions);
            assertTrue(catalog.resolve().valid());
            groups.add("write-permission-recovery");
            Path state = directory.resolve(key.hash() + ".lock");
            Files.writeString(state, "broken-state");
            var badState = catalogFor.apply(key);
            assertFalse(badState.resolve().valid());
            assertTrue(badState.resolve().valid());
            groups.add("state-recovery");
            catalog.invalidate();
            Files.createDirectory(path);
            assertFalse(catalog.resolve().valid());
            assertTrue(catalog.current().isEmpty());
            Files.delete(path);
            assertTrue(catalog.resolve().valid());
            groups.add("atomic-write-failure");
            Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("r--------"));
            assertThrows(IllegalStateException.class, catalog::invalidate);
            assertTrue(catalog.current().isEmpty());
            assertFalse(catalog.resolve().valid());
            Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rw-------"));
            catalog.invalidate();
            assertTrue(catalog.resolve().valid());
            groups.add("failed-invalidation-blocks-reuse");
            catalog.invalidate();
            counted.after = catalog::invalidate;
            assertFalse(catalog.resolve().valid());
            assertTrue(catalog.current().isEmpty());
            assertFalse(Files.exists(path));
            counted.after = null;
            assertTrue(catalog.resolve().valid());
            groups.add("in-flight-invalidation");
            assertThrows(ObservationException.class,
                         () -> new FilePhysicalStore(Path.of("/work"), List.of(Path.of("/work"), root)));
            Path link = directory.resolve("source-link");
            Files.createSymbolicLink(link, Path.of("/work"));
            assertThrows(ObservationException.class,
                         () -> new FilePhysicalStore(link, List.of(Path.of("/work"), root)));
            Files.delete(link);
            catalog.invalidate();
            Files.createSymbolicLink(path, root.resolve("examples/demo/cds/items.physical.yaml"));
            assertFalse(catalog.resolve().valid());
            Files.delete(path);
            assertTrue(catalog.resolve().valid());
            groups.add("source-and-symlink-protection");
            assertThrows(IllegalArgumentException.class, () -> new PhysicalStoreKey("tests",
                                                                                    url + password,
                                                                                    "primary",
                                                                                    key.physicalId(),
                                                                                    key.dialect(),
                                                                                    key.schema(),
                                                                                    key.table()));
            try (var files = Files.list(directory)) {
                for (var f : files.toList()) {
                    assertFalse(f.getFileName().toString().contains(password));
                    assertFalse(Files.readString(f).contains(password));
                    assertFalse(f.getFileName().toString().endsWith(".tmp"));
                }
            }
            groups.add("secret-integrity");
            var exact = (Map<String, Object>) ConformanceTest.JSON.readValue(
                    ConformanceTest.JSON.writeValueAsBytes(PhysicalYaml.decode(Files.readAllBytes(path), key)),
                    Map.class);
            var columns = (List<Map<String, Object>>) exact.get("columns");
            columns.get(0).put("nativeType", "bigint");
            columns.get(0).put("scalarType", "INT64");
            columns.get(0).put("integerRange", Map.of("minimum", "-9223372036854775808",
                                                      "maximum", "9223372036854775807"));
            columns.get(1).put("characterLength", Map.of("unit", "CHARACTERS", "value",
                                                         new java.math.BigInteger("9223372036854775807")));
            assertTrue(new String(PhysicalYaml.encode(exact, key), java.nio.charset.StandardCharsets.UTF_8)
                               .contains("9223372036854775807"));
            groups.add("exact-integer-codec");
            applicationCache(doc, key);
            groups.add("git-ignored-application-cache");
            Files.writeString(directory.resolve("proof.json"), digest(Files.readAllBytes(path)));
            System.out.println("PERSISTENCE_SEED: " + JsonSupport.write(groups));
        }
    }

    private static void git(Path repository, String... arguments) throws Exception
    {
        var command = new ArrayList<String>(List.of("git", "--literal-pathspecs", "-C", repository.toString()));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertEquals(0, process.waitFor(), "test application Git");
    }

    private static void applicationCache(Map<String, Object> observation, PhysicalStoreKey key) throws Exception
    {
        Path app = Files.createTempDirectory("arp-cache-app-");
        try {
            Path cache = Files.createDirectories(app.resolve("cache/arp/physical"));
            Path definitions = Files.createDirectories(app.resolve("arp/models"));
            Path custom = Files.createDirectory(app.resolve("custom-cache"));
            git(app, "init", "--quiet");
            assertThrows(ObservationException.class, () -> new FilePhysicalStore(cache, List.of(app)));
            Files.writeString(app.resolve(".gitignore"), "cache/\narp/models/\ncustom-cache/\n");
            var store = new FilePhysicalStore(cache, List.of(app));
            var count = new java.util.concurrent.atomic.AtomicInteger();
            FilePhysicalStore.Observe observe = () -> {
                count.incrementAndGet();
                return observation;
            };
            store.get(key, observe);
            new FilePhysicalStore(cache, List.of(app)).get(key, observe);
            assertEquals(1, count.get());
            store.invalidate(key);
            store.get(key, observe);
            assertEquals(2, count.get(), "local invalidation and regeneration");
            assertThrows(ObservationException.class, () -> new FilePhysicalStore(definitions, List.of(app)));
            assertThrows(ObservationException.class, () -> new FilePhysicalStore(custom, List.of(app)));
            new FilePhysicalStore(custom, List.of(app), List.of(custom)).get(key, observe);
            assertThrows(ObservationException.class,
                         () -> new FilePhysicalStore(cache, List.of(app), List.of(custom)));
            Path link = app.resolve("linked-cache");
            Files.createSymbolicLink(link, cache);
            assertThrows(ObservationException.class, () -> new FilePhysicalStore(link, List.of(app)));
            Files.delete(link);
            Files.writeString(app.resolve(".gitignore"), "");
            assertThrows(ObservationException.class, () -> store.get(key, observe));
            Files.writeString(app.resolve(".gitignore"), "cache/\n");
            git(app, "add", "-f", "--", store.fileFor(key).toString());
            assertThrows(ObservationException.class, () -> store.get(key, observe));
        } finally {
            // Only the disposable application created by this test.
            try (var paths = Files.walk(app)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static String digest(byte[] bytes) throws Exception
    {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}