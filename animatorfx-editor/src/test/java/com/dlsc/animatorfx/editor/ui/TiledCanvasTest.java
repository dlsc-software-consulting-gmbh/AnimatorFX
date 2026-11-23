package com.dlsc.animatorfx.editor.ui;

import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TiledCanvasTest {
    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void paintingUsesContinuousCoordinatesAcrossBothTileAxes() throws Exception {
        WritableImage image = WaitForAsyncUtils.asyncFx(() -> {
            TiledCanvas canvas = new TiledCanvas();
            canvas.draw(1300, 620, g -> {
                g.setFill(Color.RED);
                g.fillRect(0, 0, 1300, 620);
                g.setFill(Color.BLUE);
                g.fillRect(500, 500, 600, 100);
            });
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setTransform(new Scale(2, 2));
            return canvas.snapshot(parameters, null);
        }).get();
        for (int x : new int[]{510, 514, 1022, 1026, 1098}) {
            for (int y : new int[]{510, 514, 598}) {
                assertEquals(Color.BLUE, image.getPixelReader().getColor(x * 2, y * 2));
            }
        }
        assertEquals(Color.RED, image.getPixelReader().getColor(2204, 1028));
    }

    @Test
    void shrinkingAndRepaintingRemoveOldPixelsAndSurplusTiles() throws Exception {
        WaitForAsyncUtils.asyncFx(() -> {
            TiledCanvas canvas = new TiledCanvas();
            canvas.draw(1300, 620, g -> {
                g.setFill(Color.RED);
                g.fillRect(0, 0, 1300, 620);
            });
            canvas.draw(600, 80, g -> {
                g.setFill(Color.RED);
                g.fillRect(0, 0, 600, 80);
            });
            canvas.draw(600, 80, g -> {
                g.setFill(Color.BLUE);
                g.fillRect(500, 10, 50, 50);
            });
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setFill(Color.TRANSPARENT);
            WritableImage image = canvas.snapshot(parameters, null);
            assertEquals(600, image.getWidth());
            assertEquals(80, image.getHeight());
            assertEquals(Color.BLUE, image.getPixelReader().getColor(520, 20));
            assertEquals(Color.TRANSPARENT, image.getPixelReader().getColor(100, 20));
            canvas.draw(0, 0, g -> {
                throw new AssertionError("An empty surface must not be painted");
            });
            assertTrue(canvas.getBoundsInLocal().isEmpty());
        }).get();
    }
}
