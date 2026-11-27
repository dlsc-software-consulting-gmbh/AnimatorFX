package com.dlsc.animatorfx.editor.inspector.fields;

import com.dlsc.animatorfx.model.Rgba;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;

/**
 * A colour as a swatch and a hex text ({@code #RRGGBB}, or {@code #RRGGBBAA} when not opaque).
 * A click on the swatch opens a {@link ColorPopup}. The change handler gets the colour and
 * whether the change is part of a drag in the picker, which should become one undo step; the
 * adjust-end handler runs when the drag is over. {@link #setValue} only updates the display.
 */
public final class ColorField extends HBox {

    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    private final Region swatchColor = new Region();
    private final Button swatch = new Button();
    private final TextField hex = new TextField();
    private final Button pipetteButton = new Button(null, new FontIcon(Material2AL.COLORIZE));
    private ScreenPipette pipette;
    private ColorPopup popup;
    private Rgba value;
    private boolean typing;
    private boolean updatingText;
    private BiConsumer<Rgba, Boolean> onChange = (color, adjusting) -> {
    };
    private Consumer<Rgba> onScreenPick = color -> onChange.accept(color, false);
    private Runnable onAdjustEnd = () -> {
    };

    public ColorField() {
        getStyleClass().add("color-field");
        Region base = new Region();
        base.getStyleClass().add("swatch-base");
        swatchColor.getStyleClass().add("swatch-color");
        StackPane box = new StackPane(base, swatchColor);
        box.getStyleClass().add("swatch-box");
        swatch.setGraphic(box);
        swatch.getStyleClass().setAll("swatch");
        swatch.setFocusTraversable(false);
        swatch.setTooltip(new Tooltip("Pick a colour"));
        swatch.setOnAction(e -> showPicker());

        hex.getStyleClass().addAll("hex-field", "mono");
        hex.setPromptText("mixed");
        hex.setPrefColumnCount(8);
        hex.setMinWidth(0);
        hex.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(hex, Priority.ALWAYS);
        hex.textProperty().addListener(obs -> {
            if (!updatingText) {
                typing = true;
                hex.pseudoClassStateChanged(ERROR, HexColors.parse(hex.getText()).isEmpty());
            }
        });
        hex.setOnAction(e -> {
            commit();
            hex.selectAll();
        });
        hex.focusedProperty().addListener((obs, was, focused) -> {
            if (focused) {
                Platform.runLater(hex::selectAll);
            } else {
                commit();
            }
        });
        hex.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                showValue();
                e.consume();
            }
        });
        pipetteButton.getStyleClass().setAll("icon-button", "pipette");
        pipetteButton.setFocusTraversable(false);
        pipetteButton.setAccessibleText("Pick a color from the screen");
        pipetteButton.setTooltip(new Tooltip("Pick a screen color: click or press Enter under the pointer; Escape cancels"));
        pipetteButton.setOnAction(event -> pickFromScreen());
        disabledProperty().addListener((obs, old, disabled) -> {
            if (disabled) cancelPipette();
        });
        getChildren().addAll(swatch, hex, pipetteButton);
    }

    public Rgba getValue() {
        return value;
    }

    /** Shows a colour, or the "mixed" placeholder for {@code null}. */
    public void setValue(Rgba color) {
        cancelPipette();
        value = color;
        if (!(hex.isFocused() && typing)) {
            showValue();
        }
        if (popup != null && popup.isShowing() && color != null && !color.equals(popup.getColor())) {
            popup.setColor(color);
        }
    }

    public void setOnChange(BiConsumer<Rgba, Boolean> handler) {
        onChange = handler;
    }

    public void setOnAdjustEnd(Runnable handler) {
        onAdjustEnd = handler;
    }

    /** Sets the screen-pick handler, for editors that preserve each selected value's alpha. */
    public void setOnScreenPick(Consumer<Rgba> handler) {
        onScreenPick = handler;
    }

    public TextField getHexField() {
        return hex;
    }

    public Button getSwatch() {
        return swatch;
    }

    /** The picker; created on first use. */
    public ColorPopup getPopup() {
        if (popup == null) {
            popup = new ColorPopup();
            popup.setOnChange((color, adjusting) -> {
                value = color;
                showValue();
                onChange.accept(color, adjusting);
            });
            popup.setOnAdjustEnd(() -> onAdjustEnd.run());
            popup.setOnHidden(e -> onAdjustEnd.run());
        }
        return popup;
    }

    public void showPicker() {
        cancelPipette();
        getPopup().setError(null);
        getPopup().setColor(value == null ? Rgba.WHITE : value);
        getPopup().showBelow(swatch);
    }

    private void pickFromScreen() {
        if (getScene() == null || getScene().getWindow() == null) return;
        commit();
        cancelPipette();
        if (popup != null) popup.hide();
        double alpha = value == null ? 1 : value.a();
        pipette = new ScreenPipette(this, (sample, error) -> {
            pipette = null;
            if (error != null) {
                getPopup().setColor(value == null ? Rgba.WHITE : value);
                getPopup().setError("Screen color could not be sampled. On macOS, allow Screen Recording "
                        + "for AnimatorFX (or your Java launcher / IDE) in System Settings.");
                getPopup().showBelow(swatch);
            } else if (sample != null) {
                // Robot exposes 8-bit pixels through float-backed JavaFX color channels.
                value = new Rgba(Math.round(sample.getRed() * 255) / 255.0,
                        Math.round(sample.getGreen() * 255) / 255.0, Math.round(sample.getBlue() * 255) / 255.0, alpha);
                showValue();
                onScreenPick.accept(value);
            }
        });
        pipette.start();
    }

    private void cancelPipette() {
        if (pipette != null) pipette.cancel();
    }

    private void showValue() {
        updatingText = true;
        try {
            hex.setText(value == null ? "" : HexColors.format(value));
        } finally {
            updatingText = false;
        }
        typing = false;
        hex.pseudoClassStateChanged(ERROR, false);
        Color color = value == null ? Color.TRANSPARENT
                : Color.color(clamp(value.r()), clamp(value.g()), clamp(value.b()), clamp(value.a()));
        swatchColor.setBackground(new Background(new BackgroundFill(color, new CornerRadii(2), Insets.EMPTY)));
    }

    private static double clamp(double channel) {
        return Math.clamp(channel, 0.0, 1.0);
    }

    private void commit() {
        if (!typing) {
            return;
        }
        Rgba parsed = HexColors.parse(hex.getText()).orElse(null);
        if (parsed == null) {
            showValue();
            return;
        }
        boolean changed = !parsed.equals(value);
        value = parsed;
        showValue();
        if (changed) {
            onChange.accept(parsed, false);
        }
    }
}
