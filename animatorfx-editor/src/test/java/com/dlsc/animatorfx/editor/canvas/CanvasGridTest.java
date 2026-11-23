package com.dlsc.animatorfx.editor.canvas;

import com.dlsc.animatorfx.model.Vec2;
import javafx.animation.PauseTransition;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Shape;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the Metal limit with test JVM flags {@code -Dprism.order=mtl -Dprism.maxTextureSize=1024}. */
class CanvasGridTest {
    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void largeHiDpiGridRendersBeyondASingleMaskTexture() throws Exception {
        WritableImage image = WaitForAsyncUtils.asyncFx(() -> {
            CanvasGrid grid = new CanvasGrid();
            grid.update(2300, 1200, Vec2.ZERO, 1);
            grid.lookupAll(".canvas-grid").forEach(node -> ((Shape) node).setStroke(Color.WHITE));
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setFill(Color.BLACK);
            parameters.setTransform(new Scale(2, 2));
            return grid.snapshot(parameters, null);
        }).get();
        boolean verticalLine = false;
        for (int x = 40; x <= 44; x++) {
            verticalLine |= image.getPixelReader().getColor(x, 100).getBrightness() > 0.1;
        }
        assertTrue(verticalLine, "The maximized HiDPI grid must render, not fail allocating a viewport-sized mask");
    }

    @Test
    void liveWindowGridDoesNotAllocateAnOversizedMask() throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        Stage stage = null;
        try (PrintStream captured = new PrintStream(errors)) {
            System.setErr(captured);
            stage = WaitForAsyncUtils.asyncFx(() -> {
                CanvasGrid grid = new CanvasGrid();
                grid.update(400, 250, Vec2.ZERO, 1);
                grid.lookupAll(".canvas-grid").forEach(node -> ((Shape) node).setStroke(Color.WHITE));
                Stage window = new Stage();
                Scene scene = new Scene(new Pane(grid), 400, 250);
                scene.getStylesheets().add(CanvasGrid.class.getResource("/com/dlsc/animatorfx/editor/editor.css").toExternalForm());
                window.setScene(scene);
                window.show();
                // Start below the texture limit, then exercise the resized swap chain.
                PauseTransition resize = new PauseTransition(Duration.millis(250));
                resize.setOnFinished(event -> {
                    grid.update(1150, 600, new Vec2(3, -7), 2);
                    grid.lookupAll(".canvas-grid").forEach(node -> ((Shape) node).setStroke(Color.WHITE));
                    window.setWidth(1150);
                    window.setHeight(600);
                });
                resize.play();
                return window;
            }).get();
            CompletableFuture<Void> pulses = new CompletableFuture<>();
            WaitForAsyncUtils.asyncFx(() -> {
                PauseTransition delay = new PauseTransition(Duration.seconds(1));
                delay.setOnFinished(event -> pulses.complete(null));
                delay.play();
            }).get();
            pulses.get(5, TimeUnit.SECONDS);
            assertFalse(errors.toString().contains("Illegal texture dimensions"), errors::toString);
        } finally {
            System.setErr(original);
            if (stage != null) {
                Stage window = stage;
                WaitForAsyncUtils.asyncFx(window::close).get();
            }
        }
    }

    @Test
    void shrinkingAndPanningDiscardGridLinesOutsideTheNewViewport() throws Exception {
        WaitForAsyncUtils.asyncFx(() -> {
            CanvasGrid grid = new CanvasGrid();
            grid.update(2300, 1200, Vec2.ZERO, 1);
            grid.update(120, 80, new Vec2(3, -7), 2);
            assertTrue(grid.getBoundsInLocal().getMaxX() <= 121);
            assertTrue(grid.getBoundsInLocal().getMaxY() <= 81);
        }).get();
    }
}
