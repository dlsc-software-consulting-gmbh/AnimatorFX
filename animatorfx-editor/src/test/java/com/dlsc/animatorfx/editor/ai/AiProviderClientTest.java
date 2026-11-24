package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.io.Json;
import com.dlsc.animatorfx.model.io.JsonWriter;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AiProviderClientTest {

    @Test
    void logsProviderRequestsAndResponsesWithoutExposingTheKey() throws Exception {
        Logger logger = Logger.getLogger("com.dlsc.animatorfx.editor.ai.traffic");
        var records = new CopyOnWriteArrayList<String>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try {
            for (AiProviderClient.Provider provider : AiProviderClient.Provider.values()) {
                try (ServerSocket server = new ServerSocket(0)) {
                    String text = "{\"reply\":\"rendered test-key\",\"operations\":[]}";
                    String response = provider == AiProviderClient.Provider.ANTHROPIC
                            ? anthropicResponse(text)
                            : JsonWriter.write(Map.of("status", "completed", "output", java.util.List.of(Map.of(
                                    "type", "message", "content", java.util.List.of(Map.of(
                                            "type", "output_text", "text", text))))));
                    serve(server, 200, response);
                    client(server).edit(provider, "model", "test-key", "move test-key ball").get(5, TimeUnit.SECONDS);
                }
            }
            try (ServerSocket server = new ServerSocket(0)) {
                serve(server, 429, "{\"error\":{\"message\":\"quota for test-key exceeded\"}}");
                assertThrows(CompletionException.class, () -> client(server).generate(
                        AiProviderClient.Provider.OPENAI, "model", "test-key", "a scene",
                        Map.of("type", "object")).join());
            }
            String log = String.join("\n", records);
            assertTrue(log.contains("move "));
            assertTrue(log.contains(" ball"));
            assertTrue(log.contains("rendered "));
            assertTrue(log.contains("429"));
            assertTrue(log.contains("quota for"));
            assertTrue(log.contains("[redacted]"));
            assertFalse(log.contains("test-key"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void logsModelListRequestsAndResponsesWithoutExposingTheKey() throws Exception {
        Logger logger = Logger.getLogger("com.dlsc.animatorfx.editor.ai.traffic");
        var records = new CopyOnWriteArrayList<String>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try (ServerSocket server = new ServerSocket(0)) {
            serve(server, 200, "{\"data\":[{\"id\":\"model-test-key\"}]}");
            URI endpoint = URI.create("http://localhost:" + server.getLocalPort() + "/models");
            var catalog = new AiModelCatalog(HttpClient.newHttpClient(), endpoint, endpoint);
            catalog.listModels(AiProviderClient.Provider.OPENAI, "test-key").get(5, TimeUnit.SECONDS);
            String log = String.join("\n", records);
            assertTrue(log.contains("GET"));
            assertTrue(log.contains("/models"));
            assertTrue(log.contains("model-"));
            assertTrue(log.contains("[redacted]"));
            assertFalse(log.contains("test-key"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void redactsJsonEscapedKeysInRequestAndNestedResponseBodies() throws Exception {
        Logger logger = Logger.getLogger("com.dlsc.animatorfx.editor.ai.traffic");
        var records = new CopyOnWriteArrayList<String>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        String key = "test\"secret";
        try (ServerSocket server = new ServerSocket(0)) {
            serve(server, 200, anthropicResponse("{\"reply\":\"test\\\"secret\",\"operations\":[]}"));
            client(server).edit(AiProviderClient.Provider.ANTHROPIC, "model", key,
                    "move test\"secret ball").get(5, TimeUnit.SECONDS);
            String log = String.join("\n", records);
            assertTrue(log.contains("move "));
            assertTrue(log.contains("[redacted]"));
            assertFalse(log.contains(key));
            assertFalse(log.contains("test\\\"secret"));
            assertFalse(log.contains("test\\\\\\\"secret"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void openAiReturnsStructuredTextAndSendsStrictSchema() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<String> request = serve(server, 200,
                    """
                    {"status":"completed","output":[{"type":"message","content":[
                    {"type":"output_text","text":"{\\"version\\":1}"}]}]}
                    """);
            AiProviderClient client = client(server);
            assertEquals("{\"version\":1}", client.generate(AiProviderClient.Provider.OPENAI, "model",
                    "test-key", "draw a circle", Map.of("type", "object")).get(5, TimeUnit.SECONDS));
            String sent = request.get(5, TimeUnit.SECONDS);
            assertTrue(sent.startsWith("POST /test/responses "));
            assertTrue(sent.contains("Authorization: Bearer test-key") || sent.contains("authorization: Bearer test-key"));
            assertTrue(sent.contains("draw a circle"));
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) Json.parse(sent.substring(sent.indexOf("\r\n\r\n") + 4));
            assertEquals("model", body.get("model"));
            assertEquals(Boolean.FALSE, body.get("store"), "Generation requests must not be stored by OpenAI");
            Map<?, ?> text = (Map<?, ?>) body.get("text");
            Map<?, ?> format = (Map<?, ?>) text.get("format");
            assertEquals("json_schema", format.get("type"));
            assertEquals(Boolean.TRUE, format.get("strict"));
        }
    }

    @Test
    void anthropicReturnsStructuredText() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<String> request = serve(server, 200, anthropicResponse("{\"version\":1}"));
            assertEquals("{\"version\":1}", client(server).generate(AiProviderClient.Provider.ANTHROPIC, "model",
                    "test-key", "draw a circle", Map.of("type", "object")).get(5, TimeUnit.SECONDS));
            String sent = request.get(5, TimeUnit.SECONDS);
            assertTrue(sent.startsWith("POST /test/messages "));
            assertTrue(sent.toLowerCase().contains("x-api-key: test-key"));
            assertTrue(sent.contains("draw a circle"));
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) Json.parse(sent.substring(sent.indexOf("\r\n\r\n") + 4));
            Map<?, ?> output = (Map<?, ?>) body.get("output_config");
            Map<?, ?> format = (Map<?, ?>) output.get("format");
            assertEquals("json_schema", format.get("type"));
            Map<?, ?> wrapper = (Map<?, ?>) format.get("schema");
            Map<?, ?> properties = (Map<?, ?>) wrapper.get("properties");
            assertEquals("string", ((Map<?, ?>) properties.get("animation")).get("type"));
            assertEquals(1, properties.size());
            assertTrue(body.get("system").toString().contains("\"type\": \"object\""));
        }
    }

    @Test
    void sendsTheEntireAnimationSchemaToOpenAi() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            var request = serve(server, 200,
                    "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{}\"}]}]}");
            client(server).generate(AiProviderClient.Provider.OPENAI, "model", "test-key", "a scene", AiAnimation.schema())
                    .get(5, TimeUnit.SECONDS);
            String sent = request.get(5, TimeUnit.SECONDS);
            Map<?, ?> body = (Map<?, ?>) Json.parse(sent.substring(sent.indexOf("\r\n\r\n") + 4));
            Map<?, ?> format = (Map<?, ?>) ((Map<?, ?>) body.get("text")).get("format");
            assertEquals(Json.parse(JsonWriter.write(AiAnimation.schema())), format.get("schema"));
        }
    }

    @Test
    void interactiveRequestsUseTheEditContractForBothProviders() throws Exception {
        String edits = "{\"reply\":\"Done\",\"operations\":[]}";
        for (AiProviderClient.Provider provider : AiProviderClient.Provider.values()) {
            try (ServerSocket server = new ServerSocket(0)) {
                String response = provider == AiProviderClient.Provider.ANTHROPIC
                        ? anthropicResponse(edits)
                        : JsonWriter.write(Map.of("status", "completed", "output", java.util.List.of(Map.of(
                                "type", "message", "content", java.util.List.of(Map.of(
                                        "type", "output_text", "text", edits))))));
                var request = serve(server, 200, response);
                assertEquals(edits, client(server).edit(provider, "model", "test-key", "{\"instruction\":\"move it\"}")
                        .get(5, TimeUnit.SECONDS));
                String sent = request.get(5, TimeUnit.SECONDS);
                Map<?, ?> body = (Map<?, ?>) Json.parse(sent.substring(sent.indexOf("\r\n\r\n") + 4));
                assertTrue(body.toString().contains("editing assistant"));
                assertTrue(body.toString().contains("move it"));
                assertTrue(body.toString().contains("native project format"));
                assertTrue(body.toString().contains("only for new fragments"));
                Map<?, ?> format = (Map<?, ?>) ((Map<?, ?>) body.get(
                        provider == AiProviderClient.Provider.OPENAI ? "text" : "output_config")).get("format");
                if (provider == AiProviderClient.Provider.OPENAI) {
                    assertEquals(Json.parse(JsonWriter.write(AiEdits.schema())), format.get("schema"));
                } else {
                    assertTrue(body.get("system").toString().contains(JsonWriter.write(AiEdits.schema())));
                }
            }
        }
    }

    @Test
    void anthropicUsesASmallSchemaAndIncludesTheFullSchemaInInstructions() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            var request = serve(server, 200, anthropicResponse("{}"));
            assertEquals("{}", client(server).generate(AiProviderClient.Provider.ANTHROPIC, "model",
                    "test-key", "a scene", AiAnimation.schema()).get(5, TimeUnit.SECONDS));
            String sent = request.get(5, TimeUnit.SECONDS);
            Map<?, ?> body = (Map<?, ?>) Json.parse(sent.substring(sent.indexOf("\r\n\r\n") + 4));
            Map<?, ?> format = (Map<?, ?>) ((Map<?, ?>) body.get("output_config")).get("format");
            Map<?, ?> wrapper = (Map<?, ?>) format.get("schema");
            Map<?, ?> properties = (Map<?, ?>) wrapper.get("properties");
            assertEquals(1, properties.size());
            assertEquals("string", ((Map<?, ?>) properties.get("animation")).get("type"));
            assertEquals(java.util.List.of("animation"), wrapper.get("required"));
            assertEquals(Boolean.FALSE, wrapper.get("additionalProperties"));
            assertTrue(body.get("system").toString().contains(JsonWriter.write(AiAnimation.schema())));
        }
    }

    @Test
    void anthropicRejectsMalformedAnimationWrappers() throws Exception {
        for (String text : new String[]{"{", "{}", "{\"animation\":42}", "{\"animation\":\"{}\",\"extra\":1}"}) {
            try (ServerSocket server = new ServerSocket(0)) {
                serve(server, 200, JsonWriter.write(Map.of("stop_reason", "end_turn", "content",
                        java.util.List.of(Map.of("type", "text", "text", text)))));
                var failure = assertThrows(CompletionException.class, () ->
                        client(server).generate(AiProviderClient.Provider.ANTHROPIC, "model",
                                "test-key", "scene", AiAnimation.schema()).join());
                assertTrue(failure.getCause().getMessage().contains("invalid"));
            }
        }
    }

    @Test
    void anthropicAnimationStillPassesThroughLocalValidation(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
            throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            serve(server, 200, anthropicResponse("{\"version\":1,\"version\":2}"));
            AiProviderClient provider = client(server);
            AiGenerationService service = new AiGenerationService(directory.resolve("prefs"), name -> "test-key",
                    (selected, model, key, prompt) -> provider.generate(selected, model, key, prompt, AiAnimation.schema()));
            AiSettings settings = new AiSettings(AiProviderClient.Provider.ANTHROPIC, "openai", "claude");
            var failure = assertThrows(CompletionException.class, () -> service.generate(settings, "scene").join());
            assertTrue(failure.getCause().getMessage().contains("invalid animation"));
            assertTrue(failure.getCause().getMessage().toLowerCase().contains("duplicate"));
        }
    }

    private static String anthropicResponse(String animation) {
        String text = JsonWriter.write(Map.of("animation", animation));
        return JsonWriter.write(Map.of("stop_reason", "end_turn", "content",
                java.util.List.of(Map.of("type", "text", "text", text))));
    }

    @Test
    void cancellingGenerationClosesTheProviderConnection() throws Exception {
        for (AiProviderClient.Provider provider : AiProviderClient.Provider.values()) {
            try (ServerSocket server = new ServerSocket(0)) {
                var received = new CompletableFuture<Void>();
                var closed = CompletableFuture.runAsync(() -> {
                    try (var socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        int length = 0;
                        for (String line; !(line = input.readLine()).isEmpty();) {
                            if (line.toLowerCase().startsWith("content-length:")) {
                                length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                            }
                        }
                        while (length-- > 0) input.read();
                        received.complete(null);
                        assertEquals(-1, input.read());
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
                var request = client(server).generate(provider, "model",
                        "test-key", "draw a circle", Map.of("type", "object"));
                received.get(5, TimeUnit.SECONDS);
                request.cancel(true);
                closed.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void refusalAndHttpErrorsDoNotExposeProviderContent() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            serve(server, 401, "{\"error\":\"test-key draw a circle\"}");
            CompletionException error = assertThrows(CompletionException.class, () ->
                    client(server).generate(AiProviderClient.Provider.OPENAI, "model", "test-key",
                            "draw a circle", Map.of()).join());
            assertFalse(error.toString().contains("test-key"));
            assertFalse(error.toString().contains("draw a circle"));
        }
        try (ServerSocket server = new ServerSocket(0)) {
            serve(server, 200, "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"no\"}]}]}");
            assertThrows(CompletionException.class, () ->
                    client(server).generate(AiProviderClient.Provider.OPENAI, "model", "test-key",
                            "draw a circle", Map.of()).join());
        }
    }

    @Test
    void reportsProviderErrorDetailsWithoutExposingCredentialsOrPrompt() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            serve(server, 400, """
                    {"error":{"type":"invalid_request_error","message":"Unsupported schema for model; test-key draw a circle"}}
                    """);
            CompletionException error = assertThrows(CompletionException.class, () ->
                    client(server).generate(AiProviderClient.Provider.OPENAI, "model", "test-key",
                            "draw a circle", Map.of()).join());
            String message = error.getCause().getMessage();
            assertTrue(message.contains("HTTP 400"));
            assertTrue(message.contains("Unsupported schema"));
            assertFalse(message.contains("test-key"));
            assertFalse(message.contains("draw a circle"));
        }
    }

    @Test
    void providerDetailsReachTheGenerationService(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
            throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            serve(server, 400, "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Schema is not supported\"}}");
            AiProviderClient provider = client(server);
            AiGenerationService service = new AiGenerationService(directory.resolve("prefs"), name -> "test-key",
                    (selected, model, key, prompt) -> provider.generate(selected, model, key, prompt, Map.of()));
            CompletionException error = assertThrows(CompletionException.class,
                    () -> service.generate("a scene").join());
            assertTrue(error.getCause().getMessage().contains("HTTP 400"));
            assertTrue(error.getCause().getMessage().contains("Schema is not supported"));
        }
    }

    @Test
    void reportsWhyAResponseWasIncomplete() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            serve(server, 200, "{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[]}");
            CompletionException error = assertThrows(CompletionException.class, () ->
                    client(server).generate(AiProviderClient.Provider.OPENAI, "model", "test-key",
                            "draw a circle", Map.of()).join());
            assertTrue(error.getCause().getMessage().contains("max_output_tokens"));
        }
    }

    @Test
    void incompleteAndOversizedResponsesAreRejected() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            serve(server, 200, "{\"status\":\"incomplete\",\"output\":[]}");
            assertThrows(CompletionException.class, () ->
                    client(server).generate(AiProviderClient.Provider.OPENAI, "model", "test-key",
                            "draw a circle", Map.of()).join());
        }
        try (ServerSocket server = new ServerSocket(0)) {
            serve(server, 200, "x".repeat(2_000_001));
            CompletionException failure = assertThrows(CompletionException.class, () ->
                    client(server).generate(AiProviderClient.Provider.ANTHROPIC, "model", "test-key",
                            "draw a circle", Map.of()).join());
            assertTrue(failure.getCause().getMessage().contains("exceeds the size limit"));
        }
    }

    @Test
    void refusalCannotBeBypassedByAnEarlierTextBlock() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            serve(server, 200, "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":["
                    + "{\"type\":\"output_text\",\"text\":\"{}\"},{\"type\":\"refusal\",\"refusal\":\"no\"}]}]}");
            assertThrows(CompletionException.class, () ->
                    client(server).generate(AiProviderClient.Provider.OPENAI, "model", "test-key",
                            "draw a circle", Map.of()).join());
        }
    }

    private static AiProviderClient client(ServerSocket server) {
        URI endpoint = URI.create("http://localhost:" + server.getLocalPort() + "/test");
        return new AiProviderClient(HttpClient.newHttpClient(), endpoint, endpoint);
    }

    private static CompletableFuture<String> serve(ServerSocket server, int status, String body) {
        return CompletableFuture.supplyAsync(() -> {
            try (var socket = server.accept()) {
                var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder headers = new StringBuilder();
                int length = 0;
                for (String line; !(line = input.readLine()).isEmpty();) {
                    headers.append(line).append("\r\n");
                    if (line.toLowerCase().startsWith("content-length:")) {
                        length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    }
                }
                char[] chars = new char[length];
                int offset = 0;
                while (offset < length) {
                    int count = input.read(chars, offset, length - offset);
                    if (count < 0) throw new IllegalStateException("Incomplete request");
                    offset += count;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                var out = socket.getOutputStream();
                out.write(("HTTP/1.1 " + status + " Result\r\nContent-Type: application/json\r\nContent-Length: "
                        + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(bytes);
                return headers + "\r\n" + new String(chars);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
