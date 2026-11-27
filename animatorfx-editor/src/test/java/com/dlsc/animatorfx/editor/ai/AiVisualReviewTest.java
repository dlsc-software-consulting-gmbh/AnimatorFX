package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.io.AnimationFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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

    @Test
    void reviewRendersAndRetainsNewCandidateAssetBytes() throws Exception {
        Asset originalAsset = new Asset("original", Asset.Kind.IMAGE, "assets/original.png", "Original");
        Asset candidateAsset = new Asset("candidate", Asset.Kind.IMAGE, "assets/candidate.png", "Candidate");
        byte[] originalBytes = {1, 2};
        byte[] candidateBytes = {3, 4};
        Animation original = Animation.create("Original", 800, 600, 3).withAssets(List.of(originalAsset));
        Animation candidateAnimation = Animation.create("Candidate", 800, 600, 3)
                .withAssets(List.of(originalAsset, candidateAsset));
        var candidate = new AiEdits.Result("Placed", candidateAnimation, List.of(),
                Map.of(candidateAsset, candidateBytes));
        var reviewFile = new AtomicReference<AnimationFile>();
        var reviewFrames = new AtomicReference<List<byte[]>>();

        AiEdits.Result refined = AiVisualReview.refine(candidate,
                new AnimationFile(original, Map.of(originalAsset.id(), originalBytes)),
                new AiSettings(AiProviderClient.Provider.OPENAI, "model", "model", true),
                "key", List.of(), 0, List.of(), "Place the image",
                (provider, model, key, context, frames, schema, instructions) -> {
                    reviewFrames.set(frames);
                    return CompletableFuture.completedFuture("{\"reply\":\"Reviewed\",\"operations\":[]}");
                },
                file -> {
                    reviewFile.set(file);
                    return CompletableFuture.completedFuture(List.of(new byte[]{8}));
                }, message -> {}).get();

        assertSame(candidateAnimation, reviewFile.get().animation());
        assertArrayEquals(originalBytes, reviewFile.get().assets().get(originalAsset.id()));
        assertArrayEquals(candidateBytes, reviewFile.get().assets().get(candidateAsset.id()));
        assertEquals(1, reviewFrames.get().size());
        assertArrayEquals(new byte[]{8}, reviewFrames.get().getFirst());
        assertArrayEquals(candidateBytes, refined.newAssets().get(candidateAsset));
    }
}
