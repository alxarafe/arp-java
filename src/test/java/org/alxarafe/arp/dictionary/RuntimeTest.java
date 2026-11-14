package org.alxarafe.arp.dictionary;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeTest {
    private static final CatalogBuilder BUILDER = new CatalogBuilder();
    private static final String PREFIX = "{\"apiVersion\":\"arp.dictionary/v1\",\"elements\":[";
    static Source json(String elements) { return Source.utf8("provider", "memory", Source.Encoding.JSON, PREFIX + elements + "]}"); }
    static Source yaml(String text) { return Source.utf8("provider", "yaml", Source.Encoding.YAML, text); }
    static Catalog catalog(String elements) {
        var result = BUILDER.buildCatalog(List.of(json(elements)));
        assertTrue(result.valid(), () -> result.issues().toString());
        return result.catalog().orElseThrow();
    }
    static void rejected(Source source, Code code) {
        var result = BUILDER.buildCatalog(List.of(source));
        assertFalse(result.valid()); assertTrue(result.catalog().isEmpty());
        assertEquals(code, result.issues().getFirst().code());
    }

    @Test void sourceDefensivelyCopiesBytes() {
        byte[] bytes = json("{\"id\":\"copy.x\",\"type\":\"BOOLEAN\"}").content();
        var source = new Source("p", "s", Source.Encoding.JSON, bytes);
        Arrays.fill(bytes, (byte) 'x');
        byte[] exposed = source.content(); Arrays.fill(exposed, (byte) 'y');
        assertTrue(BUILDER.buildCatalog(List.of(source)).valid());
    }

    @Test void descriptorsAndNestedCollectionsAreImmutable() {
        var snapshot = catalog("{\"id\":\"immutable.x\",\"type\":\"ENUM\",\"enumValues\":[\"red\",\"blue\"],\"constraints\":{\"maximumLength\":8}}");
        var descriptor = snapshot.resolve("immutable.x").descriptor().orElseThrow();
        assertThrows(UnsupportedOperationException.class, () -> descriptor.constraints().put("maximumLength", BigInteger.ZERO));
        assertThrows(UnsupportedOperationException.class, () -> descriptor.enumValues().clear());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.projection().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.ids().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.validate("immutable.x", "missing").issues().clear());
        assertTrue(snapshot.validate("immutable.x", "blue").valid());
    }

    @Test void failedBuildExposesNoPartialCatalog() {
        var source = json("{\"id\":\"old.x\",\"type\":\"BOOLEAN\"}");
        var old = BUILDER.buildCatalog(List.of(source)).catalog().orElseThrow();
        var result = BUILDER.buildCatalog(List.of(source, json("{\"id\":\"new.x\",\"type\":\"TEXT\"}"), source));
        assertFalse(result.valid()); assertTrue(result.catalog().isEmpty());
        assertFalse(old.resolve("new.x").valid()); assertTrue(old.validate("old.x", true).valid());
        assertEquals("provider", result.issues().getFirst().provenance().providerId());
    }

    @Test void isolatedCatalogsCanAssignDifferentTypesToTheSameIdentity() {
        var booleanCatalog = catalog("{\"id\":\"context.x\",\"type\":\"BOOLEAN\"}");
        var stringCatalog = catalog("{\"id\":\"context.x\",\"type\":\"STRING\"}");
        assertTrue(booleanCatalog.validate("context.x", true).valid());
        assertFalse(stringCatalog.validate("context.x", true).valid());
        assertTrue(stringCatalog.validate("context.x", "true").valid());
        assertFalse(booleanCatalog.validate("context.x", "true").valid());
    }

    @Test void concurrentBuildsAndValidationDoNotShareState() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            var start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var jobs = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 32; i++) {
                    int index = i;
                    jobs.add(executor.submit(() -> {
                        start.await();
                        String type = index % 2 == 0 ? "BOOLEAN" : "STRING";
                        Object value = index % 2 == 0 ? Boolean.TRUE : "exact";
                        var result = BUILDER.buildCatalog(List.of(json("{\"id\":\"parallel.x\",\"type\":\"" + type + "\"}")));
                        var snapshot = result.catalog().orElseThrow();
                        for (int n = 0; n < 300; n++) {
                            assertEquals(type, snapshot.resolve("parallel.x").descriptor().orElseThrow().type().name());
                            assertTrue(snapshot.validate("parallel.x", value).valid());
                            assertFalse(snapshot.resolve("other.x").valid());
                        }
                        return null;
                    }));
                }
                start.countDown(); for (var job : jobs) job.get();
            }
        });
    }

    @Test void readersSeeCompleteGenerationsDuringConcurrentPublication() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            var first = json("{\"id\":\"generation.a\",\"type\":\"STRING\"},{\"id\":\"generation.b\",\"type\":\"STRING\"}");
            var second = json("{\"id\":\"generation.a\",\"type\":\"BOOLEAN\"},{\"id\":\"generation.b\",\"type\":\"BOOLEAN\"}");
            var active = new AtomicReference<>(BUILDER.buildCatalog(List.of(first)).catalog().orElseThrow());
            var start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var readers = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 8; i++) readers.add(executor.submit(() -> {
                    start.await();
                    for (int n = 0; n < 1500; n++) {
                        var snapshot = active.get();
                        assertEquals(snapshot.resolve("generation.a").descriptor().orElseThrow().type(), snapshot.resolve("generation.b").descriptor().orElseThrow().type());
                        assertEquals(2, snapshot.ids().size());
                    }
                    return null;
                }));
                start.countDown();
                for (int n = 0; n < 100; n++) {
                    var source = n % 2 == 0 ? second : first;
                    var result = BUILDER.buildCatalog(List.of(source));
                    active.set(result.catalog().orElseThrow());
                    Catalog previous = active.get();
                    var failed = BUILDER.buildCatalog(List.of(source, source));
                    failed.catalog().ifPresent(active::set);
                    assertSame(previous, active.get());
                }
                for (var reader : readers) reader.get();
            }
        });
    }

    @Test void exactOneMiBSourceIsAccepted() {
        String base = PREFIX + "{\"id\":\"limit.x\",\"type\":\"STRING\",\"label\":\"\"}]}";
        int fill = Limits.DEFAULT.maxDocumentBytes() - base.getBytes(StandardCharsets.UTF_8).length;
        String document = base.replace("\"label\":\"\"", "\"label\":\"" + "x".repeat(fill) + "\"");
        var source = Source.utf8("p", "oneMiB", Source.Encoding.JSON, document);
        assertEquals(1_048_576, source.content().length); assertTrue(BUILDER.buildCatalog(List.of(source)).valid());
        rejected(Source.utf8("p", "overflow", Source.Encoding.JSON, document + " "), Code.DICTIONARY_LIMIT_EXCEEDED);
    }

    @Test void twoThousandFortyEightDefinitionsAreAccepted() {
        var definitions = new ArrayList<String>();
        for (int i = 0; i < 2048; i++) definitions.add("{\"id\":\"limit.x" + i + "\",\"type\":\"TEXT\"}");
        assertEquals(2048, BUILDER.buildCatalog(List.of(json(String.join(",", definitions)))).catalog().orElseThrow().ids().size());
        definitions.add("{\"id\":\"limit.extra\",\"type\":\"TEXT\"}");
        rejected(json(String.join(",", definitions)), Code.DICTIONARY_LIMIT_EXCEEDED);
    }

    @Test void configurableLimitsApplyToBothEncodings() {
        var small = new CatalogBuilder(new Limits(128, 1, 8));
        var json = json("{\"id\":\"limit.a\",\"type\":\"TEXT\"},{\"id\":\"limit.b\",\"type\":\"TEXT\"}");
        assertEquals(Code.DICTIONARY_LIMIT_EXCEEDED, small.buildCatalog(List.of(json)).issues().getFirst().code());
        var yaml = yaml("apiVersion: arp.dictionary/v1\nelements: [{id: limit.a, type: TEXT}, {id: limit.b, type: TEXT}]");
        assertEquals(Code.DICTIONARY_LIMIT_EXCEEDED, small.buildCatalog(List.of(yaml)).issues().getFirst().code());
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 1, 1));
    }

    @Test void deepCollectionsAreRejectedBeforeRecursiveConversion() {
        String nested = "[".repeat(130) + "0" + "]".repeat(130);
        rejected(Source.utf8("p", "deep", Source.Encoding.JSON, nested), Code.DICTIONARY_LIMIT_EXCEEDED);
        rejected(yaml(nested), Code.DICTIONARY_LIMIT_EXCEEDED);
    }

    @Test void duplicateNullAndEscapedKeysAreNotOverwritten() {
        for (var source : List.of(
                Source.utf8("p", "s", Source.Encoding.JSON, "{\"a\":null,\"\\u0061\":2}"),
                yaml("a: null\na: 2"), yaml("{a: null, a: 2}"), yaml("a:\n  b: null\n  b: 2"), yaml("{a: null, \"\\u0061\": 2}")))
            rejected(source, Code.DICTIONARY_DOCUMENT_INVALID);
    }

    @Test void everyForbiddenYamlConstructIsRejected() {
        for (String input : List.of("%UNKNOWN example\n---\n{}", "%YAML 1.1\n---\n{}", "%TAG !e! tag:example.com,2026:\n---\n{}",
                "a: &scalar x", "a: *missing", "!!java/object:java.lang.ProcessBuilder {}", "!local {}", "a: !!str x", "a: {<<: {b: 1}}", "{}\n---\n{}"))
            rejected(yaml(input), Code.DICTIONARY_DOCUMENT_INVALID);
    }

    @Test void quotedForbiddenSymbolsAndBlockTextAreSafeLiteralData() {
        var input = yaml("apiVersion: arp.dictionary/v1\nelements:\n  - id: literal.x\n    type: TEXT\n    label: '! & * <<: %YAML ---'\n    description: |\n      %YAML 1.2\n      !!java/object does not execute\n");
        var result = BUILDER.buildCatalog(List.of(input)); assertTrue(result.valid(), () -> result.issues().toString());
        assertTrue(result.catalog().orElseThrow().resolve("literal.x").descriptor().orElseThrow().description().orElseThrow().contains("!!java/object"));
    }

    @Test void yamlUsesJsonSchemaInsteadOfYamlElevenBooleansOrTimestamps() {
        var result = BUILDER.buildCatalog(List.of(yaml("apiVersion: arp.dictionary/v1\nelements: [{id: literal.x, type: ENUM, enumValues: [yes, no, on, off, 2026-11-14]}]")));
        assertTrue(result.valid());
        assertEquals(List.of("yes", "no", "on", "off", "2026-11-14"), result.catalog().orElseThrow().resolve("literal.x").descriptor().orElseThrow().enumValues());
    }

    @Test void yamlAndJsonProduceTheSameExactDescriptor() throws Exception {
        var json = json("{\"id\":\"exact.x\",\"type\":\"DECIMAL\",\"constraints\":{\"minimum\":\"0.00\",\"multipleOf\":\"0.001\",\"precision\":18,\"scale\":4,\"exclusiveMinimum\":false}}");
        var yaml = yaml("apiVersion: arp.dictionary/v1\nelements:\n  - id: exact.x\n    type: DECIMAL\n    constraints: {minimum: '0.00', multipleOf: '0.001', precision: 18, scale: 4, exclusiveMinimum: false}");
        var first = BUILDER.buildCatalog(List.of(json)).catalog().orElseThrow();
        var second = BUILDER.buildCatalog(List.of(yaml)).catalog().orElseThrow();
        assertEquals(first.resolve("exact.x").descriptor().orElseThrow().projection(), second.resolve("exact.x").descriptor().orElseThrow().projection());
        assertEquals("0.003", second.validate("exact.x", "0.00300").normalizedValue().orElseThrow());
    }

    @Test void integerMetadataBeyondLongIsPreservedThroughExport() throws Exception {
        String integer = "92233720368547758081234567890123456789";
        for (Source source : List.of(json("{\"id\":\"exact.x\",\"type\":\"TEXT\",\"constraints\":{\"maximumLength\":" + integer + "}}"),
                yaml("apiVersion: arp.dictionary/v1\nelements: [{id: exact.x, type: TEXT, constraints: {maximumLength: " + integer + "}}]"))) {
            var snapshot = BUILDER.buildCatalog(List.of(source)).catalog().orElseThrow();
            var descriptor = snapshot.resolve("exact.x").descriptor().orElseThrow();
            assertEquals(new BigInteger(integer), descriptor.constraints().get("maximumLength"));
            assertEquals(new BigInteger(integer), JsonSupport.reader(Limits.DEFAULT).readTree(descriptor.toJson()).path("constraints").path("maximumLength").bigIntegerValue());
            assertTrue(snapshot.validate("exact.x", "text").valid());
        }
    }

    @Test void integralDecimalMetadataRemainsExactAndRejectsFractionalKinds() {
        for (String integer : List.of("1.00", "0.1e1", "1e10000")) {
            var snapshot = catalog("{\"id\":\"exact.x\",\"type\":\"TEXT\",\"constraints\":{\"maximumLength\":" + integer + "}}");
            assertTrue(snapshot.validate("exact.x", "a").valid());
            assertTrue(snapshot.resolve("exact.x").descriptor().orElseThrow().constraints().get("maximumLength") instanceof BigDecimal);
        }
        rejected(json("{\"id\":\"exact.x\",\"type\":\"TEXT\",\"constraints\":{\"maximumLength\":1.01}}"), Code.DICTIONARY_ELEMENT_INVALID);
    }

    @Test void malformedUnicodeIsRejectedAtTheCorrectBoundary() {
        rejected(new Source("p", "s", Source.Encoding.JSON, new byte[]{(byte) 0xc0, (byte) 0xaf}), Code.DICTIONARY_DOCUMENT_INVALID);
        rejected(yaml("a: \"\\uD800\""), Code.DICTIONARY_DOCUMENT_INVALID);
        var result = catalog("{\"id\":\"unicode.x\",\"type\":\"STRING\"}").validate("unicode.x", "\ud800");
        assertEquals(Code.DICTIONARY_VALUE_TYPE, result.issues().getFirst().code()); assertTrue(result.normalizedValue().isEmpty());
    }

    @Test void portablePatternsRejectEveryAsciiControl() {
        for (int c = 0; c <= 127; c++) if (c < 32 || c == 127) {
            for (String pattern : List.of("" + (char) c, "[" + (char) c + "]", "\\" + (char) c)) {
                var exception = assertThrows(Rejected.class, () -> new PortablePattern(pattern));
                assertEquals(Code.DICTIONARY_ELEMENT_INVALID, exception.code);
            }
        }
    }

    @Test void ambiguousRepetitionsHaveBoundedExecutionAndNoFinalNewlineException() {
        var pattern = new PortablePattern("a*".repeat(128));
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertTrue(pattern.matches("a".repeat(4096)));
            assertFalse(pattern.matches("a".repeat(4095) + "\n"));
        });
    }

    @Test void utcArithmeticPreservesSevenDigitsAndIgnoresDefaultTimezone() {
        var snapshot = catalog("{\"id\":\"instant.x\",\"type\":\"DATETIME\"}");
        TimeZone previous = TimeZone.getDefault();
        try {
            for (String zone : List.of("Pacific/Apia", "Europe/Madrid", "America/New_York")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertEquals("2026-11-15T01:59:59.7654321Z", snapshot.validate("instant.x", "2026-11-14T23:59:59.7654321-02:00").normalizedValue().orElseThrow());
            }
        } finally { TimeZone.setDefault(previous); }
        assertFalse(snapshot.validate("instant.x", "9999-12-31T23:59:59-00:01").valid());
        assertFalse(snapshot.validate("instant.x", "0001-01-01T00:00:00+00:01").valid());
    }

    @Test void catalogEnumerationUsesAsciiOrderAndNoDiscovery() {
        var snapshot = catalog("{\"id\":\"order.z\",\"type\":\"TEXT\"},{\"id\":\"order.a\",\"type\":\"TEXT\"},{\"id\":\"order.A\",\"type\":\"TEXT\"}");
        assertEquals(List.of("order.A", "order.a", "order.z"), snapshot.ids());
        assertTrue(BUILDER.buildCatalog(List.of()).catalog().orElseThrow().ids().isEmpty());
    }

    @Test void parserDoesNotCoerceQuotedBoundsOrInvokeExternalReferences() {
        rejected(yaml("apiVersion: arp.dictionary/v1\nelements: [{id: exact.x, type: DECIMAL, constraints: {minimum: 0.1}}]"), Code.DICTIONARY_ELEMENT_INVALID);
        rejected(json("{\"id\":\"external.x\",\"type\":\"STRING\",\"$ref\":\"http://127.0.0.1:1/private\"}"), Code.DICTIONARY_ELEMENT_INVALID);
        rejected(yaml("apiVersion: arp.dictionary/v1\nelements: [{id: external.x, type: STRING, label: !include file:///etc/passwd}]"), Code.DICTIONARY_DOCUMENT_INVALID);
    }
}
