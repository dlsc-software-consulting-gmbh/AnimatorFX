package com.dlsc.animatorfx.editor.timeline;

import com.dlsc.animatorfx.editor.doc.Command;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.doc.PropertyUnits;
import com.dlsc.animatorfx.editor.doc.Selection.KeyframeRef;
import com.dlsc.animatorfx.editor.inspector.fields.KeyframeToggle;
import com.dlsc.animatorfx.editor.timeline.LayerDrops.Drop;
import com.dlsc.animatorfx.editor.timeline.LayerDrops.Where;
import com.dlsc.animatorfx.editor.timeline.TimelineRow.LayerRow;
import com.dlsc.animatorfx.editor.timeline.TimelineRow.PropertyRow;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.VideoLayer;
import java.util.HashSet;
import java.util.Set;
import com.dlsc.animatorfx.model.Layers;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.ImageLayer;
import com.dlsc.animatorfx.model.Keyframe;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.SlotLayer;
import com.dlsc.animatorfx.model.TextLayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.kordamp.ikonli.material2.Material2OutlinedAL;
import org.kordamp.ikonli.material2.Material2OutlinedMZ;

/**
 * The left column of the timeline: a header with the timeline controls (auto-key, show all
 * properties, follow selection, add marker, zoom), the strip beside the cue lane with the
 * add-cue button and, below them, one row per layer and per listed property.
 *
 * <p>A layer row has a disclosure triangle (property rows and, for a group, its children), an
 * icon for the layer type, the name (double-click renames), and the visibility and lock toggles;
 * for an audio layer the visibility toggle is a speaker that mutes it.
 * A click selects the layer (Shift = range, Shortcut = toggle); dragging rows reorders them or
 * moves them into a group. A property row shows the value at the playhead and a keyframe
 * navigator: previous keyframe, keyframe at the playhead on/off, next keyframe.
 *
 * <p>Only the rows in view have nodes; these are reused, and a playhead move only updates the
 * values of the property rows.
 */
public final class LayerTreeColumn extends Region {

    public static final String RENAME_FIELD_ID = "layer-rename-field";
    public static final String AUTO_KEY_ID = "timeline-auto-key";
    public static final String SHOW_ALL_ID = "timeline-show-all";
    public static final String FOLLOW_SELECTION_ID = "timeline-follow-selection";
    public static final String ADD_MARKER_ID = "timeline-add-marker";
    public static final String ADD_CUE_ID = "timeline-add-cue";
    public static final String ZOOM_IN_ID = "timeline-zoom-in";
    public static final String ZOOM_OUT_ID = "timeline-zoom-out";
    public static final String ZOOM_FIT_ID = "timeline-zoom-fit";

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final PseudoClass SLOT = PseudoClass.getPseudoClass("slot");
    private static final PseudoClass AUDIO = PseudoClass.getPseudoClass("audio");
    private Animation maskSource;
    private Set<String> maskIds = Set.of();
    private static final PseudoClass OFF = PseudoClass.getPseudoClass("off");
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private static final PseudoClass INTO = PseudoClass.getPseudoClass("into");
    private static final double INDENT = 14;
    private static final double LEFT_PADDING = 6;
    private static final double DRAG_THRESHOLD = 4;

    private final TimelineView timeline;
    private final ToggleButton autoKey = toggle(AUTO_KEY_ID, Material2AL.FIBER_MANUAL_RECORD,
            "Auto-key: changing a value adds a keyframe at the playhead");
    private final ToggleButton showAll = toggle(SHOW_ALL_ID, Material2AL.FORMAT_LIST_BULLETED,
            "Show all properties of expanded layers, not only the animated ones");
    private final ToggleButton followSelection = toggle(FOLLOW_SELECTION_ID, Material2MZ.MY_LOCATION,
            "Follow selection: scroll to the layers selected on the canvas");
    private final HBox header = new HBox();
    private final HBox cueHeader = new HBox();
    private final ContextMenu cueMenu = new ContextMenu();
    private final Pane body = new Pane();
    private final List<LayerCell> layerCells = new ArrayList<>();
    private final List<PropertyCell> propertyCells = new ArrayList<>();
    private final Region dropIndicator = new Region();
    private final TextField renameField = new TextField();

    private String anchorLayerId;
    private String renamingLayerId;
    private LayerRow pressedRow;
    private double pressSceneY;
    private boolean selectOnRelease;
    private boolean dragging;
    private List<String> dragIds = List.of();
    private Command dropCommand;

    LayerTreeColumn(TimelineView timeline) {
        this.timeline = timeline;
        getStyleClass().add("timeline-column");
        setFocusTraversable(true);

        autoKey.getStyleClass().add("auto-key");
        followSelection.selectedProperty().bindBidirectional(timeline.followSelectionProperty());
        Button addMarker = button(ADD_MARKER_ID, Material2AL.BOOKMARK_BORDER, "Add a marker at the playhead");
        addMarker.setOnAction(e -> addMarker());
        Button zoomOut = button(ZOOM_OUT_ID, Material2MZ.REMOVE, "Zoom the timeline out");
        zoomOut.setOnAction(e -> timeline.zoomOut());
        Button zoomIn = button(ZOOM_IN_ID, Material2AL.ADD, "Zoom the timeline in");
        zoomIn.setOnAction(e -> timeline.zoomIn());
        Button fit = button(ZOOM_FIT_ID, Material2MZ.SETTINGS_ETHERNET, "Show the whole duration");
        fit.setOnAction(e -> timeline.zoomToFit());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getStyleClass().add("timeline-header");
        header.getChildren().addAll(autoKey, showAll, followSelection, addMarker, spacer, zoomOut, zoomIn, fit);

        Label cueTitle = new Label("CUES");
        cueTitle.getStyleClass().add("cue-header-title");
        Region cueSpacer = new Region();
        HBox.setHgrow(cueSpacer, Priority.ALWAYS);
        Button addCue = button(ADD_CUE_ID, Material2AL.ADD, "Add a sound cue at the playhead");
        addCue.setOnAction(e -> addCue(addCue));
        cueHeader.getStyleClass().add("cue-header");
        cueHeader.getChildren().addAll(new FontIcon(Material2MZ.VOLUME_UP), cueTitle, cueSpacer, addCue);

        body.getStyleClass().add("timeline-rows");
        body.setClip(new Rectangle());
        dropIndicator.getStyleClass().add("drop-indicator");
        dropIndicator.setManaged(false);
        dropIndicator.setMouseTransparent(true);
        dropIndicator.setVisible(false);
        renameField.setId(RENAME_FIELD_ID);
        renameField.getStyleClass().add("timeline-rename");
        renameField.setManaged(false);
        renameField.setVisible(false);
        renameField.setOnAction(e -> commitRename());
        renameField.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                commitRename();
            }
        });
        renameField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                cancelRename();
                e.consume();
            }
        });
        body.getChildren().addAll(dropIndicator, renameField);
        getChildren().addAll(body, header, cueHeader);

        body.setOnMousePressed(e -> {
            // only reached below the last row: the rows consume their presses
            requestFocus();
            if (doc() != null && !e.isShiftDown() && !e.isShortcutDown()) {
                doc().getSelection().clear();
            }
        });
        addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && dragging) {
                cancelGesture();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER && e.getTarget() == this && doc() != null
                    && doc().getSelection().layerIds().size() == 1) {
                startRename(doc().getSelection().layerIds().get(0));
                e.consume();
            }
        });
    }

    private EditorDocument doc() {
        return timeline.getDocument();
    }

    void documentChanged(EditorDocument oldDoc, EditorDocument newDoc) {
        cancelRename();
        if (oldDoc != null) {
            autoKey.selectedProperty().unbindBidirectional(oldDoc.autoKeyProperty());
            showAll.selectedProperty().unbindBidirectional(oldDoc.getViewState().showAllPropertiesProperty());
        }
        if (newDoc != null) {
            autoKey.selectedProperty().bindBidirectional(newDoc.autoKeyProperty());
            showAll.selectedProperty().bindBidirectional(newDoc.getViewState().showAllPropertiesProperty());
        }
        header.setDisable(newDoc == null);
        cueHeader.setDisable(newDoc == null);
        cueMenu.hide();
        anchorLayerId = null;
    }

    private void addMarker() {
        EditorDocument doc = doc();
        if (doc != null) {
            String name = Markers.nextName(doc.getAnimation());
            doc.execute(Markers.add(timeline.getTime()));
            timeline.setSelectedMarker(name);
        }
    }

    /**
     * The button of the cue lane: with one sound in the document a cue for it is added at the
     * playhead; otherwise a menu offers the sounds, and to import one.
     */
    private void addCue(Node anchor) {
        EditorDocument doc = doc();
        if (doc == null) {
            return;
        }
        List<Asset> sounds = Cues.audioAssets(doc.getAnimation());
        if (sounds.size() == 1) {
            timeline.addCue(timeline.getTime(), sounds.get(0).id());
        } else {
            getCueMenu().show(anchor, Side.BOTTOM, 0, 2);
        }
    }

    /** The menu of the add-cue button, brought up to date: one item per sound, and Import Sound…. */
    public ContextMenu getCueMenu() {
        List<MenuItem> items = new ArrayList<>();
        EditorDocument doc = doc();
        if (doc != null) {
            for (Asset sound : Cues.audioAssets(doc.getAnimation())) {
                MenuItem item = new MenuItem(sound.name());
                item.setMnemonicParsing(false);
                item.setUserData(sound.id());
                item.setOnAction(e -> timeline.addCue(timeline.getTime(), sound.id()));
                items.add(item);
            }
        }
        if (!items.isEmpty()) {
            items.add(new SeparatorMenuItem());
        }
        MenuItem importSound = new MenuItem("Import Sound…");
        importSound.setId("cue-menu-import");
        importSound.setOnAction(e -> timeline.importSound());
        items.add(importSound);
        cueMenu.getItems().setAll(items);
        return cueMenu;
    }

    // ------------------------------------------------------------------ rows

    @Override
    protected void layoutChildren() {
        refresh();
    }

    /** Lays the header and the visible rows out and brings them up to date. */
    void refresh() {
        double width = getWidth();
        double height = getHeight();
        double mainHeader = TimelineView.RULER_HEIGHT + TimelineView.MARKER_HEIGHT;
        header.resizeRelocate(0, 0, width, mainHeader);
        cueHeader.resizeRelocate(0, mainHeader, width, TimelineView.CUE_HEIGHT);
        double bodyHeight = Math.max(0, height - TimelineView.HEADER_HEIGHT);
        body.resizeRelocate(0, TimelineView.HEADER_HEIGHT, width, bodyHeight);
        Rectangle clip = (Rectangle) body.getClip();
        clip.setWidth(width);
        clip.setHeight(bodyHeight);

        EditorDocument doc = doc();
        List<TimelineRow> rows = timeline.getRows();
        int layers = 0;
        int properties = 0;
        if (doc != null) {
            double time = timeline.getTime();
            int first = Math.max(0, (int) Math.floor(timeline.getScrollY() / TimelineView.ROW_HEIGHT));
            for (int i = first; i < rows.size() && timeline.rowTop(i) < bodyHeight; i++) {
                Region cell;
                if (rows.get(i) instanceof LayerRow layerRow) {
                    if (layers == layerCells.size()) {
                        layerCells.add(new LayerCell());
                        body.getChildren().add(0, layerCells.get(layers));
                    }
                    LayerCell layerCell = layerCells.get(layers++);
                    layerCell.show(layerRow, timeline.isHighlighted(layerRow.layerId()));
                    cell = layerCell;
                } else {
                    if (properties == propertyCells.size()) {
                        propertyCells.add(new PropertyCell());
                        body.getChildren().add(0, propertyCells.get(properties));
                    }
                    PropertyCell propertyCell = propertyCells.get(properties++);
                    propertyCell.show((PropertyRow) rows.get(i), time);
                    cell = propertyCell;
                }
                cell.setVisible(true);
                cell.resizeRelocate(0, timeline.rowTop(i), width, TimelineView.ROW_HEIGHT);
            }
        }
        for (int i = layers; i < layerCells.size(); i++) {
            layerCells.get(i).hide();
        }
        for (int i = properties; i < propertyCells.size(); i++) {
            propertyCells.get(i).hide();
        }
        placeRenameField();
    }

    /** The playhead moved: only the values and keyframe diamonds of the property rows change. */
    void updateValues() {
        double time = timeline.getTime();
        for (PropertyCell cell : propertyCells) {
            if (cell.isVisible()) {
                cell.updateValue(time);
            }
        }
    }

    /** The row node of a layer, when it is in view. */
    public Optional<Node> layerCell(String layerId) {
        for (LayerCell cell : layerCells) {
            if (cell.isVisible() && cell.row.layerId().equals(layerId)) {
                return Optional.of(cell);
            }
        }
        return Optional.empty();
    }

    /** The row node of a property of a layer, when it is in view. */
    public Optional<Node> propertyCell(String layerId, String propertyKey) {
        for (PropertyCell cell : propertyCells) {
            if (cell.isVisible() && cell.row.layerId().equals(layerId) && cell.row.key().equals(propertyKey)) {
                return Optional.of(cell);
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ selection

    private void pressed(LayerRow row, MouseEvent event) {
        EditorDocument doc = doc();
        requestFocus();
        if (event.getButton() != MouseButton.PRIMARY || doc == null) {
            return;
        }
        ObservableList<String> selection = doc.getSelection().layerIds();
        String id = row.layerId();
        pressedRow = row;
        pressSceneY = event.getSceneY();
        selectOnRelease = false;
        if (event.isShiftDown() && anchorLayerId != null) {
            List<String> order = timeline.getRows().stream().filter(r -> r instanceof LayerRow)
                    .map(TimelineRow::layerId).toList();
            int from = order.indexOf(anchorLayerId);
            int to = order.indexOf(id);
            if (from >= 0 && to >= 0) {
                selection.setAll(order.subList(Math.min(from, to), Math.max(from, to) + 1));
                return;
            }
        }
        if (event.isShortcutDown()) {
            if (selection.remove(id)) {
                // a range now starts from what is still selected
                anchorLayerId = selection.isEmpty() ? null : selection.get(selection.size() - 1);
                doc.getSelection().keyframes().clear();
                return;
            }
            selection.add(id);
        } else if (selection.contains(id)) {
            selectOnRelease = true;
        } else {
            selection.setAll(id);
        }
        doc.getSelection().keyframes().clear();
        anchorLayerId = id;
    }

    private void dragged(MouseEvent event) {
        EditorDocument doc = doc();
        if (pressedRow == null || doc == null || !event.isPrimaryButtonDown()) {
            return;
        }
        if (!dragging && Math.abs(event.getSceneY() - pressSceneY) > DRAG_THRESHOLD) {
            dragging = true;
            dragIds = doc.getSelection().layerIds().contains(pressedRow.layerId())
                    ? List.copyOf(doc.getSelection().layerIds()) : List.of(pressedRow.layerId());
        }
        if (dragging) {
            updateDrop(body.sceneToLocal(event.getSceneX(), event.getSceneY()).getY());
        }
    }

    private void released(MouseEvent event) {
        EditorDocument doc = doc();
        if (dragging) {
            Command command = dropCommand;
            cancelGesture();
            if (command != null && doc != null) {
                Optional<String> obstacle = Commands.nestingObstacle(command.apply(doc.getAnimation()));
                if (obstacle.isPresent()) {
                    timeline.notice(obstacle.get());
                } else {
                    doc.execute(command);
                }
            }
        } else if (selectOnRelease && pressedRow != null && doc != null) {
            doc.getSelection().layerIds().setAll(pressedRow.layerId());
        }
        pressedRow = null;
        selectOnRelease = false;
    }

    /** Abandons a row drag. */
    void cancelGesture() {
        dragging = false;
        dragIds = List.of();
        dropCommand = null;
        dropIndicator.setVisible(false);
    }

    // ------------------------------------------------------------------ reordering

    /** Works out where a drop at the y coordinate (relative to the rows) goes and shows the indicator. */
    private void updateDrop(double y) {
        List<TimelineRow> rows = timeline.getRows();
        Animation animation = doc().getAnimation();
        dropCommand = null;
        dropIndicator.setVisible(false);
        if (rows.isEmpty()) {
            return;
        }
        int index = timeline.rowAt(y);
        Drop drop;
        int anchorIndex;
        if (index < 0) {
            boolean above = y < 0;
            Layer anchor = above ? animation.layers().get(animation.layers().size() - 1) : animation.layers().get(0);
            drop = new Drop(anchor.id(), above ? Where.ABOVE : Where.BELOW);
            anchorIndex = indexOfLayer(rows, anchor.id());
        } else {
            TimelineRow row = rows.get(index);
            anchorIndex = indexOfLayer(rows, row.layerId());
            boolean group = row.layer() instanceof GroupLayer;
            Where where;
            if (row instanceof PropertyRow) {
                where = group ? Where.INTO : Where.BELOW;
            } else {
                double fraction = (y - timeline.rowTop(index)) / TimelineView.ROW_HEIGHT;
                if (group) {
                    boolean open = ((LayerRow) row).expanded();
                    where = fraction < 0.25 ? Where.ABOVE : fraction > 0.75 && !open ? Where.BELOW : Where.INTO;
                } else {
                    where = fraction < 0.5 ? Where.ABOVE : Where.BELOW;
                }
            }
            drop = new Drop(row.layerId(), where);
        }
        dropCommand = LayerDrops.command(animation, dragIds, drop).orElse(null);
        if (dropCommand == null || anchorIndex < 0) {
            dropCommand = null;
            return;
        }
        double indent = LEFT_PADDING + rows.get(anchorIndex).depth() * INDENT;
        double width = body.getWidth() - indent - 4;
        dropIndicator.pseudoClassStateChanged(INTO, drop.where() == Where.INTO);
        switch (drop.where()) {
            case INTO -> dropIndicator.resizeRelocate(indent, timeline.rowTop(anchorIndex), width,
                    TimelineView.ROW_HEIGHT - 1);
            case ABOVE -> dropIndicator.resizeRelocate(indent, timeline.rowTop(anchorIndex) - 1, width, 2);
            case BELOW -> dropIndicator.resizeRelocate(indent, timeline.rowTop(endOfBlock(rows, anchorIndex)) - 1,
                    width, 2);
        }
        dropIndicator.setVisible(true);
        dropIndicator.toFront();
    }

    private static int indexOfLayer(List<TimelineRow> rows, String layerId) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof LayerRow && rows.get(i).layerId().equals(layerId)) {
                return i;
            }
        }
        return -1;
    }

    /** The index after the last row that belongs to the layer row at the index: its properties and children. */
    private static int endOfBlock(List<TimelineRow> rows, int layerIndex) {
        int depth = rows.get(layerIndex).depth();
        int end = layerIndex + 1;
        while (end < rows.size() && rows.get(end).depth() > depth) {
            end++;
        }
        return end;
    }

    // ------------------------------------------------------------------ rename

    /** Opens the inline editor for the name of a layer whose row is in view. */
    public void startRename(String layerId) {
        Optional<Node> cell = layerCell(layerId);
        if (cell.isEmpty()) {
            return;
        }
        renamingLayerId = layerId;
        renameField.setText(((LayerCell) cell.get()).row.layer().name());
        renameField.setVisible(true);
        renameField.toFront();
        placeRenameField();
        renameField.requestFocus();
        renameField.selectAll();
    }

    private void placeRenameField() {
        if (renamingLayerId == null) {
            return;
        }
        Optional<Node> cell = layerCell(renamingLayerId);
        if (cell.isEmpty()) {
            cancelRename();
            return;
        }
        LayerCell layerCell = (LayerCell) cell.get();
        layerCell.layout();
        Bounds name = layerCell.name.getBoundsInParent();
        double x = name.getMinX() - 4;
        renameField.resizeRelocate(x, layerCell.getLayoutY() + 1, Math.max(60, body.getWidth() - x - 6),
                TimelineView.ROW_HEIGHT - 3);
    }

    private void commitRename() {
        String id = renamingLayerId;
        if (id == null) {
            return;
        }
        String text = renameField.getText() == null ? "" : renameField.getText().strip();
        endRename();
        if (!text.isEmpty() && doc() != null) {
            doc().execute(Commands.updateLayer("Rename Layer", id, l -> l.withBase(l.base().withName(text))));
        }
    }

    private void cancelRename() {
        if (renamingLayerId != null) {
            endRename();
        }
    }

    private void endRename() {
        renamingLayerId = null;
        renameField.setVisible(false);
        if (renameField.isFocused()) {
            requestFocus();
        }
    }

    // ------------------------------------------------------------------ cells

    /** The ids of the layers that other layers use as their mask. */
    private Set<String> maskIds() {
        Animation animation = doc() == null ? null : doc().getAnimation();
        if (animation != maskSource) {
            Set<String> ids = new HashSet<>();
            if (animation != null) {
                for (Layer layer : Layers.flatten(animation)) {
                    if (layer.base().mask() != null) {
                        ids.add(layer.base().mask().layerId());
                    }
                }
            }
            maskIds = ids;
            maskSource = animation;
        }
        return maskIds;
    }

    private static Ikon icon(Layer layer, boolean expanded) {
        return switch (layer) {
            case ShapeLayer shape -> switch (shape.geometry()) {
                case Geometry.Rect g -> Material2AL.CROP_DIN;
                case Geometry.Ellipse g -> Material2MZ.PANORAMA_FISH_EYE;
                case Geometry.Line g -> Material2AL.HORIZONTAL_RULE;
                case Geometry.Polygon g -> Material2OutlinedAL.CHANGE_HISTORY;
                case Geometry.Star g -> Material2MZ.STAR_BORDER;
                case Geometry.PathGeometry g -> Material2AL.GESTURE;
            };
            case TextLayer text -> Material2MZ.TITLE;
            case ImageLayer image -> Material2OutlinedAL.IMAGE;
            case GroupLayer group -> expanded ? Material2OutlinedAL.FOLDER_OPEN : Material2OutlinedAL.FOLDER;
            case SlotLayer slot -> Material2OutlinedMZ.WIDGETS;
            case AudioLayer audio -> Material2AL.AUDIOTRACK;
            case VideoLayer video -> Material2MZ.VIDEOCAM;
        };
    }

    private static <B extends ButtonBase> B setUp(B button, String id, Ikon icon, String tooltip) {
        button.setId(id);
        button.setGraphic(new FontIcon(icon));
        button.getStyleClass().setAll("icon-button", "timeline-tool");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private static Button button(String id, Ikon icon, String tooltip) {
        return setUp(new Button(), id, icon, tooltip);
    }

    private static ToggleButton toggle(String id, Ikon icon, String tooltip) {
        return setUp(new ToggleButton(), id, icon, tooltip);
    }

    private static Button rowButton(String styleClass, FontIcon icon) {
        Button button = new Button(null, icon);
        button.getStyleClass().setAll("row-button", styleClass);
        button.setFocusTraversable(false);
        return button;
    }

    private final class LayerCell extends HBox {

        private final Region indent = new Region();
        private final FontIcon chevron = new FontIcon(Material2AL.CHEVRON_RIGHT);
        private final FontIcon typeIcon = new FontIcon();
        private final Label name = new Label();
        private final Label badge = new Label();
        private final FontIcon eyeIcon = new FontIcon(Material2MZ.VISIBILITY);
        private final FontIcon lockIcon = new FontIcon(Material2AL.LOCK_OPEN);
        private final FontIcon muteIcon = new FontIcon(Material2MZ.VOLUME_UP);
        private final Button mute = rowButton("video-mute", muteIcon);
        private final Button eye = rowButton("eye", eyeIcon);
        private final Button lock = rowButton("lock", lockIcon);
        private LayerRow row;

        LayerCell() {
            getStyleClass().addAll("timeline-row", "layer-row");
            setManaged(false);
            setAlignment(Pos.CENTER_LEFT);
            Button disclosure = rowButton("disclosure", chevron);
            typeIcon.getStyleClass().add("layer-icon");
            name.getStyleClass().add("layer-name");
            name.setMaxWidth(Double.MAX_VALUE);
            name.setMinWidth(0);
            HBox.setHgrow(name, Priority.ALWAYS);
            badge.getStyleClass().addAll("layer-badge", "mono");
            badge.setMinWidth(Region.USE_PREF_SIZE);
            HBox.setMargin(badge, new Insets(0, 4, 0, 0));
            getChildren().addAll(indent, disclosure, typeIcon, name, badge, mute, eye, lock);
            mute.setTooltip(new Tooltip("Mute the video's sound without hiding its picture"));
            mute.setOnAction(event -> {
                if (row.layer() instanceof VideoLayer video) {
                    doc().execute(Commands.updateLayer(video.muted() ? "Unmute Video" : "Mute Video", video.id(),
                            layer -> ((VideoLayer) layer).withMuted(!video.muted())));
                }
            });

            disclosure.setOnAction(e -> {
                var expanded = doc().getViewState().getExpandedLayerIds();
                if (!expanded.remove(row.layerId())) {
                    expanded.add(row.layerId());
                }
            });
            eye.setOnAction(e -> {
                boolean visible = row.layer().base().visible();
                if (row.layer() instanceof AudioLayer audio) {
                    // the speaker: off silences the layer, on makes it sound whatever kept it silent
                    boolean audible = visible && !audio.muted();
                    doc().execute(Commands.updateLayer(audible ? "Mute Layer" : "Unmute Layer", row.layerId(),
                            l -> audible ? l.withBase(l.base().withVisible(false))
                                    : ((AudioLayer) l).withMuted(false).withBase(l.base().withVisible(true))));
                    return;
                }
                doc().execute(Commands.updateLayer(visible ? "Hide Layer" : "Show Layer", row.layerId(),
                        l -> l.withBase(l.base().withVisible(!visible))));
            });
            lock.setOnAction(e -> {
                boolean locked = row.layer().base().locked();
                doc().execute(Commands.updateLayer(locked ? "Unlock Layer" : "Lock Layer", row.layerId(),
                        l -> l.withBase(l.base().withLocked(!locked))));
            });
            setOnMousePressed(e -> {
                pressed(row, e);
                e.consume();
            });
            setOnMouseDragged(LayerTreeColumn.this::dragged);
            setOnMouseReleased(LayerTreeColumn.this::released);
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !dragging) {
                    startRename(row.layerId());
                }
            });
        }

        void show(LayerRow newRow, boolean selected) {
            row = newRow;
            Layer layer = newRow.layer();
            indent.setMinWidth(LEFT_PADDING + newRow.depth() * INDENT);
            chevron.setRotate(newRow.expanded() ? 90 : 0);
            typeIcon.setIconCode(icon(layer, newRow.expanded()));
            name.setText(layer instanceof SlotLayer slot ? layer.name() + "  ·  " + slot.slotName() : layer.name());
            // an audio layer is not seen but heard: its eye is a speaker
            boolean audio = layer instanceof AudioLayer;
            boolean shown = layer.base().visible() && !(layer instanceof AudioLayer sound && sound.muted());
            eyeIcon.setIconCode(audio ? (shown ? Material2MZ.VOLUME_UP : Material2MZ.VOLUME_OFF)
                    : shown ? Material2MZ.VISIBILITY : Material2MZ.VISIBILITY_OFF);
            mute.setVisible(layer instanceof VideoLayer);
            mute.setManaged(layer instanceof VideoLayer);
            boolean muted = layer instanceof VideoLayer video && video.muted();
            muteIcon.setIconCode(muted ? Material2MZ.VOLUME_OFF : Material2MZ.VOLUME_UP);
            mute.pseudoClassStateChanged(ACTIVE, muted);
            pseudoClassStateChanged(AUDIO, audio);
            lockIcon.setIconCode(layer.base().locked() ? Material2AL.LOCK : Material2AL.LOCK_OPEN);
            eye.pseudoClassStateChanged(ACTIVE, !shown);
            lock.pseudoClassStateChanged(ACTIVE, layer.base().locked());
            // a layer that masks is not drawn itself; a masked layer is cut by another one
            boolean masks = maskIds().contains(layer.id());
            boolean masked = layer.base().mask() != null;
            badge.setText(masks ? "MASK" : masked ? "MASKED" : "");
            badge.setVisible(masks || masked);
            badge.setManaged(masks || masked);
            pseudoClassStateChanged(SELECTED, selected);
            pseudoClassStateChanged(SLOT, layer instanceof SlotLayer);
            pseudoClassStateChanged(OFF, !shown);
        }

        void hide() {
            setVisible(false);
            row = null;
        }
    }

    private final class PropertyCell extends HBox {

        private final Region indent = new Region();
        private final Label label = new Label();
        private final Label value = new Label();
        private final Button previous = rowButton("nav", new FontIcon(Material2AL.ARROW_LEFT));
        private final KeyframeToggle toggle = new KeyframeToggle();
        private final Button next = rowButton("nav", new FontIcon(Material2AL.ARROW_RIGHT));
        private PropertyRow row;

        PropertyCell() {
            getStyleClass().addAll("timeline-row", "property-row");
            setManaged(false);
            setAlignment(Pos.CENTER_LEFT);
            label.getStyleClass().add("property-name");
            label.setMinWidth(0);
            value.getStyleClass().addAll("property-value", "mono");
            value.setMaxWidth(Double.MAX_VALUE);
            value.setMinWidth(0);
            value.setAlignment(Pos.CENTER_RIGHT);
            HBox.setHgrow(value, Priority.ALWAYS);
            previous.setTooltip(new Tooltip("Go to the previous keyframe"));
            next.setTooltip(new Tooltip("Go to the next keyframe"));
            getChildren().addAll(indent, label, value, previous, toggle, next);

            previous.setOnAction(e -> TimelineRows.previousKeyTime(row.property().value(), doc().getPlayhead())
                    .ifPresent(doc()::setPlayhead));
            next.setOnAction(e -> TimelineRows.nextKeyTime(row.property().value(), doc().getPlayhead())
                    .ifPresent(doc()::setPlayhead));
            toggle.setOnAction(e -> {
                if (timeline.getKeyframeActions().isLocked(row.layerId())) {
                    return;
                }
                double time = timeline.getTime();
                Optional<Keyframe<?>> keyframe = TimelineRows.keyframeAt(row.property().value(), time);
                if (keyframe.isPresent()) {
                    doc().execute(Commands.removeKeyframes(
                            List.of(new KeyframeRef(row.layerId(), row.key(), keyframe.get().id()))));
                } else {
                    doc().execute(Commands.addKeyframe(row.layerId(), row.key(), time));
                }
            });
            setOnMousePressed(e -> {
                LayerTreeColumn.this.requestFocus();
                if (e.getButton() == MouseButton.PRIMARY && !doc().getSelection().layerIds().contains(row.layerId())) {
                    doc().getSelection().layerIds().setAll(row.layerId());
                }
                e.consume();
            });
        }

        void show(PropertyRow newRow, double time) {
            row = newRow;
            // the disclosure button of the layer row is as wide as one indent step
            indent.setMinWidth(LEFT_PADDING + newRow.depth() * INDENT + 6);
            label.setText(newRow.property().label());
            updateValue(time);
        }

        void updateValue(double time) {
            Animatable<?> animatable = row.property().value();
            value.setText(PropertyUnits.text(row.key(), animatable.at(time)));
            boolean animated = animatable.isAnimated();
            toggle.setState(TimelineRows.keyframeAt(animatable, time).isPresent() ? KeyframeToggle.State.KEYED
                    : animated ? KeyframeToggle.State.ANIMATED : KeyframeToggle.State.NONE);
            previous.setDisable(TimelineRows.previousKeyTime(animatable, time).isEmpty());
            next.setDisable(TimelineRows.nextKeyTime(animatable, time).isEmpty());
        }

        void hide() {
            setVisible(false);
            row = null;
        }
    }
}
