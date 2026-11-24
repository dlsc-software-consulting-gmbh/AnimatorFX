package com.dlsc.animatorfx.editor.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AiGenerationServiceTest {
    @Test
    void usesTheExplicitSelectionEvenIfSavedPreferencesChange(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("prefs");
        AiSettings.defaults().save(file);
        AiSettings selection = new AiSettings(AiProviderClient.Provider.ANTHROPIC, "other", "claude-selected");
        var used = new java.util.concurrent.atomic.AtomicReference<String>();
        String json = """
                {"version":1,"name":"Mock","width":320,"height":240,"frameRate":30,"duration":1,
                 "background":{"r":0,"g":0,"b":0,"a":0},"layers":[]}
                """;
        AiGenerationService service = new AiGenerationService(file, provider -> "test-key",
                (provider, model, key, prompt) -> {
                    used.set(provider.name() + ":" + model);
                    return CompletableFuture.completedFuture(json);
                });
        service.generate(selection, "a scene").get(5, TimeUnit.SECONDS);
        assertEquals("ANTHROPIC:claude-selected", used.get());
    }

    @Test
    void convertsProviderJsonToAnEditableAnimation(@TempDir Path directory) throws Exception {
        String json = """
                {"version":1,"name":"Mock","width":320,"height":240,"frameRate":30,"duration":1,
                 "background":{"r":0,"g":0,"b":0,"a":0},"layers":[]}
                """;
        AiGenerationService service = new AiGenerationService(directory.resolve("prefs"), provider -> "test-key",
                (provider, model, key, prompt) -> CompletableFuture.completedFuture(json));
        var result = service.generate("a scene").get(5, TimeUnit.SECONDS);
        assertEquals("Mock", result.animation().name());
        assertEquals(320, result.animation().width());
        assertTrue(result.assets().isEmpty());
    }

    @Test
    void malformedProviderJsonIsRejected(@TempDir Path directory) {
        AiGenerationService service = new AiGenerationService(directory.resolve("prefs"), provider -> "test-key",
                (provider, model, key, prompt) -> CompletableFuture.completedFuture("{}"));
        assertThrows(java.util.concurrent.CompletionException.class, () -> service.generate("a scene").join());
    }

    @Test
    void reportsTheValidationProblemWithoutEchoingThePrompt(@TempDir Path directory) {
        AiGenerationService service = new AiGenerationService(directory.resolve("prefs"), provider -> "test-key",
                (provider, model, key, prompt) -> CompletableFuture.completedFuture("""
                        {"version":1,"name":"Mock","width":0,"height":240,"frameRate":30,"duration":1,
                         "background":{"r":0,"g":0,"b":0,"a":0},"layers":[]}
                        """));
        var failure = assertThrows(java.util.concurrent.CompletionException.class,
                () -> service.generate("a scene").join());
        assertTrue(failure.getCause().getMessage().contains("width"));
        assertFalse(failure.getCause().getMessage().contains("a scene"));
    }

    @Test
    void reportsConnectionFailureInsteadOfGenericRequestFailure(@TempDir Path directory) {
        AiGenerationService service = new AiGenerationService(directory.resolve("prefs"), provider -> "test-key",
                (provider, model, key, prompt) -> CompletableFuture.failedFuture(new java.net.ConnectException()));
        var failure = assertThrows(java.util.concurrent.CompletionException.class,
                () -> service.generate("a scene").join());
        assertTrue(failure.getCause().getMessage().toLowerCase().contains("connect"));
    }

    @Test
    void reportsSynchronousRequestFailureAsAProviderError(@TempDir Path directory) {
        AiGenerationService service = new AiGenerationService(directory.resolve("prefs"), provider -> "test-key",
                (provider, model, key, prompt) -> { throw new IllegalStateException("test-key"); });
        var failure = assertThrows(java.util.concurrent.CompletionException.class,
                () -> service.generate("a scene").join());
        assertTrue(failure.getCause().getMessage().contains("provider request"));
        assertTrue(failure.getCause().getMessage().contains("IllegalStateException"));
        assertFalse(failure.getCause().getMessage().contains("test-key"));
    }

    @Test
    void cancellationInterruptsTheProviderRequest(@TempDir Path directory) throws Exception {
        CompletableFuture<String> request = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        AiGenerationService service = new AiGenerationService(directory.resolve("prefs"), provider -> "test-key",
                (provider, model, key, prompt) -> { started.countDown(); return request; });
        var result = service.generate("a circle");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        result.cancel(true);
        assertTrue(request.isCancelled());
    }

    @Test
    void cancellingDuringCredentialLookupNeverStartsARequest(@TempDir Path directory) throws Exception {
        CountDownLatch inLookup = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean requested = new AtomicBoolean();
        try (var executor = Executors.newSingleThreadExecutor()) {
            AiGenerationService service = new AiGenerationService(directory.resolve("prefs"), provider -> {
                inLookup.countDown();
                try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                return "test-key";
            }, (provider, model, key, prompt) -> {
                requested.set(true);
                return new CompletableFuture<>();
            }, executor);
            var result = service.generate("a circle");
            assertTrue(inLookup.await(5, TimeUnit.SECONDS));
            result.cancel(true);
            release.countDown();
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertTrue(result.isCancelled());
            assertFalse(requested.get());
        }
    }
}
