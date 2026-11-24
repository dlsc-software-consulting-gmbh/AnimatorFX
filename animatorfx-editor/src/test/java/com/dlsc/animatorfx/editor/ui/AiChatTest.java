package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.ai.AiEdits;
import com.dlsc.animatorfx.editor.ai.AiEditService;
import com.dlsc.animatorfx.editor.ai.AiSettings;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Effect;
import com.dlsc.animatorfx.model.Easing;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.TextLayer;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.LayerProperties;
import com.dlsc.animatorfx.model.io.JsonWriter;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ComboBox;
import javafx.event.ActionEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiChatTest {
    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void appliesAResponseAsOneUndoableEditWithoutLoggingThePrompt() throws Exception {
        CompletableFuture<AiEdits.Result> response = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture(response));
        try {
            fx(() -> { fixture.send("Add a ball"); return null; });
            var before = fx(() -> fixture.document.getAnimation());
            var ball = new ShapeLayer(LayerBase.create("Ball", 3),
                    new Geometry.Ellipse(Animatable.of(new Vec2(30, 30))),
                    Animatable.of(new Paint.Solid(Rgba.BLACK)), null, null);
            response.complete(new AiEdits.Result("Added a ball.", before.withLayers(List.of(ball))));
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                assertEquals(1, fixture.document.getAnimation().layers().size());
                assertTrue(fixture.transcript().contains("Added a ball."));
                assertTrue(fixture.transcript().contains("Add a ball"));
                assertFalse(String.join(" ", fixture.activity).contains("Add a ball"));
                fixture.document.undo();
                assertEquals(before, fixture.document.getAnimation());
                fixture.document.redo();
                assertEquals(1, fixture.document.getAnimation().layers().size());
                return null;
            });
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void rejectsAResponseIfTheDocumentChangedDuringTheRequest() throws Exception {
        CompletableFuture<AiEdits.Result> response = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture(response));
        try {
            fx(() -> { fixture.send("Change it"); return null; });
            var before = fx(() -> fixture.document.getAnimation());
            fx(() -> { fixture.document.execute(Commands.setDocument("Manual",
                    animation -> animation.withName("Manual change"))); return null; });
            response.complete(new AiEdits.Result("Done", before.withName("AI change")));
            WaitForAsyncUtils.waitForFxEvents();
            assertEquals("Manual change", fx(() -> fixture.document.getAnimation().name()));
            assertTrue(fx(fixture::transcript).contains("changed"));
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void shadowControlsAdjustTheAppliedEditAndUndoSeparately() throws Exception {
        Fixture fixture = fx(() -> new Fixture((settings, animation, selection, time, history, prompt) -> {
            try {
                var op = new java.util.LinkedHashMap<String, Object>();
                op.put("kind", "shadow");
                op.put("target", "ball");
                op.put("parent", null);
                op.put("property", null);
                op.put("value", "{\"color\":{\"r\":0,\"g\":0,\"b\":0,\"a\":0.5},"
                        + "\"radius\":12,\"offsetX\":0,\"offsetY\":4,\"spread\":0}");
                op.put("time", null);
                op.put("easing", null);
                return CompletableFuture.completedFuture(AiEdits.parse(
                        JsonWriter.write(Map.of("reply", "Added shadow.", "operations", List.of(op))), animation));
            } catch (Exception ex) {
                return CompletableFuture.failedFuture(ex);
            }
        }));
        try {
            fx(() -> {
                var ball = new ShapeLayer(LayerBase.create("Ball", 3).withId("ball"),
                        new Geometry.Ellipse(Animatable.of(new Vec2(30, 30))),
                        Animatable.of(new Paint.Solid(Rgba.BLACK)), null, null);
                fixture.document.execute(Commands.addLayer(null, 0, ball));
                fixture.send("Add a shadow");
                return null;
            });
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                TitledPane layer = (TitledPane) fixture.chat.lookup("#ai-feedback-layer-ball");
                assertNotNull(layer, "The AI response needs adjustable controls");
                layer.setExpanded(true);
                fixture.chat.applyCss();
                fixture.chat.layout();
                TextField radius = (TextField) fixture.chat.lookup("#ai-feedback-effects-0-radius");
                assertNotNull(radius);
                radius.setText("25");
                radius.fireEvent(new ActionEvent());
                assertEquals(25.0, LayerProperties.get(fixture.document.getAnimation().layers().getFirst(),
                        "effects[0].radius").at(0));
                fixture.document.undo();
                assertEquals(12.0, LayerProperties.get(fixture.document.getAnimation().layers().getFirst(),
                        "effects[0].radius").at(0));
                fixture.document.undo();
                assertTrue(fixture.document.getAnimation().layers().getFirst().base().effects().isEmpty());
                fixture.document.redo();
                fixture.document.redo();
                assertEquals(25.0, LayerProperties.get(fixture.document.getAnimation().layers().getFirst(),
                        "effects[0].radius").at(0));
                return null;
            });
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void shadowControlsNeverRetargetAfterEffectsAreReorderedBeforeExpansion() throws Exception {
        Fixture fixture = fx(() -> new Fixture((settings, animation, selection, time, history, prompt) -> {
            try {
                var op = new java.util.LinkedHashMap<String, Object>();
                op.put("kind", "shadow"); op.put("target", "ball"); op.put("parent", null);
                op.put("property", null);
                op.put("value", "{\"color\":{\"r\":0,\"g\":0,\"b\":0,\"a\":0.5},"
                        + "\"radius\":12,\"offsetX\":0,\"offsetY\":4,\"spread\":0}");
                op.put("time", null); op.put("easing", null);
                return CompletableFuture.completedFuture(AiEdits.parse(
                        JsonWriter.write(Map.of("reply", "Added shadow.", "operations", List.of(op))), animation));
            } catch (Exception ex) {
                return CompletableFuture.failedFuture(ex);
            }
        }));
        try {
            fx(() -> {
                var other = new Effect.DropShadow(Animatable.of(Rgba.BLACK), Animatable.of(3.0),
                        Animatable.of(0.0), Animatable.of(0.0), Animatable.of(0.0));
                var ball = new ShapeLayer(LayerBase.create("Ball", 3).withId("ball").withEffects(List.of(other)),
                        new Geometry.Ellipse(Animatable.of(new Vec2(30, 30))),
                        Animatable.of(new Paint.Solid(Rgba.BLACK)), null, null);
                fixture.document.execute(Commands.addLayer(null, 0, ball));
                fixture.send("Add another shadow");
                return null;
            });
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                fixture.document.execute(Commands.moveEffect("ball", 1, 0));
                ((TitledPane) fixture.chat.lookup("#ai-feedback-layer-ball")).setExpanded(true);
                fixture.chat.applyCss();
                fixture.chat.layout();
                TextField radius = (TextField) fixture.chat.lookup("#ai-feedback-effects-1-radius");
                assertNotNull(radius);
                assertTrue(radius.isDisabled());
                fixture.document.undo();
                assertFalse(radius.isDisabled());
                assertEquals("12.0", radius.getText());
                return null;
            });
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void keyframeControlsPreserveOtherKeysAndFollowLocksAndUndo() throws Exception {
        Fixture fixture = fx(() -> new Fixture((settings, animation, selection, time, history, prompt) -> {
            try {
                var op = new java.util.LinkedHashMap<String, Object>();
                op.put("kind", "keyframe"); op.put("target", "ball"); op.put("parent", null);
                op.put("property", "transform.position"); op.put("value", "{\"x\":100,\"y\":200}");
                op.put("time", 1.0); op.put("easing", "linear");
                return CompletableFuture.completedFuture(AiEdits.parse(
                        JsonWriter.write(Map.of("reply", "Moved.", "operations", List.of(op))), animation));
            } catch (Exception ex) {
                return CompletableFuture.failedFuture(ex);
            }
        }));
        try {
            fx(() -> {
                var base = LayerBase.create("Ball", 3).withId("ball");
                var position = Animatable.of(new Vec2(0, 0)).withKeyframe(0, new Vec2(5, 6), Easing.LINEAR);
                base = base.withTransform(base.transform().withPosition(position));
                fixture.document.execute(Commands.addLayer(null, 0, new ShapeLayer(base,
                        new Geometry.Ellipse(Animatable.of(new Vec2(30, 30))),
                        Animatable.of(new Paint.Solid(Rgba.BLACK)), null, null)));
                fixture.send("Move it");
                return null;
            });
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                ((TitledPane) fixture.chat.lookup("#ai-feedback-layer-ball")).setExpanded(true);
                fixture.chat.applyCss();
                fixture.chat.layout();
                TextField x = (TextField) fixture.chat.lookup("#ai-feedback-transform-position-x");
                assertNotNull(x);
                x.setText("150");
                x.fireEvent(new ActionEvent());
                var track = LayerProperties.get(fixture.document.getAnimation().layers().getFirst(), "transform.position");
                assertEquals(new Vec2(150, 200), track.at(1));
                assertEquals(new Vec2(5, 6), track.at(0));
                fixture.document.execute(Commands.updateLayer("Lock", "ball",
                        layer -> layer.withBase(layer.base().withLocked(true))));
                assertTrue(x.isDisabled());
                fixture.document.undo();
                assertFalse(x.isDisabled());
                fixture.document.undo();
                assertEquals(new Vec2(100, 200), LayerProperties.get(
                        fixture.document.getAnimation().layers().getFirst(), "transform.position").at(1));
                TextField time = (TextField) fixture.chat.lookup("#ai-feedback-keyframe-time");
                time.setText("0");
                time.fireEvent(new ActionEvent());
                assertEquals(2, LayerProperties.get(fixture.document.getAnimation().layers().getFirst(),
                        "transform.position").keyframes().size());
                time.setText("2");
                time.fireEvent(new ActionEvent());
                var moved = LayerProperties.get(fixture.document.getAnimation().layers().getFirst(), "transform.position");
                assertEquals(new Vec2(100, 200), moved.at(2));
                assertEquals(new Vec2(5, 6), moved.at(0));
                @SuppressWarnings("unchecked")
                ComboBox<String> easing = (ComboBox<String>) fixture.chat.lookup("#ai-feedback-keyframe-easing");
                easing.setValue("Hold");
                assertInstanceOf(Easing.Hold.class, LayerProperties.get(
                        fixture.document.getAnimation().layers().getFirst(), "transform.position")
                        .keyframes().getLast().easing());
                return null;
            });
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void createdTextLayerHasAdjustableContentAndFont() throws Exception {
        Fixture fixture = fx(() -> new Fixture((settings, animation, selection, time, history, prompt) -> {
            var text = new TextLayer(LayerBase.create("Title", 3).withId("title"), "Hello", "System", null,
                    Animatable.of(24.0), TextLayer.Weight.NORMAL, false, TextLayer.Align.LEFT,
                    Animatable.of(new Paint.Solid(Rgba.BLACK)), null);
            return CompletableFuture.completedFuture(new AiEdits.Result("Added title.",
                    animation.withLayers(List.of(text)), List.of(new AiEdits.Change("title", null, null))));
        }));
        try {
            fx(() -> { fixture.send("Add title"); return null; });
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                ((TitledPane) fixture.chat.lookup("#ai-feedback-layer-title")).setExpanded(true);
                fixture.chat.applyCss();
                fixture.chat.layout();
                TextField text = (TextField) fixture.chat.lookup("#ai-feedback-text");
                text.setText("Updated");
                text.fireEvent(new ActionEvent());
                assertEquals("Updated", ((TextLayer) fixture.document.getAnimation().layers().getFirst()).text());
                assertEquals("Updated", text.getText());
                fixture.document.undo();
                assertEquals("Hello", text.getText());
                TextField font = (TextField) fixture.chat.lookup("#ai-feedback-font-family");
                assertNotNull(font);
                font.setText("Serif");
                font.fireEvent(new ActionEvent());
                assertEquals("Serif", ((TextLayer) fixture.document.getAnimation().layers().getFirst()).fontFamily());
                fixture.document.undo();
                assertEquals("System", ((TextLayer) fixture.document.getAnimation().layers().getFirst()).fontFamily());
                TextField outPoint = (TextField) fixture.chat.lookup("#ai-feedback-out-point");
                assertNotNull(outPoint);
                outPoint.setText("2");
                outPoint.fireEvent(new ActionEvent());
                assertEquals(2.0, fixture.document.getAnimation().layers().getFirst().base().outPoint());
                fixture.document.undo();
                assertEquals(3.0, fixture.document.getAnimation().layers().getFirst().base().outPoint());
                EditorDocument other = EditorDocument.untitled();
                fixture.workspace.add(other);
                assertNull(fixture.chat.lookup("#ai-feedback-layer-title"));
                fixture.workspace.setActiveDocument(fixture.document);
                assertNotNull(fixture.chat.lookup("#ai-feedback-layer-title"));
                ((Button) fixture.chat.lookup("#ai-chat-clear")).fire();
                assertNull(fixture.chat.lookup("#ai-feedback-layer-title"));
                return null;
            });
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void controlsBecomeAvailableWhenAnUndoneLayerIsRedoneAfterExpansion() throws Exception {
        Fixture fixture = fx(() -> new Fixture((settings, animation, selection, time, history, prompt) -> {
            var ball = new ShapeLayer(LayerBase.create("Ball", 3).withId("ball"),
                    new Geometry.Ellipse(Animatable.of(new Vec2(30, 30))),
                    Animatable.of(new Paint.Solid(Rgba.BLACK)), null, null);
            return CompletableFuture.completedFuture(new AiEdits.Result("Added.", animation.withLayers(List.of(ball)),
                    List.of(new AiEdits.Change("ball", null, null))));
        }));
        try {
            fx(() -> { fixture.send("Add ball"); return null; });
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                fixture.document.undo();
                ((TitledPane) fixture.chat.lookup("#ai-feedback-layer-ball")).setExpanded(true);
                fixture.document.redo();
                fixture.chat.applyCss();
                fixture.chat.layout();
                TextField opacity = (TextField) fixture.chat.lookup("#ai-feedback-opacity");
                assertNotNull(opacity);
                assertFalse(opacity.isDisabled());
                opacity.setText("0.5");
                opacity.fireEvent(new ActionEvent());
                assertEquals(0.5, fixture.document.getAnimation().layers().getFirst().base().opacity().at(0));
                return null;
            });
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void rejectsAResponseEvenIfTheInterveningChangeWasUndone() throws Exception {
        CompletableFuture<AiEdits.Result> response = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture(response));
        try {
            fx(() -> { fixture.send("Change it"); return null; });
            var before = fx(() -> fixture.document.getAnimation());
            fx(() -> {
                fixture.document.execute(Commands.setDocument("Manual",
                        animation -> animation.withName("Manual change")));
                fixture.document.undo();
                return null;
            });
            response.complete(new AiEdits.Result("Done", before.withName("AI change")));
            WaitForAsyncUtils.waitForFxEvents();
            assertEquals(before, fx(() -> fixture.document.getAnimation()));
            assertTrue(fx(fixture::transcript).contains("changed"));
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void adjustingAControlInvalidatesAnInFlightAiResponse() throws Exception {
        CompletableFuture<AiEdits.Result> later = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture((settings, animation, selection, time, history, prompt) -> {
            if (prompt.equals("Second")) return later;
            var ball = new ShapeLayer(LayerBase.create("Ball", 3).withId("ball"),
                    new Geometry.Ellipse(Animatable.of(new Vec2(30, 30))),
                    Animatable.of(new Paint.Solid(Rgba.BLACK)), null, null);
            return CompletableFuture.completedFuture(new AiEdits.Result("Added.", animation.withLayers(List.of(ball)),
                    List.of(new AiEdits.Change("ball", "opacity", null))));
        }));
        try {
            fx(() -> { fixture.send("First"); return null; });
            WaitForAsyncUtils.waitForFxEvents();
            var captured = fx(() -> fixture.document.getAnimation());
            fx(() -> {
                ((TitledPane) fixture.chat.lookup("#ai-feedback-layer-ball")).setExpanded(true);
                fixture.chat.applyCss();
                fixture.chat.layout();
                fixture.send("Second");
                TextField opacity = (TextField) fixture.chat.lookup("#ai-feedback-opacity");
                opacity.setText("0.5");
                opacity.fireEvent(new ActionEvent());
                return null;
            });
            later.complete(new AiEdits.Result("Done.", captured.withName("Stale AI")));
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                assertNotEquals("Stale AI", fixture.document.getAnimation().name());
                assertEquals(0.5, fixture.document.getAnimation().layers().getFirst().base().opacity().at(0));
                assertTrue(fixture.transcript().contains("changed"));
                return null;
            });
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void cancelStopsTheRequestAndLeavesTheDocumentUntouched() throws Exception {
        CompletableFuture<AiEdits.Result> response = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture(response));
        try {
            fx(() -> { fixture.send("Add a ball"); ((Button) fixture.chat.lookup("#ai-chat-cancel")).fire(); return null; });
            WaitForAsyncUtils.waitForFxEvents();
            assertTrue(response.isCancelled());
            assertTrue(fx(() -> fixture.document.getAnimation().layers().isEmpty()));
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void conversationUsesTheCurrentProjectAndAppliesValidatedProviderEdits() throws Exception {
        AtomicReference<String> sent = new AtomicReference<>();
        var op = new java.util.LinkedHashMap<String, Object>();
        op.put("kind", "keyframe");
        op.put("target", "ball");
        op.put("parent", null);
        op.put("property", "transform.position");
        op.put("value", "{\"x\":100,\"y\":200}");
        op.put("time", 1.0);
        op.put("easing", "linear");
        String response = JsonWriter.write(Map.of("reply", "Moved.", "operations", List.of(op)));
        AiEditService service = new AiEditService(provider -> "test-key", (provider, model, key, context) -> {
            sent.set(context);
            return CompletableFuture.completedFuture(response);
        });
        Fixture fixture = fx(() -> new Fixture(service::edit));
        try {
            fx(() -> {
                var ball = new ShapeLayer(LayerBase.create("Ball", 3).withId("ball"),
                        new Geometry.Ellipse(Animatable.of(new Vec2(30, 30))),
                        Animatable.of(new Paint.Solid(Rgba.BLACK)), null, null);
                fixture.document.execute(Commands.addLayer(null, 0, ball));
                fixture.send("Move the ball");
                return null;
            });
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> fx(() ->
                    fixture.document.getAnimation().layers().getFirst().base().transform().position().isAnimated()));
            fx(() -> {
                var ball = fixture.document.getAnimation().layers().getFirst();
                assertEquals(new Vec2(100, 200), LayerProperties.get(ball, "transform.position").at(1));
                assertTrue(fixture.transcript().contains("Moved."));
                assertFalse(String.join(" ", fixture.activity).contains("Move the ball"));
                assertTrue(sent.get().contains("Move the ball"));
                assertTrue(sent.get().contains("ball"));
                return null;
            });
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void conversationsStayWithTheirOriginalDocument() throws Exception {
        CompletableFuture<AiEdits.Result> response = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture(response));
        try {
            fx(() -> { fixture.send("First project"); return null; });
            var before = fx(() -> fixture.document.getAnimation());
            EditorDocument second = EditorDocument.untitled();
            fx(() -> { fixture.workspace.add(second); return null; });
            response.complete(new AiEdits.Result("First reply", before));
            WaitForAsyncUtils.waitForFxEvents();
            assertFalse(fx(fixture::transcript).contains("First project"));
            fx(() -> {
                fixture.workspace.setActiveDocument(fixture.document);
                assertTrue(fixture.transcript().contains("First project"));
                assertTrue(fixture.transcript().contains("First reply"));
                ((Button) fixture.chat.lookup("#ai-chat-clear")).fire();
                assertEquals("", fixture.transcript());
                return null;
            });
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void followUpRequestsIncludePriorConversation() throws Exception {
        List<List<AiEdits.Message>> histories = new ArrayList<>();
        Fixture fixture = fx(() -> new Fixture((settings, animation, selection, time, history, prompt) -> {
            histories.add(history);
            return CompletableFuture.completedFuture(new AiEdits.Result("Reply", animation));
        }));
        try {
            fx(() -> { fixture.send("First"); return null; });
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> { fixture.send("Second"); return null; });
            WaitForAsyncUtils.waitForFxEvents();
            assertEquals(List.of(new AiEdits.Message("user", "First"), new AiEdits.Message("assistant", "Reply")),
                    fx(() -> histories.get(1)));
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void closingTheOriginalDocumentCancelsItsRequest() throws Exception {
        CompletableFuture<AiEdits.Result> response = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture(response));
        try {
            fx(() -> {
                fixture.send("Change this project");
                fixture.workspace.remove(fixture.document);
                return null;
            });
            assertTrue(response.isCancelled());
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        return WaitForAsyncUtils.asyncFx(action).get();
    }

    private static final class Fixture {
        final Workspace workspace = new Workspace();
        final EditorDocument document = EditorDocument.untitled();
        final List<String> activity = new ArrayList<>();
        final Stage stage = new Stage();
        final AiChat chat;

        Fixture(CompletableFuture<AiEdits.Result> response) {
            this((settings, animation, selection, time, history, prompt) -> response);
        }

        Fixture(AiChat.Request request) {
            workspace.add(document);
            chat = new AiChat(workspace, AiSettings::defaults, request, activity::add);
            stage.setScene(new Scene(chat, 400, 600));
            stage.show();
        }

        void send(String text) {
            ((TextArea) chat.lookup("#ai-chat-input")).setText(text);
            ((Button) chat.lookup("#ai-chat-send")).fire();
        }

        String transcript() {
            return ((TextArea) chat.lookup("#ai-chat-transcript")).getText();
        }
    }
}
