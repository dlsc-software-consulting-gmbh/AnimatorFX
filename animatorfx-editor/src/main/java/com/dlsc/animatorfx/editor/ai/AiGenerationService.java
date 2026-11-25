package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.io.AnimationFile;

import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.function.Consumer;

/** Coordinates credential lookup, a provider request and validation away from the UI thread. */
public final class AiGenerationService {
    public static final class Failure extends RuntimeException {
        private Failure(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    public interface Request {
        CompletableFuture<String> send(AiProviderClient.Provider provider, String model, String key, String prompt);
    }

    private final Path settingsFile;
    private final Function<String, String> readKey;
    private final Request request;
    private final Executor executor;
    private final AiReviewRequest review;
    private final Function<AnimationFile, CompletableFuture<List<byte[]>>> render;
    private final Consumer<String> activity;

    public AiGenerationService(Path settingsFile, Function<String, String> readKey, Request request) {
        this(settingsFile, readKey, request, null, AiPreviewFrames::render, ignored -> {}, ForkJoinPool.commonPool());
    }

    public AiGenerationService(Path settingsFile, Function<String, String> readKey, Request request,
                               AiReviewRequest review, Consumer<String> activity) {
        this(settingsFile, readKey, request, review, AiPreviewFrames::render, activity, ForkJoinPool.commonPool());
    }

    AiGenerationService(Path settingsFile, Function<String, String> readKey, Request request,
                        AiReviewRequest review, Function<AnimationFile, CompletableFuture<List<byte[]>>> render,
                        Consumer<String> activity) {
        this(settingsFile, readKey, request, review, render, activity, ForkJoinPool.commonPool());
    }

    AiGenerationService(Path settingsFile, Function<String, String> readKey, Request request, Executor executor) {
        this(settingsFile, readKey, request, null, AiPreviewFrames::render, ignored -> {}, executor);
    }

    private AiGenerationService(Path settingsFile, Function<String, String> readKey, Request request,
                                AiReviewRequest review, Function<AnimationFile, CompletableFuture<List<byte[]>>> render,
                                Consumer<String> activity, Executor executor) {
        this.settingsFile = settingsFile;
        this.readKey = readKey;
        this.request = request;
        this.executor = executor;
        this.review = review;
        this.render = render;
        this.activity = activity;
    }

    public CompletableFuture<AnimationFile> generate(String prompt) {
        return generateWithSettings(prompt, null);
    }

    public CompletableFuture<AnimationFile> generate(AiSettings settings, String prompt) {
        return generateWithSettings(prompt, Objects.requireNonNull(settings));
    }

    private CompletableFuture<AnimationFile> generateWithSettings(String prompt, AiSettings selection) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 8000) {
            return CompletableFuture.failedFuture(new Failure("Enter a description of at most 8,000 characters."));
        }
        CompletableFuture<AnimationFile> result = new CompletableFuture<>();
        Object lock = new Object();
        @SuppressWarnings("unchecked")
        CompletableFuture<?>[] running = new CompletableFuture[1];
        result.whenComplete((file, failure) -> {
            if (result.isCancelled()) {
                synchronized (lock) {
                    if (running[0] != null) running[0].cancel(true);
                }
            }
        });
        executor.execute(() -> {
            try {
                AiSettings settings = selection != null ? selection : AiSettings.load(settingsFile);
                String key = readKey.apply(settings.provider().name());
                if (key == null || key.isBlank()) {
                    result.completeExceptionally(new Failure(
                            "No API key is available. Add one in AI Preferences and check your credential store."));
                    return;
                }
                CompletableFuture<AnimationFile> sent;
                try {
                    synchronized (lock) {
                        if (result.isDone()) return;
                        sent = AiRepair.run(prompt,
                                input -> request.send(settings.provider(), settings.model(), key, input),
                                json -> {
                                    try {
                                        return AiAnimation.parse(json);
                                    } catch (Exception | StackOverflowError ex) {
                                        throw new Failure("The provider returned an invalid animation: "
                                                + AiErrors.sanitize(ex.getMessage() == null
                                                ? ex.getClass().getSimpleName() : ex.getMessage(), key, prompt));
                                    }
                                }, (json, error) -> {
                                    String previous = json == null ? "" : json;
                                    if (previous.length() > 120_000) previous = previous.substring(0, 120_000);
                                    return "Original request:\n" + prompt + "\nThe previous response was invalid: "
                                            + error.getMessage() + "\nCorrect this response and return the complete animation:\n"
                                            + previous;
                                });
                        running[0] = sent;
                    }
                } catch (RuntimeException ex) {
                    result.completeExceptionally(new Failure("The provider request failed: "
                            + AiErrors.sanitize(AiErrors.message(ex), key, prompt)));
                    return;
                }
                sent.whenComplete((file, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure instanceof Failure ? failure
                                : new Failure("The provider request failed: "
                                + AiErrors.sanitize(AiErrors.message(failure), key, prompt)));
                    } else {
                        CompletableFuture<AiEdits.Result> refined;
                        synchronized (lock) {
                            if (result.isDone()) return;
                            refined = AiVisualReview.refine(new AiEdits.Result("", file.animation()), file,
                                    settings, key, List.of(), 0, List.of(), prompt, review, render, activity);
                            running[0] = refined;
                        }
                        refined.whenComplete((edit, error) -> {
                            if (error != null) result.completeExceptionally(error);
                            else result.complete(new AnimationFile(edit.animation(), file.assets()));
                        });
                    }
                });
            } catch (Exception | LinkageError ex) {
                result.completeExceptionally(new Failure("Could not access AI settings or an API key: "
                        + AiErrors.message(ex)));
            }
        });
        return result;
    }
}
