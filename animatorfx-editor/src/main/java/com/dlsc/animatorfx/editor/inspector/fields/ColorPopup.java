package com.dlsc.animatorfx.editor.inspector.fields;

import com.dlsc.animatorfx.model.Rgba;
import java.util.function.BiConsumer;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.stage.Popup;

/**
 * The compact colour picker of a {@link ColorField}: a saturation/value square, a hue bar and
 * an alpha bar. It reports every change while a bar or the square is dragged, and once more when
 * the drag ends.
 */
public final class ColorPopup extends Popup {

    private static final double WIDTH = 196;
    private static final double SQUARE_HEIGHT = 124;
    private static final double BAR_HEIGHT = 12;

    private final Region hueLayer = new Region();
    private final Pane square = new Pane();
    private final Pane hueBar = new Pane();
    private final Pane alphaBar = new Pane();
    private final Region alphaLayer = new Region();
    private final Region squareHandle = handle();
    private final Region hueHandle = handle();
    private final Region alphaHandle = handle();
    private final StackPane frame = new StackPane();
    private final Label error = new Label();

    private double hue;
    private double saturation;
    private double brightness;
    private double alpha = 1;
    private BiConsumer<Rgba, Boolean> onChange = (color, adjusting) -> {
    };
    private Runnable onAdjustEnd = () -> {
    };

    public ColorPopup() {
        setAutoHide(true);
        setAutoFix(true);
        setHideOnEscape(true);

        Region white = new Region();
        white.getStyleClass().add("sv-white");
        Region black = new Region();
        black.getStyleClass().add("sv-black");
        for (Region layer : new Region[]{hueLayer, white, black}) {
            layer.setMouseTransparent(true);
            layer.resizeRelocate(0, 0, WIDTH, SQUARE_HEIGHT);
            layer.setManaged(false);
        }
        square.getStyleClass().add("sv-square");
        square.getChildren().addAll(hueLayer, white, black, squareHandle);
        size(square, SQUARE_HEIGHT);

        hueBar.getStyleClass().add("hue-bar");
        hueBar.getChildren().add(hueHandle);
        size(hueBar, BAR_HEIGHT);

        alphaLayer.setManaged(false);
        alphaLayer.setMouseTransparent(true);
        alphaLayer.resizeRelocate(0, 0, WIDTH, BAR_HEIGHT);
        alphaBar.getStyleClass().add("alpha-bar");
        alphaBar.getChildren().addAll(alphaLayer, alphaHandle);
        size(alphaBar, BAR_HEIGHT);

        drag(square, e -> {
            saturation = Math.clamp(e.getX() / WIDTH, 0, 1);
            brightness = 1 - Math.clamp(e.getY() / SQUARE_HEIGHT, 0, 1);
        });
        drag(hueBar, e -> hue = Math.clamp(e.getX() / WIDTH, 0, 1) * 360);
        drag(alphaBar, e -> alpha = Math.clamp(e.getX() / WIDTH, 0, 1));

        error.setWrapText(true);
        error.setMaxWidth(WIDTH);
        error.getStyleClass().add("pick-error");
        setError(null);
        VBox content = new VBox(square, hueBar, alphaBar, error);
        // the popup has a scene of its own: "root" gives it the palette, the frame carries "light"
        content.getStyleClass().addAll("root", "color-popup");
        frame.getChildren().add(content);
        getContent().add(frame);
    }

    void setError(String message) {
        error.setText(message);
        error.setVisible(message != null);
        error.setManaged(message != null);
    }

    private static Region handle() {
        Region handle = new Region();
        handle.getStyleClass().add("picker-handle");
        handle.setManaged(false);
        handle.setMouseTransparent(true);
        return handle;
    }

    private static void size(Pane pane, double height) {
        pane.setMinSize(WIDTH, height);
        pane.setPrefSize(WIDTH, height);
        pane.setMaxSize(WIDTH, height);
    }

    private void drag(Node node, java.util.function.Consumer<MouseEvent> update) {
        node.setOnMousePressed(e -> {
            update.accept(e);
            changed(true);
        });
        node.setOnMouseDragged(e -> {
            update.accept(e);
            changed(true);
        });
        node.setOnMouseReleased(e -> onAdjustEnd.run());
    }

    private void changed(boolean adjusting) {
        refresh();
        onChange.accept(getColor(), adjusting);
    }

    public Rgba getColor() {
        return HexColors.fromHsv(hue, saturation, brightness, alpha);
    }

    /** Shows a colour without reporting a change. Grey keeps the hue the picker had. */
    public void setColor(Rgba color) {
        double[] hsv = HexColors.toHsv(color);
        if (hsv[1] > 0 && hsv[2] > 0) {
            hue = hsv[0];
        }
        saturation = hsv[1];
        brightness = hsv[2];
        alpha = color.a();
        refresh();
    }

    /**
     * Sets hue (degrees), saturation, brightness and alpha as a drag step in the picker does;
     * {@link #finishPick()} ends the drag.
     */
    public void pick(double hue, double saturation, double brightness, double alpha) {
        this.hue = hue;
        this.saturation = saturation;
        this.brightness = brightness;
        this.alpha = alpha;
        changed(true);
    }

    /** Ends a drag in the picker, as releasing the mouse button does. */
    public void finishPick() {
        onAdjustEnd.run();
    }

    public void setOnChange(BiConsumer<Rgba, Boolean> handler) {
        onChange = handler;
    }

    public void setOnAdjustEnd(Runnable handler) {
        onAdjustEnd = handler;
    }

    private void refresh() {
        Rgba pure = HexColors.fromHsv(hue, 1, 1, 1);
        Color hueColor = Color.color(pure.r(), pure.g(), pure.b());
        hueLayer.setBackground(new Background(new BackgroundFill(hueColor, new CornerRadii(3), Insets.EMPTY)));
        Rgba opaque = HexColors.fromHsv(hue, saturation, brightness, 1);
        Color solid = Color.color(opaque.r(), opaque.g(), opaque.b());
        alphaLayer.setBackground(new Background(new BackgroundFill(new LinearGradient(0, 0, 1, 0, true,
                CycleMethod.NO_CYCLE, new Stop(0, Color.color(opaque.r(), opaque.g(), opaque.b(), 0)), new Stop(1, solid)),
                new CornerRadii(3), Insets.EMPTY)));
        place(squareHandle, saturation * WIDTH, (1 - brightness) * SQUARE_HEIGHT);
        place(hueHandle, hue / 360 * WIDTH, BAR_HEIGHT / 2);
        place(alphaHandle, alpha * WIDTH, BAR_HEIGHT / 2);
    }

    private static void place(Region handle, double x, double y) {
        handle.resizeRelocate(x - 6, y - 6, 12, 12);
    }

    /** Opens the picker under the owner, in the theme of the owner's window. */
    public void showBelow(Node owner) {
        frame.getStyleClass().setAll("color-popup-frame");
        if (owner.getScene() != null) {
            if (owner.getScene().getRoot().getStyleClass().contains("light")) {
                frame.getStyleClass().add("light");
            }
            getScene().getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        Bounds bounds = owner.localToScreen(owner.getBoundsInLocal());
        if (bounds != null) {
            show(owner, bounds.getMinX(), bounds.getMaxY() + 4);
        }
    }
}
