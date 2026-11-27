package com.dlsc.animatorfx.editor.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSettingsTest {
    @Test
    void storesTheSelectedProviderAndEachCustomModel(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("preferences.properties");
        new AiSettings(AiProviderClient.Provider.ANTHROPIC, "my-openai-model", "my-anthropic-model").save(file);
        AiSettings loaded = AiSettings.load(file);
        assertEquals(AiProviderClient.Provider.ANTHROPIC, loaded.provider());
        assertEquals("my-openai-model", loaded.openAiModel());
        assertEquals("my-anthropic-model", loaded.anthropicModel());
        assertFalse(Files.readString(file).contains("apiKey"));
    }

    @Test
    void missingSettingsUseDefaults(@TempDir Path directory) throws Exception {
        assertEquals(AiSettings.defaults(), AiSettings.load(directory.resolve("missing")));
        assertFalse(AiSettings.defaults().visualReview());
    }

    @Test
    void visualReviewIsOptInAndPersistsAcrossLegacySettings(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("preferences.properties");
        Files.writeString(file, "provider=OPENAI\nopenai.model=gpt\nanthropic.model=claude\n");
        assertFalse(AiSettings.load(file).visualReview());
        assertFalse(new AiSettings(AiProviderClient.Provider.OPENAI, "gpt", "claude").visualReview());
        new AiSettings(AiProviderClient.Provider.OPENAI, "gpt", "claude", true).save(file);
        assertTrue(AiSettings.load(file).visualReview());
    }
}
