package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.ai.AiChatAttachment;
import com.dlsc.animatorfx.editor.ai.AiEdits;
import com.dlsc.animatorfx.editor.ai.AiEditService;
import com.dlsc.animatorfx.editor.ai.AiSettings;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.Effect;
import com.dlsc.animatorfx.model.Easing;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.ImageLayer;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.TextLayer;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.LayerProperties;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.model.io.JsonWriter;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ComboBox;
import javafx.event.ActionEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
    void appliesNewAssetsAndTheResultAnimationAsOneUndoableEdit() throws Exception {
        Asset asset = new Asset("logo", Asset.Kind.IMAGE, "assets/logo.png", "logo.png");
        byte[] imageBytes = pngBytes();
        Fixture fixture = fx(() -> new Fixture((settings, file, selection, time, history, prompt, attachments) -> {
            ImageLayer layer = new ImageLayer(LayerBase.create("Logo", 3), asset.id(), 40, 20);
            var animation = file.animation().withAssets(List.of(asset)).withLayers(List.of(layer));
            return CompletableFuture.completedFuture(
                    new AiEdits.Result("Added logo.", animation, List.of(), Map.of(asset, imageBytes)));
        }));
        try {
            fx(() -> { fixture.send("Add the logo"); return null; });
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                assertEquals(1, fixture.document.getAnimation().layers().size());
                assertEquals(1, fixture.document.getAnimation().assets().size());
                assertArrayEquals(imageBytes, fixture.document.getFileContent().assets().get(asset.id()));
                fixture.document.undo();
                assertTrue(fixture.document.getAnimation().layers().isEmpty());
                assertTrue(fixture.document.getAnimation().assets().isEmpty());
                assertTrue(fixture.document.getFileContent().assets().isEmpty());
                fixture.document.redo();
                assertEquals(1, fixture.document.getAnimation().layers().size());
                assertArrayEquals(imageBytes, fixture.document.getFileContent().assets().get(asset.id()));
                return null;
            });
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void typingIndicatorShowsOnlyWhileTheProjectWaitsForAReply() throws Exception {
        CompletableFuture<AiEdits.Result> response = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture(response));
        try {
            assertNull(fx(() -> fixture.chat.lookup("#ai-chat-typing")));
            fx(() -> { fixture.send("Add a ball"); return null; });
            var before = fx(() -> fixture.document.getAnimation());
            fx(() -> {
                var transcript = (VBox) fixture.chat.lookup("#ai-chat-transcript");
                var typing = transcript.getChildren().getLast();
                assertEquals("ai-chat-typing", typing.getId());
                assertEquals(3, typing.lookupAll(".ai-chat-typing-dot").size());
                assertTrue(fixture.chat.isTyping());
                EditorDocument other = EditorDocument.untitled();
                fixture.workspace.add(other);
                fixture.workspace.setActiveDocument(other);
                assertNull(fixture.chat.lookup("#ai-chat-typing"));
                assertFalse(fixture.chat.isTyping());
                fixture.workspace.setActiveDocument(fixture.document);
                assertNotNull(fixture.chat.lookup("#ai-chat-typing"));
                return null;
            });
            response.complete(new AiEdits.Result("Done.", before));
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                assertNull(fixture.chat.lookup("#ai-chat-typing"));
                assertFalse(fixture.chat.isTyping());
                assertTrue(fixture.transcript().endsWith("Done."));
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
    void contextCardShowsProviderModelProjectAndDisclosure() throws Exception {
        Fixture fixture = fx(() -> new Fixture(new CompletableFuture<>()));
        try {
            fx(() -> {
                AiSettings defaults = AiSettings.defaults();
                VBox card = (VBox) fixture.chat.lookup(".ai-chat-context");
                assertNotNull(card);
                assertEquals(String.valueOf(defaults.provider()),
                        ((Label) card.lookup("#ai-chat-provider")).getText());
                assertEquals(defaults.model(), ((Label) card.lookup("#ai-chat-model")).getText());
                assertEquals(fixture.document.getTitle(), ((Label) card.lookup("#ai-chat-project")).getText());
                assertNotNull(card.lookup(".ai-chat-disclosure"));
                return null;
            });
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void attachmentsAreSubmittedOnlyWithTheTurnThatAddedThem() throws Exception {
        Fixture fixture = fx(() -> new Fixture((settings, file, selection, time, history, prompt, attachments) ->
                CompletableFuture.completedFuture(new AiEdits.Result("Done.", file.animation()))));
        try {
            Asset projectAsset = new Asset("logo", Asset.Kind.IMAGE, "assets/logo.png", "logo.png");
            byte[] pngBytes = pngBytes();
            fx(() -> {
                AiChatAttachmentsView view = (AiChatAttachmentsView) fixture.chat.lookup("#ai-chat-attachments");
                fixture.document.addAsset(projectAsset, pngBytes);
                view.addProjectImage(projectAsset, pngBytes);
                fixture.send("Use this image");
                return null;
            });
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                AiChatAttachmentsView view = (AiChatAttachmentsView) fixture.chat.lookup("#ai-chat-attachments");
                assertEquals(1, fixture.submitted.getFirst().size());
                assertTrue(view.getAttachments().isEmpty());
                assertTrue(fixture.transcript().contains("logo.png"));
                fixture.send("Make it move");
                return null;
            });
            WaitForAsyncUtils.waitForFxEvents();
            assertTrue(fx(() -> fixture.submitted.getLast().isEmpty()));
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void projectPickerTracksInsertionInTheSameDocument() throws Exception {
        Fixture fixture = fx(() -> new Fixture(new CompletableFuture<>()));
        Asset asset = new Asset("inserted", Asset.Kind.IMAGE, "assets/inserted.png", "Inserted");
        byte[] bytes = pngBytes();
        try {
            fx(() -> {
                fixture.document.addAsset(asset, bytes);
                @SuppressWarnings("unchecked")
                ComboBox<Asset> picker = (ComboBox<Asset>) fixture.chat.lookup("#ai-chat-project-images");
                assertEquals(List.of(asset), picker.getItems());
                picker.setValue(asset);
                assertEquals(asset, ((AiChatAttachmentsView) fixture.chat.lookup("#ai-chat-attachments"))
                        .getAttachments().getFirst().projectAsset());
                return null;
            });
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void undoingProjectAssetInsertionRefreshesPickerAndBlocksStaleSubmission() throws Exception {
        Fixture fixture = fx(() -> new Fixture((settings, file, selection, time, history, prompt, attachments) -> {
            Asset asset = attachments.getFirst().projectAsset();
            ImageLayer layer = new ImageLayer(LayerBase.create("Placed", 3), asset.id(), 20, 10);
            return CompletableFuture.completedFuture(new AiEdits.Result("Placed",
                    file.animation().withAssets(List.of(asset)).withLayers(List.of(layer))));
        }));
        Asset asset = new Asset("inserted", Asset.Kind.IMAGE, "assets/inserted.png", "Inserted");
        byte[] bytes = pngBytes();
        try {
            fx(() -> {
                fixture.document.addAsset(asset, bytes);
                AiChatAttachmentsView view = (AiChatAttachmentsView) fixture.chat.lookup("#ai-chat-attachments");
                view.setProject(fixture.document.getFileContent());
                view.addProjectImage(asset, bytes);
                fixture.document.undo();
                @SuppressWarnings("unchecked")
                ComboBox<Asset> picker = (ComboBox<Asset>) fixture.chat.lookup("#ai-chat-project-images");
                assertTrue(picker.getItems().isEmpty());
                assertFalse(view.getError().isEmpty());
                fixture.send("Place it");
                assertTrue(fixture.submitted.isEmpty());
                assertTrue(fixture.document.getAnimation().assets().isEmpty());
                assertTrue(fixture.document.getAnimation().layers().isEmpty());
                assertTrue(fixture.document.getFileContent().assets().isEmpty());
                fixture.document.redo();
                assertEquals(List.of(asset), picker.getItems());
                assertEquals("", view.getError());
                return null;
            });
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void submissionRevalidatesEveryProjectAttachmentAgainstTheExactFileSnapshot() throws Exception {
        Fixture fixture = fx(() -> new Fixture(new CompletableFuture<>()));
        Asset missing = new Asset("missing", Asset.Kind.IMAGE, "assets/missing.png", "Missing");
        Asset broken = new Asset("broken", Asset.Kind.IMAGE, "assets/broken.png", "Broken");
        byte[] bytes = pngBytes();
        try {
            fx(() -> {
                AiChatAttachmentsView view = (AiChatAttachmentsView) fixture.chat.lookup("#ai-chat-attachments");
                view.addProjectImage(missing, bytes);
                fixture.send("Place missing");
                assertTrue(fixture.submitted.isEmpty());
                assertTrue(view.getError().contains("Missing"));
                assertFalse(((TextArea) fixture.chat.lookup("#ai-chat-input")).getText().isEmpty());
                view.clear();
                fixture.document.addAsset(broken, new byte[] {1, 2, 3});
                view.addProjectImage(broken, bytes);
                fixture.send("Place broken");
                assertTrue(fixture.submitted.isEmpty());
                assertTrue(view.getError().contains("readable"));
                assertTrue(fixture.document.getAnimation().layers().isEmpty());
                return null;
            });
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void switchingDocumentsClearsDraftAttachmentsAndRefreshesProjectImages() throws Exception {
        Fixture fixture = fx(() -> new Fixture(new CompletableFuture<>()));
        try {
            Asset draft = new Asset("draft", Asset.Kind.IMAGE, "assets/draft.png", "draft.png");
            Asset available = new Asset("available", Asset.Kind.IMAGE, "assets/available.png", "available.png");
            byte[] pngBytes = pngBytes();
            EditorDocument other = EditorDocument.imported(new AnimationFile(
                    Animation.create("Other", 320, 200, 2).withAssets(List.of(available)),
                    Map.of(available.id(), pngBytes)));
            fx(() -> {
                ((AiChatAttachmentsView) fixture.chat.lookup("#ai-chat-attachments"))
                        .addProjectImage(draft, pngBytes);
                fixture.workspace.add(other);
                fixture.workspace.setActiveDocument(other);
                return null;
            });
            fx(() -> {
                AiChatAttachmentsView view = (AiChatAttachmentsView) fixture.chat.lookup("#ai-chat-attachments");
                @SuppressWarnings("unchecked")
                ComboBox<Asset> picker = (ComboBox<Asset>) view.lookup("#ai-chat-project-images");
                assertTrue(view.getAttachments().isEmpty());
                assertEquals(List.of(available), picker.getItems());
                return null;
            });
        } finally {
            fx(() -> { fixture.chat.dispose(); fixture.stage.hide(); return null; });
        }
    }

    @Test
    void shadowControlsAdjustTheAppliedEditAndUndoSeparately() throws Exception {
        Fixture fixture = fx(() -> new Fixture((settings, file, selection, time, history, prompt, attachments) -> {
            try {
                var animation = file.animation();
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
                assertNull(fixture.chat.lookup("#ai-feedback"));
                var messages = ((VBox) fixture.chat.lookup("#ai-chat-transcript")).getChildren();
                assertEquals(2, messages.size());
                assertEquals("Add a shadow", ((Label) messages.get(0)).getText());
                assertTrue(messages.get(0).getStyleClass().contains("user"));
                VBox reply = (VBox) messages.get(1);
                assertTrue(reply.getStyleClass().contains("ai-chat-reply"));
                assertEquals(2, reply.getChildren().size());
                assertEquals("Added shadow.", ((Label) reply.getChildren().get(0)).getText());
                assertTrue(reply.getChildren().get(0).getStyleClass().contains("assistant"));
                AiFeedback feedback = (AiFeedback) reply.getChildren().get(1);
                assertTrue(feedback.lookupAll(".label").stream()
                        .noneMatch(node -> "Added shadow.".equals(((Label) node).getText())),
                        "The reply text must not be repeated inside the controls");
                assertEquals("1 layer", ((Label) feedback.lookup("#ai-feedback-badge")).getText());
                assertEquals("Ball · Drop shadow", layer.getText());
                layer.setExpanded(true);
                fixture.chat.applyCss();
                fixture.chat.layout();
                TextField radius = (TextField) fixture.chat.lookup("#ai-feedback-effects-0-radius");
                assertNotNull(radius);
                radius.setText("25");
                radius.fireEvent(new ActionEvent());
                assertEquals(25.0, LayerProperties.get(fixture.document.getAnimation().layers().getFirst(),
                        "effects[0].radius").at(0));
                ((Button) fixture.chat.lookup("#ai-feedback-reset")).fire();
                assertEquals(12.0, LayerProperties.get(fixture.document.getAnimation().layers().getFirst(),
                        "effects[0].radius").at(0));
                assertEquals("12.0", radius.getText());
                fixture.document.undo();
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
        Fixture fixture = fx(() -> new Fixture((settings, file, selection, time, history, prompt, attachments) -> {
            try {
                var animation = file.animation();
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
        Fixture fixture = fx(() -> new Fixture((settings, file, selection, time, history, prompt, attachments) -> {
            try {
                var animation = file.animation();
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
        Fixture fixture = fx(() -> new Fixture((settings, file, selection, time, history, prompt, attachments) -> {
            var animation = file.animation();
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
        Fixture fixture = fx(() -> new Fixture((settings, file, selection, time, history, prompt, attachments) -> {
            var animation = file.animation();
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
        Fixture fixture = fx(() -> new Fixture((settings, file, selection, time, history, prompt, attachments) -> {
            var animation = file.animation();
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
    void failedRequestsLeaveTheDocumentUntouched() throws Exception {
        CompletableFuture<AiEdits.Result> response = new CompletableFuture<>();
        Fixture fixture = fx(() -> new Fixture(response));
        try {
            var before = fx(() -> fixture.document.getAnimation());
            fx(() -> { fixture.send("Change it"); return null; });
            response.completeExceptionally(new IllegalStateException("Provider unavailable"));
            WaitForAsyncUtils.waitForFxEvents();
            fx(() -> {
                assertSame(before, fixture.document.getAnimation());
                assertFalse(fixture.document.isCanUndo());
                return null;
            });
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
        AiEditService service = new AiEditService(provider -> "test-key", (provider, model, key, context, attachments) -> {
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
        Fixture fixture = fx(() -> new Fixture((settings, file, selection, time, history, prompt, attachments) -> {
            histories.add(history);
            return CompletableFuture.completedFuture(new AiEdits.Result("Reply", file.animation()));
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

    private static byte[] pngBytes() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    private static final class Fixture {
        final Workspace workspace = new Workspace();
        final EditorDocument document = EditorDocument.untitled();
        final List<String> activity = new ArrayList<>();
        final List<List<AiChatAttachment>> submitted = new ArrayList<>();
        final Stage stage = new Stage();
        final AiChat chat;

        Fixture(CompletableFuture<AiEdits.Result> response) {
            this((settings, file, selection, time, history, prompt, attachments) -> response);
        }

        Fixture(AiChat.Request request) {
            workspace.add(document);
            chat = new AiChat(workspace, AiSettings::defaults,
                    (settings, file, selection, time, history, prompt, attachments) -> {
                        submitted.add(List.copyOf(attachments));
                        return request.edit(settings, file, selection, time, history, prompt, attachments);
                    }, activity::add);
            stage.setScene(new Scene(chat, 400, 600));
            stage.show();
        }

        void send(String text) {
            ((TextArea) chat.lookup("#ai-chat-input")).setText(text);
            ((Button) chat.lookup("#ai-chat-send")).fire();
        }

        String transcript() {
            return ((VBox) chat.lookup("#ai-chat-transcript")).getChildren().stream()
                    .map(node -> node.getStyleClass().contains("ai-chat-reply")
                            ? ((VBox) node).getChildren().getFirst() : node)
                    .filter(Label.class::isInstance).map(node -> ((Label) node).getText())
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
    }
}
