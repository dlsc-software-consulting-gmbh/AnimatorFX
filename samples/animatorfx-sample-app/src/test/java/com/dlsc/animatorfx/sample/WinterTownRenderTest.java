package com.dlsc.animatorfx.sample;

import com.dlsc.animatorfx.model.io.AnimationIO;
import com.dlsc.animatorfx.player.AnimationView;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WinterTownRenderTest {

    @Test
    void sceneRendersAndChangesDuringPlayback() throws Exception {
        var file = AnimationIO.read(Path.of(System.getProperty("animatorfx.samples.dir", "../animations"))
                .resolve("winter-town.afx"));
        WritableImage[] images = Fx.call(() -> {
            AnimationView view = new AnimationView(file);
            StackPane pane = new StackPane(view);
            pane.setPrefSize(1200, 800);
            new Scene(pane);
            pane.applyCss();
            pane.layout();
            WritableImage[] result = new WritableImage[3];
            for (int i = 0; i < result.length; i++) {
                view.seek(i * 5);
                result[i] = Fx.snapshot(pane, Color.TRANSPARENT);
            }
            view.dispose();
            return result;
        });
        assertEquals(1200, images[0].getWidth());
        assertEquals(800, images[0].getHeight());
        int different = 0;
        for (int y = 0; y < 800; y += 3) {
            for (int x = 0; x < 1200; x += 3) {
                if (images[0].getPixelReader().getArgb(x, y) != images[1].getPixelReader().getArgb(x, y)) {
                    different++;
                }
            }
        }
        assertTrue(different > 500, "frames should visibly differ");
        Path screenshots = Fx.screenshots();
        if (screenshots != null) {
            for (int i = 0; i < images.length; i++) {
                Fx.writePng(images[i], screenshots.resolve("winter-town-" + i * 5 + ".png"));
            }
        }
    }
}
