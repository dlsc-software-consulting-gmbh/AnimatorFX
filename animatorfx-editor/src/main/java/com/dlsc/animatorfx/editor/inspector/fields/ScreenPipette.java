package com.dlsc.animatorfx.editor.inspector.fields;

import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Temporarily intercepts desktop clicks without letting the picked click reach another application. */
final class ScreenPipette {

    private static final double MAX_RENDER_SIZE = 1024;

    private final Node owner;
    private final Scene ownerScene;
    private final Window ownerWindow;
    private final Node previousFocus;
    private final BiConsumer<Color, Throwable> completed;
    private final List<Stage> overlays = new ArrayList<>();
    private final List<Node> ancestors = new ArrayList<>();
    private final PauseTransition settle = new PauseTransition(Duration.millis(120));
    private final ChangeListener<Boolean> showing = (obs, old, visible) -> {
        if (!visible) cancel();
    };
    private final ChangeListener<Scene> scene = (obs, old, value) -> cancel();
    private final ChangeListener<Boolean> visible = (obs, old, value) -> {
        if (!value) cancel();
    };
    private Robot robot;
    private final EventHandler<KeyEvent> keys = event -> {
        if (event.getEventType() == KeyEvent.KEY_PRESSED) {
            if (event.getCode() == KeyCode.ESCAPE) {
                cancel();
            } else if (event.getCode() == KeyCode.ENTER && robot != null) {
                sample(robot.getMouseX(), robot.getMouseY());
            }
        }
        event.consume();
    };
    private boolean active = true;

    ScreenPipette(Node owner, BiConsumer<Color, Throwable> completed) {
        this.owner = owner;
        ownerScene = owner.getScene();
        ownerWindow = ownerScene.getWindow();
        previousFocus = ownerScene.getFocusOwner();
        this.completed = completed;
        for (Node node = owner; node != null; node = node.getParent()) ancestors.add(node);
    }

    void start() {
        ownerWindow.showingProperty().addListener(showing);
        owner.sceneProperty().addListener(scene);
        ancestors.forEach(node -> node.visibleProperty().addListener(visible));
        ownerScene.addEventFilter(KeyEvent.ANY, keys);
        try {
            robot = new Robot();
            List<Screen> screens = List.copyOf(Screen.getScreens());
            double scaleX = screens.stream().mapToDouble(screen -> Math.ceil(screen.getOutputScaleX())).max().orElse(1);
            double scaleY = screens.stream().mapToDouble(screen -> Math.ceil(screen.getOutputScaleY())).max().orElse(1);
            // Respect system UI bounds; Enter still samples anywhere on the desktop.
            List<Rectangle2D> regions = screens.stream()
                    .flatMap(screen -> overlayBounds(screen.getVisualBounds(), scaleX, scaleY).stream()).toList();
            // Metal limits the native window surface, so tile windows rather than their canvas content.
            for (Rectangle2D bounds : regions) {
                Pane surface = new Pane();
                surface.getStyleClass().add("color-pipette-overlay");
                surface.setFocusTraversable(true);
                // One alpha step gives transparent windows a hit-test surface on every platform.
                surface.setBackground(new Background(new BackgroundFill(Color.color(0, 0, 0, 1.0 / 255),
                        CornerRadii.EMPTY, null)));
                Scene scene = new Scene(surface, bounds.getWidth(), bounds.getHeight(), Color.TRANSPARENT);
                scene.setCursor(Cursor.CROSSHAIR);
                scene.addEventFilter(KeyEvent.ANY, keys);
                surface.setOnMousePressed(event -> event.consume());
                surface.setOnMouseReleased(event -> {
                    if (event.getButton() == MouseButton.PRIMARY) {
                        sample(event.getScreenX(), event.getScreenY());
                    } else if (event.getButton() == MouseButton.SECONDARY) {
                        cancel();
                    }
                    event.consume();
                });
                Stage overlay = new Stage(StageStyle.TRANSPARENT);
                overlay.initOwner(ownerWindow);
                overlay.setAlwaysOnTop(true);
                overlay.setResizable(false);
                overlay.setScene(scene);
                overlay.setX(bounds.getMinX());
                overlay.setY(bounds.getMinY());
                overlay.setWidth(bounds.getWidth());
                overlay.setHeight(bounds.getHeight());
                overlays.add(overlay);
                overlay.show();
                surface.requestFocus();
            }
        } catch (RuntimeException error) {
            finish(null, error);
        }
    }

    private static List<Rectangle2D> overlayBounds(Rectangle2D screen, double scaleX, double scaleY) {
        // Leave one pixel for rounding fractional native window scales.
        double width = Math.max(1, Math.floor((MAX_RENDER_SIZE - 1) / scaleX));
        double height = Math.max(1, Math.floor((MAX_RENDER_SIZE - 1) / scaleY));
        List<Rectangle2D> regions = new ArrayList<>();
        for (double y = screen.getMinY(); y < screen.getMaxY(); y += height) {
            for (double x = screen.getMinX(); x < screen.getMaxX(); x += width) {
                regions.add(new Rectangle2D(x, y, Math.min(width, screen.getMaxX() - x),
                        Math.min(height, screen.getMaxY() - y)));
            }
        }
        return List.copyOf(regions);
    }

    private void sample(double screenX, double screenY) {
        if (!active || settle.getStatus() == javafx.animation.Animation.Status.RUNNING) return;
        overlays.forEach(Stage::hide);
        // Let the window server remove the intercepting surfaces before reading the native pixel.
        settle.setOnFinished(event -> {
            if (!active) return;
            try {
                finish(robot.getPixelColor(screenX, screenY), null);
            } catch (RuntimeException error) {
                finish(null, error);
            }
        });
        settle.playFromStart();
    }

    void cancel() {
        finish(null, null);
    }

    private void finish(Color color, Throwable error) {
        if (!active) return;
        active = false;
        settle.stop();
        ownerWindow.showingProperty().removeListener(showing);
        owner.sceneProperty().removeListener(scene);
        ancestors.forEach(node -> node.visibleProperty().removeListener(visible));
        ownerScene.removeEventFilter(KeyEvent.ANY, keys);
        overlays.forEach(Stage::close);
        overlays.clear();
        if (ownerWindow.isShowing() && owner.getScene() == ownerScene) {
            ownerWindow.requestFocus();
            if (previousFocus != null && previousFocus.getScene() == ownerScene) previousFocus.requestFocus();
        }
        completed.accept(color, error);
    }
}
