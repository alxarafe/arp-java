package org.alxarafe.arp.dictionary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import static org.junit.jupiter.api.Assertions.*;

/** Executes original expectations, never PHP output or copied fixture values. */
@Execution(ExecutionMode.SAME_THREAD)
class ConformanceTest {
    static final String SHA = "0172ab64742218b9f64a6ae96c43ebdfe690f4c6";
    static final Path ROOT = Path.of(System.getProperty("arp.contract.path")).toAbsolutePath().normalize();
    static final Path DIR = ROOT.resolve("fixtures/dictionary/v1");
    static final ObjectMapper JSON = JsonSupport.reader(new Limits(4_194_304, 2048, 128));
    static final CatalogBuilder BUILDER = new CatalogBuilder();
    static final Map<String, AtomicInteger> PASSED = new LinkedHashMap<>(), FAILED = new LinkedHashMap<>(), PLANNED = new LinkedHashMap<>();

    @BeforeAll static void pinnedContract() throws Exception {
        var process = new ProcessBuilder("git", "-C", ROOT.toString(), "rev-parse", "HEAD").redirectErrorStream(true).start();
        String actual = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor());
        assertEquals(SHA, actual, "Use the exact pinned contract checkout");
        assertEquals(SHA, Files.readString(Path.of("contract.ref")).trim());
        assertEquals(0, new ProcessBuilder("git", "-C", ROOT.toString(), "diff", "--quiet", "HEAD", "--", "fixtures", "schemas", "docs/features/arp-dict-001.md").start().waitFor(), "Contract inputs must not be edited");
    }

    static JsonNode read(Path path) throws Exception { return JSON.readTree(Files.readAllBytes(path)); }
    static Source source(JsonNode document) throws Exception {
        return new Source("fixture", "memory", Source.Encoding.JSON, JSON.writeValueAsBytes(document));
    }
    static BuildResult build(JsonNode document) throws Exception { return BUILDER.buildCatalog(List.of(source(document))); }
    static DynamicTest vector(String group, String name, Executable body) {
        PLANNED.computeIfAbsent(group, _ -> new AtomicInteger()).incrementAndGet();
        PASSED.computeIfAbsent(group, _ -> new AtomicInteger());
        FAILED.computeIfAbsent(group, _ -> new AtomicInteger());
        return DynamicTest.dynamicTest(group + ": " + name, () -> {
            try { body.execute(); PASSED.get(group).incrementAndGet(); }
            catch (Throwable failure) { FAILED.get(group).incrementAndGet(); throw failure; }
        });
    }
    static void compare(Map<String, Object> actual, JsonNode expected, boolean diagnostics) throws Exception {
        JsonNode projection = JSON.readTree(JsonSupport.write(actual));
        if (diagnostics && projection.has("issues")) {
            for (int i = 0; i < projection.get("issues").size(); i++) {
                if (!expected.path("issues").path(i).has("elementId"))
                    ((com.fasterxml.jackson.databind.node.ObjectNode) projection.get("issues").get(i)).remove("elementId");
            }
        }
        assertEquals(expected, projection);
    }

    @TestFactory Stream<DynamicTest> sharedFixtures() throws Exception {
        JsonNode manifest = read(DIR.resolve("manifest.json"));
        var names = new java.util.HashSet<String>(); manifest.path("sets").fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("catalog", "values", "definitions", "sources", "patterns", "catalogs"), names);
        assertEquals("ARP-DICT-001", manifest.path("feature").asText());
        assertEquals("arp.dictionary/v1", manifest.path("apiVersion").asText());
        var tests = new ArrayList<DynamicTest>();
        tests.add(vector("catalog", "all scalar types", () -> {
            var result = build(read(DIR.resolve(manifest.path("sets").path("catalog").asText())));
            assertTrue(result.valid(), () -> result.issues().toString());
            assertEquals(17, result.catalog().orElseThrow().ids().size());
        }));
        for (var value : read(DIR.resolve(manifest.path("sets").path("values").asText()))) {
            tests.add(vector("values", value.path("id").asText(), () -> {
                var document = JSON.createObjectNode().put("apiVersion", manifest.path("apiVersion").asText());
                document.putArray("elements").add(value.get("element"));
                var result = build(document); assertTrue(result.valid(), () -> result.issues().toString());
                Object input = JSON.treeToValue(value.get("value"), Object.class);
                compare(result.catalog().orElseThrow().validate(value.path("element").path("id").asText(), input).projection(), value.get("expected"), false);
            }));
        }
        for (var definition : read(DIR.resolve(manifest.path("sets").path("definitions").asText())))
            tests.add(vector("definitions", definition.path("id").asText(), () -> compare(build(definition.get("document")).projection(), definition.get("expected"), true)));
        for (var intake : read(DIR.resolve(manifest.path("sets").path("sources").asText()))) {
            tests.add(vector("sources", intake.path("id").asText(), () -> {
                Path path = ROOT.resolve(intake.path("path").asText()).normalize();
                assertTrue(path.startsWith(DIR));
                var source = new Source("fixture", intake.path("id").asText(), Source.Encoding.valueOf(intake.path("encoding").asText().toUpperCase(java.util.Locale.ROOT)), Files.readAllBytes(path));
                compare(BUILDER.buildCatalog(List.of(source)).projection(), intake.get("expected"), true);
            }));
        }
        for (var pattern : read(DIR.resolve(manifest.path("sets").path("patterns").asText()))) {
            var document = JSON.createObjectNode().put("apiVersion", manifest.path("apiVersion").asText());
            document.putArray("elements").addObject().put("id", "fixture.pattern").put("type", "STRING")
                    .putObject("constraints").set("pattern", pattern.get("pattern"));
            tests.add(vector("patterns", pattern.path("id").asText(), () -> {
                var result = build(document);
                Map<String, Object> actual = result.valid() ? Map.of("valid", true) : Map.of("valid", false, "code", result.issues().getFirst().code().name());
                compare(actual, pattern.get("expected"), false);
            }));
            int index = 0;
            for (var sample : pattern.path("samples")) {
                tests.add(vector("pattern-samples", pattern.path("id").asText() + "/" + index++, () -> {
                    var result = build(document); assertTrue(result.valid());
                    assertEquals(sample.path("matches").asBoolean(), result.catalog().orElseThrow().validate("fixture.pattern", sample.path("value").asText()).valid());
                }));
            }
        }
        for (var scenario : read(DIR.resolve(manifest.path("sets").path("catalogs").asText()))) {
            var contexts = new HashMap<String, Catalog>();
            var lastBuild = new HashMap<String, BuildResult>();
            int index = 0;
            for (var step : scenario.path("steps")) {
                String context = step.path("context").asText(), name = scenario.path("id").asText() + "/" + index++;
                String operation = step.path("operation").asText();
                switch (operation) {
                    case "buildCatalog" -> tests.add(vector("catalog-steps", name, () -> {
                        var sources = new ArrayList<Source>();
                        for (var s : step.path("sources")) sources.add(new Source(s.path("providerId").asText(), s.path("sourceId").asText(), Source.Encoding.JSON, JSON.writeValueAsBytes(s.get("document"))));
                        var result = BUILDER.buildCatalog(sources);
                        lastBuild.put(context, result); result.catalog().ifPresent(catalog -> contexts.put(context, catalog));
                        compare(result.projection(), step.get("expected"), true);
                    }));
                    case "resolve" -> {
                        tests.add(vector("catalog-steps", name, () -> compare(contexts.get(context).resolve(step.path("elementId").asText()).projection(), step.get("expected"), false)));
                        if (step.has("descriptorProjection")) tests.add(vector("descriptors", name, () -> compare(contexts.get(context).resolve(step.path("elementId").asText()).descriptor().orElseThrow().projection(), step.get("descriptorProjection"), false)));
                    }
                    case "assertRejectedGeneration" -> tests.add(vector("catalog-steps", name, () -> compare(Map.of("published", lastBuild.get(context).catalog().isPresent()), step.get("expected"), false)));
                    default -> fail("Unknown fixture operation " + operation);
                }
            }
        }
        return tests.stream();
    }

    @AfterAll static void report() throws Exception {
        var groups = new LinkedHashMap<String, Object>();
        int passed = 0, failed = 0, notExecuted = 0;
        for (String group : PASSED.keySet()) {
            int p = PASSED.get(group).get(), f = FAILED.get(group).get(); passed += p; failed += f;
            int pending = PLANNED.get(group).get() - p - f; notExecuted += pending;
            groups.put(group, Map.of("passed", p, "failed", f, "notExecuted", pending));
        }
        var report = Map.of("contractSha", SHA, "passed", passed, "failed", failed, "notExecuted", notExecuted, "groups", groups);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/conformance-summary.json"), JsonSupport.write(report) + "\n");
        System.out.println("Shared fixture assertions: " + passed + " passed, " + failed + " failed. " + JsonSupport.write(groups));
    }
}
