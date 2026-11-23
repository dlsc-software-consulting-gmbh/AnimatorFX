package com.dlsc.animatorfx.editor.timeline;

import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.ui.Palette;
import com.dlsc.animatorfx.editor.ui.Palette.Tone;
import com.dlsc.animatorfx.editor.ui.TiledCanvas;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Marker;
import java.util.List;
import java.util.Optional;
import javafx.css.PseudoClass;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;

/**
 * The lane under the ruler that shows the markers as amber ranges with their names. A click
 * selects a marker, dragging its body moves it and dragging an end resizes it, both snapped to
 * frames unless Alt is held. A double-click on a marker renames it (names are unique; the
 * editor says so inline), a double-click on free space adds one. Delete removes the selected
 * marker while the lane has the focus. The secondary button opens a menu for the marker, which
 * also adds a sound cue at its start.
 */
public final class MarkerLane extends Region {

    public static final String RENAME_FIELD_ID = "marker-rename-field";
    public static final String RENAME_ERROR_ID = "marker-rename-error";
    public static final String MENU_ADD_CUE_ID = "marker-menu-add-cue";
    public static final String MENU_RENAME_ID = "marker-menu-rename";
    public static final String MENU_DELETE_ID = "marker-menu-delete";

    /** The part of a marker under the pointer. */
    public enum Part {START, BODY, END}

    /** A marker and the part of it at a position. */
    public record Hit(Marker marker, Part part) {
    }

    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");
    private static final double GRIP = 4;
    private static final double DRAG_THRESHOLD = 3;

    private final TimelineView timeline;
    private final TiledCanvas canvas = new TiledCanvas();
    private final Label fontProbe = new Label();
    private final TextField renameField = new TextField();
    private final Label renameError = new Label();

    private ContextMenu markerMenu;
    private String renaming;
    private Hit grabbed;
    private double pressX;
    private boolean dragging;

    MarkerLane(TimelineView timeline) {
        this.timeline = timeline;
        getStyleClass().add("marker-lane");
        setFocusTraversable(true);
        fontProbe.getStyleClass().add("marker-label");
        fontProbe.setManaged(false);
        fontProbe.setVisible(false);
        fontProbe.fontProperty().addListener(obs -> timeline.requestLayout());
        renameField.setId(RENAME_FIELD_ID);
        renameField.getStyleClass().add("timeline-rename");
        renameField.setManaged(false);
        renameField.setVisible(false);
        renameError.setId(RENAME_ERROR_ID);
        renameError.getStyleClass().add("inline-error");
        renameError.setManaged(false);
        renameError.setVisible(false);
        getChildren().addAll(canvas, fontProbe, renameField, renameError);

        renameField.setOnAction(e -> commitRename(true));
        renameField.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                commitRename(false);
            }
        });
        renameField.textProperty().addListener(obs -> validateRename());
        renameField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                endRename();
                e.consume();
            }
        });
        setOnMousePressed(this::pressed);
        setOnMouseDragged(this::dragged);
        setOnMouseReleased(this::released);
        setOnMouseMoved(e -> setCursor(hitAt(e.getX()).filter(h -> h.part() != Part.BODY).isPresent()
                ? Cursor.H_RESIZE : Cursor.DEFAULT));
        setOnContextMenuRequested(e -> {
            Optional<Hit> hit = hitAt(e.getX());
            if (doc() != null && hit.isPresent()) {
                requestFocus();
                timeline.setSelectedMarker(hit.get().marker().name());
                getMarkerMenu().show(this, e.getScreenX(), e.getScreenY());
            }
            e.consume();
        });
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

    /** The marker at an x coordinate, the one drawn last first; its ends count a few pixels wide. */
    public Optional<Hit> hitAt(double x) {
        EditorDocument doc = doc();
        if (doc == null) {
            return Optional.empty();
        }
        TimeScale scale = timeline.getScale();
        List<Marker> markers = doc.getAnimation().markers();
        for (int i = markers.size() - 1; i >= 0; i--) {
            Marker marker = markers.get(i);
            double start = scale.toX(marker.start());
            double end = scale.toX(marker.end());
            if (x < start - GRIP || x > end + GRIP) {
                continue;
            }
            boolean narrow = end - start < 3 * GRIP;
            if (x <= start + (narrow ? 0 : GRIP)) {
                return Optional.of(new Hit(marker, Part.START));
            }
            if (x >= end - (narrow ? 0 : GRIP)) {
                return Optional.of(new Hit(marker, Part.END));
            }
            return Optional.of(new Hit(marker, Part.BODY));
        }
        return Optional.empty();
    }

    /** The menu for the selected marker: Add Cue at Marker Start, Rename, Delete. */
    public ContextMenu getMarkerMenu() {
        if (markerMenu == null) {
            MenuItem addCue = new MenuItem("Add Cue at Marker Start");
            addCue.setId(MENU_ADD_CUE_ID);
            addCue.setOnAction(e -> selected().ifPresent(marker -> timeline.addCue(marker.start(), null)));
            MenuItem rename = new MenuItem("Rename");
            rename.setId(MENU_RENAME_ID);
            rename.setOnAction(e -> selected().ifPresent(marker -> startRename(marker.name())));
            MenuItem delete = new MenuItem("Delete");
            delete.setId(MENU_DELETE_ID);
            delete.setOnAction(e -> selected().ifPresent(marker -> {
                doc().execute(Markers.remove(marker.name()));
                timeline.setSelectedMarker(null);
            }));
            markerMenu = new ContextMenu(addCue, new SeparatorMenuItem(), rename, delete);
        }
        return markerMenu;
    }

    private Optional<Marker> selected() {
        String name = timeline.getSelectedMarker();
        return doc() == null || name == null ? Optional.empty() : doc().getAnimation().marker(name);
    }

    // ------------------------------------------------------------------ pointer

    private void pressed(MouseEvent event) {
        EditorDocument doc = doc();
        requestFocus();
        if (doc == null || event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        doc.endGesture();
        if (markerMenu != null) {
            markerMenu.hide();
        }
        doc.getSelection().cueIds().clear();
        Optional<Hit> hit = hitAt(event.getX());
        timeline.setSelectedMarker(hit.map(h -> h.marker().name()).orElse(null));
        grabbed = hit.orElse(null);
        pressX = event.getX();
        dragging = false;
        if (event.getClickCount() == 2) {
            grabbed = null;
            if (hit.isPresent()) {
                startRename(hit.get().marker().name());
            } else {
                String name = Markers.nextName(doc.getAnimation());
                doc.execute(Markers.add(timeline.snapTime(timeline.getScale().toTime(event.getX()), event.isAltDown())));
                timeline.setSelectedMarker(name);
            }
        }
        event.consume();
    }

    private void dragged(MouseEvent event) {
        EditorDocument doc = doc();
        if (grabbed == null || doc == null) {
            return;
        }
        if (!dragging && Math.abs(event.getX() - pressX) < DRAG_THRESHOLD) {
            return;
        }
        dragging = true;
        Animation animation = doc.getAnimation();
        Marker original = grabbed.marker();
        double delta = timeline.getScale().toDuration(event.getX() - pressX);
        boolean free = event.isAltDown();
        double frame = TimeScale.frame(animation.frameRate());
        double start = original.start();
        double end = original.end();
        String name = "Resize Marker";
        switch (grabbed.part()) {
            case BODY -> {
                double length = end - start;
                start = Math.clamp(timeline.snapTime(start + delta, free), 0, Math.max(0, animation.duration() - length));
                end = start + length;
                name = "Move Marker";
            }
            case START -> start = Math.min(timeline.snapTime(start + delta, free), end - frame);
            case END -> end = Math.max(timeline.snapTime(end + delta, free), start + frame);
        }
        doc.executeCoalesced(grabbed, Markers.setRange(name, original.name(), start, end));
        event.consume();
    }

    private void released(MouseEvent event) {
        if (dragging && doc() != null) {
            doc().endGesture();
        }
        grabbed = null;
        dragging = false;
    }

    /** Abandons a marker drag and returns the marker to where it was. */
    void cancelGesture() {
        if (dragging && doc() != null) {
            doc().cancelGesture();
        }
        grabbed = null;
        dragging = false;
        endRename();
    }

    // ------------------------------------------------------------------ rename

    /** Opens the inline editor for the name of a marker. */
    public void startRename(String markerName) {
        if (doc() == null || doc().getAnimation().marker(markerName).isEmpty()) {
            return;
        }
        renaming = markerName;
        renameField.setText(markerName);
        renameField.setVisible(true);
        placeRenameField();
        renameField.requestFocus();
        renameField.selectAll();
    }

    private Optional<String> renameError() {
        return Markers.nameError(doc().getAnimation(), renameField.getText() == null ? "" : renameField.getText().strip(),
                renaming);
    }

    private void validateRename() {
        if (renaming == null || doc() == null) {
            return;
        }
        Optional<String> error = renameError();
        renameField.pseudoClassStateChanged(ERROR, error.isPresent());
        renameError.setText(error.orElse(""));
        renameError.setVisible(error.isPresent());
        placeRenameField();
    }

    /**
     * @param keepOpenOnError Enter on a name that is not allowed keeps the editor open with its
     *                        message; losing the focus abandons the rename instead
     */
    private void commitRename(boolean keepOpenOnError) {
        if (renaming == null || doc() == null) {
            return;
        }
        if (renameError().isPresent()) {
            if (!keepOpenOnError) {
                endRename();
            }
            return;
        }
        String from = renaming;
        String to = renameField.getText().strip();
        endRename();
        doc().execute(Markers.rename(from, to));
        if (from.equals(timeline.getSelectedMarker()) || timeline.getSelectedMarker() == null) {
            timeline.setSelectedMarker(to);
        }
    }

    private void endRename() {
        if (renaming == null) {
            return;
        }
        renaming = null;
        renameField.setVisible(false);
        renameError.setVisible(false);
        renameField.pseudoClassStateChanged(ERROR, false);
        if (renameField.isFocused()) {
            requestFocus();
        }
    }

    private void placeRenameField() {
        if (renaming == null) {
            return;
        }
        Optional<Marker> marker = doc() == null ? Optional.empty() : doc().getAnimation().marker(renaming);
        if (marker.isEmpty()) {
            endRename();
            return;
        }
        double x = Math.clamp(timeline.getScale().toX(marker.get().start()), 0, Math.max(0, getWidth() - 120));
        renameField.resizeRelocate(x, 0, 110, getHeight() - 1);
        double errorWidth = renameError.prefWidth(-1);
        renameError.resizeRelocate(x + 114, 0, errorWidth, getHeight() - 1);
    }

    // ------------------------------------------------------------------ drawing

    void draw() {
        double width = getWidth();
        double height = getHeight();
        canvas.draw(width, height, g -> draw(g, width, height));
        placeRenameField();
    }

    private void draw(GraphicsContext g, double width, double height) {
        Palette palette = timeline.getPalette();
        g.setFill(palette.get(Tone.PANEL_ALT));
        g.fillRect(0, 0, width, height);
        EditorDocument doc = doc();
        if (doc != null && width > 0) {
            Animation animation = doc.getAnimation();
            TimeScale scale = timeline.getScale();
            double end = scale.toX(animation.duration());
            if (end < width) {
                g.setFill(palette.get(Tone.BG, 0.55));
                g.fillRect(Math.max(0, end), 0, width - Math.max(0, end), height);
            }
            g.setFont(fontProbe.getFont());
            g.setTextBaseline(VPos.CENTER);
            g.setLineWidth(1);
            for (Marker marker : animation.markers()) {
                double x1 = Math.round(scale.toX(marker.start()));
                double x2 = Math.max(x1 + 2, Math.round(scale.toX(marker.end())));
                if (x2 < 0 || x1 > width) {
                    continue;
                }
                boolean selected = marker.name().equals(timeline.getSelectedMarker());
                g.setFill(palette.get(selected ? Tone.KEY_BAR : Tone.KEY_SOFT));
                g.fillRoundRect(x1, 2, x2 - x1, height - 5, 3, 3);
                g.setStroke(palette.get(Tone.KEY, selected ? 1 : 0.55));
                g.strokeRoundRect(x1 + 0.5, 2.5, x2 - x1 - 1, height - 6, 3, 3);
                g.save();
                g.beginPath();
                g.rect(x1, 0, x2 - x1 - 3, height);
                g.clip();
                g.setFill(palette.get(Tone.KEY));
                g.fillText(marker.name(), x1 + 5, (height - 1) / 2);
                g.restore();
            }
        }
        g.setStroke(palette.get(Tone.LINE));
        g.strokeLine(0, height - 0.5, width, height - 0.5);
    }
}
