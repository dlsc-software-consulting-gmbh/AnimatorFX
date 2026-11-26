package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.io.AnimationFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Keeps the validated candidate when optional visual refinement cannot be completed. */
final class AiVisualReview {
    private AiVisualReview() {}

    static CompletableFuture<AiEdits.Result> refine(AiEdits.Result candidate, AnimationFile file,
                                                    AiSettings settings, String key, List<String> selection,
                                                    double playhead, List<AiEdits.Message> history, String prompt,
                                                    AiReviewRequest request,
                                                    Function<AnimationFile, CompletableFuture<List<byte[]>>> render,
                                                    Consumer<String> status) {
        return run(candidate, settings.visualReview(),
                () -> {
                    var assets = new LinkedHashMap<>(file.assets());
                    candidate.newAssets().forEach((asset, bytes) -> assets.put(asset.id(), bytes));
                    return render.apply(new AnimationFile(candidate.animation(), assets));
                },
                frames -> {
                    if (request == null) return CompletableFuture.failedFuture(
                            new IllegalStateException("Visual review is unavailable."));
                    String context = AiEdits.context(candidate.animation(), selection, playhead, history, prompt);
                    CompletableFuture<String> network = request.review(settings.provider(), settings.model(), key,
                            context, frames, AiEdits.schema(), AiEdits.instructions()
                                    + "\nThe images show the candidate at the beginning, middle, and end. "
                                    + "Review them for the user's request; return only necessary refinements "
                                    + "against the candidate project, or no operations if it already looks good.");
                    CompletableFuture<AiEdits.Result> result = new CompletableFuture<>();
                    result.whenComplete((value, error) -> {
                        if (result.isCancelled()) network.cancel(true);
                    });
                    network.whenComplete((response, failure) -> {
                        if (result.isDone()) return;
                        if (failure != null) {
                            result.completeExceptionally(failure);
                            return;
                        }
                        try {
                            AiEdits.Result revision = AiEdits.parse(response, candidate.animation());
                            var changes = new ArrayList<>(candidate.changes());
                            changes.addAll(revision.changes());
                            result.complete(new AiEdits.Result(candidate.reply(), revision.animation(), changes,
                                    candidate.newAssets()));
                        } catch (Exception | StackOverflowError ex) {
                            result.completeExceptionally(ex);
                        }
                    });
                    return result;
                }, status);
    }

    static <T> CompletableFuture<T> run(T candidate, boolean enabled,
                                        Supplier<CompletableFuture<List<byte[]>>> render,
                                        Function<List<byte[]>, CompletableFuture<T>> review,
                                        Consumer<String> status) {
        if (!enabled) return CompletableFuture.completedFuture(candidate);
        try {
            status.accept("AI visual review started.");
        } catch (RuntimeException ignored) {}
        CompletableFuture<T> result = new CompletableFuture<>();
        Object lock = new Object();
        CompletableFuture<?>[] active = new CompletableFuture[1];
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) synchronized (lock) {
                if (active[0] != null) active[0].cancel(true);
            }
        });
        Runnable skip = () -> {
            if (!result.isDone()) {
                try {
                    status.accept("Visual review skipped; keeping the validated result.");
                } finally {
                    result.complete(candidate);
                }
            }
        };
        try {
            CompletableFuture<List<byte[]>> frames;
            synchronized (lock) {
                if (result.isDone()) return result;
                frames = render.get();
                active[0] = frames;
            }
            frames.whenComplete((images, failure) -> {
                if (result.isDone()) return;
                if (failure != null || images == null || images.isEmpty()) {
                    skip.run();
                    return;
                }
                try {
                    CompletableFuture<T> revised;
                    synchronized (lock) {
                        if (result.isDone()) return;
                        revised = review.apply(images);
                        active[0] = revised;
                    }
                    revised.whenComplete((value, error) -> {
                        if (result.isDone()) return;
                        if (error != null || value == null) skip.run();
                        else {
                            try {
                                status.accept("AI visual review completed.");
                            } finally {
                                result.complete(value);
                            }
                        }
                    });
                } catch (RuntimeException ex) {
                    skip.run();
                }
            });
        } catch (RuntimeException ex) {
            skip.run();
        }
        return result;
    }
}
