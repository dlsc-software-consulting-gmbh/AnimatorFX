package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.Animation;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class AiEditService {
    private static final Pattern HTTP = Pattern.compile("\\bHTTP ([1-5][0-9]{2})(?: \\(([a-z_]{1,64})\\))?");
    private static final Set<String> HTTP_TYPES = Set.of("invalid_request_error", "authentication_error",
            "permission_error", "not_found_error", "rate_limit_error", "api_error", "overloaded_error");
    private static final Set<String> EDIT_ERRORS = Set.of("Invalid AI edit response.", "Invalid AI edit operation.",
            "Invalid add operation.", "A new layer identifier is ambiguous.", "Too many new layers.",
            "Too many new keyframes.", "A new layer extends beyond the project duration.",
            "A new keyframe is beyond the project duration.", "Invalid property edit.", "Unknown target layer.",
            "The target layer is locked.", "Invalid shadow edit.",
            "Invalid text edit.", "Unsupported property.", "Property is unavailable on the target layer.",
            "A static edit cannot replace an animated track.", "Invalid keyframe.", "Unsupported edit operation.",
            "Invalid property value.", "Invalid numeric value.", "Invalid color.",
            "Vector is outside the allowed range.", "Number is outside the allowed range.");
    @FunctionalInterface
    public interface Request {
        CompletableFuture<String> send(AiProviderClient.Provider provider, String model, String key, String context);
    }

    private final Function<String, String> readKey;
    private final Request request;

    public AiEditService(Function<String, String> readKey, Request request) {
        this.readKey = Objects.requireNonNull(readKey);
        this.request = Objects.requireNonNull(request);
    }

    public static String activityMessage(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) failure = failure.getCause();
        return failure instanceof Failure detail ? detail.activityMessage : safeProviderError(failure);
    }

    static final class Failure extends RuntimeException {
        private final String activityMessage;

        Failure(String message, String activityMessage) {
            super(message);
            this.activityMessage = activityMessage;
        }
    }

    public CompletableFuture<AiEdits.Result> edit(AiSettings settings, Animation snapshot, List<String> selection,
                                                   double playhead, List<AiEdits.Message> history, String prompt) {
        var result = new CompletableFuture<AiEdits.Result>();
        Object lock = new Object();
        @SuppressWarnings("unchecked")
        CompletableFuture<String>[] active = new CompletableFuture[1];
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) synchronized (lock) {
                if (active[0] != null) active[0].cancel(true);
            }
        });
        CompletableFuture.runAsync(() -> {
            try {
                String context = AiEdits.context(snapshot, selection, playhead, history, prompt);
                String key = readKey.apply(settings.provider().name());
                if (key == null || key.isBlank()) {
                    result.completeExceptionally(new AiErrors.Detail("No API key is available for the selected provider."));
                    return;
                }
                CompletableFuture<String> network;
                synchronized (lock) {
                    if (result.isDone()) return;
                    network = request.send(settings.provider(), settings.model(), key, context);
                    active[0] = network;
                }
                network.whenComplete((json, failure) -> {
                    if (failure != null) {
                        String detail = AiErrors.sanitize(AiErrors.message(failure), key, context);
                        detail = AiErrors.sanitize(detail, key, prompt);
                        result.completeExceptionally(new Failure(detail, safeProviderError(failure)));
                    } else if (!result.isDone()) {
                        try {
                            result.complete(AiEdits.parse(json, snapshot));
                        } catch (Exception | StackOverflowError ex) {
                            String detail = ex instanceof IllegalArgumentException && EDIT_ERRORS.contains(ex.getMessage())
                                    ? " " + ex.getMessage() : "";
                            result.completeExceptionally(new AiErrors.Detail(
                                    "The AI provider returned invalid or unsupported edits." + detail));
                        }
                    }
                });
            } catch (Exception | LinkageError ex) {
                if (ex instanceof IllegalArgumentException
                        && "The project context is too large.".equals(ex.getMessage())) {
                    result.completeExceptionally(new AiErrors.Detail(ex.getMessage()));
                    return;
                }
                result.completeExceptionally(new AiErrors.Detail(
                        "Could not prepare the project context or access the API key."));
            }
        });
        return result;
    }

    private static String safeProviderError(Throwable failure) {
        String message = AiErrors.message(failure);
        var status = HTTP.matcher(message);
        if (status.find()) {
            String type = status.group(2);
            return "The AI provider returned HTTP " + status.group(1)
                    + (type != null && HTTP_TYPES.contains(type) ? " (" + type + ")" : "") + ".";
        }
        return switch (message) {
            case "The request timed out.", "The provider's hostname could not be resolved (DNS).",
                    "Could not connect to the AI provider.", "The secure connection to the AI provider failed (TLS)." -> message;
            default -> "The AI provider request failed.";
        };
    }
}
