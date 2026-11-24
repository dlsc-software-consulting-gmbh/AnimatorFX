package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.io.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Lists models visible to a provider credential. */
public final class AiModelCatalog {
    private final HttpClient client;
    private final URI openAi;
    private final URI anthropic;

    public AiModelCatalog() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
                URI.create("https://api.openai.com/v1/models"),
                URI.create("https://api.anthropic.com/v1/models"));
    }

    AiModelCatalog(HttpClient client, URI openAi, URI anthropic) {
        this.client = client;
        this.openAi = openAi;
        this.anthropic = anthropic;
    }

    public CompletableFuture<List<String>> listModels(AiProviderClient.Provider provider, String apiKey) {
        if (provider == null || apiKey == null || apiKey.isBlank() || apiKey.length() > 1000) {
            return CompletableFuture.failedFuture(new AiErrors.Detail("A provider and API key are required."));
        }
        Request request = new Request(provider, apiKey);
        request.result.orTimeout(45, TimeUnit.SECONDS);
        request.result.whenComplete((models, failure) -> {
            if (failure != null) {
                synchronized (request) {
                    if (request.network != null) request.network.cancel(true);
                }
            }
        });
        request.fetch(null);
        return request.result;
    }

    private final class Request {
        private final AiProviderClient.Provider provider;
        private final String key;
        private final CompletableFuture<List<String>> result = new CompletableFuture<>();
        private final Set<String> ids = new LinkedHashSet<>();
        private final Set<String> cursors = new HashSet<>();
        private CompletableFuture<HttpResponse<byte[]>> network;
        private int pages;

        private Request(AiProviderClient.Provider provider, String key) {
            this.provider = provider;
            this.key = key;
        }

        private void fetch(String cursor) {
            try {
                URI endpoint = provider == AiProviderClient.Provider.OPENAI ? openAi : anthropic;
                if (provider == AiProviderClient.Provider.ANTHROPIC) {
                    String query = "?limit=1000" + (cursor == null ? "" : "&after_id="
                            + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
                    endpoint = URI.create(endpoint.toString() + query);
                }
                HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15)).GET();
                if (provider == AiProviderClient.Provider.OPENAI) {
                    builder.header("Authorization", "Bearer " + key);
                } else {
                    builder.header("x-api-key", key).header("anthropic-version", "2023-06-01");
                }
                CompletableFuture<HttpResponse<byte[]>> sent;
                long logId;
                synchronized (this) {
                    if (result.isDone()) return;
                    logId = AiTrafficLogger.request(provider, "GET", endpoint, null, key);
                    sent = client.sendAsync(builder.build(), ignored -> new AiProviderClient.LimitedBodySubscriber());
                    network = sent;
                }
                sent.whenComplete((response, failure) -> {
                    if (failure != null) {
                        AiTrafficLogger.failed(logId, failure, key);
                        if (result.isDone()) return;
                        fail(AiErrors.message(failure));
                        return;
                    }
                    AiTrafficLogger.response(logId, response.statusCode(), response.body(), key);
                    if (result.isDone()) return;
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        result.completeExceptionally(AiErrors.http(response.statusCode(), response.body(), key, null));
                        return;
                    }
                    try {
                        readPage(new String(response.body(), StandardCharsets.UTF_8));
                    } catch (Exception ex) {
                        fail("The provider returned an invalid model list.");
                    }
                });
            } catch (RuntimeException ex) {
                fail("Invalid model-list request.");
            }
        }

        private void readPage(String body) throws Exception {
            if (++pages > 100 || !(Json.parse(body) instanceof Map<?, ?> root)
                    || !(root.get("data") instanceof List<?> models)) throw new IllegalArgumentException();
            for (Object value : models) {
                if (!(value instanceof Map<?, ?> model) || !(model.get("id") instanceof String id)
                        || id.isBlank() || id.length() > 200) throw new IllegalArgumentException();
                ids.add(id);
                if (ids.size() > 50_000) throw new IllegalArgumentException();
            }
            if (provider == AiProviderClient.Provider.ANTHROPIC) {
                if (!(root.get("has_more") instanceof Boolean more)) throw new IllegalArgumentException();
                if (more) {
                    if (!(root.get("last_id") instanceof String next) || next.isBlank()
                            || next.length() > 200 || !cursors.add(next)) throw new IllegalArgumentException();
                    fetch(next);
                    return;
                }
            }
            result.complete(List.copyOf(ids));
        }

        private void fail(String reason) {
            result.completeExceptionally(new AiErrors.Detail("Could not load models: " + reason));
        }
    }
}
