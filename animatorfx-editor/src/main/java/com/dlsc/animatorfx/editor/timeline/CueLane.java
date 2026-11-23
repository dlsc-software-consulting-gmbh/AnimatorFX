package com.dlsc.animatorfx.editor.timeline;

import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.ui.Palette;
import com.dlsc.animatorfx.editor.ui.Palette.Tone;
import com.dlsc.animatorfx.editor.ui.TiledCanvas;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.SoundCue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * The lane under the marker lane that shows the sound cues: a tick at the time of each cue with
 * a small speaker and its name, dimmed for a muted cue. A click selects a cue (Shift or Shortcut
 * adds, Shortcut on a selected one removes), a drag moves the selected cues together, snapped
 * to frames unless Alt is held, as one undo step; Escape cancels the drag. A double-click on
 * free space adds a cue there. Delete removes the selected cues while the lane has the focus.
 */
public final class CueLane extends Region {

    private static final double REACH = 4;
    private static final double LABEL_X = 14;
    private static final double LABEL_PAD = 5;
    private static final double MAX_LABEL = 120;
    private static final double DRAG_THRESHOLD = 3;

    /** Where a cue is drawn: its tick and the right end of its label. */
    private record Box(SoundCue cue, double x, double right) {
    }

    private final TimelineView timeline;
    private final TiledCanvas canvas = new TiledCanvas();
    private final Label fontProbe = new Label();
    private final Text measure = new Text();
    private final Map<String, Double> widths = new HashMap<>();
    private Font measuredFont;
    private final Object gestureKey = new Object();

    private List<String> moving = List.of();
    private Animation original;
    private String collapseTo;
    private double pressX;
    private boolean dragging;

    CueLane(TimelineView timeline) {
        this.timeline = timeline;
        getStyleClass().add("cue-lane");
        setFocusTraversable(true);
        fontProbe.getStyleClass().add("cue-label");
        fontProbe.setManaged(false);
        fontProbe.setVisible(false);
        fontProbe.fontProperty().addListener(obs -> timeline.requestLayout());
        getChildren().addAll(canvas, fontProbe);
        setOnMousePressed(this::pressed);
        setOnMouseDragged(this::dragged);
        setOnMouseReleased(this::released);
        addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && dragging) {
                cancelGesture();
                e.consume();
            }
        });
    }

    private EditorDocument doc() {
        return timeline.getDocument();
    }

    // ------------------------------------------------------------------ geometry

    private double textWidth(String text) {
        Font font = fontProbe.getFont();
        if (font != measuredFont) {
            measuredFont = font;
            widths.clear();
        }
        if (widths.size() > 500) {
            widths.clear();
        }
        return widths.computeIfAbsent(text, t -> {
            measure.setFont(font);
            measure.setText(t);
            return measure.getLayoutBounds().getWidth();
        });
    }

    /** The cues in time order with the room each one has: a label ends before the next tick. */
    private List<Box> boxes() {
        EditorDocument doc = doc();
        if (doc == null) {
            return List.of();
        }
        TimeScale scale = timeline.getScale();
        List<SoundCue> cues = new ArrayList<>(doc.getAnimation().cues());
        cues.sort(Comparator.comparingDouble(SoundCue::time));
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < cues.size(); i++) {
            SoundCue cue = cues.get(i);
            double x = Math.round(scale.toX(cue.time()));
            double right = x + LABEL_X + Math.min(MAX_LABEL, textWidth(cue.name())) + LABEL_PAD;
            if (i + 1 < cues.size()) {
                double next = Math.round(scale.toX(cues.get(i + 1).time()));
                right = Math.max(x + REACH, Math.min(right, next - 3));
            }
            boxes.add(new Box(cue, x, right));
        }
        return boxes;
    }

    /** The cue at an x coordinate: on its tick or on its label. */
    public Optional<SoundCue> hitAt(double x) {
        List<Box> boxes = boxes();
        for (int i = boxes.size() - 1; i >= 0; i--) {
            Box box = boxes.get(i);
            if (x >= box.x() - REACH && x <= box.right()) {
                return Optional.of(box.cue());
            }
        }
        return Optional.empty();
    }

    /** The x coordinate of the tick of a cue. */
    public double cueX(String cueId) {
        return boxes().stream().filter(box -> box.cue().id().equals(cueId)).mapToDouble(Box::x).findFirst().orElse(Double.NaN);
    }

    // ------------------------------------------------------------------ pointer

    private void pressed(MouseEvent event) {
        EditorDocument doc = doc();
        requestFocus();
        if (doc == null || event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        doc.endGesture();
        timeline.setSelectedMarker(null);
        Set<String> selection = doc.getSelection().cueIds();
        Optional<SoundCue> hit = hitAt(event.getX());
        boolean add = event.isShiftDown() || event.isShortcutDown();
        moving = List.of();
        collapseTo = null;
        dragging = false;
        pressX = event.getX();
        if (hit.isEmpty()) {
            if (!add) {
                selection.clear();
            }
            if (event.getClickCount() == 2) {
                timeline.addCue(timeline.getScale().toTime(event.getX()), null);
            }
            event.consume();
            return;
        }
        String id = hit.get().id();
        if (add) {
            if (selection.contains(id) && event.isShortcutDown()) {
                selection.remove(id);
                event.consume();
                return;
            }
            selection.add(id);
        } else if (selection.contains(id)) {
            collapseTo = id;
        } else {
            selection.retainAll(Set.of(id));
            selection.add(id);
        }
        // the grabbed cue first: it is the one that lands on a frame
        Set<String> ids = new LinkedHashSet<>();
        ids.add(id);
        ids.addAll(selection);
        moving = List.copyOf(ids);
        original = doc.getAnimation();
        event.consume();
    }

    private void dragged(MouseEvent event) {
        EditorDocument doc = doc();
        if (doc == null || moving.isEmpty() || !event.isPrimaryButtonDown()) {
            return;
        }
        if (!dragging && Math.abs(event.getX() - pressX) < DRAG_THRESHOLD) {
            return;
        }
        dragging = true;
        double delta = timeline.getScale().toDuration(event.getX() - pressX);
        Animation start = original;
        List<String> ids = moving;
        boolean free = event.isAltDown();
        doc.executeCoalesced(gestureKey, Commands.setDocument(ids.size() == 1 ? "Move Cue" : "Move Cues",
                current -> Cues.move(ids, delta, free).apply(start)));
        event.consume();
    }

    private void released(MouseEvent event) {
        EditorDocument doc = doc();
        if (doc != null) {
            if (dragging) {
                doc.endGesture();
            } else if (collapseTo != null) {
                doc.getSelection().cueIds().retainAll(Set.of(collapseTo));
            }
        }
        reset();
    }

    /** Abandons a cue drag and returns the cues to where they were. */
    void cancelGesture() {
        if (dragging && doc() != null) {
            doc().cancelGesture();
        }
        reset();
    }

    private void reset() {
        moving = List.of();
        original = null;
        collapseTo = null;
        dragging = false;
    }

    // ------------------------------------------------------------------ drawing

    void draw() {
        double width = getWidth();
        double height = getHeight();
        canvas.draw(width, height, g -> draw(g, width, height));
    }

    private void draw(GraphicsContext g, double width, double height) {
        Palette palette = timeline.getPalette();
        g.setFill(palette.get(Tone.PANEL_ALT));
        g.fillRect(0, 0, width, height);
        EditorDocument doc = doc();
        if (doc != null && width > 0) {
            Animation animation = doc.getAnimation();
            double end = timeline.getScale().toX(animation.duration());
            if (end < width) {
                g.setFill(palette.get(Tone.BG, 0.55));
                g.fillRect(Math.max(0, end), 0, width - Math.max(0, end), height);
            }
            g.setFont(fontProbe.getFont());
            g.setTextBaseline(VPos.CENTER);
            g.setLineWidth(1);
            Set<String> selected = doc.getSelection().cueIds();
            double middle = Math.round((height - 1) / 2);
            for (Box box : boxes()) {
                if (box.right() < 0 || box.x() - REACH > width) {
                    continue;
                }
                SoundCue cue = box.cue();
                boolean isSelected = selected.contains(cue.id());
                double strength = cue.muted() ? 0.45 : 1;
                Color color = palette.get(Tone.AUDIO, strength);
                double x = box.x();
                if (isSelected) {
                    g.setFill(palette.get(Tone.AUDIO_BAR));
                    g.fillRoundRect(x, 2, box.right() - x, height - 5, 3, 3);
                    g.setStroke(palette.get(Tone.AUDIO));
                    g.strokeRoundRect(x + 0.5, 2.5, box.right() - x - 1, height - 6, 3, 3);
                }
                g.setStroke(color);
                g.setLineWidth(isSelected ? 2 : 1);
                g.strokeLine(x + (isSelected ? 1 : 0.5), 2, x + (isSelected ? 1 : 0.5), height - 3);
                g.setLineWidth(1);
                if (box.right() - x > LABEL_X) {
                    speaker(g, x + 4, middle + 0.5, color, cue.muted());
                }
                double room = box.right() - x - LABEL_X - 2;
                if (room > 6) {
                    g.save();
                    g.beginPath();
                    g.rect(x + LABEL_X, 0, room, height);
                    g.clip();
                    g.setFill(isSelected ? palette.get(Tone.FG, strength) : color);
                    g.fillText(cue.name(), x + LABEL_X, middle);
                    g.restore();
                }
            }
        }
        g.setStroke(palette.get(Tone.LINE));
        g.strokeLine(0, height - 0.5, width, height - 0.5);
    }

    /** A speaker 8 pixels wide with its left edge at x; struck through for a muted cue. */
    private static void speaker(GraphicsContext g, double x, double y, Color color, boolean muted) {
        g.setFill(color);
        g.beginPath();
        g.moveTo(x, y - 1.5);
        g.lineTo(x + 2, y - 1.5);
        g.lineTo(x + 4.5, y - 4);
        g.lineTo(x + 4.5, y + 4);
        g.lineTo(x + 2, y + 1.5);
        g.lineTo(x, y + 1.5);
        g.closePath();
        g.fill();
        g.setStroke(color);
        if (muted) {
            g.strokeLine(x + 6, y - 2, x + 9, y + 2);
            g.strokeLine(x + 9, y - 2, x + 6, y + 2);
        } else {
            g.strokeArc(x + 3, y - 3, 5, 6, -55, 110, ArcType.OPEN);
        }
    }
}
