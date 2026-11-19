package org.alxarafe.arp.demo;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.alxarafe.arp.dictionary.Catalog;
import org.alxarafe.arp.dictionary.CatalogBuilder;
import org.alxarafe.arp.dictionary.Source;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Local demo transport only; no HTTP dependency is added to the library.
 */
public final class DictionaryHost
{
    private static final int LIMIT = 16_384;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(LIMIT)
                    .maxNumberLength(LIMIT).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final Catalog catalog;
    private final String origin;
    private final Map<String, Object> info;

    private DictionaryHost() throws Exception
    {
        origin = System.getenv().getOrDefault("ARP_DEMO_ORIGIN", "http://127.0.0.1:4173");
        URI uri = URI.create(origin);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !uri.getRawPath().isEmpty() || !origin.equals(uri.getScheme() + "://" + uri.getRawAuthority()))
        {
            throw new IllegalArgumentException("ARP_DEMO_ORIGIN must be one exact http(s) origin");
        }
        Path root = Path.of(System.getenv().getOrDefault("ARP_CONTRACT_PATH", "/contract"));
        var sources = new ArrayList<Source>();
        var hashes = new ArrayList<Map<String, String>>();
        for (String path : List.of("fixtures/dictionary/v1/catalog.json",
                                   "fixtures/dictionary/v1/sources/portable-equivalent.json")) {
            byte[] bytes = Files.readAllBytes(root.resolve(path));
            sources.add(new Source("arp-demo", path, Source.Encoding.JSON, bytes));
            hashes.add(Map.of("path", path, "sha256",
                              HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
        }
        var build = new CatalogBuilder().buildCatalog(sources);
        if (!build.valid()) {
            throw new IllegalStateException("Demo dictionary sources are invalid");
        }
        catalog = build.catalog().orElseThrow();
        info = Map.of("demoProtocol", "arp-demo-java/1", "runtime", "java",
                      "libraryContractRef", Files.readString(Path.of("contract.ref")).trim(),
                      "capabilities", List.of("dictionary.list", "dictionary.describe", "dictionary.validate"),
                      "sources", hashes);
    }

    public static void main(String[] args) throws Exception
    {
        var host = new DictionaryHost();
        var server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 0);
        server.createContext("/", host::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1)));
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException
    {
        try (exchange) {
            try {
                route(exchange);
            } catch (InputFailure failure) {
                error(exchange, failure.status, failure.code);
            } catch (Exception failure) {
                failure.printStackTrace(System.err);
                error(exchange, 500, "DEMO_EXECUTION_FAILURE");
            }
        }
    }

    private void route(HttpExchange exchange) throws Exception
    {
        String supplied = exchange.getRequestHeaders().getFirst("Origin");
        boolean allowed = origin.equals(supplied);
        if (allowed) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().set("Vary", "Origin");
        }
        String method = exchange.getRequestMethod();
        if (method.equals("OPTIONS") && supplied != null
                && exchange.getRequestHeaders().containsKey("Access-Control-Request-Method"))
        {
            if (allowed) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            }
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (supplied != null && !allowed) {
            throw new InputFailure(403, "DEMO_ORIGIN_NOT_ALLOWED");
        }
        String path = exchange.getRequestURI().getPath();
        String prefix = "/demo/dictionary/elements/";
        if (path.equals("/demo/info")) {
            requireMethod(method, "GET");
            send(exchange, 200, info);
        } else if (path.equals("/demo/dictionary/elements")) {
            requireMethod(method, "GET");
            send(exchange, 200, Map.of("elements", catalog.ids().stream().map(id ->
                                                                                      Map.of("id",
                                                                                             id,
                                                                                             "type",
                                                                                             catalog.resolve(id).descriptor().orElseThrow().type().name())).toList()));
        } else if (path.startsWith(prefix)) {
            String suffix = path.substring(prefix.length());
            boolean validation = suffix.endsWith("/validate");
            String id = validation ? suffix.substring(0, suffix.length() - "/validate".length()) : suffix;
            if (id.isEmpty() || id.contains("/")) {
                throw new InputFailure(404, "DEMO_HTTP_ERROR");
            }
            requireMethod(method, validation ? "POST" : "GET");
            if (validation) {
                Object value = input(exchange);
                send(exchange, catalog.resolve(id).valid() ? 200 : 404, catalog.validate(id, value).projection());
            } else {
                var result = catalog.resolve(id);
                if (result.valid()) {
                    sendJson(exchange, 200, result.descriptor().orElseThrow().toJson());
                } else {
                    send(exchange, 404, result.projection());
                }
            }
        } else {
            throw new InputFailure(404, "DEMO_HTTP_ERROR");
        }
    }

    private static Object input(HttpExchange exchange) throws IOException, InputFailure
    {
        String content = exchange.getRequestHeaders().getFirst("Content-Type");
        String media = content == null ? "" : content.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (!(media.equals("application/json") || media.startsWith("application/") && media.endsWith("+json"))) {
            throw new InputFailure(415, "DEMO_JSON_REQUIRED");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(LIMIT + 1);
        if (bytes.length > LIMIT) {
            throw new InputFailure(413, "DEMO_REQUEST_REJECTED");
        }
        JsonNode root;
        String text;
        int fields = 0;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            root = JSON.readTree(text);
            if (root == null || root.isMissingNode()) {
                throw new InputFailure(400, "DEMO_INVALID_JSON");
            }
            if (!root.isObject()) {
                throw new InputFailure(400, "DEMO_INPUT_SHAPE");
            }
            // A separate token pass preserves duplicate root fields that tree parsing would discard.
            try (var parser = JSON.createParser(text)) {
                int depth = 0;
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                        depth++;
                    } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                        depth--;
                    } else if (token == JsonToken.FIELD_NAME || token == JsonToken.VALUE_STRING) {
                        unicode(parser.getText());
                        if (token == JsonToken.FIELD_NAME && depth == 1) {
                            fields++;
                        }
                    }
                }
            }
        } catch (JsonProcessingException | CharacterCodingException failure) {
            throw new InputFailure(400, "DEMO_INVALID_JSON");
        }
        if (fields != 1 || !root.has("value")) {
            throw new InputFailure(400, "DEMO_INPUT_SHAPE");
        }
        JsonNode value = root.get("value");
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        return value.isNull() ? null : value; // Library rejects incompatible kinds; no numeric coercion.
    }

    private static void unicode(String text) throws InputFailure
    {
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (Character.isHighSurrogate(value)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new InputFailure(400, "DEMO_INVALID_JSON");
                }
            } else if (Character.isLowSurrogate(value)) {
                throw new InputFailure(400, "DEMO_INVALID_JSON");
            }
        }
    }

    private static void requireMethod(String actual, String expected) throws InputFailure
    {
        if (!actual.equals(expected)) {
            throw new InputFailure(405, "DEMO_HTTP_ERROR");
        }
    }

    private static void error(HttpExchange exchange, int status, String code) throws IOException
    {
        send(exchange, status, Map.of("valid", false, "issues", List.of(Map.of("code", code))));
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException
    {
        sendJson(exchange, status, JSON.writeValueAsString(body));
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class InputFailure extends Exception
    {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final String code;

        private InputFailure(int status, String code)
        {
            this.status = status;
            this.code = code;
        }
    }
}