package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.ai.AiProviderClient.Provider;
import com.dlsc.animatorfx.editor.ai.AiModelCatalog;
import com.dlsc.animatorfx.editor.ai.AiSettings;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiPreferencesDialogTest {
    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void showsOnlyTheSelectedProviderAndPreservesEditsWhenSwitching(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("prefs");
        var savedKeys = new java.util.concurrent.ConcurrentHashMap<String, String>();
        Stage stage = fx(() -> {
            DialogLayer layer = new DialogLayer();
            Stage s = new Stage();
            Scene scene = new Scene(new StackPane(layer.getPane()), 800, 600);
            Theme theme = new Theme();
            theme.install(scene);
            s.setOnHidden(event -> theme.dispose());
            s.setScene(scene);
            s.show();
            new AiPreferencesDialog(layer, file, savedKeys::put, provider -> {}, provider -> null,
                    (provider, secret) -> CompletableFuture.completedFuture(List.of())).show();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            return s;
        });
        try {
            fx(() -> {
                Scene scene = stage.getScene();
                @SuppressWarnings("unchecked")
                ChoiceBox<Provider> provider = (ChoiceBox<Provider>) scene.lookup("#ai-provider");
                ComboBox<?> openAi = (ComboBox<?>) scene.lookup("#ai-openai-model");
                ComboBox<?> anthropic = (ComboBox<?>) scene.lookup("#ai-anthropic-model");
                PasswordField openAiKey = (PasswordField) scene.lookup("#ai-openai-key");
                PasswordField anthropicKey = (PasswordField) scene.lookup("#ai-anthropic-key");
                assertTrue(showing(openAi));
                assertTrue(showing(openAiKey));
                assertFalse(showing(anthropic));
                assertFalse(showing(anthropicKey));
                assertFalse(anthropic.getParent().getParent().isManaged());
                openAi.getEditor().setText("edited-openai");
                openAiKey.setText("openai-test-key");
                provider.setValue(Provider.ANTHROPIC);
                assertFalse(showing(openAi));
                assertFalse(showing(openAiKey));
                assertTrue(showing(anthropic));
                assertTrue(showing(anthropicKey));
                assertFalse(openAi.getParent().getParent().isManaged());
                anthropic.getEditor().setText("edited-anthropic");
                anthropicKey.setText("anthropic-test-key");
                provider.setValue(Provider.OPENAI);
                assertEquals("edited-openai", openAi.getEditor().getText());
                assertEquals("openai-test-key", openAiKey.getText());
                provider.setValue(Provider.ANTHROPIC);
                assertEquals("edited-anthropic", anthropic.getEditor().getText());
                assertEquals("anthropic-test-key", anthropicKey.getText());
                ((Button) scene.lookup("#ai-save-preferences")).fire();
                return null;
            });
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> Files.exists(file)
                    && AiSettings.load(file).anthropicModel().equals("edited-anthropic"));
            AiSettings settings = AiSettings.load(file);
            assertEquals(Provider.ANTHROPIC, settings.provider());
            assertEquals("edited-openai", settings.openAiModel());
            assertEquals("edited-anthropic", settings.anthropicModel());
            assertEquals("openai-test-key", savedKeys.get("OPENAI"));
            assertEquals("anthropic-test-key", savedKeys.get("ANTHROPIC"));
        } finally {
            fx(() -> { stage.hide(); return null; });
        }
    }

    @Test
    void savesKeysSeparatelyFromModels(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("settings.properties");
        CountDownLatch saved = new CountDownLatch(1);
        AtomicReference<String> key = new AtomicReference<>();
        Stage stage = fx(() -> {
            DialogLayer layer = new DialogLayer();
            Stage s = new Stage();
            Scene scene = new Scene(new StackPane(layer.getPane()), 800, 600);
            Theme theme = new Theme();
            theme.install(scene);
            s.setOnHidden(event -> theme.dispose());
            s.setScene(scene);
            s.show();
            AiPreferencesDialog dialog = new AiPreferencesDialog(layer, file,
                    (provider, secret) -> { key.set(secret); saved.countDown(); }, provider -> {},
                    provider -> { throw new IllegalStateException(); },
                    (provider, secret) -> CompletableFuture.completedFuture(List.of()));
            dialog.show();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            @SuppressWarnings("unchecked")
            ChoiceBox<Provider> provider = (ChoiceBox<Provider>) scene.lookup("#ai-provider");
            provider.setValue(Provider.ANTHROPIC);
            @SuppressWarnings("unchecked")
            ComboBox<String> model = (ComboBox<String>) scene.lookup("#ai-anthropic-model");
            model.getEditor().setText("custom-model");
            ((PasswordField) scene.lookup("#ai-anthropic-key")).setText("synthetic-secret");
            ((Button) scene.lookup("#ai-save-preferences")).fire();
            return s;
        });
        try {
            assertTrue(saved.await(5, TimeUnit.SECONDS));
            WaitForAsyncUtils.waitForFxEvents();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> Files.exists(file) && AiSettings.load(file).provider() == Provider.ANTHROPIC
                            && AiSettings.load(file).anthropicModel().equals("custom-model"));
            AiSettings settings = AiSettings.load(file);
            assertEquals(Provider.ANTHROPIC, settings.provider());
            assertEquals("custom-model", settings.anthropicModel());
            assertEquals("synthetic-secret", key.get());
            assertFalse(Files.readString(file).contains("synthetic-secret"));
        } finally {
            fx(() -> { stage.hide(); return null; });
        }
    }

    @Test
    void loadsBothProvidersAndKeepsTheSavedCustomSelections(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("settings.properties");
        new AiSettings(Provider.ANTHROPIC, "custom-openai", "custom-claude").save(file);
        Stage stage = fx(() -> {
            DialogLayer layer = new DialogLayer();
            Stage s = new Stage();
            Scene scene = new Scene(new StackPane(layer.getPane()), 800, 600);
            Theme theme = new Theme();
            theme.install(scene);
            s.setOnHidden(event -> theme.dispose());
            s.setScene(scene);
            s.show();
            new AiPreferencesDialog(layer, file, (provider, secret) -> {}, provider -> {},
                    provider -> "dummy-key", (provider, secret) -> CompletableFuture.completedFuture(
                            provider == Provider.OPENAI ? List.of("gpt-example") : List.of("claude-example"))).show();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            return s;
        });
        try {
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> fx(() -> {
                ComboBox<?> openAi = (ComboBox<?>) stage.getScene().lookup("#ai-openai-model");
                ComboBox<?> anthropic = (ComboBox<?>) stage.getScene().lookup("#ai-anthropic-model");
                return openAi.getItems().contains("gpt-example") && anthropic.getItems().contains("claude-example");
            }));
            fx(() -> {
                ComboBox<?> openAi = (ComboBox<?>) stage.getScene().lookup("#ai-openai-model");
                ComboBox<?> anthropic = (ComboBox<?>) stage.getScene().lookup("#ai-anthropic-model");
                assertEquals("custom-openai", openAi.getEditor().getText());
                assertEquals("custom-claude", anthropic.getEditor().getText());
                assertFalse(showing(openAi));
                assertTrue(showing(anthropic));
                return null;
            });
        } finally {
            fx(() -> { stage.hide(); return null; });
        }
    }

    @Test
    void showsTheReasonModelLoadingFailed(@TempDir Path directory) throws Exception {
        Stage stage = fx(() -> {
            DialogLayer layer = new DialogLayer();
            Stage s = new Stage();
            Scene scene = new Scene(new StackPane(layer.getPane()), 800, 600);
            Theme theme = new Theme();
            theme.install(scene);
            s.setOnHidden(event -> theme.dispose());
            s.setScene(scene);
            s.show();
            new AiPreferencesDialog(layer, directory.resolve("prefs"), (provider, secret) -> {}, provider -> {},
                    provider -> "dummy-key", (provider, secret) -> new AiModelCatalog().listModels(provider, "")).show();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            return s;
        });
        try {
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> fx(() ->
                    ((Label) stage.getScene().lookup("#ai-openai-model-status")).getText().contains("API key are required")));
        } finally {
            fx(() -> { stage.hide(); return null; });
        }
    }

    @Test
    void refreshWithANewKeyDoesNotLetAnOldLookupReplaceTheNewList(@TempDir Path directory) throws Exception {
        CompletableFuture<List<String>> oldResponse = new CompletableFuture<>();
        CountDownLatch oldRequest = new CountDownLatch(1);
        Stage stage = fx(() -> {
            DialogLayer layer = new DialogLayer();
            Stage s = new Stage();
            Scene scene = new Scene(new StackPane(layer.getPane()), 800, 600);
            Theme theme = new Theme();
            theme.install(scene);
            s.setOnHidden(event -> theme.dispose());
            s.setScene(scene);
            s.show();
            new AiPreferencesDialog(layer, directory.resolve("prefs"), (provider, secret) -> {}, provider -> {},
                    provider -> "old-key", (provider, secret) -> {
                        if (provider == Provider.OPENAI && secret.equals("old-key")) {
                            oldRequest.countDown();
                            return oldResponse;
                        }
                        return CompletableFuture.completedFuture(List.of("new-model"));
                    }).show();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            return s;
        });
        try {
            assertTrue(oldRequest.await(5, TimeUnit.SECONDS));
            fx(() -> {
                ((PasswordField) stage.getScene().lookup("#ai-openai-key")).setText("new-key");
                ((Button) stage.getScene().lookup("#ai-openai-refresh")).fire();
                return null;
            });
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> fx(() ->
                    ((ComboBox<?>) stage.getScene().lookup("#ai-openai-model")).getItems().contains("new-model")));
            oldResponse.complete(List.of("old-model"));
            WaitForAsyncUtils.waitForFxEvents();
            assertFalse(fx(() -> ((ComboBox<?>) stage.getScene().lookup("#ai-openai-model"))
                    .getItems().contains("old-model")));
        } finally {
            fx(() -> { stage.hide(); return null; });
        }
    }

    @Test
    void selectingAProviderModelSavesItsIdentifier(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("prefs");
        Stage stage = fx(() -> {
            DialogLayer layer = new DialogLayer();
            Stage s = new Stage();
            Scene scene = new Scene(new StackPane(layer.getPane()), 800, 600);
            Theme theme = new Theme();
            theme.install(scene);
            s.setOnHidden(event -> theme.dispose());
            s.setScene(scene);
            s.show();
            new AiPreferencesDialog(layer, file, (provider, secret) -> {}, provider -> {},
                    provider -> "dummy-key", (provider, secret) -> CompletableFuture.completedFuture(
                            List.of(provider == Provider.OPENAI ? "gpt-example" : "claude-example"))).show();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            return s;
        });
        try {
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> fx(() ->
                    ((ComboBox<?>) stage.getScene().lookup("#ai-anthropic-model")).getItems().contains("claude-example")));
            fx(() -> {
                @SuppressWarnings("unchecked")
                ChoiceBox<Provider> provider = (ChoiceBox<Provider>) stage.getScene().lookup("#ai-provider");
                provider.setValue(Provider.ANTHROPIC);
                @SuppressWarnings("unchecked")
                ComboBox<String> model = (ComboBox<String>) stage.getScene().lookup("#ai-anthropic-model");
                model.getSelectionModel().select("claude-example");
                ((Button) stage.getScene().lookup("#ai-save-preferences")).fire();
                return null;
            });
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> Files.exists(file) && AiSettings.load(file).anthropicModel().equals("claude-example"));
            assertEquals("claude-example", AiSettings.load(file).anthropicModel());
        } finally {
            fx(() -> { stage.hide(); return null; });
        }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        return WaitForAsyncUtils.asyncFx(action).get();
    }

    private static boolean showing(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible()) return false;
        }
        return true;
    }
}
