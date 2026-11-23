package com.dlsc.animatorfx.editor.canvas;

import com.dlsc.animatorfx.editor.canvas.tools.ImageTool;
import com.dlsc.animatorfx.editor.canvas.tools.MotionPathTool;
import com.dlsc.animatorfx.editor.canvas.tools.PathEditTool;
import com.dlsc.animatorfx.editor.canvas.tools.PathTarget;
import com.dlsc.animatorfx.editor.canvas.tools.PenTool;
import com.dlsc.animatorfx.editor.canvas.tools.Pointer;
import com.dlsc.animatorfx.editor.canvas.tools.SelectTool;
import com.dlsc.animatorfx.editor.canvas.tools.ShapeTool;
import com.dlsc.animatorfx.editor.canvas.tools.SlotTool;
import com.dlsc.animatorfx.editor.canvas.tools.TextTool;
import com.dlsc.animatorfx.editor.canvas.tools.Tool;
import com.dlsc.animatorfx.editor.doc.Command;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.doc.Matrices;
import com.dlsc.animatorfx.editor.doc.ViewState;
import com.dlsc.animatorfx.editor.ui.ToolKind;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.player.AnimationView;
import com.dlsc.animatorfx.player.Fit;
import javafx.beans.InvalidationListener;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Affine;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The interactive canvas: the artboard of the document on the grid, with zoom and pan, the
 * selection overlay above it, and the tools that turn pointer gestures into commands.
 *
 * <p>The artboard is an {@link AnimationView} at the size of the document, drawn 1:1 inside a
 * pane that the canvas scales and moves, so zooming stays crisp. It never plays by itself; it
 * shows the frame at the playhead, or the running frame during {@link #togglePlayback() playback}.
 * Zoom and pan are kept in the document's {@link ViewState}. The pan is the offset of the
 * artboard centre from the centre of the canvas in view pixels, so (0, 0) is centred at any size.
 *
 * <p>Three coordinate spaces matter: <em>view</em> pixels of this region (the overlay and every
 * {@link Pointer}), <em>document</em> pixels, and the <em>parent</em> space of a layer in which
 * its position lives. {@link #getGeometry()} converts between them.
 */
public final class CanvasView extends Region {

    public static final double MIN_ZOOM = 0.25;
    public static final double MAX_ZOOM = 32;

    private static final double FIT_MARGIN = 48;
    private static final double[] ZOOM_STEPS = {0.25, 0.33, 0.5, 0.67, 1, 1.5, 2, 3, 4, 6, 8, 12, 16, 24, 32};
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg");

    private final ObjectProperty<EditorDocument> document = new SimpleObjectProperty<>(this, "document");
    private final ObjectProperty<ToolKind> activeTool = new SimpleObjectProperty<>(this, "activeTool", ToolKind.SELECT);
    private final ReadOnlyStringWrapper enteredGroupId = new ReadOnlyStringWrapper(this, "enteredGroupId");

    private final AnimationView view = new AnimationView();
    private final CanvasGrid grid = new CanvasGrid();
    private final Region artboard = new Region();
    private final Pane world = new Pane(view);
    private final Affine worldTransform = new Affine();
    private final Overlay overlay;
    private final Pane editorLayer = new Pane();
    private final LayerGeometry geometry = new LayerGeometry(this);
    private final Playback playback = new Playback(view, this::notice);
    private final CanvasActions actions;
    private final Map<ToolKind, Tool> tools = new EnumMap<>(ToolKind.class);
    private final Tool idleTool = new Tool() {
    };
    /** The zoom that {@link #autoFit} last chose for a document; NaN once the user has taken over. */
    private final Map<EditorDocument, Double> autoZoom = new WeakHashMap<>();

    private final List<Predicate<Layer>> doubleClickHandlers = new ArrayList<>();

    private final ContextMenu contextMenu = new ContextMenu();
    private Tool modalTool;
    private Consumer<String> notifier = message -> {
    };
    private boolean toolHasPointer;
    private boolean spaceDown;
    private boolean spaceUsed;
    private boolean panning;
    private Vec2 panStartPointer;
    private Vec2 panStart;
    private Pointer lastPointer;

    private final InvalidationListener refresh = obs -> requestLayout();
    private final ChangeListener<Number> playheadListener = (obs, oldTime, newTime) -> {
        playback.playheadChanged();
        requestLayout();
    };
    private final ChangeListener<Animation> animationListener = (obs, oldAnimation, newAnimation) -> {
        if (!(geometry.layer(getEnteredGroupId()).orElse(null) instanceof GroupLayer)) {
            enteredGroupId.set(null);
        }
        requestLayout();
    };
    private final EventHandler<KeyEvent> spaceFilter = this::handleSpace;
    private final EventHandler<KeyEvent> keyHandler = this::handleKey;
    private BooleanSupplier keyboardTaken = () -> false;

    public CanvasView() {
        getStyleClass().addAll("canvas-pane", "canvas-view");
        setMinSize(0, 0);
        setFocusTraversable(true);

        artboard.getStyleClass().add("artboard");
        artboard.setManaged(false);
        artboard.setMouseTransparent(true);
        view.setFit(Fit.NONE);
        view.setAutoPlay(false);
        world.setManaged(false);
        world.setMouseTransparent(true);
        world.getTransforms().add(worldTransform);
        overlay = new Overlay(this);
        editorLayer.setPickOnBounds(false);
        editorLayer.setManaged(false);
        getChildren().addAll(grid, artboard, world, overlay, editorLayer);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        actions = new CanvasActions(this);
        tools.put(ToolKind.SELECT, new SelectTool(this));
        tools.put(ToolKind.RECTANGLE, new ShapeTool(this, ShapeTool.Kind.RECTANGLE));
        tools.put(ToolKind.ELLIPSE, new ShapeTool(this, ShapeTool.Kind.ELLIPSE));
        tools.put(ToolKind.LINE, new ShapeTool(this, ShapeTool.Kind.LINE));
        tools.put(ToolKind.POLYGON, new ShapeTool(this, ShapeTool.Kind.POLYGON));
        tools.put(ToolKind.STAR, new ShapeTool(this, ShapeTool.Kind.STAR));
        tools.put(ToolKind.PEN, new PenTool(this));
        tools.put(ToolKind.TEXT, new TextTool(this));
        tools.put(ToolKind.IMAGE, new ImageTool(this));
        tools.put(ToolKind.SLOT, new SlotTool(this));

        document.addListener((obs, oldDoc, newDoc) -> documentChanged(oldDoc, newDoc));
        activeTool.addListener((obs, oldTool, newTool) -> {
            tool(oldTool).finish();
            setModalTool(null);
            overlay.clearFeedback();
            tool(newTool).activate();
            updateCursor();
        });
        view.getPlayer().frameProperty().addListener(refresh);
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.removeEventFilter(KeyEvent.ANY, spaceFilter);
                oldScene.removeEventHandler(KeyEvent.KEY_PRESSED, keyHandler);
            }
            if (newScene != null) {
                newScene.addEventFilter(KeyEvent.ANY, spaceFilter);
                newScene.addEventHandler(KeyEvent.KEY_PRESSED, keyHandler);
            }
        });
        doubleClickHandlers.add(layer -> editPath(layer.id()));
        installPointer();
        installDrop();
    }

    // ------------------------------------------------------------------ properties

    /**
     * The document that the canvas shows and edits.
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
     * The tool that pointer gestures go to.
     *
     * @return the active tool property
     */
    public final ObjectProperty<ToolKind> activeToolProperty() {
        return activeTool;
    }

    public final ToolKind getActiveTool() {
        return activeTool.get();
    }

    public final void setActiveTool(ToolKind value) {
        activeTool.set(Objects.requireNonNull(value, "tool"));
    }

    /**
     * The group whose children the pointer selects directly, after a double-click on the group;
     * {@code null} at the top level.
     *
     * @return the entered-group-id property
     */
    public final ReadOnlyStringProperty enteredGroupIdProperty() {
        return enteredGroupId.getReadOnlyProperty();
    }

    public final String getEnteredGroupId() {
        return enteredGroupId.get();
    }

    /**
     * Whether the document is playing.
     *
     * @return the playing property
     */
    public final ReadOnlyBooleanProperty playingProperty() {
        return playback.playingProperty();
    }

    public final boolean isPlaying() {
        return playback.isPlaying();
    }

    /**
     * Whether playback starts over at the end instead of stopping there.
     *
     * @return the loop property
     */
    public final BooleanProperty loopProperty() {
        return playback.loopProperty();
    }

    public final boolean isLoop() {
        return playback.isLoop();
    }

    public final void setLoop(boolean value) {
        playback.setLoop(value);
    }

    // ------------------------------------------------------------------ parts

    /** The view that renders the document; its player holds the frame on screen. */
    public AnimationView getAnimationView() {
        return view;
    }

    /** Layer matrices, bounds and hit testing for the frame on screen. */
    public LayerGeometry getGeometry() {
        return geometry;
    }

    /** The layer above the artboard for selection, guides and tool feedback, in view pixels. */
    public Overlay getOverlay() {
        return overlay;
    }

    /** Delete, duplicate, group, clipboard and the other commands on the selection. */
    public CanvasActions getActions() {
        return actions;
    }

    /**
     * Where tools put inline controls such as a text editor; above the overlay, in view pixels.
     * Children are not laid out: position them and follow {@link Tool#viewChanged()}.
     */
    public Pane getEditorLayer() {
        return editorLayer;
    }

    /**
     * Registers the tool for a kind, replacing the one that was there.
     */
    public void setTool(ToolKind kind, Tool tool) {
        Objects.requireNonNull(tool, "tool");
        boolean active = kind == getActiveTool() && modalTool == null;
        if (active) {
            tool(kind).finish();
        }
        tools.put(kind, tool);
        if (active) {
            tool.activate();
        }
    }

    public Tool getTool(ToolKind kind) {
        return tool(kind);
    }

    /**
     * Lets a tool take over all input until it is replaced by {@code null}, the active tool
     * changes or the document changes; for editing modes that are not in the tool rail.
     */
    public void setModalTool(Tool tool) {
        if (tool == modalTool) {
            return;
        }
        if (modalTool != null) {
            modalTool.finish();
        } else if (tool != null) {
            tool(getActiveTool()).finish();
        }
        modalTool = tool;
        if (tool != null) {
            tool.activate();
        }
        updateCursor();
        requestLayout();
    }

    public Tool getModalTool() {
        return modalTool;
    }

    /**
     * Whether the single-letter tool shortcuts are to be ignored for now: a path is being drawn
     * or edited, and Enter or Escape is the way out.
     */
    public boolean blocksToolShortcuts() {
        return getDocument() != null && currentTool().blocksToolShortcuts();
    }

    /** The menu of a secondary click; its items come from {@link Tool#menuItems} of the tool in charge. */
    public ContextMenu getContextMenu() {
        return contextMenu;
    }

    /**
     * Tells when the keyboard belongs to something else in the window, such as a dialog: the
     * canvas then leaves Space and its other keys alone, as it does while the user is typing.
     */
    public void setKeyboardTaken(BooleanSupplier taken) {
        keyboardTaken = Objects.requireNonNull(taken, "taken");
    }

    /** Receives messages for the user, such as why something could not be ungrouped. */
    public void setNotifier(Consumer<String> notifier) {
        this.notifier = Objects.requireNonNull(notifier, "notifier");
    }

    public void notice(String message) {
        notifier.accept(message);
    }

    private Tool tool(ToolKind kind) {
        return tools.getOrDefault(kind, idleTool);
    }

    private Tool currentTool() {
        return modalTool != null ? modalTool : tool(getActiveTool());
    }

    // ------------------------------------------------------------------ document

    private void documentChanged(EditorDocument oldDoc, EditorDocument newDoc) {
        tools.values().forEach(Tool::finish);
        setModalTool(null);
        overlay.clearFeedback();
        enteredGroupId.set(null);
        view.animationProperty().unbind();
        if (oldDoc != null) {
            oldDoc.endGesture();
            oldDoc.animationProperty().removeListener(animationListener);
            oldDoc.playheadProperty().removeListener(playheadListener);
            oldDoc.getSelection().layerIds().removeListener(refresh);
            oldDoc.getSlotPreviews().removeListener(refresh);
            ViewState state = oldDoc.getViewState();
            state.canvasZoomProperty().removeListener(refresh);
            state.canvasPanXProperty().removeListener(refresh);
            state.canvasPanYProperty().removeListener(refresh);
        }
        playback.setDocument(newDoc);
        if (newDoc != null) {
            view.animationProperty().bind(newDoc.fileContentProperty());
            view.seek(newDoc.getPlayhead());
            newDoc.animationProperty().addListener(animationListener);
            newDoc.playheadProperty().addListener(playheadListener);
            newDoc.getSelection().layerIds().addListener(refresh);
            newDoc.getSlotPreviews().addListener(refresh);
            ViewState state = newDoc.getViewState();
            state.canvasZoomProperty().addListener(refresh);
            state.canvasPanXProperty().addListener(refresh);
            state.canvasPanYProperty().addListener(refresh);
        } else {
            view.setAnimation(null);
        }
        artboard.setVisible(newDoc != null);
        world.setVisible(newDoc != null);
        requestLayout();
    }

    /** The time of the frame on screen, in seconds: the playhead, limited to the duration. */
    public double getTime() {
        return view.getCurrentTime();
    }

    /**
     * The command that sets an animatable property of a layer at the time on screen, following
     * the auto-key setting of the document. Every canvas edit of a value goes through this.
     */
    public Command valueCommand(String layerId, String propertyKey, Object value) {
        return Commands.setValueAt(layerId, propertyKey, getTime(), value, getDocument().isAutoKey());
    }

    /**
     * Asked in order when a layer is double-clicked with the select tool; the first one that
     * returns {@code true} has handled it. Without a taker, a double-click enters a group.
     */
    public List<Predicate<Layer>> getDoubleClickHandlers() {
        return doubleClickHandlers;
    }

    /**
     * Makes the children of a group directly selectable; {@code null} returns to the top level.
     */
    public void enterGroup(String groupIdOrNull) {
        boolean group = geometry.layer(groupIdOrNull).orElse(null) instanceof GroupLayer;
        enteredGroupId.set(group ? groupIdOrNull : null);
        requestLayout();
    }

    // ------------------------------------------------------------------ paths

    /** Whether the layer is a shape whose geometry is a Bezier path, which can be edited point by point. */
    public static boolean isPathLayer(Layer layer) {
        return layer instanceof ShapeLayer shape && shape.geometry() instanceof Geometry.PathGeometry;
    }

    /**
     * Enters path editing for a path layer, which becomes the selection.
     *
     * @return whether the layer is an unlocked path layer
     */
    public boolean editPath(String layerId) {
        Layer layer = geometry.layer(layerId).orElse(null);
        if (layer == null || !isPathLayer(layer) || layer.base().locked()) {
            return false;
        }
        return editPath(PathTarget.geometry(layerId));
    }

    /**
     * Enters path editing for the motion path of a layer.
     *
     * @return whether the layer has a motion path
     */
    public boolean editMotionPath(String layerId) {
        Layer layer = geometry.layer(layerId).orElse(null);
        return layer != null && layer.base().motionPath() != null && editPath(PathTarget.motionPath(layerId));
    }

    private boolean editPath(PathTarget target) {
        setActiveTool(ToolKind.SELECT);
        if (!getDocument().getSelection().layerIds().equals(List.of(target.layerId()))) {
            getDocument().getSelection().layerIds().setAll(target.layerId());
        }
        setModalTool(new PathEditTool(this, target));
        return true;
    }

    /** Starts drawing a motion path for a layer, which becomes the selection. */
    public void drawMotionPath(String layerId) {
        if (geometry.layer(layerId).isEmpty()) {
            return;
        }
        setActiveTool(ToolKind.SELECT);
        if (!getDocument().getSelection().layerIds().equals(List.of(layerId))) {
            getDocument().getSelection().layerIds().setAll(layerId);
        }
        setModalTool(new MotionPathTool(this, layerId));
    }

    // ------------------------------------------------------------------ playback

    /**
     * Starts playback from the playhead, or pauses it. Without looping, playback stops at the
     * end, and starts from the beginning when the playhead is there already.
     */
    public void togglePlayback() {
        if (!playback.isPlaying()) {
            currentTool().finish();
        }
        playback.toggle();
    }

    public void pausePlayback() {
        playback.pause();
    }

    /**
     * Whether the sound of the document is switched off, for playback and for scrubbing.
     *
     * @return the muted property
     */
    public final BooleanProperty mutedProperty() {
        return view.mutedProperty();
    }

    public final boolean isMuted() {
        return view.isMuted();
    }

    public final void setMuted(boolean value) {
        view.setMuted(value);
    }

    /**
     * Lets the sound at a time of the document be heard for a moment: for dragging the playhead.
     * Silent while the document plays or the sound is muted, and limited to one snippet every 50 ms.
     */
    public void scrub(double seconds) {
        playback.scrub(seconds);
    }

    /** Stops playback and releases what makes the sound; for when the window closes. */
    public void dispose() {
        playback.dispose();
    }

    // ------------------------------------------------------------------ zoom and pan

    public double getZoom() {
        EditorDocument doc = getDocument();
        return doc == null ? 1 : clampZoom(doc.getViewState().getCanvasZoom());
    }

    private static double clampZoom(double zoom) {
        return Double.isFinite(zoom) ? Math.clamp(zoom, MIN_ZOOM, MAX_ZOOM) : 1;
    }

    /** Where the top-left corner of the artboard is, in view pixels. */
    private Vec2 origin() {
        EditorDocument doc = getDocument();
        if (doc == null) {
            return Vec2.ZERO;
        }
        Animation animation = doc.getAnimation();
        ViewState state = doc.getViewState();
        double zoom = getZoom();
        return new Vec2(Math.rint((getWidth() - animation.width() * zoom) / 2 + state.getCanvasPanX()),
                Math.rint((getHeight() - animation.height() * zoom) / 2 + state.getCanvasPanY()));
    }

    /** The matrix from document pixels to view pixels. */
    public double[] documentToView() {
        Vec2 origin = origin();
        double zoom = getZoom();
        return new double[]{zoom, 0, origin.x(), 0, zoom, origin.y()};
    }

    public Vec2 documentToView(Vec2 documentPoint) {
        return Matrices.apply(documentToView(), documentPoint);
    }

    public Vec2 viewToDocument(Vec2 viewPoint) {
        Vec2 origin = origin();
        double zoom = getZoom();
        return new Vec2((viewPoint.x() - origin.x()) / zoom, (viewPoint.y() - origin.y()) / zoom);
    }

    /**
     * Sets the zoom (limited to 25 %–3200 %) so that the document point under the given view
     * point stays where it is.
     */
    public void zoomTo(double zoom, Vec2 anchor) {
        EditorDocument doc = getDocument();
        if (doc == null) {
            return;
        }
        double target = clampZoom(zoom);
        Vec2 fixed = viewToDocument(anchor);
        Animation animation = doc.getAnimation();
        ViewState state = doc.getViewState();
        state.setCanvasZoom(target);
        state.setCanvasPanX(anchor.x() - fixed.x() * target - (getWidth() - animation.width() * target) / 2);
        state.setCanvasPanY(anchor.y() - fixed.y() * target - (getHeight() - animation.height() * target) / 2);
    }

    private Vec2 viewCentre() {
        return new Vec2(getWidth() / 2, getHeight() / 2);
    }

    /** The next step up of 25, 33, 50, 67, 100, 150, 200, … 3200 %, about the centre of the canvas. */
    public void zoomIn() {
        double zoom = getZoom();
        for (double step : ZOOM_STEPS) {
            if (step > zoom * 1.001) {
                zoomTo(step, viewCentre());
                return;
            }
        }
    }

    public void zoomOut() {
        double zoom = getZoom();
        for (int i = ZOOM_STEPS.length - 1; i >= 0; i--) {
            if (ZOOM_STEPS[i] < zoom * 0.999) {
                zoomTo(ZOOM_STEPS[i], viewCentre());
                return;
            }
        }
    }

    /** 100 %, keeping the centre of the canvas where it is. */
    public void zoomToActualSize() {
        zoomTo(1, viewCentre());
    }

    /** Centres the artboard at the largest zoom that shows all of it with a margin. */
    public void zoomToFit() {
        EditorDocument doc = getDocument();
        if (doc == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        Animation animation = doc.getAnimation();
        double zoom = Math.min((getWidth() - 2 * FIT_MARGIN) / animation.width(),
                (getHeight() - 2 * FIT_MARGIN) / animation.height());
        ViewState state = doc.getViewState();
        state.setCanvasZoom(clampZoom(zoom));
        state.setCanvasPanX(0);
        state.setCanvasPanY(0);
    }

    public void panBy(double dx, double dy) {
        EditorDocument doc = getDocument();
        if (doc != null) {
            ViewState state = doc.getViewState();
            state.setCanvasPanX(state.getCanvasPanX() + dx);
            state.setCanvasPanY(state.getCanvasPanY() + dy);
        }
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void layoutChildren() {
        EditorDocument doc = getDocument();
        double width = getWidth();
        double height = getHeight();
        if (doc != null && width > 0 && height > 0) {
            autoFit(doc);
        }
        overlay.resizeRelocate(0, 0, width, height);
        editorLayer.resizeRelocate(0, 0, width, height);
        if (doc == null) {
            grid.update(width, height, Vec2.ZERO, 1);
            overlay.refresh();
            return;
        }
        Animation animation = doc.getAnimation();
        Vec2 origin = origin();
        double zoom = getZoom();
        grid.update(width, height, origin, zoom);
        artboard.resizeRelocate(origin.x(), origin.y(), Math.rint(animation.width() * zoom),
                Math.rint(animation.height() * zoom));
        worldTransform.setToTransform(zoom, 0, origin.x(), 0, zoom, origin.y());
        world.resizeRelocate(0, 0, animation.width(), animation.height());
        view.resizeRelocate(0, 0, animation.width(), animation.height());
        overlay.refresh();
        // every tool: an inline editor can be open while another tool is the active one
        tools.values().forEach(Tool::viewChanged);
        if (modalTool != null) {
            modalTool.viewChanged();
        }
    }

    /**
     * A document that comes with the default view (100 %, centred) is kept fitting into the
     * canvas, at 100 % at most, until the user zooms or pans. So a new document is shown whole,
     * whatever the window size, and a view that was chosen deliberately is never touched.
     */
    private void autoFit(EditorDocument doc) {
        ViewState state = doc.getViewState();
        boolean centred = state.getCanvasPanX() == 0 && state.getCanvasPanY() == 0;
        if (!autoZoom.containsKey(doc)) {
            autoZoom.put(doc, centred && state.getCanvasZoom() == 1 ? 1.0 : Double.NaN);
        }
        double auto = autoZoom.get(doc);
        if (Double.isNaN(auto)) {
            return;
        }
        if (!centred || state.getCanvasZoom() != auto) {
            autoZoom.put(doc, Double.NaN);
            return;
        }
        Animation animation = doc.getAnimation();
        double fit = clampZoom(Math.min(1, Math.min((getWidth() - 2 * FIT_MARGIN) / animation.width(),
                (getHeight() - 2 * FIT_MARGIN) / animation.height())));
        if (fit != auto) {
            autoZoom.put(doc, fit);
            state.setCanvasZoom(fit);
        }
    }

    // ------------------------------------------------------------------ pointer

    private Pointer pointer(MouseEvent event) {
        return new Pointer(new Vec2(event.getX(), event.getY()), event.isShiftDown(), event.isAltDown(),
                event.isControlDown(), event.getClickCount());
    }

    private void installPointer() {
        addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            requestFocus();
            if (getDocument() == null) {
                return;
            }
            if (event.getButton() == MouseButton.MIDDLE || (spaceDown && event.getButton() == MouseButton.PRIMARY)) {
                panning = true;
                spaceUsed = true;
                panStartPointer = new Vec2(event.getX(), event.getY());
                ViewState state = getDocument().getViewState();
                panStart = new Vec2(state.getCanvasPanX(), state.getCanvasPanY());
                setCursor(Cursor.CLOSED_HAND);
            } else if (event.getButton() == MouseButton.PRIMARY) {
                playback.pause();
                toolHasPointer = true;
                lastPointer = pointer(event);
                currentTool().pressed(lastPointer);
                requestLayout();
            }
            event.consume();
        });
        addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (panning && getDocument() != null) {
                ViewState state = getDocument().getViewState();
                state.setCanvasPanX(panStart.x() + event.getX() - panStartPointer.x());
                state.setCanvasPanY(panStart.y() + event.getY() - panStartPointer.y());
            } else if (toolHasPointer) {
                lastPointer = pointer(event);
                currentTool().dragged(lastPointer);
                requestLayout();
            }
            event.consume();
        });
        addEventHandler(MouseEvent.MOUSE_RELEASED, event -> {
            if (panning) {
                panning = false;
            } else if (toolHasPointer) {
                toolHasPointer = false;
                lastPointer = pointer(event);
                currentTool().released(lastPointer);
                requestLayout();
            }
            updateCursor();
            event.consume();
        });
        addEventHandler(MouseEvent.MOUSE_MOVED, event -> {
            lastPointer = pointer(event);
            if (getDocument() != null) {
                currentTool().moved(lastPointer);
            }
            updateCursor();
        });
        addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
            if (!toolHasPointer) {
                currentTool().exited();
            }
        });
        setOnContextMenuRequested(event -> {
            if (getDocument() == null || toolHasPointer || panning) {
                return;
            }
            Pointer at = new Pointer(new Vec2(event.getX(), event.getY()), false, false, false, 1);
            List<MenuItem> items = currentTool().menuItems(at);
            contextMenu.hide();
            if (!items.isEmpty()) {
                contextMenu.getItems().setAll(items);
                contextMenu.show(this, event.getScreenX(), event.getScreenY());
            }
            requestLayout();
            event.consume();
        });
        setOnScroll(event -> {
            if (getDocument() == null) {
                return;
            }
            if (event.isShortcutDown() || event.isControlDown()) {
                zoomTo(getZoom() * Math.exp(event.getDeltaY() * 0.004), new Vec2(event.getX(), event.getY()));
            } else {
                panBy(event.getDeltaX(), event.getDeltaY());
            }
            event.consume();
        });
        setOnZoom(event -> {
            if (getDocument() != null && Double.isFinite(event.getZoomFactor()) && event.getZoomFactor() > 0) {
                zoomTo(getZoom() * event.getZoomFactor(), new Vec2(event.getX(), event.getY()));
            }
            event.consume();
        });
    }

    private void updateCursor() {
        if (panning) {
            setCursor(Cursor.CLOSED_HAND);
        } else if (spaceDown) {
            setCursor(Cursor.OPEN_HAND);
        } else if (lastPointer != null && getDocument() != null) {
            setCursor(currentTool().cursor(lastPointer));
        } else {
            setCursor(Cursor.DEFAULT);
        }
    }

    // ------------------------------------------------------------------ keyboard

    /** Whether the keyboard is not the canvas's: the user is typing text, or a dialog has it. */
    private boolean typing() {
        Scene scene = getScene();
        return keyboardTaken.getAsBoolean() || (scene != null && scene.getFocusOwner() instanceof TextInputControl);
    }

    /** Space held and dragged pans; space tapped plays or pauses. Not while typing. */
    private void handleSpace(KeyEvent event) {
        if (event.getCode() != KeyCode.SPACE && !" ".equals(event.getCharacter())) {
            return;
        }
        if (typing() || getDocument() == null) {
            spaceDown = false;
            return;
        }
        if (event.getEventType() == KeyEvent.KEY_PRESSED) {
            if (!spaceDown) {
                spaceDown = true;
                spaceUsed = false;
            }
        } else if (event.getEventType() == KeyEvent.KEY_RELEASED && spaceDown) {
            spaceDown = false;
            if (!spaceUsed) {
                togglePlayback();
            }
        }
        updateCursor();
        event.consume();
    }

    private void handleKey(KeyEvent event) {
        if (event.isConsumed() || typing() || getDocument() == null) {
            return;
        }
        boolean used = switch (event.getCode()) {
            case ESCAPE -> {
                if (!currentTool().cancel()) {
                    escape();
                }
                requestLayout();
                yield true;
            }
            case DELETE, BACK_SPACE -> {
                // a tool that edits points removes points; otherwise the selected layers go
                if (!event.isShortcutDown() && !currentTool().keyPressed(event)) {
                    actions.delete();
                }
                yield !event.isShortcutDown();
            }
            default -> currentTool().keyPressed(event);
        };
        if (used) {
            event.consume();
        }
    }

    /** Escape with nothing to cancel: back to the select tool, out of the group, then deselect. */
    private void escape() {
        if (modalTool != null) {
            setModalTool(null);
        } else if (getActiveTool() != ToolKind.SELECT) {
            setActiveTool(ToolKind.SELECT);
        } else if (getEnteredGroupId() != null) {
            String group = getEnteredGroupId();
            enterGroup(null);
            getDocument().getSelection().layerIds().setAll(group);
        } else {
            getDocument().getSelection().layerIds().clear();
        }
    }

    // ------------------------------------------------------------------ drop

    private static boolean isImage(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return file.isFile() && dot >= 0 && IMAGE_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private void installDrop() {
        Predicate<javafx.scene.input.Dragboard> hasImage =
                board -> board.hasFiles() && board.getFiles().stream().anyMatch(CanvasView::isImage);
        setOnDragOver(event -> {
            if (getDocument() != null && hasImage.test(event.getDragboard())) {
                event.acceptTransferModes(TransferMode.COPY);
                event.consume();
            }
        });
        setOnDragDropped(event -> {
            if (getDocument() == null || !hasImage.test(event.getDragboard())) {
                return;
            }
            boolean added = false;
            Vec2 at = new Vec2(event.getX(), event.getY());
            for (File file : event.getDragboard().getFiles()) {
                if (isImage(file)) {
                    added |= actions.addImage(file.toPath(), at).isPresent();
                    at = at.plus(new Vec2(16, 16));
                }
            }
            event.setDropCompleted(added);
            event.consume();
        });
    }

    /**
     * Adds an image file as an asset and an image layer in the middle of the artboard.
     *
     * @return the id of the new layer; empty when the file could not be read as an image
     */
    public Optional<String> addImage(Path file) {
        return actions.addImage(file, null);
    }
}
