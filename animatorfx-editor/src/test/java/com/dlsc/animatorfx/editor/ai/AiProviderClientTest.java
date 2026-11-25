package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.io.Json;
import com.dlsc.animatorfx.model.io.JsonWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.Base64;
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
    void generationCollectsProviderReportedTokens() throws Exception {
        AiUsage usage = new AiUsage();
        try (ServerSocket server = new ServerSocket(0)) {
            serve(server, 200, "{\"status\":\"completed\",\"usage\":{\"input_tokens\":123,"
                    + "\"output_tokens\":45},\"output\":[{\"type\":\"message\",\"content\":["
                    + "{\"type\":\"output_text\",\"text\":\"{}\"}]}]}");
            var result = client(server).generate(AiProviderClient.Provider.OPENAI, "model", "key", "scene",
                    Map.of(), usage);
            assertEquals("{}", result.get(5, TimeUnit.SECONDS));
            AiUsage.Snapshot snapshot = usage.snapshot();
            assertEquals(123, snapshot.inputTokens());
            assertEquals(45, snapshot.outputTokens());
            assertTrue(snapshot.inputComplete());
            assertTrue(snapshot.outputComplete());
        }
    }

    @Test
    void aggregatesAnthropicGenerationAndReviewUsageIncludingCachedInput() throws Exception {
        AiUsage usage = new AiUsage();
        try (ServerSocket server = new ServerSocket(0)) {
            String text = JsonWriter.write(Map.of("animation", "{}"));
            serve(server, 200, JsonWriter.write(Map.of("stop_reason", "end_turn", "content",
                    List.of(Map.of("type", "text", "text", text)),
                    "usage", Map.of("input_tokens", 100, "cache_read_input_tokens", 300,
                            "cache_creation_input_tokens", 20, "output_tokens", 50))));
            client(server).generate(AiProviderClient.Provider.ANTHROPIC, "model", "key", "scene",
                    Map.of(), usage).get(5, TimeUnit.SECONDS);
            serve(server, 200, JsonWriter.write(Map.of("stop_reason", "end_turn", "content",
                    List.of(Map.of("type", "text", "text", text)),
                    "usage", Map.of("input_tokens", 80, "output_tokens", 10))));
            client(server).review(AiProviderClient.Provider.ANTHROPIC, "model", "key", "review",
                    List.of(new byte[] {1}), Map.of(), "review", usage).get(5, TimeUnit.SECONDS);
            AiUsage.Snapshot snapshot = usage.snapshot();
            assertEquals(500, snapshot.inputTokens());
            assertEquals(60, snapshot.outputTokens());
            assertEquals(2, snapshot.requests());
            assertTrue(snapshot.inputComplete());
            assertTrue(snapshot.outputComplete());
        }
    }

    @Test
    void incompleteProviderUsageIsNotReportedAsAnExactTotal() throws Exception {
        AiUsage usage = new AiUsage();
        try (ServerSocket server = new ServerSocket(0)) {
            serve(server, 200, "{\"status\":\"completed\",\"usage\":{\"input_tokens\":123},"
                    + "\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{}\"}]}]}");
            client(server).generate(AiProviderClient.Provider.OPENAI, "model", "key", "scene",
                    Map.of(), usage).get(5, TimeUnit.SECONDS);
            AiUsage.Snapshot snapshot = usage.snapshot();
            assertEquals(123, snapshot.inputTokens());
            assertTrue(snapshot.inputComplete());
            assertFalse(snapshot.outputComplete());
        }
    }

    @Test
    void concurrentGenerationRequestsKeepTokenUsageSeparate() throws Exception {
        AiUsage first = new AiUsage();
        AiUsage second = new AiUsage();
        try (ServerSocket firstServer = new ServerSocket(0); ServerSocket secondServer = new ServerSocket(0)) {
            serve(firstServer, 200, anthropicResponseWithUsage("{}", 100, 10));
            serve(secondServer, 200, anthropicResponseWithUsage("{}", 200, 20));
            var one = client(firstServer).generate(AiProviderClient.Provider.ANTHROPIC, "model", "key",
                    "first", Map.of(), first);
            var two = client(secondServer).generate(AiProviderClient.Provider.ANTHROPIC, "model", "key",
                    "second", Map.of(), second);
            CompletableFuture.allOf(one, two).get(5, TimeUnit.SECONDS);
            assertEquals(100, first.snapshot().inputTokens());
            assertEquals(10, first.snapshot().outputTokens());
            assertEquals(200, second.snapshot().inputTokens());
            assertEquals(20, second.snapshot().outputTokens());
        }
    }

    @Test
    void inFlightRequestsMakeUsageIncompleteUntilTheyFinish() throws Exception {
        AiUsage usage = new AiUsage();
        try (ServerSocket server = new ServerSocket(0)) {
            var accepted = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var listener = CompletableFuture.runAsync(() -> {
                try (var socket = server.accept()) {
                    accepted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
            var future = client(server).generate(AiProviderClient.Provider.OPENAI, "model", "key",
                    "scene", Map.of(), usage);
            try {
                assertTrue(accepted.await(5, TimeUnit.SECONDS));
                AiUsage.Snapshot snapshot = usage.snapshot();
                assertEquals(1, snapshot.requests());
                assertFalse(snapshot.inputComplete());
                assertFalse(snapshot.outputComplete());
            } finally {
                future.cancel(true);
                release.countDown();
                listener.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void generationRepairsAndVisualReviewAccumulateTheSameJobsUsage(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        String valid = """
                {"version":1,"name":"Mock","width":320,"height":240,"frameRate":30,"duration":1,
                 "background":{"r":0,"g":0,"b":0,"a":0},"layers":[]}
                """;
        AiUsage usage = new AiUsage();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (ServerSocket server = new ServerSocket(0)) {
            AiProviderClient client = client(server);
            AiGenerationService service = new AiGenerationService(directory.resolve("prefs"), ignored -> "key",
                    (provider, model, key, prompt) -> {
                        int call = calls.incrementAndGet();
                        serve(server, 200, anthropicResponseWithUsage(call == 1 ? "{}" : valid, call * 100, call * 10));
                        return client.generate(provider, model, key, prompt, AiAnimation.schema(), usage);
                    },
                    (provider, model, key, context, frames, schema, instructions) -> {
                        serve(server, 200, anthropicResponseWithUsage(
                                "{\"reply\":\"Looks good\",\"operations\":[]}", 300, 30));
                        return client.review(provider, model, key, context, frames, schema, instructions, usage);
                    }, file -> CompletableFuture.completedFuture(List.of(new byte[] {1})), ignored -> {});
            AiSettings settings = new AiSettings(AiProviderClient.Provider.ANTHROPIC, "unused", "model", true);
            assertEquals("Mock", service.generate(settings, "scene").get(5, TimeUnit.SECONDS).animation().name());
            AiUsage.Snapshot snapshot = usage.snapshot();
            assertEquals(3, snapshot.requests());
            assertEquals(600, snapshot.inputTokens());
            assertEquals(60, snapshot.outputTokens());
            assertTrue(snapshot.inputComplete());
            assertTrue(snapshot.outputComplete());
        }
    }

    private static String anthropicResponseWithUsage(String animation, int input, int output) {
        return JsonWriter.write(Map.of("stop_reason", "end_turn", "content",
                List.of(Map.of("type", "text", "text", JsonWriter.write(Map.of("animation", animation)))),
                "usage", Map.of("input_tokens", input, "output_tokens", output)));
    }

    @Test
    @EnabledIfSystemProperty(named = "animatorfx.slowAiTests", matches = "true")
    void generationAcceptsSlowResponsesFromBothProvidersBeforeAndAfterHeaders() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        try (ServerSocket openAiHeaders = new ServerSocket(0);
             ServerSocket openAiBody = new ServerSocket(0);
             ServerSocket anthropicHeaders = new ServerSocket(0);
             ServerSocket anthropicBody = new ServerSocket(0)) {
            String openAiResponse = JsonWriter.write(Map.of("status", "completed", "output", List.of(Map.of(
                    "type", "message", "content", List.of(Map.of("type", "output_text", "text", "{}"))))));
            String anthropicResponse = anthropicResponse("{}");
            serveSlowly(openAiHeaders, openAiResponse, false, release);
            serveSlowly(openAiBody, openAiResponse, true, release);
            serveSlowly(anthropicHeaders, anthropicResponse, false, release);
            serveSlowly(anthropicBody, anthropicResponse, true, release);
            var requests = List.of(
                    client(openAiHeaders).generate(AiProviderClient.Provider.OPENAI, "model", "key", "scene", Map.of()),
                    client(openAiBody).generate(AiProviderClient.Provider.OPENAI, "model", "key", "scene", Map.of()),
                    client(anthropicHeaders).generate(AiProviderClient.Provider.ANTHROPIC, "model", "key", "scene", Map.of()),
                    client(anthropicBody).generate(AiProviderClient.Provider.ANTHROPIC, "model", "key", "scene", Map.of()));
            try {
                for (var request : requests) assertEquals("{}", request.get(110, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                requests.forEach(request -> request.cancel(true));
            }
        }
    }

    private static void serveSlowly(ServerSocket server, String body, boolean sendHeaders, java.util.concurrent.CountDownLatch release) {
        CompletableFuture.runAsync(() -> {
            try (var socket = server.accept()) {
                var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                int length = 0;
                for (String line; !(line = input.readLine()).isEmpty();) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    }
                }
                while (length-- > 0) input.read();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                byte[] headers = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                var output = socket.getOutputStream();
                if (sendHeaders) {
                    output.write(headers);
                    output.write(bytes, 0, 1);
                    output.flush();
                }
                if (release.await(96, TimeUnit.SECONDS)) return;
                if (!sendHeaders) output.write(headers);
                output.write(bytes, sendHeaders ? 1 : 0, bytes.length - (sendHeaders ? 1 : 0));
                output.flush();
            } catch (Exception ex) {
                if (release.getCount() != 0) throw new RuntimeException(ex);
            }
        });
    }

    @Test
    void reviewSendsEveryFrameForBothProvidersWithoutLoggingImageData() throws Exception {
        byte[] first = "synthetic-image-one".getBytes(StandardCharsets.UTF_8);
        byte[] second = "synthetic-image-two".getBytes(StandardCharsets.UTF_8);
        byte[] third = "synthetic-image-three".getBytes(StandardCharsets.UTF_8);
        String encodedFirst = Base64.getEncoder().encodeToString(first);
        String encodedSecond = Base64.getEncoder().encodeToString(second);
        String encodedThird = Base64.getEncoder().encodeToString(third);
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
                    String result = "{\"review\":\"good\"}";
                    String response = provider == AiProviderClient.Provider.ANTHROPIC
                            ? anthropicResponse(result)
                            : JsonWriter.write(Map.of("status", "completed", "output", List.of(Map.of(
                                    "type", "message", "content", List.of(Map.of(
                                            "type", "output_text", "text", result))))));
                    var request = serve(server, 200, response);
                    assertEquals(result, client(server).review(provider, "model", "test-key", "review context",
                            List.of(first, second, third), Map.of("type", "object"), "review instructions")
                            .get(5, TimeUnit.SECONDS));
                    String sent = request.get(5, TimeUnit.SECONDS);
                    assertTrue(sent.contains(encodedFirst));
                    assertTrue(sent.contains(encodedSecond));
                    assertTrue(sent.contains(encodedThird));
                    assertTrue(sent.contains("image/png"));
                    assertTrue(sent.contains("review context"));
                    assertTrue(sent.contains("review instructions"));
                    Map<?, ?> body = (Map<?, ?>) Json.parse(sent.substring(sent.indexOf("\r\n\r\n") + 4));
                    List<?> messages = (List<?>) body.get(provider == AiProviderClient.Provider.OPENAI ? "input" : "messages");
                    Map<?, ?> user = (Map<?, ?>) messages.get(messages.size() - 1);
                    List<?> blocks = (List<?>) user.get("content");
                    assertEquals(4, blocks.size());
                    for (int i = 1; i < blocks.size(); i++) {
                        Map<?, ?> block = (Map<?, ?>) blocks.get(i);
                        String encoded = List.of(encodedFirst, encodedSecond, encodedThird).get(i - 1);
                        if (provider == AiProviderClient.Provider.OPENAI) {
                            assertEquals("input_image", block.get("type"));
                            assertEquals("data:image/png;base64," + encoded, block.get("image_url"));
                        } else {
                            assertEquals("image", block.get("type"));
                            Map<?, ?> source = (Map<?, ?>) block.get("source");
                            assertEquals("base64", source.get("type"));
                            assertEquals("image/png", source.get("media_type"));
                            assertEquals(encoded, source.get("data"));
                        }
                    }
                    Map<?, ?> format = (Map<?, ?>) ((Map<?, ?>) body.get(
                            provider == AiProviderClient.Provider.OPENAI ? "text" : "output_config")).get("format");
                    assertEquals("json_schema", format.get("type"));
                }
            }
            String log = String.join("\n", records);
            assertTrue(log.contains("review context"));
            assertFalse(log.contains(encodedFirst));
            assertFalse(log.contains(encodedSecond));
            assertFalse(log.contains(encodedThird));
            assertFalse(log.contains("data:image"));
            assertFalse(log.contains("test-key"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void reviewRejectsInvalidOrOversizedFramesWithoutSending() {
        AiProviderClient client = new AiProviderClient();
        for (List<byte[]> frames : List.of(List.<byte[]>of(), List.of(new byte[0]),
                List.of(new byte[1], new byte[1], new byte[1], new byte[1]),
                List.of(new byte[1_000_001]), List.of(new byte[500_001], new byte[500_000]),
                java.util.Arrays.asList((byte[]) null))) {
            assertThrows(CompletionException.class, () -> client.review(AiProviderClient.Provider.OPENAI,
                    "model", "key", "context", frames, Map.of("type", "object"), "instructions").join());
        }
        assertThrows(CompletionException.class, () -> client.review(AiProviderClient.Provider.OPENAI,
                "model", "key", "context", null, Map.of("type", "object"), "instructions").join());
    }

    @Test
    void reviewDoesNotRetryAndRedactsImagesEchoedInProviderErrors() throws Exception {
        byte[] frame = "private-image-data".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(frame);
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
                    server.setSoTimeout(500);
                    var request = serve(server, 429, JsonWriter.write(Map.of("error", Map.of(
                            "message", "bad data:image/png;base64," + encoded))));
                    var failure = assertThrows(CompletionException.class, () -> client(server).review(provider,
                            "model", "test-key", "review context", List.of(frame),
                            Map.of("type", "object"), "review instructions").join());
                    request.get(5, TimeUnit.SECONDS);
                    assertFalse(failure.toString().contains(encoded));
                    assertThrows(java.net.SocketTimeoutException.class, server::accept);
                }
            }
            String log = String.join("\n", records);
            assertFalse(log.contains(encoded));
            assertFalse(log.contains("data:image"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void reviewRedactsJsonEscapedImageDataInProviderResponses() throws Exception {
        Logger logger = Logger.getLogger("com.dlsc.animatorfx.editor.ai.traffic");
        var records = new CopyOnWriteArrayList<String>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try {
            for (byte[] frame : List.of(new byte[] {(byte) 255, (byte) 255, (byte) 255},
                    "private-image-data".getBytes(StandardCharsets.UTF_8))) {
                try (ServerSocket server = new ServerSocket(0)) {
                    String encoded = Base64.getEncoder().encodeToString(frame).replace("/", "\\/");
                    serve(server, 400, "{\"error\":{\"message\":\"data:image\\/png;base64," + encoded + "\"}}");
                    assertThrows(CompletionException.class, () -> client(server).review(AiProviderClient.Provider.OPENAI,
                            "model", "test-key", "context", List.of(frame), Map.of("type", "object"), "review").join());
                }
            }
            String log = String.join("\n", records);
            assertFalse(log.contains("data:image"));
            assertFalse(log.contains("\\/\\/\\/\\/"));
        } finally {
            logger.removeHandler(handler);
        }
    }

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
    void opus55GenerationRequestsALargerBudgetWithoutChangingOtherRequests() throws Exception {
        for (String model : List.of("claude-opus-5-5", "claude-sonnet-4-5")) {
            try (ServerSocket server = new ServerSocket(0)) {
                var request = serve(server, 200, anthropicResponse("{}"));
                client(server).generate(AiProviderClient.Provider.ANTHROPIC, model,
                        "test-key", "scene", Map.of()).get(5, TimeUnit.SECONDS);
                String sent = request.get(5, TimeUnit.SECONDS);
                Map<?, ?> body = (Map<?, ?>) Json.parse(sent.substring(sent.indexOf("\r\n\r\n") + 4));
                assertEquals(model.equals("claude-opus-5-5") ? 64000 : 16384,
                        ((Number) body.get("max_tokens")).intValue());
            }
        }
        try (ServerSocket server = new ServerSocket(0)) {
            var request = serve(server, 200, anthropicResponse("{}"));
            client(server).edit(AiProviderClient.Provider.ANTHROPIC, "claude-opus-5-5",
                    "test-key", "scene").get(5, TimeUnit.SECONDS);
            String sent = request.get(5, TimeUnit.SECONDS);
            Map<?, ?> body = (Map<?, ?>) Json.parse(sent.substring(sent.indexOf("\r\n\r\n") + 4));
            assertEquals(16384, ((Number) body.get("max_tokens")).intValue());
        }
        try (ServerSocket server = new ServerSocket(0)) {
            var request = serve(server, 200, anthropicResponse("{}"));
            client(server).review(AiProviderClient.Provider.ANTHROPIC, "claude-opus-5-5",
                    "test-key", "scene", List.of(new byte[] {1}), Map.of(), "review").get(5, TimeUnit.SECONDS);
            String sent = request.get(5, TimeUnit.SECONDS);
            Map<?, ?> body = (Map<?, ?>) Json.parse(sent.substring(sent.indexOf("\r\n\r\n") + 4));
            assertEquals(16384, ((Number) body.get("max_tokens")).intValue());
        }
    }

    @Test
    void anthropicTokenExhaustionExplainsHowToProceed() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            serve(server, 200, "{\"stop_reason\":\"max_tokens\",\"content\":[]}");
            CompletionException error = assertThrows(CompletionException.class, () ->
                    client(server).generate(AiProviderClient.Provider.ANTHROPIC, "claude-opus-5-5",
                            "test-key", "scene", Map.of()).join());
            String message = error.getCause().getMessage().toLowerCase();
            assertTrue(message.contains("token"));
            assertTrue(message.contains("simpler"));
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
                assertTrue(body.toString().contains("shadow"));
                assertTrue(body.toString().contains("offsetX"));
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
            for (int i = 0; i < 3; i++) {
                serve(server, 200, anthropicResponse("{\"version\":1,\"version\":2}"));
            }
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
            assertCancellingClosesConnection(provider, false);
        }
    }

    @Test
    void cancellingReviewClosesTheProviderConnection() throws Exception {
        for (AiProviderClient.Provider provider : AiProviderClient.Provider.values()) {
            assertCancellingClosesConnection(provider, true);
        }
    }

    private static void assertCancellingClosesConnection(AiProviderClient.Provider provider, boolean review)
            throws Exception {
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
            var request = review
                    ? client(server).review(provider, "model", "test-key", "draw a circle",
                            List.of(new byte[] {1}), Map.of("type", "object"), "review")
                    : client(server).generate(provider, "model", "test-key",
                            "draw a circle", Map.of("type", "object"));
            received.get(5, TimeUnit.SECONDS);
            request.cancel(true);
            closed.get(5, TimeUnit.SECONDS);
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
