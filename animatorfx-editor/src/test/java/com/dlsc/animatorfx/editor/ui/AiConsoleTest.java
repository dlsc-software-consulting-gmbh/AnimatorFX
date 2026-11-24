package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.session.RecoveryStore;
import com.dlsc.animatorfx.editor.session.SessionStore;
import com.dlsc.animatorfx.editor.ai.AiProviderClient.Provider;
import com.dlsc.animatorfx.editor.ai.AiSettings;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiConsoleTest {
    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void configuredProviderMakesTheConsoleAvailable(@TempDir Path directory) throws Exception {
        MainWindow window = WaitForAsyncUtils.asyncFx(() -> new MainWindow(new Stage(),
                new SessionStore(directory), new RecoveryStore(directory.resolve("recovery")), null,
                provider -> "test-key", (provider, key) -> CompletableFuture.completedFuture(List.of()),
                directory.resolve("ai.properties"), (provider, key) -> {}, provider -> {})).get();
        try {
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> WaitForAsyncUtils.asyncFx(() ->
                    window.getSidePanel().getTabPane().getTabs().stream()
                            .anyMatch(tab -> "ai-console".equals(tab.getId()))).get());
            assertTrue(WaitForAsyncUtils.asyncFx(() -> window.getSidePanel().getTabPane().getTabs().stream()
                    .anyMatch(tab -> "ai-console".equals(tab.getId()))).get());
            WaitForAsyncUtils.asyncFx(() -> {
                window.getSidePanel().setActiveTab("ai-console");
                layout(window);
                assertTrue(window.getScene().lookup("#ai-chat-input") instanceof TextArea);
                return null;
            }).get();
        } finally {
            WaitForAsyncUtils.asyncFx(() -> { window.shutdown(); window.getStage().hide(); return null; }).get();
        }
    }

    @Test
    void consoleFollowsTheSelectedProviderAndSavedKey(@TempDir Path directory) throws Exception {
        Path settings = directory.resolve("ai.properties");
        AiSettings.defaults().save(settings);
        var keys = new ConcurrentHashMap<String, String>();
        keys.put("OPENAI", "test-key");
        MainWindow window = WaitForAsyncUtils.asyncFx(() -> {
            MainWindow result = new MainWindow(new Stage(), new SessionStore(directory.resolve("session")),
                    new RecoveryStore(directory.resolve("recovery")), null,
                    keys::get, (provider, key) -> CompletableFuture.completedFuture(List.of()),
                    settings, keys::put, keys::remove);
            result.show();
            return result;
        }).get();
        try {
            waitForConsole(window, true);
            WaitForAsyncUtils.asyncFx(() -> {
                window.showAiPreferences();
                layout(window);
                @SuppressWarnings("unchecked")
                ChoiceBox<Provider> provider = (ChoiceBox<Provider>) window.getScene().lookup("#ai-provider");
                provider.setValue(Provider.ANTHROPIC);
                ((Button) window.getScene().lookup("#ai-save-preferences")).fire();
                return null;
            }).get();
            waitForConsole(window, false);
            WaitForAsyncUtils.asyncFx(() -> {
                window.showAiPreferences();
                layout(window);
                ((PasswordField) window.getScene().lookup("#ai-anthropic-key")).setText("another-key");
                ((Button) window.getScene().lookup("#ai-save-preferences")).fire();
                return null;
            }).get();
            waitForConsole(window, true);
            WaitForAsyncUtils.asyncFx(() -> {
                window.showAiPreferences();
                layout(window);
                CheckBox remove = window.getScene().getRoot().lookupAll(".check-box").stream()
                        .filter(CheckBox.class::isInstance).map(CheckBox.class::cast)
                        .filter(box -> box.getText().equals("Remove saved Anthropic key")).findFirst().orElseThrow();
                remove.setSelected(true);
                ((Button) window.getScene().lookup("#ai-save-preferences")).fire();
                return null;
            }).get();
            waitForConsole(window, false);
            assertTrue(keys.containsKey("OPENAI"));
            assertFalse(keys.containsKey("ANTHROPIC"));
        } finally {
            WaitForAsyncUtils.asyncFx(() -> { window.shutdown(); window.getStage().hide(); return null; }).get();
        }
    }

    @Test
    void consoleCanBeCleared() throws Exception {
        WaitForAsyncUtils.asyncFx(() -> {
            AiConsole console = new AiConsole();
            new Scene(new StackPane(console));
            console.log("Generation started: OPENAI / model");
            TextArea output = (TextArea) console.lookup("#ai-console-output");
            assertTrue(output.getText().contains("Generation started"));
            ((Button) console.lookup("#ai-console-clear")).fire();
            assertEquals("", output.getText());
            return null;
        }).get();
    }

    @Test
    void tabTitlesRemainReadableWhenConsoleIsAvailable(@TempDir Path directory) throws Exception {
        MainWindow window = WaitForAsyncUtils.asyncFx(() -> {
            MainWindow result = new MainWindow(new Stage(), new SessionStore(directory),
                    new RecoveryStore(directory.resolve("recovery")), null,
                    provider -> "test-key", (provider, key) -> CompletableFuture.completedFuture(List.of()),
                    directory.resolve("ai.properties"), (provider, key) -> {}, provider -> {});
            result.getStage().setWidth(1240);
            result.getStage().setHeight(780);
            result.show();
            return result;
        }).get();
        try {
            waitForConsole(window, true);
            WaitForAsyncUtils.asyncFx(() -> {
                layout(window);
                var pane = window.getSidePanel().getTabPane();
                var headers = pane.lookup("#side-panel-tabs > .tab-header-area > .headers-region");
                var clip = headers.localToScene(headers.getClip().getBoundsInParent());
                var tabs = pane.lookupAll("#side-panel-tabs > .tab-header-area > .headers-region > .tab");
                assertEquals(4, tabs.size());
                for (var tab : tabs) {
                    Label label = (Label) tab.lookup(".tab-label");
                    assertTrue(label.getWidth() >= Math.floor(label.prefWidth(-1)));
                    assertTrue(tab.localToScene(tab.getLayoutBounds()).getMaxX() <= clip.getMaxX() + 0.5);
                }
                window.getStage().setWidth(900);
                window.getSidePanel().setActiveTab("ai-console");
                layout(window);
                var sideBounds = window.getSidePanel().localToScene(window.getSidePanel().getLayoutBounds());
                for (String id : List.of("#ai-chat-input", "#ai-chat-send", "#ai-chat-cancel", "#ai-chat-clear")) {
                    var node = window.getScene().lookup(id);
                    assertTrue(node != null, id);
                    assertTrue(node.localToScene(node.getLayoutBounds()).getMaxX() <= sideBounds.getMaxX() + 0.5, id);
                }
                return null;
            }).get();
        } finally {
            WaitForAsyncUtils.asyncFx(() -> { window.shutdown(); window.getStage().hide(); return null; }).get();
        }
    }

    @Test
    void selectedConsoleIsRestoredWhenProviderIsStillConfigured(@TempDir Path directory) throws Exception {
        MainWindow first = configuredWindow(directory);
        waitForConsole(first, true);
        WaitForAsyncUtils.asyncFx(() -> {
            first.getSidePanel().setActiveTab("ai-console");
            first.shutdown();
            first.getStage().hide();
            return null;
        }).get();
        MainWindow second = configuredWindow(directory);
        try {
            waitForConsole(second, true);
            assertEquals("ai-console", WaitForAsyncUtils.asyncFx(() ->
                    second.getSidePanel().getActiveTab()).get());
        } finally {
            WaitForAsyncUtils.asyncFx(() -> { second.shutdown(); second.getStage().hide(); return null; }).get();
        }
    }

    private static MainWindow configuredWindow(Path directory) throws Exception {
        return WaitForAsyncUtils.asyncFx(() -> {
            MainWindow window = new MainWindow(new Stage(), new SessionStore(directory),
                    new RecoveryStore(directory.resolve("recovery")), null,
                    provider -> "test-key", (provider, key) -> CompletableFuture.completedFuture(List.of()),
                    directory.resolve("ai.properties"), (provider, key) -> {}, provider -> {});
            window.show();
            return window;
        }).get();
    }

    private static void waitForConsole(MainWindow window, boolean present) throws Exception {
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> WaitForAsyncUtils.asyncFx(() ->
                window.getSidePanel().getTabPane().getTabs().stream()
                        .anyMatch(tab -> "ai-console".equals(tab.getId())) == present).get());
    }

    private static void layout(MainWindow window) {
        window.getScene().getRoot().applyCss();
        window.getScene().getRoot().layout();
    }
}
