package com.dlsc.animatorfx.editor.timeline;

import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.doc.Selection.KeyframeRef;
import com.dlsc.animatorfx.editor.timeline.TimelineRow.PropertyRow;
import com.dlsc.animatorfx.editor.timeline.TrackPane.BarPart;
import com.dlsc.animatorfx.editor.timeline.TrackPane.Hit;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.VideoLayer;
import com.dlsc.animatorfx.model.Easing;
import com.dlsc.animatorfx.model.Keyframe;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.LayerProperties;
import com.dlsc.animatorfx.model.Layers;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.collections.ObservableList;
import javafx.scene.Cursor;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/**
 * The pointer gestures on the tracks.
 * <ul>
 * <li>Keyframes: a click selects (Shift or Shortcut adds, Shortcut on a selected one removes),
 * a drag moves all selected keyframes together as one undo step, a double-click on free space of
 * a property row adds a keyframe, the secondary button opens the keyframe menu.</li>
 * <li>Free space: a drag selects the keyframes inside a marquee (Shift or Shortcut adds).</li>
 * <li>In/out bars: dragging the body moves the range, dragging an end trims it.</li>
 * <li>Bars of audio layers: the same, but trimming the start keeps the sound in place, and
 * Alt-dragging the body slides the source under the bar; see {@link #dragMediaBar}.</li>
 * </ul>
 * Times snap to frames unless Alt is held (always on audio bars). Escape cancels a drag.
 */
final class TrackInteraction {

    private enum Mode {NONE, KEYS, BAR, MARQUEE}

    private static final double DRAG_THRESHOLD = 3;

    private final TimelineView timeline;
    private final TrackPane tracks;
    private ContextMenu keyframeMenu;

    private Mode mode = Mode.NONE;
    private boolean moved;
    private double pressX;
    private double pressY;
    private final Object gestureKey = new Object();
    private Animation original;
    // keyframes
    private List<KeyframeRef> movingKeys = List.of();
    private KeyframeRef pressedKey;
    private boolean collapseOnRelease;
    private double grabTime;
    private double earliest;
    private double latest;
    // bar
    private Hit.Bar bar;
    /** Alt was held when the body of an audio bar was pressed: the drag slides the sound under the bar. */
    private boolean slip;
    // marquee, in content coordinates: x, y (scrolled), width, height
    private double[] marquee;
    private boolean additive;
    private Set<KeyframeRef> marqueeBase = Set.of();
    private List<String> marqueeBaseLayers = List.of();

    TrackInteraction(TimelineView timeline, TrackPane tracks) {
        this.timeline = timeline;
        this.tracks = tracks;
        tracks.setOnMousePressed(this::pressed);
        tracks.setOnMouseDragged(this::dragged);
        tracks.setOnMouseReleased(this::released);
        tracks.setOnMouseMoved(e -> tracks.setCursor(tracks.hitAt(e.getX(), e.getY()) instanceof Hit.Bar b
                && b.part() != BarPart.BODY && !timeline.getKeyframeActions().isLocked(b.layerId())
                ? Cursor.H_RESIZE : Cursor.DEFAULT));
        tracks.setOnContextMenuRequested(e -> {
            // on a keyframe: the menu is about the selected keyframes, this one included
            if (doc() != null && tracks.hitAt(e.getX(), e.getY()) instanceof Hit.Key key) {
                tracks.requestFocus();
                if (!doc().getSelection().keyframes().contains(key.ref())) {
                    selectKeys(List.of(key.ref()), false);
                }
                getKeyframeMenu().show(tracks, e.getScreenX(), e.getScreenY());
            }
            e.consume();
        });
        tracks.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && mode != Mode.NONE && moved) {
                cancel();
                e.consume();
            }
        });
    }

    private EditorDocument doc() {
        return timeline.getDocument();
    }

    double[] marquee() {
        return mode == Mode.MARQUEE && moved ? marquee : null;
    }

    // ------------------------------------------------------------------ press

    private void pressed(MouseEvent event) {
        EditorDocument doc = doc();
        tracks.requestFocus();
        if (doc == null) {
            return;
        }
        Hit hit = tracks.hitAt(event.getX(), event.getY());
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        if (keyframeMenu != null) {
            keyframeMenu.hide();
        }
        doc.endGesture();
        mode = Mode.NONE;
        moved = false;
        pressX = event.getX();
        pressY = event.getY();
        original = doc.getAnimation();
        boolean add = event.isShiftDown() || event.isShortcutDown();
        switch (hit) {
            case Hit.Key key -> pressedKey(key, event, add);
            case Hit.Bar barHit -> {
                ObservableList<String> layers = doc.getSelection().layerIds();
                if (add) {
                    if (!layers.contains(barHit.layerId())) {
                        layers.add(barHit.layerId());
                    }
                } else if (!layers.contains(barHit.layerId())) {
                    layers.setAll(barHit.layerId());
                }
                doc.getSelection().keyframes().clear();
                // a locked layer can be selected here, but its range stays as it is
                if (!timeline.getKeyframeActions().isLocked(barHit.layerId())) {
                    bar = barHit;
                    slip = event.isAltDown() && barHit.part() == BarPart.BODY;
                    mode = Mode.BAR;
                }
            }
            case Hit.Empty empty -> pressedEmpty(empty, event, add);
        }
        event.consume();
    }

    private void pressedKey(Hit.Key key, MouseEvent event, boolean add) {
        Set<KeyframeRef> selection = doc().getSelection().keyframes();
        boolean selected = selection.contains(key.ref());
        collapseOnRelease = false;
        if (add) {
            if (selected && event.isShortcutDown()) {
                selection.remove(key.ref());
                return;
            }
            selectKeys(List.of(key.ref()), true);
        } else if (selected) {
            collapseOnRelease = true;
        } else {
            selectKeys(List.of(key.ref()), false);
        }
        // the keyframes of locked layers can be selected, but they stay where they are
        if (timeline.getKeyframeActions().isLocked(key.ref().layerId())) {
            return;
        }
        pressedKey = key.ref();
        grabTime = key.time();
        movingKeys = timeline.getKeyframeActions().editable(selection);
        earliest = Double.MAX_VALUE;
        latest = 0;
        for (KeyframeRef ref : movingKeys) {
            double time = timeOf(ref);
            earliest = Math.min(earliest, time);
            latest = Math.max(latest, time);
        }
        mode = Mode.KEYS;
    }

    private void pressedEmpty(Hit.Empty empty, MouseEvent event, boolean add) {
        EditorDocument doc = doc();
        TimelineRow row = empty.row() < 0 ? null : timeline.getRows().get(empty.row());
        if (event.getClickCount() == 2 && row instanceof PropertyRow property) {
            if (timeline.getKeyframeActions().isLocked(property.layerId())) {
                return;
            }
            double time = timeline.snapTime(timeline.getScale().toTime(event.getX()), event.isAltDown());
            doc.execute(Commands.addKeyframe(property.layerId(), property.key(), time));
            Layers.find(doc.getAnimation(), property.layerId())
                    .map(layer -> LayerProperties.get(layer, property.key()))
                    .flatMap(value -> TimelineRows.keyframeAt(value, time))
                    .ifPresent(k -> selectKeys(List.of(new KeyframeRef(property.layerId(), property.key(), k.id())), false));
            return;
        }
        additive = add;
        marqueeBase = add ? new LinkedHashSet<>(doc.getSelection().keyframes()) : Set.of();
        marqueeBaseLayers = List.copyOf(doc.getSelection().layerIds());
        marquee = new double[]{event.getX(), event.getY() + timeline.getScrollY(), 0, 0};
        mode = Mode.MARQUEE;
    }

    private double timeOf(KeyframeRef ref) {
        Layer layer = Layers.find(original, ref.layerId()).orElse(null);
        if (layer != null) {
            for (Keyframe<?> keyframe : LayerProperties.get(layer, ref.propertyKey()).keyframes()) {
                if (keyframe.id().equals(ref.keyframeId())) {
                    return keyframe.time();
                }
            }
        }
        return 0;
    }

    /** Selects keyframes together with their layers; {@code add} keeps what is selected. */
    private void selectKeys(List<KeyframeRef> refs, boolean add) {
        EditorDocument doc = doc();
        Set<String> layers = new LinkedHashSet<>();
        List<KeyframeRef> keys = new ArrayList<>();
        if (add) {
            layers.addAll(doc.getSelection().layerIds());
            keys.addAll(doc.getSelection().keyframes());
        }
        for (KeyframeRef ref : refs) {
            layers.add(ref.layerId());
            keys.add(ref);
        }
        timeline.getKeyframeActions().select(layers, keys);
    }

    // ------------------------------------------------------------------ drag

    private void dragged(MouseEvent event) {
        EditorDocument doc = doc();
        if (doc == null || mode == Mode.NONE || !event.isPrimaryButtonDown()) {
            return;
        }
        if (!moved && Math.hypot(event.getX() - pressX, event.getY() - pressY) < DRAG_THRESHOLD) {
            return;
        }
        moved = true;
        double delta = timeline.getScale().toDuration(event.getX() - pressX);
        boolean free = event.isAltDown();
        Animation start = original;
        double frameRate = start.frameRate();
        switch (mode) {
            case KEYS -> {
                double target = grabTime + delta;
                double snapped = free ? target : TimeScale.snap(target, frameRate);
                // the selection moves as a block: it stops when its first keyframe reaches zero
                double shift = Math.max(snapped - grabTime, -earliest);
                if (latest <= start.duration()) {
                    shift = Math.min(shift, start.duration() - latest);
                }
                double total = shift;
                List<KeyframeRef> refs = movingKeys;
                doc.executeCoalesced(gestureKey, Commands.setDocument(refs.size() == 1 ? "Move Keyframe" : "Move Keyframes",
                        current -> Commands.moveKeyframes(refs, total).apply(start)));
            }
            case BAR -> dragBar(doc, start, delta, free);
            case MARQUEE -> {
                double x = Math.min(pressX, event.getX());
                double y = Math.min(pressY, event.getY()) + timeline.getScrollY();
                marquee = new double[]{x, y, Math.abs(event.getX() - pressX), Math.abs(event.getY() - pressY)};
                selectInMarquee();
                timeline.requestLayout();
            }
            default -> {
            }
        }
        event.consume();
    }

    private void dragBar(EditorDocument doc, Animation start, double delta, boolean free) {
        Layer layer = Layers.find(start, bar.layerId()).orElse(null);
        if (layer == null) {
            return;
        }
        if (layer instanceof AudioLayer audio) {
            dragMediaBar(doc, start, audio, audio.clipOffset(), audio.assetId(), delta, free);
            return;
        }
        if (layer instanceof VideoLayer video) {
            dragMediaBar(doc, start, video, video.clipOffset(), video.assetId(), delta, free);
            return;
        }
        double duration = start.duration();
        double frame = Math.min(TimeScale.frame(start.frameRate()), duration);
        double in = layer.base().inPoint();
        double out = layer.base().outPoint();
        String name;
        switch (bar.part()) {
            case START -> {
                in = Math.clamp(snap(in + delta, free, start), 0, Math.max(0, Math.min(out, duration) - frame));
                name = "Trim Layer";
            }
            case END -> {
                out = Math.clamp(snap(out + delta, free, start), Math.min(duration, Math.max(in, 0) + frame), duration);
                name = "Trim Layer";
            }
            default -> {
                double length = out - in;
                in = Math.clamp(snap(in + delta, free, start), 0, Math.max(0, duration - length));
                out = in + length;
                name = "Move Layer Range";
            }
        }
        double newIn = in;
        double newOut = out;
        doc.executeCoalesced(gestureKey, Commands.setDocument(name, current -> Layers.replace(start, bar.layerId(), l -> {
            LayerBase base = l.base().withInPoint(newIn).withOutPoint(newOut);
            return base.equals(l.base()) ? l : l.withBase(base);
        })));
    }

    /**
     * The bar of an audio or video layer. Times always snap to frames here, because Alt has another
     * meaning: Alt-dragging the body slides the sound under the bar (the clip offset changes,
     * the bar stays). Dragging the start trims it and moves the clip offset along, so the sound
     * stays where it is in time; with Alt only the in-point moves and the sound moves with it.
     * Dragging the body moves the bar with its sound, dragging the end trims the end.
     */
    private void dragMediaBar(EditorDocument doc, Animation start, Layer layer, double offset, String assetId,
                              double delta, boolean alt) {
        double duration = start.duration();
        double frame = Math.min(TimeScale.frame(start.frameRate()), duration);
        double in = layer.base().inPoint();
        double out = layer.base().outPoint();
        String name;
        if (bar.part() == BarPart.BODY && slip) {
            double clip = timeline.clipDurationOf(assetId);
            double rate = start.frameRate();
            offset = AudioClips.slide(offset, rate > 0 ? Math.round(delta * rate) / rate : delta, clip);
            name = "Slide Clip";
        } else if (bar.part() == BarPart.START) {
            double wanted = Math.clamp(snap(in + delta, false, start), 0, Math.max(0, Math.min(out, duration) - frame));
            if (alt) {
                in = wanted;
            } else {
                AudioClips.Trim trim = AudioClips.trimStart(in, Math.min(out, duration), offset, wanted, frame);
                in = trim.inPoint();
                offset = trim.clipOffset();
            }
            name = "Trim Layer";
        } else if (bar.part() == BarPart.END) {
            out = Math.clamp(snap(out + delta, false, start), Math.min(duration, Math.max(in, 0) + frame), duration);
            name = "Trim Layer";
        } else {
            double length = out - in;
            in = Math.clamp(snap(in + delta, false, start), 0, Math.max(0, duration - length));
            out = in + length;
            name = "Move Layer Range";
        }
        double newIn = in;
        double newOut = out;
        double newOffset = offset;
        doc.executeCoalesced(gestureKey, Commands.setDocument(name, current -> Layers.replace(start, bar.layerId(), l -> {
            LayerBase base = l.base().withInPoint(newIn).withOutPoint(newOut);
            if (l instanceof AudioLayer audio) {
                return base.equals(audio.base()) && newOffset == audio.clipOffset() ? l
                        : audio.withBase(base).withClipOffset(newOffset);
            }
            VideoLayer video = (VideoLayer) l;
            return base.equals(video.base()) && newOffset == video.clipOffset() ? l
                    : video.withBase(base).withClipOffset(newOffset);
        })));
    }

    private static double snap(double time, boolean free, Animation animation) {
        return free ? time : TimeScale.snap(time, animation.frameRate());
    }

    private void selectInMarquee() {
        EditorDocument doc = doc();
        TimeScale scale = timeline.getScale();
        List<TimelineRow> rows = timeline.getRows();
        Set<String> layers = new LinkedHashSet<>(additive ? marqueeBaseLayers : List.of());
        List<KeyframeRef> keys = new ArrayList<>(marqueeBase);
        int first = Math.max(0, (int) Math.floor(marquee[1] / TimelineView.ROW_HEIGHT));
        int last = Math.min(rows.size() - 1, (int) Math.floor((marquee[1] + marquee[3]) / TimelineView.ROW_HEIGHT));
        for (int i = first; i <= last; i++) {
            if (rows.get(i) instanceof PropertyRow property) {
                for (Keyframe<?> keyframe : property.property().value().keyframes()) {
                    double x = scale.toX(keyframe.time());
                    if (x >= marquee[0] - TrackPane.KEY_SIZE && x <= marquee[0] + marquee[2] + TrackPane.KEY_SIZE) {
                        keys.add(new KeyframeRef(property.layerId(), property.key(), keyframe.id()));
                        layers.add(property.layerId());
                    }
                }
            }
        }
        if (layers.isEmpty()) {
            layers.addAll(marqueeBaseLayers);
        }
        timeline.getKeyframeActions().select(layers, keys);
    }

    // ------------------------------------------------------------------ release and cancel

    private void released(MouseEvent event) {
        EditorDocument doc = doc();
        if (doc == null || event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        if (moved && (mode == Mode.KEYS || mode == Mode.BAR)) {
            doc.endGesture();
        } else if (!moved && mode == Mode.KEYS && collapseOnRelease && pressedKey != null) {
            selectKeys(List.of(pressedKey), false);
        } else if (!moved && mode == Mode.MARQUEE && !additive) {
            // a plain click on free space: no keyframes, and the layer of the row
            doc.getSelection().keyframes().clear();
            int row = timeline.rowAt(pressY);
            if (row >= 0) {
                String layerId = timeline.getRows().get(row).layerId();
                if (!doc.getSelection().layerIds().contains(layerId) || doc.getSelection().layerIds().size() > 1) {
                    doc.getSelection().layerIds().setAll(layerId);
                }
            }
        }
        reset();
    }

    /** Abandons the gesture in progress: moved keyframes and bars return, the marquee selection is undone. */
    void cancel() {
        EditorDocument doc = doc();
        if (doc != null && moved) {
            if (mode == Mode.KEYS || mode == Mode.BAR) {
                doc.cancelGesture();
            } else if (mode == Mode.MARQUEE) {
                timeline.getKeyframeActions().select(new LinkedHashSet<>(marqueeBaseLayers), List.copyOf(marqueeBase));
            }
        }
        reset();
    }

    private void reset() {
        mode = Mode.NONE;
        moved = false;
        marquee = null;
        pressedKey = null;
        bar = null;
        slip = false;
        original = null;
        movingKeys = List.of();
        timeline.requestLayout();
    }

    // ------------------------------------------------------------------ menu

    ContextMenu getKeyframeMenu() {
        if (keyframeMenu == null) {
            Menu easing = new Menu("Easing");
            easing.setId("keyframe-menu-easing");
            easing.getItems().addAll(easingItem("Linear", Easing.LINEAR), easingItem("Hold", Easing.HOLD),
                    new SeparatorMenuItem());
            for (Map.Entry<String, Easing> preset : Easing.presets().entrySet()) {
                easing.getItems().add(easingItem(preset.getKey(), preset.getValue()));
            }
            MenuItem delete = new MenuItem("Delete");
            delete.setId("keyframe-menu-delete");
            delete.setOnAction(e -> timeline.getKeyframeActions().delete());
            keyframeMenu = new ContextMenu(easing, new SeparatorMenuItem(), delete);
        }
        return keyframeMenu;
    }

    private MenuItem easingItem(String name, Easing easing) {
        MenuItem item = new MenuItem(name);
        item.setOnAction(e -> timeline.getKeyframeActions().setEasing(easing));
        return item;
    }
}
