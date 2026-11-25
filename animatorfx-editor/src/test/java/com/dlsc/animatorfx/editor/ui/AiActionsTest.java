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
    void completedGenerationWaitsForTheUserToOpenTheResult() throws Exception {
        CompletableFuture<AnimationFile> response = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture(response));
        try {
            fx(() -> {
                fixture.actions.generate();
                fixture.scene.getRoot().applyCss();
                fixture.scene.getRoot().layout();
                ((TextArea) fixture.scene.lookup("#ai-description")).setText("A ball");
                ((Button) fixture.scene.lookup("#ai-generate")).fire();
                return null;
            });
            WaitForAsyncUtils.waitForFxEvents();
            response.complete(new AnimationFile(Animation.create("Ball", 400, 300, 2), Map.of()));
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                assertEquals(1, fixture.workspace.getDocuments().size());
                assertTrue(fixture.notifications.pane().getInfoCenterView().isTransparent());
                String summary = fixture.notifications.pane().getInfoCenterView()
                        .getUnmodifiableNotifications().getFirst().getSummary();
                assertTrue(summary.contains("OpenAI"));
                assertTrue(summary.contains("gpt-4.1"));
                assertTrue(summary.contains("Time:"));
                assertTrue(summary.contains("Input tokens: unavailable"));
                assertTrue(summary.contains("Output tokens: unavailable"));
                fixture.scene.getRoot().applyCss();
                fixture.scene.getRoot().layout();
                Button open = (Button) fixture.scene.lookup("#ai-open-generated");
                assertNotNull(open);
                open.fire();
                assertEquals(2, fixture.workspace.getDocuments().size());
                assertEquals("Ball", fixture.workspace.getActiveDocument().getAnimation().name());
                open.fire();
                assertEquals(2, fixture.workspace.getDocuments().size());
                return null;
            });
        } finally {
            fx(() -> { fixture.actions.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void notificationTypesUseTheirOwnBackgroundAndReadableText() throws Exception {
        Fixture fixture = fx(() -> new Fixture(new CompletableFuture<>()));
        try {
            fx(() -> {
                var group = new com.dlsc.gemsfx.infocenter.NotificationGroup<Object, com.dlsc.gemsfx.infocenter.Notification<Object>>("Types");
                for (var type : com.dlsc.gemsfx.infocenter.Notification.Type.values()) {
                    var notification = new com.dlsc.gemsfx.infocenter.Notification<Object>(type.name(), "Summary");
                    notification.setType(type);
                    group.getNotifications().add(notification);
                }
                group.setExpanded(true);
                fixture.notifications.pane().getInfoCenterView().getGroups().add(group);
                fixture.notifications.pane().setShowInfoCenter(true);
                return null;
            });
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                fixture.scene.getRoot().applyCss();
                fixture.scene.getRoot().layout();
                Map<String, String> background = Map.of("info", "-afx-accent", "warning", "-afx-warning",
                        "danger", "-afx-danger", "success", "-afx-success");
                Map<String, String> text = Map.of("info", "-afx-on-accent", "warning", "-afx-on-warning",
                        "danger", "-afx-on-accent", "success", "-afx-on-accent");
                for (String type : background.keySet()) {
                    var view = fixture.scene.getRoot().lookup(".notification-view." + type);
                    assertNotNull(view, type);
                    var content = (javafx.scene.layout.Region) view.lookup(".content");
                    assertEquals(paint(fixture, background.get(type)),
                            content.getBackground().getFills().getLast().getFill(), type + " background");
                    Label title = (Label) view.lookup(".title-label");
                    assertEquals(paint(fixture, text.get(type)), title.getTextFill(), type + " title");
                    Label description = (Label) view.lookup(".description-label");
                    assertEquals(paint(fixture, text.get(type)), description.getTextFill(), type + " summary");
                }
                return null;
            });
        } finally {
            fx(() -> { fixture.actions.dispose(); fixture.stage.hide(); return null; });
        }
    }

    // resolves a theme colour by styling a probe inside the scene with it
    private static javafx.scene.paint.Paint paint(Fixture fixture, String variable) {
        var probe = new javafx.scene.layout.Region();
        probe.setStyle("-fx-background-color: " + variable + ";");
        var root = (StackPane) fixture.scene.getRoot();
        root.getChildren().add(probe);
        probe.applyCss();
        var fill = probe.getBackground().getFills().getFirst().getFill();
        root.getChildren().remove(probe);
        return fill;
    }

    @Test
    void dismissingANotificationKeepsItsJobInSessionHistory() throws Exception {
        CompletableFuture<AnimationFile> response = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture(response));
        try {
            fx(() -> {
                fixture.actions.generate();
                fixture.scene.getRoot().applyCss();
                fixture.scene.getRoot().layout();
                ((TextArea) fixture.scene.lookup("#ai-description")).setText("A ball");
                ((Button) fixture.scene.lookup("#ai-generate")).fire();
                return null;
            });
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                fixture.notifications.pane().getInfoCenterView().getUnmodifiableNotifications().getFirst().remove();
                assertFalse(response.isCancelled());
                return null;
            });
            response.complete(new AnimationFile(Animation.create("Ball", 400, 300, 2), Map.of()));
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                assertEquals(1, fixture.notifications.jobs().size());
                assertEquals("Animation ready", fixture.notifications.pane().getInfoCenterView()
                        .getUnmodifiableNotifications().getFirst().getTitle());
                return null;
            });
        } finally {
            fx(() -> { fixture.actions.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void historyShowsLiveJobsStatisticsAndPreviewAfterOpening() throws Exception {
        CompletableFuture<AnimationFile> response = new CompletableFuture<>();
        CompletableFuture<AnimationFile> other = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture(response));
        Theme theme = fx(Theme::new);
        List<String> requested = new ArrayList<>();
        AiGenerationHistory history = fx(() -> new AiGenerationHistory(fixture.notifications, theme,
                () -> requested.add("new")));
        try {
            fx(() -> {
                Button anchor = new Button("History");
                fixture.notifications.pane().setContent(anchor);
                fixture.notifications.start(AiSettings.defaults(), "A ball", (settings, prompt, usage) -> response);
                fixture.notifications.start(AiSettings.defaults(), "Other", (settings, prompt, usage) -> other);
                history.show(anchor);
                return null;
            });
            fx(() -> {
                var anchor = (Button) fixture.notifications.pane().getContent();
                var popup = javafx.stage.Window.getWindows().stream().filter(com.dlsc.gemsfx.PopOver.class::isInstance)
                        .map(com.dlsc.gemsfx.PopOver.class::cast).filter(com.dlsc.gemsfx.PopOver::isShowing)
                        .findFirst().orElseThrow();
                assertEquals(2, popup.getContentNode().lookupAll("#ai-history-entry").size());
                Button create = (Button) popup.getContentNode().lookup("#ai-history-new");
                assertEquals(create.getTextFill(),
                        ((org.kordamp.ikonli.javafx.FontIcon) create.getGraphic()).getIconColor(),
                        "The icon of the accent button must match its label");
                create.fire();
                assertEquals(List.of("new"), requested);
                assertFalse(popup.isShowing());
                history.show(anchor);
                ((Button) popup.getContentNode().lookup("#ai-history-cancel")).fire();
                assertTrue(other.isCancelled());
                assertFalse(response.isCancelled());
                return null;
            });
            response.complete(new AnimationFile(Animation.create("Ball", 400, 300, 2), Map.of()));
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> fx(() ->
                    fixture.notifications.jobs().getFirst().preview != null));
            fx(() -> {
                var popup = javafx.stage.Window.getWindows().stream().filter(com.dlsc.gemsfx.PopOver.class::isInstance)
                        .map(com.dlsc.gemsfx.PopOver.class::cast).filter(com.dlsc.gemsfx.PopOver::isShowing)
                        .findFirst().orElseThrow();
                popup.getContentNode().applyCss();
                assertNotNull(popup.getContentNode().lookup("#ai-history-preview"));
                String labels = popup.getContentNode().lookupAll(".label").stream()
                        .filter(Label.class::isInstance).map(Label.class::cast).map(Label::getText)
                        .reduce("", (a, b) -> a + "\n" + b);
                assertTrue(labels.contains("A ball"));
                assertTrue(labels.contains("Model: gpt-4.1"));
                assertTrue(labels.contains("Input tokens: unavailable"));
                Button open = (Button) popup.getContentNode().lookup("#ai-history-open");
                assertNotNull(open);
                open.fire();
                assertEquals("Ball", fixture.workspace.getActiveDocument().getAnimation().name());
                assertEquals(2, fixture.notifications.jobs().size());
                assertTrue(((Button) popup.getContentNode().lookup("#ai-history-open")).isDisabled());
                return null;
            });
        } finally {
            fx(() -> { history.dispose(); theme.dispose(); fixture.actions.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void generationButtonAnimatesOnlyWhileAJobIsRunning() throws Exception {
        CompletableFuture<AnimationFile> first = new CompletableFuture<>();
        CompletableFuture<AnimationFile> second = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture(first));
        Button button = fx(() -> HeaderBarView.generationButton(fixture.notifications.runningProperty(), ignored -> {}));
        var running = javafx.css.PseudoClass.getPseudoClass("running");
        try {
            assertFalse(fx(() -> button.getPseudoClassStates().contains(running)));
            fx(() -> {
                fixture.notifications.start(AiSettings.defaults(), "First", (settings, prompt, usage) -> first);
                fixture.notifications.start(AiSettings.defaults(), "Second", (settings, prompt, usage) -> second);
                return null;
            });
            assertTrue(fx(() -> button.getPseudoClassStates().contains(running)));
            first.complete(new AnimationFile(Animation.create("Ball", 400, 300, 2), Map.of()));
            WaitForAsyncUtils.waitForFxEvents();
            assertTrue(fx(() -> button.getPseudoClassStates().contains(running)));
            fx(() -> { fixture.notifications.cancel(fixture.notifications.jobs().getLast()); return null; });
            assertFalse(fx(() -> button.getPseudoClassStates().contains(running)));
            assertEquals(1.0, fx(() -> button.getGraphic().getOpacity()));
        } finally {
            fx(() -> { fixture.actions.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void multipleJobsRemainIndependentAndCanBeCancelledSeparately() throws Exception {
        CompletableFuture<AnimationFile> first = new CompletableFuture<>();
        CompletableFuture<AnimationFile> second = new CompletableFuture<>();
        List<CompletableFuture<AnimationFile>> responses = new ArrayList<>(List.of(first, second));
        Fixture fixture = fx(() -> new Fixture(prompt -> responses.remove(0)));
        try {
            for (String prompt : List.of("First", "Second")) {
                fx(() -> {
                    fixture.actions.generate();
                    fixture.scene.getRoot().applyCss();
                    fixture.scene.getRoot().layout();
                    ((TextArea) fixture.scene.lookup("#ai-description")).setText(prompt);
                    ((Button) fixture.scene.lookup("#ai-generate")).fire();
                    return null;
                });
                WaitForAsyncUtils.waitForFxEvents();
            }
            second.complete(new AnimationFile(Animation.create("Second", 400, 300, 2), Map.of()));
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                assertEquals(2, fixture.notifications.pane().getInfoCenterView().getUnmodifiableNotifications().size());
                assertFalse(fixture.layer.isShowing());
                fixture.scene.getRoot().applyCss();
                fixture.scene.getRoot().layout();
                fixture.scene.getRoot().lookupAll("#ai-open-generated").stream().filter(javafx.scene.Node::isVisible)
                        .map(Button.class::cast).findFirst().orElseThrow().fire();
                assertEquals("Second", fixture.workspace.getActiveDocument().getAnimation().name());
                fixture.scene.getRoot().lookupAll("#ai-cancel-generation").stream().filter(javafx.scene.Node::isVisible)
                        .map(Button.class::cast).findFirst().orElseThrow().fire();
                assertTrue(first.isCancelled());
                assertFalse(second.isCancelled());
                return null;
            });
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
                fixture.scene.getRoot().applyCss();
                fixture.scene.getRoot().layout();
                ((Button) fixture.scene.lookup("#ai-open-generated")).fire();
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
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> fx(() -> fixture.notifications.pane()
                    .getInfoCenterView().getUnmodifiableNotifications().stream()
                    .anyMatch(notification -> notification.getTitle().equals("Animation ready"))));
            fx(() -> {
                fixture.scene.getRoot().applyCss();
                fixture.scene.getRoot().layout();
                ((Button) fixture.scene.lookup("#ai-open-generated")).fire();
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
        final List<String> activity = new ArrayList<>();
        final AiNotifications notifications = new AiNotifications(workspace, activity::add);
        final Scene scene = new Scene(new StackPane(notifications.pane(), layer.getPane()), 800, 600);
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
            actions = new AiActions(layer, notifications, AiSettings::defaults,
                    (settings, prompt, usage) -> generator.apply(prompt));
        }

        Fixture(AtomicReference<AiSettings> current, AtomicReference<AiSettings> used,
                CompletableFuture<AnimationFile> response) {
            workspace.add(EditorDocument.untitled());
            Theme theme = new Theme();
            theme.install(scene);
            stage.setOnHidden(event -> theme.dispose());
            stage.setScene(scene);
            stage.show();
            actions = new AiActions(layer, notifications, current::get, (settings, prompt, usage) -> {
                used.set(settings);
                return response;
            });
        }
    }
}
