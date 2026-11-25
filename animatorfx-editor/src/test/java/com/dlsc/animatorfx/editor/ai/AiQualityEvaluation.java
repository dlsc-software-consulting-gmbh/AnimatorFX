package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.model.io.AnimationIO;
import com.dlsc.animatorfx.model.io.AnimationJson;
import com.dlsc.animatorfx.model.io.JsonWriter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Manually invoked, billable evaluation; deliberately not a JUnit test.
 * The offline companion test injects only credentials and the provider transport.
 */
public final class AiQualityEvaluation {
    private record Prompt(String id, String description) {}

    private static final List<Prompt> PROMPTS = List.of(
            new Prompt("bounce", "Create a 320x240, 3-second animation on a pale background: a blue ball "
                    + "falls onto a dark horizontal ground line, squashes at contact, then rebounds twice "
                    + "with diminishing height. Keep all objects within the artboard. Use editable vectors only."),
            new Prompt("loop", "Create a 320x240, 3-second seamless looping loader: eight small circles "
                    + "arranged around the center, with a bright accent traveling clockwise through opacity "
                    + "changes. First and final frames must match. Use editable vectors only."),
            new Prompt("staged-card", "Create a 480x320, 3-second welcome card: a rounded white panel enters "
                    + "from below, then a green check mark and the readable text Welcome appear in sequence. "
                    + "Use a group for the panel contents, System font, generous margins and a dark background. "
                    + "Hold the completed composition for the final second. Use editable vectors only."),
            new Prompt("path-orbit", "Create a 400x300, 3-second animation: an orange dot travels once around "
                    + "a visible closed cubic Bezier oval path while a small central diamond rotates. "
                    + "Keep the dot centered on the path, make start and end match, and use contrasting "
                    + "colors on a dark background. Use editable paths and vector shapes only."));

    private record Options(AiProviderClient.Provider provider, List<String> models, Path output) {
        static Options parse(String[] args) {
            AiProviderClient.Provider provider = null;
            Path output = null;
            List<String> models = new ArrayList<>();
            for (int i = 0; i < args.length; i += 2) {
                if (i + 1 == args.length) throw new IllegalArgumentException();
                String value = args[i + 1];
                switch (args[i]) {
                    case "--provider" -> {
                        if (provider != null) throw new IllegalArgumentException();
                        provider = AiProviderClient.Provider.valueOf(value.toUpperCase(Locale.ROOT));
                    }
                    case "--model" -> {
                        if (!value.matches("[A-Za-z0-9.][A-Za-z0-9._:/-]{0,199}")) {
                            throw new IllegalArgumentException();
                        }
                        models.add(value);
                    }
                    case "--output" -> {
                        if (output != null || value.isBlank()) throw new IllegalArgumentException();
                        output = Path.of(value).toAbsolutePath().normalize();
                    }
                    default -> throw new IllegalArgumentException();
                }
            }
            if (provider == null || models.isEmpty() || output == null) throw new IllegalArgumentException();
            return new Options(provider, List.copyOf(models), output);
        }
    }

    private AiQualityEvaluation() {}

    public static void main(String[] args) {
        AiProviderClient client = new AiProviderClient();
        int status = execute(args, System::getenv, provider -> new AiCredentialStore().get(provider),
                (provider, model, key, prompt) -> client.generate(provider, model, key, prompt, AiAnimation.schema()),
                System.out, Duration.ofMinutes(6));
        if (status != 0) {
            throw new IllegalStateException("Quality evaluation unsuccessful; status " + status
                    + " (1: failed cases, 2: setup/report failure).");
        }
    }

    static int execute(String[] args, Function<String, String> environment,
                       Function<String, String> credentials, AiGenerationService.Request request,
                       PrintStream output, Duration timeout) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (RuntimeException ex) {
            output.println("Usage: --provider OPENAI|ANTHROPIC --model MODEL [--model MODEL ...] --output DIRECTORY");
            return 2;
        }
        String key;
        try {
            String variable = options.provider() == AiProviderClient.Provider.OPENAI
                    ? "OPENAI_API_KEY" : "ANTHROPIC_API_KEY";
            key = environment.apply(variable);
            if (key == null || key.isBlank()) key = credentials.apply(options.provider().name());
            if (key == null || key.isBlank() || key.length() > 1000) throw new IllegalArgumentException();
        } catch (RuntimeException | LinkageError ex) {
            output.println("No usable API key. Set the provider environment variable or use AI Preferences.");
            return 2;
        }
        for (String model : options.models()) {
            if (model.contains(key)) {
                output.println("Model IDs must not contain credentials.");
                return 2;
            }
        }
        // Never echo exception messages: even credential-store or filesystem errors may contain secrets.
        try {
            return evaluate(options, key, request, output, timeout);
        } catch (IOException | RuntimeException ex) {
            output.println("Could not create or finish the evaluation report. Check the destination.");
            return 2;
        }
    }

    private static int evaluate(Options options, String key, AiGenerationService.Request request,
                                PrintStream output, Duration timeout) throws IOException {
        Files.createDirectories(options.output());
        Path run = Files.createDirectory(options.output().resolve("quality-" + UUID.randomUUID()));
        boolean failed = false;
        try (var report = Files.newBufferedWriter(run.resolve("results.jsonl"), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            output.println("Evaluation started; each model runs four synthetic prompts sequentially.");
            for (int modelIndex = 0; modelIndex < options.models().size(); modelIndex++) {
                String model = options.models().get(modelIndex);
                AiSettings settings = new AiSettings(options.provider(), model, model);
                for (int promptIndex = 0; promptIndex < PROMPTS.size(); promptIndex++) {
                    Prompt prompt = PROMPTS.get(promptIndex);
                    AtomicInteger calls = new AtomicInteger();
                    AiGenerationService service = new AiGenerationService(run.resolve("unused-settings"),
                            ignored -> key, (provider, selected, credential, description) -> {
                                calls.incrementAndGet();
                                return request.send(provider, selected, credential, description);
                            }, Runnable::run);
                    String status = "generation_failed";
                    String validation = "not_passed";
                    String fileName = null;
                    long started = System.nanoTime();
                    CompletableFuture<AnimationFile> pending = service.generate(settings, prompt.description());
                    AnimationFile animation = null;
                    boolean interrupted = false;
                    try {
                        animation = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                        validation = "passed";
                    } catch (TimeoutException ex) {
                        pending.cancel(true);
                        status = "timed_out";
                    } catch (InterruptedException ex) {
                        pending.cancel(true);
                        interrupted = true;
                        status = "interrupted";
                    } catch (ExecutionException ex) {
                        // The service retains provider sanitization; reports intentionally omit error details.
                    }
                    double latencyMillis = (System.nanoTime() - started) / 1_000_000.0;
                    if (animation != null) {
                        String encodedKey = JsonWriter.write(key);
                        String document = JsonWriter.write(AnimationJson.toJson(animation.animation()));
                        if (document.contains(encodedKey.substring(1, encodedKey.length() - 1))) {
                            animation = null;
                            status = "sensitive_output";
                        }
                    }
                    if (animation != null) {
                        String candidate = "model-" + (modelIndex + 1) + "-prompt-" + (promptIndex + 1) + ".afx";
                        try {
                            try (var stream = Files.newOutputStream(run.resolve(candidate), StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE)) {
                                // The path overload replaces existing files; the exclusive stream never does.
                                AnimationIO.write(animation, stream);
                            }
                            fileName = candidate;
                            status = "saved";
                        } catch (IOException | RuntimeException ex) {
                            status = "save_failed";
                        }
                    }
                    var row = new LinkedHashMap<String, Object>();
                    row.put("provider", options.provider().name());
                    row.put("model", model);
                    row.put("promptId", prompt.id());
                    row.put("prompt", prompt.description());
                    row.put("validation", validation);
                    row.put("status", status);
                    row.put("latencyMillis", latencyMillis);
                    row.put("providerCalls", calls.get());
                    row.put("file", fileName);
                    report.write(JsonWriter.write(row).replace("\n", ""));
                    report.newLine();
                    report.flush();
                    output.println("Model " + (modelIndex + 1) + ", prompt " + (promptIndex + 1) + ": " + status);
                    failed |= !"saved".equals(status);
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                        return 1;
                    }
                }
            }
        }
        output.println("Evaluation finished. Inspect the new quality-* directory under the selected destination.");
        return failed ? 1 : 0;
    }
}
