package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.model.io.JsonWriter;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Consumer;
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
            "Vector is outside the allowed range.", "Number is outside the allowed range.",
            "Invalid layer edit.", "Invalid layer name.", "Invalid sibling index.",
            "Cannot reorder across a locked layer.", "Too many new layers or keyframes.",
            "Invalid keyframe easing.");
    @FunctionalInterface
    public interface Request {
        CompletableFuture<String> send(AiProviderClient.Provider provider, String model, String key, String context,
                                       List<AiChatAttachment> attachments);
    }

    private final Function<String, String> readKey;
    private final Request request;
    private final AiReviewRequest review;
    private final Function<AnimationFile, CompletableFuture<List<byte[]>>> render;
    private final Consumer<String> activity;

    public AiEditService(Function<String, String> readKey, Request request) {
        this(readKey, request, null, AiPreviewFrames::render, ignored -> {});
    }

    public AiEditService(Function<String, String> readKey, Request request, AiReviewRequest review,
                         Consumer<String> activity) {
        this(readKey, request, review, AiPreviewFrames::render, activity);
    }

    AiEditService(Function<String, String> readKey, Request request, AiReviewRequest review,
                  Function<AnimationFile, CompletableFuture<List<byte[]>>> render, Consumer<String> activity) {
        this.readKey = Objects.requireNonNull(readKey);
        this.request = Objects.requireNonNull(request);
        this.review = review;
        this.render = Objects.requireNonNull(render);
        this.activity = Objects.requireNonNull(activity);
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

    private static final class InvalidEdits extends RuntimeException {
        InvalidEdits(String detail) {
            super(detail);
        }
    }

    public CompletableFuture<AiEdits.Result> edit(AiSettings settings, Animation snapshot, List<String> selection,
                                                   double playhead, List<AiEdits.Message> history, String prompt) {
        return edit(settings, new AnimationFile(snapshot, java.util.Map.of()), selection, playhead, history, prompt,
                List.of());
    }

    public CompletableFuture<AiEdits.Result> edit(AiSettings settings, AnimationFile file, List<String> selection,
                                                   double playhead, List<AiEdits.Message> history, String prompt) {
        return edit(settings, file, selection, playhead, history, prompt, List.of());
    }

    public CompletableFuture<AiEdits.Result> edit(AiSettings settings, AnimationFile file, List<String> selection,
                                                   double playhead, List<AiEdits.Message> history, String prompt,
                                                   List<AiChatAttachment> attachments) {
        Animation snapshot = file.animation();
        List<AiChatAttachment> currentAttachments = List.copyOf(attachments);
        var result = new CompletableFuture<AiEdits.Result>();
        Object lock = new Object();
        @SuppressWarnings("unchecked")
        CompletableFuture<?>[] active = new CompletableFuture[1];
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
                CompletableFuture<AiEdits.Result> network;
                synchronized (lock) {
                    if (result.isDone()) return;
                    network = AiRepair.run(context,
                            input -> request.send(settings.provider(), settings.model(), key, input, currentAttachments),
                            json -> {
                                try {
                                    return AiEdits.parse(json, snapshot, currentAttachments);
                                } catch (Exception | StackOverflowError ex) {
                                    String detail = ex instanceof IllegalArgumentException && EDIT_ERRORS.contains(ex.getMessage())
                                            ? ex.getMessage() : "Invalid AI edit response.";
                                    throw new InvalidEdits(detail);
                                }
                            }, (json, error) -> {
                                String previous = json == null ? "" : json;
                                if (previous.length() > 120_000) previous = previous.substring(0, 120_000);
                                String feedback = JsonWriter.write(java.util.Map.of(
                                        "error", error.getMessage(), "previousResponse", previous));
                                String next = context.substring(0, context.length() - 1)
                                        + ",\"validationFeedback\":" + feedback + "}";
                                return next.length() <= 400_000 ? next : null;
                            });
                    active[0] = network;
                }
                network.whenComplete((edits, failure) -> {
                    if (failure != null) {
                        if (failure instanceof InvalidEdits invalid) {
                            result.completeExceptionally(new AiErrors.Detail(
                                    "The AI provider returned invalid or unsupported edits. " + invalid.getMessage()));
                            return;
                        }
                        String detail = AiErrors.sanitize(AiErrors.message(failure), key, context);
                        detail = AiErrors.sanitize(detail, key, prompt);
                        result.completeExceptionally(new Failure(detail, safeProviderError(failure)));
                    } else if (snapshot.equals(edits.animation())) {
                        result.complete(edits);
                    } else {
                        CompletableFuture<AiEdits.Result> refined;
                        synchronized (lock) {
                            if (result.isDone()) return;
                            refined = AiVisualReview.refine(edits, file, settings, key, selection, playhead,
                                    history, prompt, review, render, activity);
                            active[0] = refined;
                        }
                        refined.whenComplete((value, error) -> {
                            if (error != null) result.completeExceptionally(error);
                            else result.complete(value);
                        });
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
