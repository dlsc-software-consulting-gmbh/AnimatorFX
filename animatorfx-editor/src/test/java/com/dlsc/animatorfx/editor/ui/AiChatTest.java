package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.ai.AiEdits;
import com.dlsc.animatorfx.editor.ai.AiEditService;
import com.dlsc.animatorfx.editor.ai.AiSettings;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.LayerProperties;
import com.dlsc.animatorfx.model.io.JsonWriter;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
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
