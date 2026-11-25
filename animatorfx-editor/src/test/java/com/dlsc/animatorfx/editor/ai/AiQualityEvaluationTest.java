package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.io.AnimationIO;
import com.dlsc.animatorfx.model.io.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AiQualityEvaluationTest {
    private static final String VALID = """
            {"version":1,"name":"Synthetic","width":320,"height":240,"frameRate":30,"duration":2,
             "background":{"r":0,"g":0,"b":0,"a":0},"layers":[]}
            """;

    @Test
    void requiresExplicitProviderModelsAndDestinationBeforeLookingUpCredentials(@TempDir Path directory) {
        List<String[]> invalid = List.of(
                new String[]{},
                new String[]{"--model", "model", "--output", directory.toString()},
                new String[]{"--provider", "OPENAI", "--output", directory.toString()},
                new String[]{"--provider", "OPENAI", "--model", "model"},
                args(directory, "OTHER", "model"),
                args(directory, "OPENAI", "model\ninjected"),
                new String[]{"--provider", "OPENAI", "--model", "model", "--output", directory.toString(),
                        "--key", "never-echo-this"},
                new String[]{"--provider", "OPENAI", "--model", "model", "--output"});
        for (String[] arguments : invalid) {
            var output = new ByteArrayOutputStream();
            assertEquals(2, AiQualityEvaluation.execute(arguments,
                    name -> fail("Must not read environment"), name -> fail("Must not read credential store"),
                    (provider, model, key, prompt) -> fail("Must not request a provider"),
                    new PrintStream(output), Duration.ofSeconds(1)));
            assertFalse(output.toString().contains("never-echo-this"));
        }
        assertEquals(0, directory.toFile().list().length);
    }

    @Test
    void comparesModelsAndWritesEditableFilesAndMeasuredResultsWithoutOverwriting(@TempDir Path directory)
            throws Exception {
        List<String> selections = new ArrayList<>();
        var output = new ByteArrayOutputStream();
        AiGenerationService.Request request = (provider, model, key, prompt) -> {
            assertEquals(AiProviderClient.Provider.OPENAI, provider);
            assertEquals("environment-secret", key);
            assertFalse(prompt.isBlank());
            selections.add(model);
            return CompletableFuture.completedFuture(VALID);
        };
        String[] arguments = {"--provider", "OPENAI", "--model", "org/model:latest",
                "--model", "../other", "--output", directory.toString()};
        for (int run = 0; run < 2; run++) {
            assertEquals(0, AiQualityEvaluation.execute(arguments,
                    name -> name.equals("OPENAI_API_KEY") ? "environment-secret" : null,
                    name -> fail("Environment key takes precedence"), request, new PrintStream(output),
                    Duration.ofSeconds(1)));
        }
        var runs = children(directory);
        assertEquals(2, runs.size());
        for (Path run : runs) {
            var rows = rows(run);
            assertTrue(rows.size() >= 6, "Several prompts must be evaluated for each model");
            assertEquals(rows.size() / 2, rows.stream().filter(row -> row.get("model").equals("../other")).count());
            for (Map<?, ?> row : rows) {
                assertEquals("OPENAI", row.get("provider"));
                assertEquals("passed", row.get("validation"));
                assertEquals("saved", row.get("status"));
                assertTrue(((Number) row.get("latencyMillis")).doubleValue() >= 0);
                assertEquals(1, ((Number) row.get("providerCalls")).intValue());
                String fileName = (String) row.get("file");
                assertTrue(fileName.matches("model-[0-9]+-prompt-[0-9]+\\.afx"));
                Path file = run.resolve(fileName);
                assertEquals(run, file.getParent());
                assertEquals("Synthetic", AnimationIO.read(file).animation().name());
            }
            assertEquals(rows.size() + 1, children(run).size());
            assertFalse(Files.readString(run.resolve("results.jsonl")).contains("environment-secret"));
        }
        assertEquals(selections.size() / 2, selections.stream().filter("../other"::equals).count());
        assertFalse(output.toString().contains("environment-secret"));
    }

    @Test
    void recordsInvalidAndProviderFailuresAndContinuesToSuccessfulModels(@TempDir Path directory) throws Exception {
        var output = new ByteArrayOutputStream();
        String[] arguments = {"--provider", "ANTHROPIC", "--model", "invalid",
                "--model", "offline", "--model", "good", "--output", directory.toString()};
        assertEquals(1, AiQualityEvaluation.execute(arguments, name -> null, provider -> {
            assertEquals("ANTHROPIC", provider);
            return "credential-secret";
        }, (provider, model, key, prompt) -> switch (model) {
            case "invalid" -> CompletableFuture.completedFuture("{}");
            case "offline" -> CompletableFuture.failedFuture(
                    new IllegalStateException("credential-secret " + prompt));
            default -> CompletableFuture.completedFuture(VALID);
        }, new PrintStream(output), Duration.ofSeconds(1)));
        Path run = children(directory).getFirst();
        for (Map<?, ?> row : rows(run)) {
            boolean good = row.get("model").equals("good");
            assertEquals(good ? "saved" : "generation_failed", row.get("status"));
            assertEquals(good ? "passed" : "not_passed", row.get("validation"));
            assertEquals(good, row.get("file") instanceof String);
            assertEquals(row.get("model").equals("invalid") ? 3 : 1,
                    ((Number) row.get("providerCalls")).intValue());
        }
        assertFalse(Files.readString(run.resolve("results.jsonl")).contains("credential-secret"));
        assertFalse(output.toString().contains("credential-secret"));
    }

    @Test
    void savesARepairedResponseAndIncludesAllAttempts(@TempDir Path directory) throws Exception {
        var calls = new AtomicInteger();
        assertEquals(0, AiQualityEvaluation.execute(args(directory, "OPENAI", "model"),
                name -> "secret", name -> fail("No store access"),
                (provider, model, key, prompt) -> CompletableFuture.completedFuture(
                        calls.getAndIncrement() % 2 == 0 ? "{}" : VALID),
                new PrintStream(new ByteArrayOutputStream()), Duration.ofSeconds(1)));
        for (Map<?, ?> row : rows(children(directory).getFirst())) {
            assertEquals("passed", row.get("validation"));
            assertEquals(2, ((Number) row.get("providerCalls")).intValue());
        }
    }

    @Test
    void neverPersistsACredentialEchoedByAnOtherwiseValidProviderDocument(@TempDir Path directory) throws Exception {
        var output = new ByteArrayOutputStream();
        assertEquals(1, AiQualityEvaluation.execute(args(directory, "OPENAI", "model"),
                name -> "credential-secret", name -> null,
                (provider, model, key, prompt) -> CompletableFuture.completedFuture(
                        VALID.replace("Synthetic", "credential-secret")),
                new PrintStream(output), Duration.ofSeconds(1)));
        Path run = children(directory).getFirst();
        assertEquals(1, children(run).size(), "Only a report, never a document containing the credential");
        for (Map<?, ?> row : rows(run)) {
            assertEquals("sensitive_output", row.get("status"));
            assertEquals("passed", row.get("validation"));
            assertNull(row.get("file"));
        }
        assertFalse(Files.readString(run.resolve("results.jsonl")).contains("credential-secret"));
        assertFalse(output.toString().contains("credential-secret"));
    }

    @Test
    void rejectsACredentialAccidentallyUsedAsAModelId(@TempDir Path directory) {
        var output = new ByteArrayOutputStream();
        assertEquals(2, AiQualityEvaluation.execute(args(directory, "OPENAI", "credential-secret"),
                name -> "credential-secret", name -> null,
                (provider, model, key, prompt) -> fail("Must not send a credential as the model ID"),
                new PrintStream(output), Duration.ofSeconds(1)));
        assertFalse(output.toString().contains("credential-secret"));
        assertEquals(0, directory.toFile().list().length);
    }

    @Test
    void refusesToOverwriteAnExistingOutputEvenWhenCreatedDuringGeneration(@TempDir Path directory)
            throws Exception {
        var calls = new AtomicInteger();
        assertEquals(1, AiQualityEvaluation.execute(args(directory, "OPENAI", "model"),
                name -> "secret", name -> fail("No store access"), (provider, model, key, prompt) -> {
                    if (calls.getAndIncrement() == 0) {
                        try {
                            Files.writeString(children(directory).getFirst().resolve("model-1-prompt-1.afx"), "keep");
                        } catch (Exception ex) {
                            throw new AssertionError(ex);
                        }
                    }
                    return CompletableFuture.completedFuture(VALID);
                }, new PrintStream(new ByteArrayOutputStream()), Duration.ofSeconds(1)));
        Path run = children(directory).getFirst();
        assertEquals("keep", Files.readString(run.resolve("model-1-prompt-1.afx")));
        assertEquals("save_failed", rows(run).getFirst().get("status"));
        assertEquals("passed", rows(run).getFirst().get("validation"));
        assertNull(rows(run).getFirst().get("file"));
        assertEquals("saved", rows(run).getLast().get("status"));
    }

    @Test
    void missingCredentialsAndUnwritableDestinationMakeNoRequests(@TempDir Path directory) throws Exception {
        var output = new PrintStream(new ByteArrayOutputStream());
        assertEquals(2, AiQualityEvaluation.execute(args(directory, "OPENAI", "model"),
                name -> null, name -> { throw new IllegalStateException("sensitive store detail"); },
                (provider, model, key, prompt) -> fail("No request without credentials"),
                output, Duration.ofSeconds(1)));
        Path notDirectory = directory.resolve("file");
        Files.writeString(notDirectory, "keep");
        assertEquals(2, AiQualityEvaluation.execute(args(notDirectory, "OPENAI", "model"),
                name -> "secret", name -> null,
                (provider, model, key, prompt) -> fail("No request without output"),
                output, Duration.ofSeconds(1)));
        assertEquals("keep", Files.readString(notDirectory));
        assertEquals(1, children(directory).size());
    }

    @Test
    void doesNotFollowAnExistingOutputSymlink(@TempDir Path directory) throws Exception {
        Path original = directory.resolve("original.afx");
        Files.writeString(original, "keep");
        Path destination = directory.resolve("runs");
        var calls = new AtomicInteger();
        assertEquals(1, AiQualityEvaluation.execute(args(destination, "OPENAI", "model"),
                name -> "secret", name -> null, (provider, model, key, prompt) -> {
                    if (calls.getAndIncrement() == 0) {
                        try {
                            Files.createSymbolicLink(children(destination).getFirst()
                                    .resolve("model-1-prompt-1.afx"), original);
                        } catch (Exception ex) {
                            throw new AssertionError(ex);
                        }
                    }
                    return CompletableFuture.completedFuture(VALID);
                }, new PrintStream(new ByteArrayOutputStream()), Duration.ofSeconds(1)));
        assertEquals("keep", Files.readString(original));
        assertEquals("save_failed", rows(children(destination).getFirst()).getFirst().get("status"));
    }

    @Test
    void interruptionCancelsAndReportsTheCurrentCaseWithoutStartingMore(@TempDir Path directory) throws Exception {
        var pending = new CompletableFuture<String>();
        try {
            assertEquals(1, AiQualityEvaluation.execute(args(directory, "OPENAI", "model"),
                    name -> "secret", name -> null, (provider, model, key, prompt) -> {
                        Thread.currentThread().interrupt();
                        return pending;
                    }, new PrintStream(new ByteArrayOutputStream()), Duration.ofSeconds(1)));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(pending.isCancelled());
        } finally {
            Thread.interrupted();
        }
        var rows = rows(children(directory).getFirst());
        assertEquals(1, rows.size());
        assertEquals("interrupted", rows.getFirst().get("status"));
    }

    @Test
    void timesOutAndCancelsOutstandingRequests(@TempDir Path directory) throws Exception {
        List<CompletableFuture<String>> requests = new ArrayList<>();
        assertEquals(1, AiQualityEvaluation.execute(args(directory, "OPENAI", "model"),
                name -> "secret", name -> null, (provider, model, key, prompt) -> {
                    var future = new CompletableFuture<String>();
                    requests.add(future);
                    return future;
                }, new PrintStream(new ByteArrayOutputStream()), Duration.ofMillis(20)));
        assertTrue(requests.stream().allMatch(CompletableFuture::isCancelled));
        for (Map<?, ?> row : rows(children(directory).getFirst())) {
            assertEquals("timed_out", row.get("status"));
            assertEquals("not_passed", row.get("validation"));
            assertNull(row.get("file"));
        }
    }

    private static String[] args(Path directory, String provider, String model) {
        return new String[]{"--provider", provider, "--model", model, "--output", directory.toString()};
    }

    private static List<Path> children(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return files.sorted().toList();
        }
    }

    private static List<Map<?, ?>> rows(Path directory) throws Exception {
        List<Map<?, ?>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(directory.resolve("results.jsonl"))) {
            rows.add((Map<?, ?>) Json.parse(line));
        }
        return rows;
    }
}
