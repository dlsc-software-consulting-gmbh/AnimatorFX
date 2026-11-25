package com.dlsc.animatorfx.editor.ai;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Validates provider output and permits a bounded number of corrections before failing. */
final class AiRepair {
    @FunctionalInterface
    interface Validator<T> {
        T validate(String response) throws Exception;
    }

    private AiRepair() {}

    static <T> CompletableFuture<T> run(String initial, Function<String, CompletableFuture<String>> request,
                                         Validator<T> validator, BiFunction<String, Exception, String> correction) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Object lock = new Object();
        @SuppressWarnings("unchecked")
        CompletableFuture<String>[] active = new CompletableFuture[1];
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) synchronized (lock) {
                if (active[0] != null) active[0].cancel(true);
            }
        });
        class Attempt {
            void send(String input, int number) {
                CompletableFuture<String> network;
                try {
                    synchronized (lock) {
                        if (result.isDone()) return;
                        network = Objects.requireNonNull(request.apply(input));
                        active[0] = network;
                    }
                } catch (RuntimeException ex) {
                    result.completeExceptionally(ex);
                    return;
                }
                network.whenComplete((response, failure) -> {
                    if (result.isDone()) return;
                    if (failure != null) {
                        result.completeExceptionally(failure);
                        return;
                    }
                    try {
                        result.complete(validator.validate(response));
                    } catch (Exception ex) {
                        if (number >= 2) {
                            result.completeExceptionally(ex);
                        } else {
                            try {
                                String next = correction.apply(response, ex);
                                if (next == null) result.completeExceptionally(ex);
                                else send(next, number + 1);
                            } catch (RuntimeException error) {
                                result.completeExceptionally(error);
                            }
                        }
                    }
                });
            }
        }
        new Attempt().send(initial, 0);
        return result;
    }
}
