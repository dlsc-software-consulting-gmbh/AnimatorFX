package com.dlsc.animatorfx.editor.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/** Non-secret AI preferences. API keys are stored separately in the operating system. */
public record AiSettings(AiProviderClient.Provider provider, String openAiModel, String anthropicModel) {

    public AiSettings {
        Objects.requireNonNull(provider);
        if (openAiModel == null || openAiModel.isBlank() || openAiModel.length() > 200
                || anthropicModel == null || anthropicModel.isBlank() || anthropicModel.length() > 200) {
            throw new IllegalArgumentException("Enter a model name for each provider.");
        }
    }

    public static AiSettings defaults() {
        return new AiSettings(AiProviderClient.Provider.OPENAI, "gpt-4.1", "claude-sonnet-4-5");
    }

    public String model() {
        return provider == AiProviderClient.Provider.OPENAI ? openAiModel : anthropicModel;
    }

    public static AiSettings load(Path path) throws IOException {
        if (!Files.exists(path)) return defaults();
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        AiSettings defaults = defaults();
        try {
            return new AiSettings(AiProviderClient.Provider.valueOf(properties.getProperty("provider", defaults.provider.name())),
                    properties.getProperty("openai.model", defaults.openAiModel),
                    properties.getProperty("anthropic.model", defaults.anthropicModel));
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid AI preferences.", ex);
        }
    }

    public void save(Path path) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("provider", provider.name());
        properties.setProperty("openai.model", openAiModel);
        properties.setProperty("anthropic.model", anthropicModel);
        Path parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        try (OutputStream output = Files.newOutputStream(path)) {
            properties.store(output, "AnimatorFX AI preferences (no API keys)");
        }
    }
}
