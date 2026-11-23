package com.dlsc.animatorfx.editor.timeline;

import com.dlsc.animatorfx.editor.audio.AudioImport;
import com.dlsc.animatorfx.editor.video.VideoImport;
import com.dlsc.animatorfx.editor.video.VideoInfo;
import com.dlsc.animatorfx.editor.audio.AudioInfo;
import com.dlsc.animatorfx.editor.audio.Waveform;
import com.dlsc.animatorfx.editor.audio.WaveformCache;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.doc.Selection.KeyframeRef;
import com.dlsc.animatorfx.editor.doc.ViewState;
import com.dlsc.animatorfx.editor.ui.Palette;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.InvalidationListener;
import javafx.beans.WeakInvalidationListener;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Orientation;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

/**
 * The timeline at the bottom of the window. The left column is the layer tree
 * ({@link LayerTreeColumn}); the right side shows the time ruler ({@link TimeRuler}), the marker
 * lane ({@link MarkerLane}), the cue lane ({@link CueLane}) and the tracks ({@link TrackPane})
 * with the playhead across them.
 * Both sides share the rows ({@link TimelineRow}) and one vertical scroll position; a divider
 * between them sets the width of the column.
 *
 * <p>The view keeps the rows and the scale; the parts draw themselves from them when the view
 * is laid out. A move of the playhead only moves the playhead and updates the values of the
 * visible property rows.
 *
 * <p>Keyboard focus: the layer column takes the focus when clicked, and so do the tracks, the
 * ruler, the marker lane and the cue lane. While one of the latter four has it, Delete removes
 * the selected cues, else the selected keyframes, else the selected marker; anywhere else Delete
 * removes the selected layers, or the selected cues when cues are selected.
 */
public final class TimelineView extends Region {

    public static final double ROW_HEIGHT = 22;
    public static final double RULER_HEIGHT = 22;
    public static final double MARKER_HEIGHT = 18;
    public static final double CUE_HEIGHT = 18;
    public static final double HEADER_HEIGHT = RULER_HEIGHT + MARKER_HEIGHT + CUE_HEIGHT;

    private static final double MIN_COLUMN_WIDTH = 190;
    private static final double MIN_TRACK_WIDTH = 160;
    private static final double DIVIDER_GRIP = 5;
    private static final double SCROLL_BAR_SIZE = 10;

    private final ObjectProperty<EditorDocument> document = new SimpleObjectProperty<>(this, "document");
    private final StringProperty selectedMarker = new SimpleStringProperty(this, "selectedMarker");
    private final Palette palette = new Palette();
    private final KeyframeActions keyframeActions = new KeyframeActions(this);
    private final LayerTreeColumn column = new LayerTreeColumn(this);
    private final TimeRuler ruler = new TimeRuler(this);
    private final MarkerLane markerLane = new MarkerLane(this);
    private final CueLane cueLane = new CueLane(this);
    private final TrackPane tracks = new TrackPane(this);
    private final Region divider = new Region();
    private final ScrollBar hScroll = new ScrollBar();
    private final ScrollBar vScroll = new ScrollBar();
    private final Region playheadLine = new Region();
    private final Region playheadHead = new Region();
    private final Pane playheadLayer = new Pane(playheadLine, playheadHead);

    private double columnWidth = 264;
    private double trackWidth;
    private double scrollY;
    private List<TimelineRow> rows = List.of();
    private boolean rowsDirty = true;
    private boolean syncingScroll;

    private final InvalidationListener structure = obs -> invalidateRows();
    private final InvalidationListener redraw = obs -> requestLayout();
    private final InvalidationListener playhead = obs -> playheadMoved();
    private final InvalidationListener layerSelection = obs -> {
        pruneKeyframeSelection();
        requestLayout();
    };

    private Consumer<String> notifier = message -> {
    };
    private Runnable soundImporter = () -> {
    };
    private Consumer<Double> scrubber = time -> {
    };
    private String lastCueAsset;

    public TimelineView() {
        getStyleClass().addAll("timeline-pane", "timeline-view");
        setMinSize(0, 0);
        divider.getStyleClass().add("timeline-divider");
        divider.setCursor(Cursor.H_RESIZE);
        divider.setOnMouseDragged(e -> {
            columnWidth = sceneToLocal(e.getSceneX(), e.getSceneY()).getX();
            requestLayout();
        });
        hScroll.getStyleClass().add("timeline-scroll-bar");
        vScroll.getStyleClass().add("timeline-scroll-bar");
        vScroll.setOrientation(Orientation.VERTICAL);
        playheadLine.getStyleClass().add("playhead");
        playheadHead.getStyleClass().add("playhead-head");
        playheadLine.setManaged(false);
        playheadHead.setManaged(false);
        playheadLayer.setMouseTransparent(true);
        playheadLayer.setClip(new Rectangle());
        getChildren().addAll(palette, tracks, cueLane, markerLane, ruler, playheadLayer, column, divider, hScroll, vScroll);
        // a waveform that arrives is drawn; the cache outlives the view
        WaveformCache.shared().revisionProperty().addListener(new WeakInvalidationListener(redraw));
        VideoImport.revisionProperty().addListener(new WeakInvalidationListener(redraw));

        palette.setOnChange(this::requestLayout);
        hScroll.valueProperty().addListener((obs, oldValue, value) -> {
            if (!syncingScroll && getDocument() != null) {
                getDocument().getViewState().setTimelineScroll(value.doubleValue());
            }
        });
        vScroll.valueProperty().addListener((obs, oldValue, value) -> {
            if (!syncingScroll) {
                scrollY = value.doubleValue();
                requestLayout();
            }
        });
        selectedMarker.addListener(redraw);
        document.addListener((obs, oldDoc, newDoc) -> documentChanged(oldDoc, newDoc));
        addEventHandler(ScrollEvent.SCROLL, this::scrolled);
        addEventHandler(ZoomEvent.ZOOM, e -> {
            zoomAt(e.getZoomFactor(), sceneToLocal(e.getSceneX(), e.getSceneY()).getX() - trackLeft());
            e.consume();
        });
        addEventHandler(KeyEvent.KEY_PRESSED, this::keyPressed);
    }

    // ------------------------------------------------------------------ properties

    /**
     * The document that the timeline shows and edits.
     *
     * @return the document property
     */
    public final ObjectProperty<EditorDocument> documentProperty() {
        return document;
    }

    public final EditorDocument getDocument() {
        return document.get();
    }

    public final void setDocument(EditorDocument value) {
        document.set(value);
    }

    /**
     * The name of the marker that is selected in the marker lane, or {@code null}. The selection
     * belongs to the timeline, not to the document.
     *
     * @return the selected-marker property
     */
    public final StringProperty selectedMarkerProperty() {
        return selectedMarker;
    }

    public final String getSelectedMarker() {
        return selectedMarker.get();
    }

    public final void setSelectedMarker(String value) {
        selectedMarker.set(value);
    }

    // ------------------------------------------------------------------ parts and state

    public LayerTreeColumn getLayerColumn() {
        return column;
    }

    public TimeRuler getRuler() {
        return ruler;
    }

    public MarkerLane getMarkerLane() {
        return markerLane;
    }

    public CueLane getCueLane() {
        return cueLane;
    }

    public TrackPane getTrackPane() {
        return tracks;
    }

    /** Copy, paste, delete and easing for the selected keyframes. */
    public KeyframeActions getKeyframeActions() {
        return keyframeActions;
    }

    public Palette getPalette() {
        return palette;
    }

    /** The rows that the timeline shows, top-most layer first. */
    public List<TimelineRow> getRows() {
        if (rowsDirty) {
            rowsDirty = false;
            EditorDocument doc = getDocument();
            rows = doc == null ? List.of() : TimelineRows.build(doc.getAnimation(),
                    doc.getViewState().getExpandedLayerIds(), doc.getViewState().isShowAllProperties());
        }
        return rows;
    }

    /** The mapping between time and x coordinates of the ruler, the marker lane and the tracks. */
    public TimeScale getScale() {
        EditorDocument doc = getDocument();
        if (doc == null) {
            return new TimeScale(100, 0);
        }
        Animation animation = doc.getAnimation();
        ViewState view = doc.getViewState();
        return TimeScale.of(view.getTimelineZoom(), view.getTimelineScroll(), trackWidth, animation.duration(),
                animation.frameRate());
    }

    /** How far the rows are scrolled up, in pixels. */
    public double getScrollY() {
        return scrollY;
    }

    /** The y coordinate of the top of a row, relative to the top of the tracks and of the layer rows. */
    public double rowTop(int index) {
        return index * ROW_HEIGHT - scrollY;
    }

    /** The index of the row at a y coordinate relative to the top of the tracks; -1 below the last row. */
    public int rowAt(double y) {
        int index = (int) Math.floor((y + scrollY) / ROW_HEIGHT);
        return index >= 0 && index < getRows().size() ? index : -1;
    }

    /** The playhead limited to the duration: the time of the frame on the canvas. */
    public double getTime() {
        EditorDocument doc = getDocument();
        return doc == null ? 0 : Math.clamp(doc.getPlayhead(), 0, Math.max(0, doc.getAnimation().duration()));
    }

    /** A time snapped to the frames of the document, unless {@code free}; limited to the duration. */
    public double snapTime(double time, boolean free) {
        Animation animation = getDocument().getAnimation();
        double snapped = free ? time : TimeScale.snap(time, animation.frameRate());
        return Math.clamp(snapped, 0, Math.max(0, animation.duration()));
    }

    /** The waveform of an audio asset of the document, once it has been worked out. */
    public Optional<Waveform> waveformOf(String assetId) {
        EditorDocument doc = getDocument();
        if (doc == null) {
            return Optional.empty();
        }
        for (Asset asset : doc.getAnimation().assets()) {
            if (asset.id().equals(assetId) && asset.kind() == Asset.Kind.AUDIO) {
                return AudioImport.waveform(doc, asset);
            }
        }
        return Optional.empty();
    }

    /**
     * The length of the sound of an audio asset in seconds, as soon as it is known, which is
     * before its waveform is; NaN until then.
     */
    public double clipDurationOf(String assetId) {
        EditorDocument doc = getDocument();
        if (doc != null) {
            for (Asset asset : doc.getAnimation().assets()) {
                if (asset.id().equals(assetId) && asset.kind() == Asset.Kind.VIDEO) {
                    return VideoImport.info(doc, asset).map(VideoInfo::duration).orElse(Double.NaN);
                }
                if (asset.id().equals(assetId) && asset.kind() == Asset.Kind.AUDIO) {
                    double known = AudioImport.info(doc, asset).map(AudioInfo::duration).orElse(Double.NaN);
                    return known > 0 ? known : AudioImport.waveform(doc, asset).map(Waveform::duration).orElse(Double.NaN);
                }
            }
        }
        return Double.NaN;
    }

    /** Receives messages for the user, such as why layers could not be dropped into a group. */
    public void setNotifier(Consumer<String> notifier) {
        this.notifier = Objects.requireNonNull(notifier, "notifier");
    }

    public void notice(String message) {
        notifier.accept(message);
    }

    /** Asks for a sound file and adds it with a cue at the playhead: "Import Sound…" of the cue menu. */
    public void setSoundImporter(Runnable importer) {
        this.soundImporter = Objects.requireNonNull(importer, "importer");
    }

    void importSound() {
        soundImporter.run();
    }

    /**
     * Receives the time whenever the user moves the playhead in the ruler, after the playhead
     * has been set: the moment to let the sound at that time be heard.
     */
    public void setScrubber(Consumer<Double> scrubber) {
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
    }

    void scrubbed(double time) {
        scrubber.accept(time);
    }

    /**
     * Adds a sound cue at the frame of a time and selects it. Without an asset the cue gets the
     * sound that was used last ({@link Cues#defaultAsset}); a document without sounds gets a
     * notice instead.
     *
     * @return the id of the new cue
     */
    public Optional<String> addCue(double time, String assetIdOrNull) {
        EditorDocument doc = getDocument();
        if (doc == null) {
            return Optional.empty();
        }
        String assetId = assetIdOrNull != null ? assetIdOrNull
                : Cues.defaultAsset(doc.getAnimation(), lastCueAsset).orElse(null);
        if (assetId == null || !Commands.isAudioAsset(doc.getAnimation(), assetId)) {
            notice("Add a sound first: File ▸ Import ▸ Audio…, or drop a sound file on the window.");
            return Optional.empty();
        }
        String id = UUID.randomUUID().toString();
        doc.execute(Cues.add(id, assetId, time));
        if (doc.getAnimation().cues().stream().noneMatch(cue -> cue.id().equals(id))) {
            return Optional.empty();
        }
        lastCueAsset = assetId;
        selectCue(id);
        return Optional.of(id);
    }

    /** Selects one cue and nothing else. */
    public void selectCue(String cueId) {
        EditorDocument doc = getDocument();
        if (doc != null) {
            setSelectedMarker(null);
            doc.getSelection().cueIds().retainAll(Set.of(cueId));
            doc.getSelection().cueIds().add(cueId);
        }
    }

    /**
     * Whether the tracks, the ruler, the marker lane or the cue lane have the keyboard focus, so
     * that Delete, Copy and Paste are about keyframes, markers and cues rather than layers.
     */
    public boolean hasTrackFocus() {
        Node owner = getScene() == null ? null : getScene().getFocusOwner();
        return owner == tracks || owner == ruler || owner == markerLane || owner == cueLane;
    }

    // ------------------------------------------------------------------ zoom and scroll

    /** Multiplies the zoom, keeping the time under the x coordinate (relative to the tracks) in place. */
    public void zoomAt(double factor, double x) {
        EditorDocument doc = getDocument();
        if (doc == null || !(factor > 0)) {
            return;
        }
        Animation animation = doc.getAnimation();
        ViewState view = doc.getViewState();
        double time = getScale().toTime(x);
        double zoom = TimeScale.clampZoom(view.getTimelineZoom() * factor, trackWidth, animation.duration(),
                animation.frameRate());
        double scroll = TimeScale.scrollToKeep(time, x, zoom, trackWidth, animation.duration(), animation.frameRate());
        view.setTimelineZoom(zoom);
        view.setTimelineScroll(TimeScale.of(zoom, scroll, trackWidth, animation.duration(), animation.frameRate()).scroll());
    }

    public void zoomIn() {
        zoomAt(1.5, playheadX());
    }

    public void zoomOut() {
        zoomAt(1 / 1.5, playheadX());
    }

    /** Shows the whole duration. */
    public void zoomToFit() {
        EditorDocument doc = getDocument();
        if (doc != null) {
            doc.getViewState().setTimelineZoom(1);
            doc.getViewState().setTimelineScroll(0);
        }
    }

    /** The x coordinate of the playhead when it is in view, else the middle of the tracks. */
    private double playheadX() {
        double x = getScale().toX(getTime());
        return x >= 0 && x <= trackWidth ? x : trackWidth / 2;
    }

    private void scrolled(ScrollEvent event) {
        EditorDocument doc = getDocument();
        if (doc == null) {
            return;
        }
        double dx = event.getDeltaX();
        double dy = event.getDeltaY();
        if (event.isShortcutDown()) {
            double delta = dy != 0 ? dy : dx;
            zoomAt(Math.exp(delta * 0.004), sceneToLocal(event.getSceneX(), event.getSceneY()).getX() - trackLeft());
        } else {
            if (event.isShiftDown() && dx == 0) {
                dx = dy;
                dy = 0;
            }
            if (dx != 0) {
                Animation animation = doc.getAnimation();
                TimeScale scale = getScale();
                doc.getViewState().setTimelineScroll(TimeScale.of(doc.getViewState().getTimelineZoom(),
                        scale.scroll() - scale.toDuration(dx), trackWidth, animation.duration(),
                        animation.frameRate()).scroll());
            }
            if (dy != 0) {
                scrollY -= dy;
                requestLayout();
            }
        }
        event.consume();
    }

    // ------------------------------------------------------------------ keys

    private void keyPressed(KeyEvent event) {
        EditorDocument doc = getDocument();
        if (doc == null || getScene().getFocusOwner() instanceof TextInputControl || !deletesHere()) {
            return;
        }
        if ((event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) && !event.isShortcutDown()) {
            deleteSelection();
            event.consume();
        }
    }

    /**
     * Whether Delete is about cues, keyframes and markers rather than layers: while the tracks,
     * the ruler, the marker lane or the cue lane have the focus; anywhere in the timeline, the
     * layer column included, while keyframes are selected; and anywhere at all while cues are
     * selected, because then no layer is.
     */
    public boolean deletesHere() {
        EditorDocument doc = getDocument();
        return hasTrackFocus() || (doc != null && !doc.getSelection().cueIds().isEmpty())
                || (isFocusWithin() && doc != null && !doc.getSelection().keyframes().isEmpty());
    }

    /** Removes the selected cues, or else the selected keyframes, or else the selected marker. */
    public void deleteSelection() {
        EditorDocument doc = getDocument();
        if (doc == null) {
            return;
        }
        if (!doc.getSelection().cueIds().isEmpty()) {
            doc.execute(Commands.removeCues(List.copyOf(doc.getSelection().cueIds())));
        } else if (!doc.getSelection().keyframes().isEmpty()) {
            keyframeActions.delete();
        } else if (getSelectedMarker() != null) {
            doc.execute(Markers.remove(getSelectedMarker()));
            setSelectedMarker(null);
        }
    }

    // ------------------------------------------------------------------ document

    private void documentChanged(EditorDocument oldDoc, EditorDocument newDoc) {
        tracks.cancelGesture();
        markerLane.cancelGesture();
        cueLane.cancelGesture();
        column.cancelGesture();
        if (oldDoc != null) {
            oldDoc.animationProperty().removeListener(structure);
            oldDoc.getSelection().layerIds().removeListener(layerSelection);
            oldDoc.getSelection().keyframes().removeListener(redraw);
            oldDoc.getSelection().cueIds().removeListener(redraw);
            oldDoc.playheadProperty().removeListener(playhead);
            ViewState view = oldDoc.getViewState();
            view.timelineZoomProperty().removeListener(redraw);
            view.timelineScrollProperty().removeListener(redraw);
            view.showAllPropertiesProperty().removeListener(structure);
            view.getExpandedLayerIds().removeListener(structure);
        }
        if (newDoc != null) {
            newDoc.animationProperty().addListener(structure);
            newDoc.getSelection().layerIds().addListener(layerSelection);
            newDoc.getSelection().keyframes().addListener(redraw);
            newDoc.getSelection().cueIds().addListener(redraw);
            newDoc.playheadProperty().addListener(playhead);
            ViewState view = newDoc.getViewState();
            view.timelineZoomProperty().addListener(redraw);
            view.timelineScrollProperty().addListener(redraw);
            view.showAllPropertiesProperty().addListener(structure);
            view.getExpandedLayerIds().addListener(structure);
        }
        setSelectedMarker(null);
        lastCueAsset = null;
        scrollY = 0;
        column.documentChanged(oldDoc, newDoc);
        invalidateRows();
    }

    private void invalidateRows() {
        rowsDirty = true;
        EditorDocument doc = getDocument();
        if (doc != null && getSelectedMarker() != null && doc.getAnimation().marker(getSelectedMarker()).isEmpty()) {
            setSelectedMarker(null);
        }
        requestLayout();
    }

    /** Selected keyframes always belong to selected layers. */
    private void pruneKeyframeSelection() {
        EditorDocument doc = getDocument();
        if (doc.getSelection().keyframes().isEmpty()) {
            return;
        }
        List<KeyframeRef> stale = new ArrayList<>();
        for (KeyframeRef ref : doc.getSelection().keyframes()) {
            if (!doc.getSelection().layerIds().contains(ref.layerId())) {
                stale.add(ref);
            }
        }
        if (!stale.isEmpty()) {
            doc.getSelection().keyframes().removeAll(stale);
        }
    }

    private void playheadMoved() {
        followPlayhead();
        positionPlayhead();
        column.updateValues();
    }

    /** When the playhead leaves the visible part of a zoomed-in timeline, the view pages after it. */
    private void followPlayhead() {
        EditorDocument doc = getDocument();
        if (doc == null || trackWidth <= 0) {
            return;
        }
        TimeScale scale = getScale();
        double x = scale.toX(getTime());
        if (x < 0 || x > trackWidth) {
            Animation animation = doc.getAnimation();
            doc.getViewState().setTimelineScroll(TimeScale.of(doc.getViewState().getTimelineZoom(),
                    getTime() - scale.toDuration(x < 0 ? trackWidth * 0.25 : 0), trackWidth, animation.duration(),
                    animation.frameRate()).scroll());
        }
    }

    // ------------------------------------------------------------------ layout

    private double trackLeft() {
        return snapPositionX(Math.clamp(columnWidth, MIN_COLUMN_WIDTH,
                Math.max(MIN_COLUMN_WIDTH, getWidth() - MIN_TRACK_WIDTH))) + 1;
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        double left = trackLeft();
        EditorDocument doc = getDocument();
        List<TimelineRow> current = getRows();

        double barSize = SCROLL_BAR_SIZE;
        double contentHeight = current.size() * ROW_HEIGHT;
        boolean vertical = contentHeight > height - HEADER_HEIGHT + 0.5;
        trackWidth = Math.max(0, width - left - (vertical ? barSize : 0));
        Animation animation = doc == null ? null : doc.getAnimation();
        double zoom = doc == null ? 1 : doc.getViewState().getTimelineZoom();
        double maxScroll = animation == null ? 0
                : TimeScale.maxScroll(zoom, trackWidth, animation.duration(), animation.frameRate());
        boolean horizontal = maxScroll > 1e-9;
        double trackHeight = Math.max(0, height - HEADER_HEIGHT - (horizontal ? barSize : 0));
        scrollY = Math.clamp(scrollY, 0, Math.max(0, contentHeight - trackHeight));
        TimeScale scale = getScale();

        syncingScroll = true;
        vScroll.setVisible(vertical);
        if (vertical) {
            vScroll.setMax(contentHeight - trackHeight);
            vScroll.setVisibleAmount(vScroll.getMax() * trackHeight / contentHeight);
            vScroll.setValue(scrollY);
            vScroll.resizeRelocate(left + trackWidth, HEADER_HEIGHT, barSize, trackHeight);
        }
        hScroll.setVisible(horizontal);
        if (horizontal) {
            double visible = scale.toDuration(Math.max(1, trackWidth - TimeScale.INSET - TimeScale.END_SLACK));
            hScroll.setMax(maxScroll);
            hScroll.setVisibleAmount(maxScroll * visible / (maxScroll + visible));
            hScroll.setValue(scale.scroll());
            hScroll.resizeRelocate(left, height - barSize, trackWidth, barSize);
        }
        syncingScroll = false;

        column.resizeRelocate(0, 0, left - 1, height);
        divider.resizeRelocate(left - 1 - (DIVIDER_GRIP - 1) / 2, 0, DIVIDER_GRIP, height);
        ruler.resizeRelocate(left, 0, trackWidth, RULER_HEIGHT);
        markerLane.resizeRelocate(left, RULER_HEIGHT, trackWidth, MARKER_HEIGHT);
        cueLane.resizeRelocate(left, RULER_HEIGHT + MARKER_HEIGHT, trackWidth, CUE_HEIGHT);
        tracks.resizeRelocate(left, HEADER_HEIGHT, trackWidth, trackHeight);
        playheadLayer.resizeRelocate(left, 0, trackWidth, HEADER_HEIGHT + trackHeight);
        Rectangle clip = (Rectangle) playheadLayer.getClip();
        clip.setWidth(trackWidth);
        clip.setHeight(HEADER_HEIGHT + trackHeight);

        column.refresh();
        ruler.draw();
        markerLane.draw();
        cueLane.draw();
        tracks.draw();
        positionPlayhead();
    }

    private void positionPlayhead() {
        boolean shown = getDocument() != null;
        playheadLine.setVisible(shown);
        playheadHead.setVisible(shown);
        if (shown) {
            double x = Math.round(getScale().toX(getTime()));
            playheadLine.resizeRelocate(x, 0, 1, playheadLayer.getHeight());
            playheadHead.resizeRelocate(x - 5, 0, 11, 9);
        }
    }
}
