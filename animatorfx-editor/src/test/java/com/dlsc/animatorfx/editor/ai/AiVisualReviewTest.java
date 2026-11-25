package com.dlsc.animatorfx.editor.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiVisualReviewTest {
    @Test
    void disabledReviewDoesNotRenderOrSendFrames() {
        var called = new AtomicBoolean();
        var result = AiVisualReview.run("candidate", false,
                () -> { called.set(true); return CompletableFuture.completedFuture(List.of()); },
                frames -> CompletableFuture.completedFuture("revised"), message -> {}).join();
        assertEquals("candidate", result);
        assertFalse(called.get());
    }

    @Test
    void failedReviewKeepsTheValidatedCandidateAndReportsIt() {
        var status = new AtomicReference<String>();
        var result = AiVisualReview.run("candidate", true,
                () -> CompletableFuture.completedFuture(List.of(new byte[]{1})),
                frames -> CompletableFuture.failedFuture(new IllegalArgumentException("bad output")),
                status::set).join();
        assertEquals("candidate", result);
        assertTrue(status.get().toLowerCase().contains("skipped"));
    }

    @Test
    void cancellationInterruptsTheReviewRequest() {
        var network = new CompletableFuture<String>();
        var result = AiVisualReview.run("candidate", true,
                () -> CompletableFuture.completedFuture(List.of(new byte[]{1})),
                frames -> network, message -> {});
        result.cancel(true);
        assertTrue(network.isCancelled());
    }

    @Test
    void successfulReviewReturnsTheRefinedCandidate() {
        var result = AiVisualReview.run("candidate", true,
                () -> CompletableFuture.completedFuture(List.of(new byte[]{1})),
                frames -> CompletableFuture.completedFuture("revised"), message -> {}).join();
        assertEquals("revised", result);
    }
}
