package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.Animation;
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
