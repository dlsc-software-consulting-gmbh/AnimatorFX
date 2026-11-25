package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.io.AnimationFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiEditServiceTest {
    @Test
    void visualReviewDoesNotInventEditsForAnAnswerWithoutChanges() throws Exception {
        var reviewed = new java.util.concurrent.atomic.AtomicBoolean();
        AiEditService service = new AiEditService(provider -> "test-key",
                (provider, model, key, context) ->
                        CompletableFuture.completedFuture("{\"reply\":\"What color?\",\"operations\":[]}"),
                (provider, model, key, context, frames, schema, instructions) -> {
                    reviewed.set(true);
                    return CompletableFuture.completedFuture("{\"reply\":\"Done\",\"operations\":[]}");
                }, file -> CompletableFuture.completedFuture(List.of(new byte[]{1})), message -> {});
        Animation before = Animation.create("Project", 800, 600, 3);
        var settings = new AiSettings(AiProviderClient.Provider.OPENAI, "model", "model", true);
        var result = service.edit(settings, new AnimationFile(before, java.util.Map.of()), List.of(), 0, List.of(),
                "Can you help?").get(5, TimeUnit.SECONDS);
        assertSame(before, result.animation());
        assertEquals("What color?", result.reply());
        assertFalse(reviewed.get());
    }

    @Test
    void optInVisualReviewRefinesTheStagedEditWithoutChangingTheOriginal() throws Exception {
        var shape = new ShapeLayer(LayerBase.create("Ball", 3).withId("ball"),
                new Geometry.Ellipse(Animatable.of(new Vec2(30, 30))),
                Animatable.of(new Paint.Solid(Rgba.BLACK)), null, null);
        Animation before = Animation.create("Project", 800, 600, 3).withLayers(List.of(shape));
        String initial = "{\"reply\":\"Done\",\"operations\":[{\"kind\":\"set\",\"target\":\"ball\","
                + "\"parent\":null,\"property\":\"opacity\",\"value\":\"0.5\",\"time\":null,\"easing\":null}]}";
        String refined = initial.replace("\"0.5\"", "\"0.7\"");
        var frameFile = new AtomicReference<AnimationFile>();
        AiEditService service = new AiEditService(provider -> "test-key",
                (provider, model, key, context) -> CompletableFuture.completedFuture(initial),
                (provider, model, key, context, frames, schema, instructions) ->
                        CompletableFuture.completedFuture(refined),
                file -> {
                    frameFile.set(file);
                    return CompletableFuture.completedFuture(List.of(new byte[]{1}));
                }, message -> {});
        AiSettings settings = new AiSettings(AiProviderClient.Provider.OPENAI, "model", "model", true);
        var result = service.edit(settings, new AnimationFile(before, java.util.Map.of()), List.of(), 0,
                List.of(), "Adjust opacity").get(5, TimeUnit.SECONDS);
        assertEquals(1.0, before.layers().getFirst().base().opacity().at(0));
        assertEquals(0.5, frameFile.get().animation().layers().getFirst().base().opacity().at(0));
        assertEquals(0.7, result.animation().layers().getFirst().base().opacity().at(0));
    }

    @Test
    void repairsInvalidEditsWithoutChangingTheOriginalProject() throws Exception {
        var contexts = new java.util.concurrent.CopyOnWriteArrayList<String>();
        String invalid = "{\"reply\":\"Done\",\"operations\":[{\"kind\":\"set\",\"target\":\"missing\","
                + "\"parent\":null,\"property\":\"opacity\",\"value\":\"0.5\",\"time\":null,\"easing\":null}]}";
        AiEditService service = new AiEditService(provider -> "test-key", (provider, model, key, context) -> {
            contexts.add(context);
            return CompletableFuture.completedFuture(contexts.size() == 1 ? invalid
                    : "{\"reply\":\"Nothing to change\",\"operations\":[]}");
        });
        Animation animation = Animation.create("Project", 800, 600, 3);
        var result = service.edit(AiSettings.defaults(), animation, List.of(), 0, List.of(), "Adjust opacity")
                .get(5, TimeUnit.SECONDS);
        assertSame(animation, result.animation());
        assertEquals(2, contexts.size());
        assertTrue(contexts.get(1).contains("Unknown target layer"));
        assertTrue(contexts.get(1).contains("Adjust opacity"));
    }

    @Test
    void sendsContextAndValidatesReturnedEdits() throws Exception {
        AtomicReference<String> sent = new AtomicReference<>();
        AiEditService service = new AiEditService(provider -> "test-key", (provider, model, key, context) -> {
            sent.set(context);
            return CompletableFuture.completedFuture("{\"reply\":\"What color?\",\"operations\":[]}");
        });
        Animation animation = Animation.create("Project", 800, 600, 3);
        var result = service.edit(AiSettings.defaults(), animation, List.of(), 0,
                List.of(new AiEdits.Message("user", "Add a ball")), "Make it bounce").get(5, TimeUnit.SECONDS);
        assertEquals("What color?", result.reply());
        assertSame(animation, result.animation());
        assertTrue(sent.get().contains("Add a ball"));
        assertTrue(sent.get().contains("Make it bounce"));
        assertFalse(sent.get().contains("test-key"));
    }

    @Test
    void cancellationReachesTheProviderRequest() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        CompletableFuture<String> network = new CompletableFuture<>();
        AiEditService service = new AiEditService(provider -> "test-key", (provider, model, key, context) -> {
            sent.countDown();
            return network;
        });
        var result = service.edit(AiSettings.defaults(), Animation.create("Project", 800, 600, 3),
                List.of(), 0, List.of(), "Move the ball");
        assertTrue(sent.await(5, TimeUnit.SECONDS));
        result.cancel(true);
        assertTrue(network.isCancelled());
    }

    @Test
    void showsTechnicalDetailsButKeepsProviderTextOutOfActivityDiagnostics() {
        AiEditService service = new AiEditService(provider -> "test-key", (provider, model, key, context) ->
                CompletableFuture.failedFuture(new AiErrors.Detail(
                        "The AI provider returned HTTP 400: Unsupported schema; test-key Move the ball Project")));
        var failure = assertThrows(CompletionException.class, () -> service.edit(AiSettings.defaults(),
                Animation.create("Project", 800, 600, 3), List.of(), 0, List.of(), "Move the ball").join());
        String message = AiErrors.message(failure);
        assertTrue(message.contains("HTTP 400"));
        assertTrue(message.contains("Unsupported schema"));
        assertFalse(message.contains("test-key"));
        assertFalse(message.contains("Move the ball"));
        String activity = AiEditService.activityMessage(failure);
        assertTrue(activity.contains("HTTP 400"));
        assertFalse(activity.contains("Project"));
        assertFalse(activity.contains("Move the ball"));
        assertFalse(activity.contains("test-key"));
        assertFalse(activity.contains("Unsupported schema"));
    }
}
