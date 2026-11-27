package com.dlsc.animatorfx.editor.inspector.fields;

import javafx.event.Event;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;

import static com.dlsc.animatorfx.editor.ui.EditorUiTest.fx;
import static com.dlsc.animatorfx.editor.ui.EditorUiTest.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenPipetteRenderTest {

    private Stage owner;

    @BeforeAll
    static void toolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeOwner() {
        run(() -> {
            if (owner != null) owner.close();
        });
    }

    @Test
    void desktopCaptureDoesNotRequireAFullResolutionRenderTexture() {
        run(() -> {
            ColorField field = new ColorField();
            owner = new Stage(StageStyle.UNDECORATED);
            owner.setScene(new Scene(field, 256, 128));
            owner.show();
            owner.getScene().getRoot().applyCss();
            ((Button) field.lookup(".pipette")).fire();
        });
        WaitForAsyncUtils.waitForFxEvents();

        run(() -> {
            List<Window> overlays = Window.getWindows().stream()
                    .filter(window -> window.getScene() != null
                            && window.getScene().getRoot().getStyleClass().contains("color-pipette-overlay"))
                    .toList();
            assertTrue(overlays.size() >= Screen.getScreens().size());
            for (Window overlay : overlays) {
                double width = Math.ceil(overlay.getScene().getWidth() * overlay.getRenderScaleX());
                double height = Math.ceil(overlay.getScene().getHeight() * overlay.getRenderScaleY());
                assertTrue(width <= 1024 && height <= 1024,
                        "Full-screen input must use a small GPU surface, not " + width + "x" + height);
                assertTrue(overlay.getScene().getWidth() * overlay.getOutputScaleX() <= 1024);
                assertTrue(overlay.getScene().getHeight() * overlay.getOutputScaleY() <= 1024);
            }
            for (Screen screen : Screen.getScreens()) {
                List<Rectangle2D> regions = overlays.stream().map(window -> new Rectangle2D(window.getX(), window.getY(),
                                window.getWidth(), window.getHeight()))
                        .filter(bounds -> screen.getVisualBounds().contains(bounds)).toList();
                double area = regions.stream().mapToDouble(bounds -> bounds.getWidth() * bounds.getHeight()).sum();
                assertEquals(screen.getVisualBounds().getWidth() * screen.getVisualBounds().getHeight(), area, 1);
                for (int i = 0; i < regions.size(); i++) {
                    for (int j = i + 1; j < regions.size(); j++) {
                        Rectangle2D a = regions.get(i);
                        Rectangle2D b = regions.get(j);
                        double overlapWidth = Math.min(a.getMaxX(), b.getMaxX()) - Math.max(a.getMinX(), b.getMinX());
                        double overlapHeight = Math.min(a.getMaxY(), b.getMaxY()) - Math.max(a.getMinY(), b.getMinY());
                        assertFalse(overlapWidth > 0 && overlapHeight > 0,
                                "Capture windows must not overlap: " + a + " and " + b);
                    }
                }
            }
            Event.fireEvent(overlays.getFirst().getScene().getRoot(),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
        });
        assertTrue(fx(() -> Window.getWindows().stream()
                .noneMatch(window -> window.getScene() != null
                        && window.getScene().getRoot().getStyleClass().contains("color-pipette-overlay"))));
    }
}
