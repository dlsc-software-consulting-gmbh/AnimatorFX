package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.io.AnimationFile;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

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

    public AiGenerationService(Path settingsFile, Function<String, String> readKey, Request request) {
        this(settingsFile, readKey, request, ForkJoinPool.commonPool());
    }

    AiGenerationService(Path settingsFile, Function<String, String> readKey, Request request, Executor executor) {
        this.settingsFile = settingsFile;
        this.readKey = readKey;
        this.request = request;
        this.executor = executor;
    }

    public CompletableFuture<AnimationFile> generate(String prompt) {
        return generateWithSettings(prompt, null);
    }

    public CompletableFuture<AnimationFile> generate(AiSettings settings, String prompt) {
        return generateWithSettings(prompt, Objects.requireNonNull(settings));
    }

    private CompletableFuture<AnimationFile> generateWithSettings(String prompt, AiSettings selection) {
        CompletableFuture<AnimationFile> result = new CompletableFuture<>();
        Object lock = new Object();
        @SuppressWarnings("unchecked")
        CompletableFuture<String>[] running = new CompletableFuture[1];
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
                CompletableFuture<String> sent;
                try {
                    synchronized (lock) {
                        if (result.isDone()) return;
                        sent = request.send(settings.provider(), settings.model(), key, prompt);
                        running[0] = sent;
                    }
                } catch (RuntimeException ex) {
                    result.completeExceptionally(new Failure("The provider request failed: "
                            + AiErrors.sanitize(AiErrors.message(ex), key, prompt)));
                    return;
                }
                sent.whenComplete((json, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(new Failure("The provider request failed: "
                                + AiErrors.sanitize(AiErrors.message(failure), key, prompt)));
                    } else if (!result.isDone()) {
                        try {
                            result.complete(AiAnimation.parse(json));
                        } catch (Exception ex) {
                            result.completeExceptionally(new Failure("The provider returned an invalid animation: "
                                    + AiErrors.sanitize(ex.getMessage() == null ? ex.getClass().getSimpleName()
                                    : ex.getMessage(), key, prompt)));
                        }
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
