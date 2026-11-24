package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.io.Json;
import com.dlsc.animatorfx.model.io.JsonWriter;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Sends a description to a provider and returns its structured JSON. */
public final class AiProviderClient {

    public enum Provider {OPENAI, ANTHROPIC}

    private static final int MAX_RESPONSE = 2_000_000;
    private static final String INSTRUCTIONS = "Create an editable vector animation matching the user's description. "
            + "Follow the schema exactly. Do not include image, audio, video, or external assets.";
    private static final Map<String, Object> ANTHROPIC_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("animation", Map.of("type", "string")),
            "required", List.of("animation"),
            "additionalProperties", false);

    private final HttpClient client;
    private final URI openAi;
    private final URI anthropic;

    public AiProviderClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
                URI.create("https://api.openai.com/v1"),
                URI.create("https://api.anthropic.com/v1"));
    }

    AiProviderClient(HttpClient client, URI openAi, URI anthropic) {
        this.client = client;
        this.openAi = openAi;
        this.anthropic = anthropic;
    }

    public CompletableFuture<String> edit(Provider provider, String model, String apiKey, String context) {
        return send(provider, model, apiKey, context, AiEdits.schema(), AiEdits.instructions(), 400_000);
    }

    public CompletableFuture<String> generate(Provider provider, String model, String apiKey, String prompt,
                                              Map<String, Object> schema) {
        return send(provider, model, apiKey, prompt, schema, INSTRUCTIONS, 8000);
    }

    private CompletableFuture<String> send(Provider provider, String model, String apiKey, String prompt,
                                           Map<String, Object> schema, String baseInstructions, int promptLimit) {
        if (provider == null || model == null || model.isBlank() || model.length() > 200
                || apiKey == null || apiKey.isBlank() || apiKey.length() > 1000
                || prompt == null || prompt.isBlank() || prompt.length() > promptLimit || schema == null) {
            return CompletableFuture.failedFuture(new AiErrors.Detail("Check the provider, model, key and description."));
        }
        try {
            boolean wrapped = provider == Provider.ANTHROPIC;
            ResponseFormat format = ResponseFormat.builder().type(ResponseFormatType.JSON)
                    .jsonSchema(JsonSchema.builder().name("animation")
                            .rootElement(JsonRawSchema.from(JsonWriter.write(wrapped ? ANTHROPIC_SCHEMA : schema))).build()).build();
            String instructions = wrapped
                    ? baseInstructions + " Return the complete result as JSON text in the animation string property. "
                        + "The result JSON must conform to this schema:\n" + JsonWriter.write(schema)
                    : baseInstructions;
            BoundedClientBuilder transport = new BoundedClientBuilder(provider, apiKey, prompt);
            ChatModel chatModel = provider == Provider.OPENAI
                    ? OpenAiResponsesChatModel.builder().baseUrl(openAi.toString()).apiKey(apiKey)
                        .modelName(model).responseFormat(format).strictJsonSchema(true).store(false)
                        .httpClientBuilder(transport).build()
                    : AnthropicChatModel.builder().baseUrl(anthropic.toString()).apiKey(apiKey)
                        .modelName(model).maxTokens(16384).responseFormat(format).maxRetries(0)
                        .httpClientBuilder(transport).build();
            var network = chatModel.chatAsync(ChatRequest.builder()
                    .messages(SystemMessage.from(instructions), UserMessage.from(prompt)).build());
            CompletableFuture<String> result = new CompletableFuture<>();
            result.orTimeout(95, TimeUnit.SECONDS);
            result.whenComplete((value, error) -> {
                if (error != null) {
                    network.cancel(true);
                    transport.cancel();
                }
            });
            network.whenComplete((response, error) -> {
                if (error != null) {
                    result.completeExceptionally(new AiErrors.Detail(
                            AiErrors.sanitize(AiErrors.message(error), apiKey, prompt)));
                } else if (response.aiMessage() == null || response.aiMessage().text() == null) {
                    result.completeExceptionally(new AiErrors.Detail("The AI provider returned an invalid response."));
                } else {
                    try {
                        String text = response.aiMessage().text();
                        if (wrapped) {
                            Object value = Json.parse(text);
                            if (!(value instanceof Map<?, ?> map) || map.size() != 1
                                    || !(map.get("animation") instanceof String animation)) {
                                throw new IllegalArgumentException();
                            }
                            text = animation;
                        }
                        result.complete(text);
                    } catch (Exception ex) {
                        result.completeExceptionally(new AiErrors.Detail("The AI provider returned an invalid response."));
                    }
                }
            });
            return result;
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(new AiErrors.Detail(
                    "Could not start the AI request: " + AiErrors.sanitize(AiErrors.message(ex), apiKey, prompt)));
        }
    }

    private final class BoundedClientBuilder implements HttpClientBuilder {
        private final Provider provider;
        private final String key;
        private final String prompt;
        private volatile CompletableFuture<?> active;
        private Duration connectTimeout = Duration.ofSeconds(15);
        private Duration readTimeout = Duration.ofSeconds(90);

        private BoundedClientBuilder(Provider provider, String key, String prompt) {
            this.provider = provider;
            this.key = key;
            this.prompt = prompt;
        }

        @Override public Duration connectTimeout() { return connectTimeout; }
        @Override public HttpClientBuilder connectTimeout(Duration timeout) { connectTimeout = timeout; return this; }
        @Override public Duration readTimeout() { return readTimeout; }
        @Override public HttpClientBuilder readTimeout(Duration timeout) { readTimeout = timeout; return this; }

        private void cancel() {
            // Anthropic's async response parsing does not propagate cancellation to its HTTP future.
            CompletableFuture<?> request = active;
            if (request != null) request.cancel(true);
        }

        @Override
        public dev.langchain4j.http.client.HttpClient build() {
            return new dev.langchain4j.http.client.HttpClient() {
                @Override
                public SuccessfulHttpResponse execute(dev.langchain4j.http.client.HttpRequest request) {
                    return executeAsync(request).join();
                }

                @Override
                public CompletableFuture<SuccessfulHttpResponse> executeAsync(dev.langchain4j.http.client.HttpRequest request) {
                    if (request.body() != null
                            && request.body().getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE) {
                        return CompletableFuture.failedFuture(new AiErrors.Detail("Request is too large."));
                    }
                    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url()))
                            .timeout(Duration.ofSeconds(90));
                    request.headers().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
                    builder.method(request.method().name(), request.body() == null
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(request.body()));
                    long logId = AiTrafficLogger.request(provider, request.method().name(),
                            URI.create(request.url()), request.body(), key);
                    var network = client.sendAsync(builder.build(), ignored -> new LimitedBodySubscriber());
                    active = network;
                    CompletableFuture<SuccessfulHttpResponse> result = new CompletableFuture<>();
                    result.whenComplete((value, error) -> { if (error != null) network.cancel(true); });
                    network.whenComplete((response, error) -> {
                        if (error != null) {
                            AiTrafficLogger.failed(logId, error, key);
                            result.completeExceptionally(new AiErrors.Detail(AiErrors.message(error)));
                        } else {
                            AiTrafficLogger.response(logId, response.statusCode(), response.body(), key);
                            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                                result.completeExceptionally(AiErrors.http(response.statusCode(), response.body(), key, prompt));
                                return;
                            }
                            try {
                                extract(provider, new String(response.body(), StandardCharsets.UTF_8));
                                result.complete(SuccessfulHttpResponse.builder().statusCode(response.statusCode())
                                        .headers(response.headers().map()).body(response.body()).build());
                            } catch (AiErrors.Detail ex) {
                                result.completeExceptionally(new AiErrors.Detail(AiErrors.sanitize(ex.getMessage(), key, prompt)));
                            } catch (Exception ex) {
                                result.completeExceptionally(new AiErrors.Detail("The AI provider returned an invalid response."));
                            }
                        }
                    });
                    return result;
                }

                @Override
                public void execute(dev.langchain4j.http.client.HttpRequest request, ServerSentEventParser parser,
                                    ServerSentEventListener listener) {
                    throw new UnsupportedOperationException("Streaming is not used for animation generation.");
                }
            };
        }
    }

    private static String extract(Provider provider, String json) throws Exception {
        if (!(Json.parse(json) instanceof Map<?, ?> root)) throw new IllegalArgumentException();
        if (provider == Provider.OPENAI) {
            if (!"completed".equals(root.get("status"))) {
                Object details = root.get("incomplete_details");
                Object reason = details instanceof Map<?, ?> map ? map.get("reason") : null;
                throw new AiErrors.Detail("The AI response was not completed"
                        + (reason instanceof String ? ": " + reason : "."));
            }
            if (!(root.get("output") instanceof List<?> output)) throw new IllegalArgumentException();
            String text = null;
            for (Object entry : output) {
                if (entry instanceof Map<?, ?> message && "message".equals(message.get("type"))
                        && message.get("content") instanceof List<?> content) {
                    for (Object item : content) {
                        if (item instanceof Map<?, ?> block) {
                            if ("refusal".equals(block.get("type"))) throw new AiErrors.Detail("The AI provider refused the request.");
                            if ("output_text".equals(block.get("type")) && block.get("text") instanceof String value) {
                                if (text != null) throw new IllegalArgumentException();
                                text = value;
                            }
                        }
                    }
                }
            }
            if (text != null) return text;
        } else {
            if (!"end_turn".equals(root.get("stop_reason"))) {
                Object reason = root.get("stop_reason");
                throw new AiErrors.Detail("The AI response was not completed"
                        + (reason instanceof String ? ": " + reason : "."));
            }
            if (!(root.get("content") instanceof List<?> content)) throw new IllegalArgumentException();
            for (Object item : content) {
                if (item instanceof Map<?, ?> block && "text".equals(block.get("type"))
                        && block.get("text") instanceof String text) return text;
            }
        }
        throw new IllegalArgumentException();
    }

    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int size;
        private boolean done;

        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE - size) {
                    done = true;
                    subscription.cancel();
                    delegate.onError(new AiErrors.Detail("The AI provider response exceeds the size limit."));
                    return;
                }
                size += buffer.remaining();
            }
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { if (!done) delegate.onError(error); }
        @Override public void onComplete() { if (!done) delegate.onComplete(); }
    }
}
