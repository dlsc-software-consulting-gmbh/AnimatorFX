package com.dlsc.animatorfx.editor.inspector;

import com.dlsc.animatorfx.editor.doc.Command;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.Selection.KeyframeRef;
import com.dlsc.animatorfx.editor.inspector.fields.Combos;
import com.dlsc.animatorfx.editor.inspector.fields.TextValueField;
import com.dlsc.animatorfx.editor.ui.Palette;
import com.dlsc.animatorfx.editor.ui.Palette.Tone;
import com.dlsc.animatorfx.editor.ui.TiledCanvas;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Easing;
import com.dlsc.animatorfx.model.Easing.CubicBezier;
import com.dlsc.animatorfx.model.Keyframe;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.Layers;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

/**
 * Edits the easing of the selected keyframes: the easing of the segment that follows each of
 * them. It shows the curve on a grid with two draggable control handles (x stays within 0..1, y
 * may overshoot from -0.5 to 1.5), a preset selector, Linear and Hold buttons, and the
 * {@code cubic-bezier(x1, y1, x2, y2)} notation, which can be edited. Keyframes with different
 * easings show "Mixed". The last keyframe of a property has no segment after it and is left out;
 * when only such keyframes are selected the editor says so.
 */
public final class EasingEditor extends VBox {

    public static final String CURVE_ID = "easing-curve";
    public static final String PRESET_ID = "easing-preset";
    public static final String BEZIER_ID = "easing-bezier";
    public static final String LINEAR_ID = "easing-linear";
    public static final String HOLD_ID = "easing-hold";
    public static final String HINT_ID = "easing-hint";

    private static final double VIEW_HEIGHT = 152;
    private static final double PADDING = 12;
    private static final int SAMPLES = 64;

    private final Palette palette = new Palette();
    private final TiledCanvas canvas = new TiledCanvas();
    private final Label fontProbe = new Label();
    private final Region curve = new Region() {
        {
            getChildren().addAll(palette, canvas, fontProbe);
        }

        @Override
        protected void layoutChildren() {
            draw();
        }
    };
    private final ComboBox<String> presets = new ComboBox<>();
    private final Button linear = new Button("Linear");
    private final Button hold = new Button("Hold");
    private final TextValueField bezier = new TextValueField();
    private final Label hint = new Label(
            "The last keyframe of a property has no segment after it. Easing belongs to the keyframe a segment starts from.");

    private InspectorContext context;
    private List<KeyframeRef> targets = List.of();
    /** The easing that all targets share; {@code null} for a mix or without targets. */
    private Easing easing;
    private int draggedHandle = -1;
    private Animation shownAnimation;
    private List<KeyframeRef> shownSelection;
    private boolean updating;

    public EasingEditor() {
        getStyleClass().add("easing-editor");
        curve.setId(CURVE_ID);
        curve.getStyleClass().add("easing-curve");
        curve.setMinHeight(VIEW_HEIGHT);
        curve.setPrefHeight(VIEW_HEIGHT);
        curve.setMaxHeight(VIEW_HEIGHT);
        curve.setMinWidth(0);
        fontProbe.getStyleClass().add("easing-label");
        fontProbe.setManaged(false);
        fontProbe.setVisible(false);
        palette.setOnChange(curve::requestLayout);
        curve.setOnMousePressed(this::pressed);
        curve.setOnMouseDragged(this::dragged);
        curve.setOnMouseReleased(e -> released());

        presets.setId(PRESET_ID);
        Combos.compact(presets);
        presets.getItems().addAll(Easing.presets().keySet());
        presets.setVisibleRowCount(12);
        presets.setMaxWidth(Double.MAX_VALUE);
        presets.setMinWidth(0);
        HBox.setHgrow(presets, Priority.ALWAYS);
        presets.valueProperty().addListener((obs, old, name) -> {
            if (!updating && name != null) {
                apply(Easing.presets().get(name), null);
            }
        });
        linear.setId(LINEAR_ID);
        hold.setId(HOLD_ID);
        linear.getStyleClass().add("section-button");
        hold.getStyleClass().add("section-button");
        linear.setOnAction(e -> apply(Easing.LINEAR, null));
        hold.setOnAction(e -> apply(Easing.HOLD, null));
        HBox choice = new HBox(presets, linear, hold);
        choice.getStyleClass().add("easing-choice");

        bezier.setId(BEZIER_ID);
        bezier.getStyleClass().add("mono");
        bezier.setValidator(text -> parse(text).isPresent() ? Optional.empty()
                : Optional.of("cubic-bezier(x1, y1, x2, y2), linear or hold"));
        bezier.setOnCommit(text -> parse(text).ifPresent(value -> apply(value, null)));

        hint.setId(HINT_ID);
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        getChildren().addAll(curve, choice, bezier, hint);
    }

    private static Optional<Easing> parse(String text) {
        String body = text == null ? "" : text.strip().toLowerCase();
        if (body.equals("linear")) {
            return Optional.of(Easing.LINEAR);
        }
        if (body.equals("hold")) {
            return Optional.of(Easing.HOLD);
        }
        return EasingCurve.parse(body).map(b -> b);
    }

    /** The keyframes that the editor changes: the selected ones that start a segment. */
    public List<KeyframeRef> getTargets() {
        return targets;
    }

    /** The easing that the editor shows; {@code null} for "Mixed" or without targets. */
    public Easing getEasing() {
        return easing;
    }

    /** The geometry of the curve view, for mapping easing values to positions in it. */
    public EasingCurve getGeometry() {
        double size = Math.min(curve.getWidth(), curve.getHeight());
        return new EasingCurve(size, size, PADDING);
    }

    /** The x offset of the square plot inside the curve view. */
    public double getPlotX() {
        return Math.round((curve.getWidth() - Math.min(curve.getWidth(), curve.getHeight())) / 2);
    }

    // ------------------------------------------------------------------ update

    /** Shows the easing of the selected keyframes of the context. */
    public void update(InspectorContext newContext) {
        context = newContext;
        List<KeyframeRef> selected = newContext.keyframes();
        if (newContext.animation() == shownAnimation && selected.equals(shownSelection)) {
            // a playhead move: nothing here depends on the time
            return;
        }
        shownAnimation = newContext.animation();
        shownSelection = selected;
        List<KeyframeRef> found = new ArrayList<>();
        List<Easing> easings = new ArrayList<>();
        for (KeyframeRef ref : selected) {
            Layer layer = Layers.find(newContext.animation(), ref.layerId()).orElse(null);
            Animatable<?> value = layer == null ? null : InspectorContext.property(layer, ref.propertyKey()).orElse(null);
            if (value == null) {
                continue;
            }
            List<? extends Keyframe<?>> keyframes = value.keyframes();
            for (int i = 0; i < keyframes.size() - 1; i++) {
                if (keyframes.get(i).id().equals(ref.keyframeId())) {
                    found.add(ref);
                    easings.add(keyframes.get(i).easing());
                }
            }
        }
        targets = List.copyOf(found);
        easing = EasingCurve.common(easings).orElse(null);
        boolean none = targets.isEmpty();
        String name = none ? "" : easing == null ? "Mixed" : EasingCurve.name(easing);

        updating = true;
        try {
            presets.setValue(Easing.presets().containsKey(name) ? name : null);
            presets.setPromptText(name);
        } finally {
            updating = false;
        }
        CubicBezier shown = easing == null ? null : EasingCurve.asBezier(easing);
        bezier.setValue(easing == null ? null : shown == null ? "hold" : EasingCurve.format(shown));
        for (Region control : new Region[]{presets, linear, hold, bezier}) {
            control.setDisable(none);
        }
        hint.setVisible(none);
        hint.setManaged(none);
        curve.requestLayout();
    }

    private void apply(Easing value, Object gestureKey) {
        if (context == null || targets.isEmpty()) {
            return;
        }
        Command command = Commands.setEasing(targets, value);
        if (gestureKey == null) {
            context.document().execute(command);
        } else {
            context.document().executeCoalesced(gestureKey, command);
        }
    }

    // ------------------------------------------------------------------ handles

    private void pressed(MouseEvent event) {
        CubicBezier shown = easing == null ? null : EasingCurve.asBezier(easing);
        draggedHandle = shown == null ? -1 : getGeometry().handleAt(shown, event.getX() - getPlotX(), event.getY());
        if (draggedHandle >= 0) {
            context.document().endGesture();
        }
    }

    private void dragged(MouseEvent event) {
        CubicBezier shown = easing == null ? null : EasingCurve.asBezier(easing);
        if (draggedHandle < 0 || shown == null) {
            return;
        }
        apply(EasingCurve.withHandle(shown, draggedHandle,
                getGeometry().toValue(event.getX() - getPlotX(), event.getY())), this);
    }

    private void released() {
        if (draggedHandle >= 0 && context != null) {
            draggedHandle = -1;
            context.document().endGesture();
            curve.requestLayout();
        }
        draggedHandle = -1;
    }

    // ------------------------------------------------------------------ drawing

    private void draw() {
        double width = curve.getWidth();
        double height = curve.getHeight();
        canvas.draw(width, height, g -> draw(g, width, height));
    }

    private void draw(GraphicsContext g, double width, double height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        EasingCurve geometry = getGeometry();
        double left = getPlotX();
        g.save();
        g.translate(left, 0);
        g.setLineWidth(1);

        // the unit square with quarter lines; the room above and below is for overshoot
        g.setStroke(palette.get(Tone.GRID));
        for (int i = 0; i <= 4; i++) {
            double x = Math.round(geometry.x(i / 4.0)) + 0.5;
            double y = Math.round(geometry.y(i / 4.0)) + 0.5;
            g.strokeLine(x, geometry.y(1), x, geometry.y(0));
            g.strokeLine(geometry.x(0), y, geometry.x(1), y);
        }
        g.setStroke(palette.get(Tone.LINE));
        g.strokeRect(Math.round(geometry.x(0)) + 0.5, Math.round(geometry.y(1)) + 0.5,
                Math.round(geometry.x(1) - geometry.x(0)), Math.round(geometry.y(0) - geometry.y(1)));
        g.setStroke(palette.get(Tone.MUTED, 0.5));
        g.setLineDashes(3, 3);
        g.strokeLine(geometry.x(0), geometry.y(0), geometry.x(1), geometry.y(1));
        g.setLineDashes();

        if (targets.isEmpty() || easing == null) {
            g.setFill(palette.get(Tone.MUTED));
            g.setFont(fontProbe.getFont());
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.CENTER);
            g.fillText(targets.isEmpty() ? "No segment" : "Mixed", geometry.x(0.5), geometry.y(0.5));
        } else if (easing instanceof Easing.Hold) {
            g.setStroke(palette.get(Tone.ACCENT));
            g.setLineWidth(2);
            g.strokePolyline(new double[]{geometry.x(0), geometry.x(1), geometry.x(1)},
                    new double[]{geometry.y(0), geometry.y(0), geometry.y(1)}, 3);
        } else {
            CubicBezier b = EasingCurve.asBezier(easing);
            g.setStroke(palette.get(Tone.MUTED));
            g.strokeLine(geometry.x(0), geometry.y(0), geometry.x(b.x1()), geometry.y(b.y1()));
            g.strokeLine(geometry.x(1), geometry.y(1), geometry.x(b.x2()), geometry.y(b.y2()));
            double[] xs = new double[SAMPLES + 1];
            double[] ys = new double[SAMPLES + 1];
            for (int i = 0; i <= SAMPLES; i++) {
                double t = i / (double) SAMPLES;
                double s = 1 - t;
                xs[i] = geometry.x(3 * s * s * t * b.x1() + 3 * s * t * t * b.x2() + t * t * t);
                ys[i] = geometry.y(3 * s * s * t * b.y1() + 3 * s * t * t * b.y2() + t * t * t);
            }
            g.setStroke(palette.get(Tone.ACCENT));
            g.setLineWidth(2);
            g.strokePolyline(xs, ys, SAMPLES + 1);
            g.setLineWidth(1.5);
            for (int handle = 0; handle < 2; handle++) {
                double x = geometry.x(handle == 0 ? b.x1() : b.x2());
                double y = geometry.y(handle == 0 ? b.y1() : b.y2());
                g.setFill(palette.get(handle == draggedHandle ? Tone.ACCENT : Tone.PANEL));
                g.fillOval(x - 4.5, y - 4.5, 9, 9);
                g.setStroke(palette.get(Tone.ACCENT));
                g.strokeOval(x - 4.5, y - 4.5, 9, 9);
            }
        }
        g.restore();
    }
}
