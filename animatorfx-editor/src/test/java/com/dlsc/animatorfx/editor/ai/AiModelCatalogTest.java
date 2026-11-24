package com.dlsc.animatorfx.editor.ai;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AiModelCatalogTest {
    @Test
    void retrievesAllOpenAiModelsUsingTheProviderKey() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            var requests = serve(server, 200, List.of("""
                    {"object":"list","data":[{"id":"gpt-example","object":"model"},
                    {"id":"embedding-example","object":"model"}]}
                    """));
            var catalog = catalog(server);
            assertEquals(List.of("gpt-example", "embedding-example"),
                    catalog.listModels(AiProviderClient.Provider.OPENAI, "dummy-key").get(5, TimeUnit.SECONDS));
            String sent = requests.get(5, TimeUnit.SECONDS).get(0).toLowerCase();
            assertTrue(sent.startsWith("get /v1/models"));
            assertTrue(sent.contains("authorization: bearer dummy-key"));
        }
    }

    @Test
    void followsAnthropicPaginationWithoutLosingModels() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            var requests = serve(server, 200, List.of(
                    "{\"data\":[{\"id\":\"claude+first\"}],\"has_more\":true,\"last_id\":\"claude+first\"}",
                    "{\"data\":[{\"id\":\"claude-second\"}],\"has_more\":false,\"last_id\":\"claude-second\"}"));
            assertEquals(List.of("claude+first", "claude-second"),
                    catalog(server).listModels(AiProviderClient.Provider.ANTHROPIC, "dummy-key")
                            .get(5, TimeUnit.SECONDS));
            var sent = requests.get(5, TimeUnit.SECONDS);
            assertTrue(sent.get(0).toLowerCase().contains("x-api-key: dummy-key"));
            assertTrue(sent.get(1).contains("after_id=claude%2Bfirst"));
        }
    }

    @Test
    void failedOrInvalidResponsesDoNotExposeTheKey() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            serve(server, 401, List.of("{\"error\":\"dummy-key\"}"));
            var failure = assertThrows(CompletionException.class,
                    () -> catalog(server).listModels(AiProviderClient.Provider.OPENAI, "dummy-key").join());
            assertFalse(failure.toString().contains("dummy-key"));
        }
        try (ServerSocket server = new ServerSocket(0)) {
            serve(server, 200, List.of("{\"data\":[],\"has_more\":true,\"last_id\":null}"));
            assertThrows(CompletionException.class,
                    () -> catalog(server).listModels(AiProviderClient.Provider.ANTHROPIC, "dummy-key").join());
        }
    }

    @Test
    void reportsProviderErrorWhenLoadingModels() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            serve(server, 429, List.of("{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Too many requests; dummy-key\"}}"));
            var failure = assertThrows(CompletionException.class,
                    () -> catalog(server).listModels(AiProviderClient.Provider.ANTHROPIC, "dummy-key").join());
            String message = failure.getCause().getMessage();
            assertTrue(message.contains("HTTP 429"));
            assertTrue(message.contains("Too many requests"));
            assertFalse(message.contains("dummy-key"));
        }
    }

    @Test
    void rejectsARepeatedPaginationCursorInsteadOfLooping() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            var requests = serve(server, 200, List.of(
                    "{\"data\":[{\"id\":\"same\"}],\"has_more\":true,\"last_id\":\"same\"}",
                    "{\"data\":[{\"id\":\"same\"}],\"has_more\":true,\"last_id\":\"same\"}"));
            assertThrows(CompletionException.class,
                    () -> catalog(server).listModels(AiProviderClient.Provider.ANTHROPIC, "dummy-key").join());
            assertEquals(2, requests.get(5, TimeUnit.SECONDS).size());
        }
    }

    private static AiModelCatalog catalog(ServerSocket server) {
        URI endpoint = URI.create("http://localhost:" + server.getLocalPort() + "/v1/models");
        return new AiModelCatalog(HttpClient.newHttpClient(), endpoint, endpoint);
    }

    private static CompletableFuture<List<String>> serve(ServerSocket server, int status, List<String> responses) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> requests = new ArrayList<>();
                for (String body : responses) {
                    try (var socket = server.accept()) {
                        var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        StringBuilder headers = new StringBuilder();
                        for (String line; !(line = input.readLine()).isEmpty();) headers.append(line).append('\n');
                        requests.add(headers.toString());
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        var out = socket.getOutputStream();
                        out.write(("HTTP/1.1 " + status + " Result\r\nContent-Type: application/json\r\nContent-Length: "
                                + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        out.write(bytes);
                    }
                }
                return requests;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }
}
