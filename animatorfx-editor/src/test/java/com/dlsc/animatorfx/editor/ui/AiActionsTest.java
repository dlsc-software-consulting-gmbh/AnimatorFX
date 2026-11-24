package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.ai.AiGenerationService;
import com.dlsc.animatorfx.editor.ai.AiProviderClient.Provider;
import com.dlsc.animatorfx.editor.ai.AiSettings;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.io.AnimationFile;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class AiActionsTest {
    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void showsTheProviderAndModelActuallyUsedForGeneration() throws Exception {
        AiSettings shown = new AiSettings(Provider.ANTHROPIC, "openai-model", "claude-selected");
        AtomicReference<AiSettings> current = new AtomicReference<>(shown);
        AtomicReference<AiSettings> used = new AtomicReference<>();
        CompletableFuture<AnimationFile> response = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture(current, used, response));
        try {
            fx(() -> {
                fixture.actions.generate();
                fixture.scene.getRoot().applyCss();
                fixture.scene.getRoot().layout();
                String label = ((Label) fixture.scene.lookup("#ai-generation-model")).getText();
                assertTrue(label.contains("Anthropic"));
                assertTrue(label.contains("claude-selected"));
                current.set(new AiSettings(Provider.OPENAI, "different-model", "claude-selected"));
                ((TextArea) fixture.scene.lookup("#ai-description")).setText("A ball");
                ((Button) fixture.scene.lookup("#ai-generate")).fire();
                return null;
            });
            WaitForAsyncUtils.waitForFxEvents();
            assertEquals(shown, used.get());
        } finally {
            fx(() -> { fixture.actions.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void successfulGenerationCreatesANewDirtyUntitledDocument() throws Exception {
        CompletableFuture<AnimationFile> response = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture(response));
        try {
            fx(() -> {
                fixture.actions.generate();
                fixture.scene.getRoot().applyCss();
                fixture.scene.getRoot().layout();
                ((TextArea) fixture.scene.lookup("#ai-description")).setText("A bouncing ball");
                ((Button) fixture.scene.lookup("#ai-generate")).fire();
                return null;
            });
            WaitForAsyncUtils.waitForFxEvents();
            response.complete(new AnimationFile(Animation.create("Ball", 400, 300, 2), Map.of()));
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                assertEquals(2, fixture.workspace.getDocuments().size());
                EditorDocument generated = fixture.workspace.getActiveDocument();
                assertTrue(generated.isDirty());
                assertNull(generated.getPath());
                assertEquals("Ball", generated.getAnimation().name());
                String activity = String.join("\n", fixture.activity);
                assertTrue(activity.contains("started"));
                assertTrue(activity.contains("completed"));
                assertTrue(activity.contains("OPENAI"));
                assertTrue(activity.contains("gpt-4.1"));
                assertFalse(activity.contains("A bouncing ball"));
                return null;
            });
        } finally {
            fx(() -> { fixture.stage.hide(); return null; });
        }
    }

    @Test
    void cancellingGenerationLeavesExistingDocumentsUntouched() throws Exception {
        CompletableFuture<AnimationFile> response = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture(response));
        try {
            fx(() -> {
                fixture.actions.generate();
                fixture.scene.getRoot().applyCss();
                fixture.scene.getRoot().layout();
                ((TextArea) fixture.scene.lookup("#ai-description")).setText("A bouncing ball");
                ((Button) fixture.scene.lookup("#ai-generate")).fire();
                return null;
            });
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                fixture.scene.getRoot().applyCss();
                fixture.scene.getRoot().layout();
                ((Button) fixture.scene.lookup("#ai-cancel-generation")).fire();
                return null;
            });
            assertTrue(response.isCancelled());
            assertEquals(1, fx(() -> fixture.workspace.getDocuments().size()));
            assertTrue(fx(() -> String.join("\n", fixture.activity).contains("cancelled")));
        } finally {
            fx(() -> { fixture.stage.hide(); return null; });
        }
    }

    @Test
    void shuttingDownCancelsGenerationBeforeItCanAddADocument() throws Exception {
        CompletableFuture<AnimationFile> response = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture(response));
        try {
            fx(() -> {
                fixture.actions.generate();
                fixture.scene.getRoot().applyCss();
                fixture.scene.getRoot().layout();
                ((TextArea) fixture.scene.lookup("#ai-description")).setText("A bouncing ball");
                ((Button) fixture.scene.lookup("#ai-generate")).fire();
                return null;
            });
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> { fixture.actions.dispose(); return null; });
            assertTrue(response.isCancelled());
            assertEquals(1, fx(() -> fixture.workspace.getDocuments().size()));
        } finally {
            fx(() -> { fixture.stage.hide(); return null; });
        }
    }

    @Test
    void shuttingDownIgnoresACompletedResultStillQueuedForTheUi() throws Exception {
        CompletableFuture<AnimationFile> response = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture(response));
        try {
            fx(() -> {
                fixture.actions.generate();
                fixture.scene.getRoot().applyCss();
                fixture.scene.getRoot().layout();
                ((TextArea) fixture.scene.lookup("#ai-description")).setText("A bouncing ball");
                ((Button) fixture.scene.lookup("#ai-generate")).fire();
                return null;
            });
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                response.complete(new AnimationFile(Animation.create("Ball", 400, 300, 2), Map.of()));
                fixture.actions.dispose();
                return null;
            });
            WaitForAsyncUtils.waitForFxEvents();
            assertEquals(1, fx(() -> fixture.workspace.getDocuments().size()));
        } finally {
            fx(() -> { fixture.stage.hide(); return null; });
        }
    }

    @Test
    void providerResultIsValidatedAndOpenedInTheEditor(@TempDir Path directory) throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        CompletableFuture<String> response = new CompletableFuture<>();
        AiGenerationService service = new AiGenerationService(directory.resolve("prefs"), provider -> "test-key",
                (provider, model, key, prompt) -> { sent.countDown(); return response; });
        Fixture fixture = fx(() -> new Fixture(service::generate));
        try {
            fx(() -> {
                fixture.actions.generate();
                fixture.scene.getRoot().applyCss();
                fixture.scene.getRoot().layout();
                ((TextArea) fixture.scene.lookup("#ai-description")).setText("A scene");
                ((Button) fixture.scene.lookup("#ai-generate")).fire();
                return null;
            });
            assertTrue(sent.await(5, TimeUnit.SECONDS));
            response.complete("""
                    {"version":1,"name":"Mock","width":320,"height":240,"frameRate":30,"duration":1,
                     "background":{"r":0,"g":0,"b":0,"a":0},"layers":[]}
                    """);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> fx(() -> fixture.workspace.getDocuments().size() == 2));
            fx(() -> {
                assertEquals("Mock", fixture.workspace.getActiveDocument().getAnimation().name());
                assertTrue(fixture.workspace.getActiveDocument().isDirty());
                assertNull(fixture.workspace.getActiveDocument().getPath());
                return null;
            });
        } finally {
            fx(() -> { fixture.stage.hide(); return null; });
        }
    }

    @Test
    void displaysTheTechnicalReasonForFailedGeneration(@TempDir Path directory) throws Exception {
        AiGenerationService service = new AiGenerationService(directory.resolve("prefs"), provider -> "test-key",
                (provider, model, key, prompt) -> CompletableFuture.completedFuture("""
                        {"version":1,"name":"Mock","width":0,"height":240,"frameRate":30,"duration":1,
                         "background":{"r":0,"g":0,"b":0,"a":0},"layers":[]}
                        """));
        Fixture fixture = fx(() -> new Fixture(service::generate));
        try {
            fx(() -> {
                fixture.actions.generate();
                fixture.scene.getRoot().applyCss();
                fixture.scene.getRoot().layout();
                ((TextArea) fixture.scene.lookup("#ai-description")).setText("A scene");
                ((Button) fixture.scene.lookup("#ai-generate")).fire();
                return null;
            });
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> fx(() -> fixture.scene.getRoot()
                    .lookupAll(".label").stream().filter(Label.class::isInstance)
                    .map(Label.class::cast).anyMatch(label -> label.getText().contains("width"))));
            assertEquals(1, fx(() -> fixture.workspace.getDocuments().size()));
            String activity = fx(() -> String.join("\n", fixture.activity));
            assertTrue(activity.contains("failed"));
            assertTrue(activity.contains("width"));
            assertFalse(activity.contains("A scene"));
        } finally {
            fx(() -> { fixture.actions.dispose(); fixture.stage.hide(); return null; });
        }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        return WaitForAsyncUtils.asyncFx(action).get();
    }

    private static final class Fixture {
        final Workspace workspace = new Workspace();
        final DialogLayer layer = new DialogLayer();
        final Stage stage = new Stage();
        final Scene scene = new Scene(new StackPane(layer.getPane()), 800, 600);
        final List<String> activity = new ArrayList<>();
        final AiActions actions;

        Fixture(CompletableFuture<AnimationFile> response) {
            this(prompt -> response);
        }

        Fixture(Function<String, CompletableFuture<AnimationFile>> generator) {
            workspace.add(EditorDocument.untitled());
            Theme theme = new Theme();
            theme.install(scene);
            stage.setOnHidden(event -> theme.dispose());
            stage.setScene(scene);
            stage.show();
            actions = new AiActions(layer, workspace, AiSettings::defaults,
                    (settings, prompt) -> generator.apply(prompt), activity::add);
        }

        Fixture(AtomicReference<AiSettings> current, AtomicReference<AiSettings> used,
                CompletableFuture<AnimationFile> response) {
            workspace.add(EditorDocument.untitled());
            Theme theme = new Theme();
            theme.install(scene);
            stage.setOnHidden(event -> theme.dispose());
            stage.setScene(scene);
            stage.show();
            actions = new AiActions(layer, workspace, current::get, (settings, prompt) -> {
                used.set(settings);
                return response;
            }, activity::add);
        }
    }
}
