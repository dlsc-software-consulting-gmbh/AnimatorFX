package com.dlsc.animatorfx.editor.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.Selection.KeyframeRef;
import com.dlsc.animatorfx.editor.timeline.TimelineRow.PropertyRow;
import com.dlsc.animatorfx.editor.ui.EditorUiTest;
import com.dlsc.animatorfx.editor.ui.Palette;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Easing;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.Keyframe;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.Marker;
import com.dlsc.animatorfx.model.Vec2;
import java.util.List;
import java.util.Set;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TimelineViewTest extends EditorUiTest {

    private static final String POSITION = "transform.position";
    private static final String OPACITY = "opacity";

    private TrackPane tracks;
    private LayerTreeColumn column;

    /** Bottom to top: a (position keyframes at 0.5 and 1.5, opacity at 1.0), b, c. */
    @BeforeEach
    void content() {
        tracks = timeline.getTrackPane();
        column = timeline.getLayerColumn();
        timeline.getKeyframeActions().setClipboard(new KeyframeClipboard());
        add(rect("a", 100, 100, 100, 100), rect("b", 300, 100, 100, 100), rect("c", 500, 100, 100, 100));
        execute(Commands.setProperty("a", POSITION, Animatable.keyframed(List.of(
                new Keyframe<>("p1", 0.5, new Vec2(150, 150), Easing.LINEAR),
                new Keyframe<>("p2", 1.5, new Vec2(350, 150), Easing.LINEAR)))));
        execute(Commands.setProperty("a", OPACITY, Animatable.keyframed(List.of(
                new Keyframe<>("o1", 1.0, 0.5, Easing.LINEAR)))));
        run(() -> {
            doc.undo();
            doc.redo();
        });
    }

    private void expand(String... ids) {
        run(() -> doc.getViewState().getExpandedLayerIds().addAll(List.of(ids)));
        layout();
    }

    private double x(double time) {
        return fx(() -> tracks.timeToX(time));
    }

    private double y(String layerId, String key) {
        return fx(() -> tracks.rowCenter(key == null ? tracks.rowOf(layerId) : tracks.rowOf(layerId, key)));
    }

    private Node layerCell(String id) {
        return fx(() -> column.layerCell(id).orElseThrow());
    }

    private Node propertyCell(String id, String key) {
        return fx(() -> column.propertyCell(id, key).orElseThrow());
    }

    private Set<KeyframeRef> selectedKeys() {
        return Set.copyOf(doc.getSelection().keyframes());
    }

    private static KeyframeRef ref(String layer, String key, String id) {
        return new KeyframeRef(layer, key, id);
    }

    private double time(String layer, String key, String id) {
        return keyframes(layer, key).stream().filter(k -> k.id().equals(id)).findFirst().orElseThrow().time();
    }

    private void fire(String selector) {
        run(() -> ((ButtonBase) window.getScene().lookup(selector)).fire());
        layout();
    }

    // ------------------------------------------------------------------ rows

    @Test
    void layerRowsListTheTopMostLayerFirstAndExpandingShowsAnimatedProperties() {
        assertEquals(List.of("c", "b", "a"), fx(() -> timeline.getRows().stream().map(TimelineRow::layerId).toList()));
        assertEquals(0, fx(() -> tracks.rowOf("c")));
        assertEquals(-1, fx(() -> tracks.rowOf("a", POSITION)));

        run(() -> ((ButtonBase) layerCell("a").lookup(".disclosure")).fire());
        layout();
        assertTrue(doc.getViewState().getExpandedLayerIds().contains("a"));
        assertEquals(List.of(POSITION, OPACITY), fx(() -> timeline.getRows().stream()
                .filter(r -> r instanceof PropertyRow).map(r -> ((PropertyRow) r).key()).toList()));
        assertEquals("Position", ((Label) propertyCell("a", POSITION).lookup(".property-name")).getText());

        fire("#" + LayerTreeColumn.SHOW_ALL_ID);
        assertTrue(doc.getViewState().isShowAllProperties());
        assertEquals(9, fx(() -> timeline.getRows().stream().filter(r -> r instanceof PropertyRow).count()));
    }

    @Test
    void aGroupShowsItsChildrenWhenExpanded() {
        add(group("g", rect("g1", 0, 0, 10, 10), rect("g2", 0, 0, 10, 10)));
        assertEquals(List.of("g", "c", "b", "a"), fx(() -> timeline.getRows().stream().map(TimelineRow::layerId).toList()));
        expand("g");
        assertEquals(List.of("g", "g2", "g1", "c", "b", "a"),
                fx(() -> timeline.getRows().stream().map(TimelineRow::layerId).toList()));
        assertEquals(1, fx(() -> timeline.getRows().get(1).depth()));
    }

    @Test
    void theValueReadOutFollowsThePlayheadWithoutRebuildingTheRows() {
        expand("a");
        Node cell = propertyCell("a", POSITION);
        Label value = (Label) cell.lookup(".property-value");
        playhead(0.5);
        assertEquals("150, 150", value.getText());
        run(() -> doc.setPlayhead(1.0));
        // no layout pass in between: the value is updated in place
        assertEquals("250, 150", fx(value::getText));
        assertSame(cell, propertyCell("a", POSITION));
        assertEquals("50%", ((Label) propertyCell("a", OPACITY).lookup(".property-value")).getText());
    }

    // ------------------------------------------------------------------ layer column

    @Test
    void clickingLayerRowsSelectsInSyncWithTheDocument() {
        click(layerCell("b"), 100, 10);
        assertEquals(List.of("b"), selection());
        assertTrue(layerCell("b").getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("selected")));
        click(layerCell("a"), 100, 10, Keys.SHORTCUT);
        assertEquals(List.of("b", "a"), selection());
        click(layerCell("b"), 100, 10, Keys.SHORTCUT);
        assertEquals(List.of("a"), selection());
        click(layerCell("c"), 100, 10, Keys.SHIFT);
        assertEquals(List.of("c", "b", "a"), selection());
        // the other way round: selecting in the document marks the rows
        select("c");
        assertTrue(layerCell("c").getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("selected")));
        assertFalse(layerCell("a").getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("selected")));
    }

    private void addManyLayers() {
        Layer[] more = new Layer[40];
        for (int i = 0; i < more.length; i++) {
            more[i] = rect("m" + i, 0, 0, 10, 10);
        }
        add(more);
        layout();
    }

    private boolean rowInView(String layerId) {
        return fx(() -> {
            double top = timeline.rowTop(tracks.rowOf(layerId));
            return top >= 0 && top + TimelineView.ROW_HEIGHT <= tracks.getHeight();
        });
    }

    @Test
    void selectingALayerElsewhereScrollsItsRowIntoView() {
        addManyLayers();
        assertFalse(rowInView("a"));
        select("a");
        layout();
        assertTrue(rowInView("a"));
        select("m39");
        layout();
        assertTrue(rowInView("m39"));
    }

    @Test
    void theFollowSelectionButtonSwitchesScrollingOff() {
        addManyLayers();
        assertTrue(timeline.isFollowSelection());
        fire("#" + LayerTreeColumn.FOLLOW_SELECTION_ID);
        assertFalse(timeline.isFollowSelection());
        select("a");
        layout();
        assertFalse(rowInView("a"));
        assertEquals(0, fx(timeline::getScrollY));
    }

    @Test
    void withFollowSelectionOffOnlyTimelineClicksChangeTheHighlight() {
        fire("#" + LayerTreeColumn.FOLLOW_SELECTION_ID);
        select("a");
        layout();
        assertFalse(selectedPseudo(layerCell("a")));
        assertFalse(fx(() -> timeline.isHighlighted("a")));

        click(layerCell("b"), 100, 10);
        assertEquals(List.of("b"), selection());
        assertTrue(selectedPseudo(layerCell("b")));
        assertTrue(fx(() -> timeline.isHighlighted("b")));

        select("c");
        layout();
        assertTrue(selectedPseudo(layerCell("b")));
        assertFalse(selectedPseudo(layerCell("c")));

        fire("#" + LayerTreeColumn.FOLLOW_SELECTION_ID);
        assertTrue(selectedPseudo(layerCell("c")));
        assertFalse(selectedPseudo(layerCell("b")));
    }

    private static boolean selectedPseudo(Node node) {
        return node.getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("selected"));
    }

    @Test
    void aLayerInACollapsedGroupRevealsTheGroupWithoutExpandingIt() {
        add(group("g", rect("g1", 0, 0, 10, 10)));
        addManyLayers();
        assertFalse(rowInView("g"));
        select("g1");
        layout();
        assertTrue(rowInView("g"));
        assertFalse(doc.getViewState().getExpandedLayerIds().contains("g"));
    }

    @Test
    void doubleClickRenamesALayerAsOneUndoStep() {
        doubleClick(layerCell("b"), 100, 10);
        TextField field = lookup("#" + LayerTreeColumn.RENAME_FIELD_ID);
        assertTrue(field.isVisible());
        assertEquals("b", field.getText());
        run(() -> {
            field.setText("Badge");
            Event.fireEvent(field, new ActionEvent());
        });
        layout();
        assertEquals("Badge", layer("b").name());
        assertEquals("Rename Layer", doc.getUndoName());
        assertFalse(field.isVisible());
        run(doc::undo);
        assertEquals("b", layer("b").name());

        // Escape leaves the name alone
        doubleClick(layerCell("b"), 100, 10);
        run(() -> field.setText("Nope"));
        key(KeyCode.ESCAPE);
        assertFalse(field.isVisible());
        assertEquals("b", layer("b").name());
    }

    @Test
    void theEyeAndTheLockToggleTheLayer() {
        run(() -> ((ButtonBase) layerCell("b").lookup(".eye")).fire());
        assertFalse(layer("b").base().visible());
        assertEquals("Hide Layer", doc.getUndoName());
        layout();
        run(() -> ((ButtonBase) layerCell("b").lookup(".lock")).fire());
        assertTrue(layer("b").base().locked());
        layout();
        run(() -> ((ButtonBase) layerCell("b").lookup(".eye")).fire());
        assertTrue(layer("b").base().visible());
    }

    @Test
    void draggingARowReordersTheLayersAsOneUndoStep() {
        // rows: c (0), b (1), a (2); drag a to the upper half of c
        Node a = layerCell("a");
        Node c = layerCell("c");
        double toY = fx(() -> a.sceneToLocal(c.localToScene(0, 4)).getY());
        drag(a, 100, 10, 100, toY);
        assertEquals(List.of("b", "c", "a"), layerIds());
        assertEquals("Move Layer", doc.getUndoName());
        run(doc::undo);
        assertEquals(List.of("a", "b", "c"), layerIds());
    }

    @Test
    void droppingARowOnAGroupMovesTheLayerIntoIt() {
        add(group("g", rect("g1", 0, 0, 10, 10)));
        Node a = layerCell("a");
        Node g = layerCell("g");
        double toY = fx(() -> a.sceneToLocal(g.localToScene(0, 11)).getY());
        drag(a, 100, 10, 100, toY);
        assertEquals(List.of("b", "c", "g"), layerIds());
        assertEquals(List.of("g1", "a"), ((GroupLayer) layer("g")).children().stream().map(Layer::id).toList());
    }

    @Test
    void aDropThatWouldNestLayersTooDeepIsRefusedWithANotice() {
        add(com.dlsc.animatorfx.editor.doc.SaveableCommandsTest.nested("d", 100), group("g", rect("g1", 0, 0, 10, 10)));
        Node deep = layerCell("d1");
        Node g = layerCell("g");
        double toY = fx(() -> deep.sceneToLocal(g.localToScene(0, 11)).getY());
        drag(deep, 100, 10, 100, toY);

        assertEquals(List.of("a", "b", "c", "d1", "g"), layerIds());
        assertEquals(List.of("Layers cannot be nested deeper than 100 levels."),
                fx(() -> window.getNoticeBar().getMessages()));
    }

    @Test
    void theKeyframeNavigatorJumpsAndToggles() {
        expand("a");
        playhead(1.0);
        Node cell = propertyCell("a", POSITION);
        List<Node> nav = List.copyOf(cell.lookupAll(".nav"));
        run(() -> ((ButtonBase) nav.get(1)).fire());
        assertEquals(1.5, doc.getPlayhead(), 1e-9);
        layout();
        assertTrue(fx(() -> ((ButtonBase) List.copyOf(propertyCell("a", POSITION).lookupAll(".nav")).get(1)).isDisabled()));
        run(() -> ((ButtonBase) List.copyOf(propertyCell("a", POSITION).lookupAll(".nav")).get(0)).fire());
        assertEquals(0.5, doc.getPlayhead(), 1e-9);

        playhead(1.0);
        run(() -> ((ButtonBase) propertyCell("a", POSITION).lookup(".keyframe-toggle")).fire());
        assertEquals(3, keyframes("a", POSITION).size());
        assertEquals(new Vec2(250, 150), keyframes("a", POSITION).get(1).value());
        layout();
        run(() -> ((ButtonBase) propertyCell("a", POSITION).lookup(".keyframe-toggle")).fire());
        assertEquals(2, keyframes("a", POSITION).size());
    }

    @Test
    void theAutoKeyButtonFollowsTheDocument() {
        assertFalse(doc.isAutoKey());
        fire("#" + LayerTreeColumn.AUTO_KEY_ID);
        assertTrue(doc.isAutoKey());
        run(() -> doc.setAutoKey(false));
        assertFalse(fx(() -> ((javafx.scene.control.ToggleButton) window.getScene()
                .lookup("#" + LayerTreeColumn.AUTO_KEY_ID)).isSelected()));
    }

    // ------------------------------------------------------------------ ruler

    @Test
    void scrubbingTheRulerMovesThePlayheadAndTheCanvas() {
        TimeRuler ruler = timeline.getRuler();
        press(ruler, x(1.0) + 1, 8, Keys.NONE);
        assertEquals(1.0, doc.getPlayhead(), 1e-9);
        dragTo(ruler, x(2.004), 8, Keys.NONE);
        assertEquals(2.0, doc.getPlayhead(), 1e-9, "snapped to a frame");
        assertEquals(2.0, fx(() -> canvas.getTime()), 1e-9);
        assertEquals(2.0, fx(() -> canvas.getAnimationView().getCurrentTime()), 1e-9);
        // Alt: no snapping
        double free = fx(() -> timeline.getScale().toTime(x(1.204)));
        dragTo(ruler, x(1.204), 8, Keys.ALT);
        assertEquals(free, doc.getPlayhead(), 1e-6);
        assertNotEquals(TimeScale.snap(free, 60), doc.getPlayhead(), 1e-4);
        // beyond the ends the playhead stops
        dragTo(ruler, x(3) + 200, 8, Keys.NONE);
        assertEquals(3.0, doc.getPlayhead(), 1e-9);
        dragTo(ruler, -50, 8, Keys.NONE);
        assertEquals(0.0, doc.getPlayhead(), 1e-9);
        release(ruler, 0, 8, Keys.NONE);
    }

    // ------------------------------------------------------------------ keyframes

    @Test
    void clickingKeyframesSelectsThem() {
        expand("a");
        click(tracks, x(0.5), y("a", POSITION));
        assertEquals(Set.of(ref("a", POSITION, "p1")), selectedKeys());
        assertEquals(List.of("a"), selection(), "the layer of a selected keyframe is selected");
        click(tracks, x(1.0), y("a", OPACITY), Keys.SHIFT);
        assertEquals(Set.of(ref("a", POSITION, "p1"), ref("a", OPACITY, "o1")), selectedKeys());
        click(tracks, x(0.5), y("a", POSITION), Keys.SHORTCUT);
        assertEquals(Set.of(ref("a", OPACITY, "o1")), selectedKeys());
        click(tracks, x(1.5), y("a", POSITION));
        assertEquals(Set.of(ref("a", POSITION, "p2")), selectedKeys());
        // free space deselects the keyframes; selecting another layer does too
        click(tracks, x(2.5), y("a", POSITION));
        assertEquals(Set.of(), selectedKeys());
        click(tracks, x(1.5), y("a", POSITION));
        select("b");
        assertEquals(Set.of(), selectedKeys());
    }

    @Test
    void draggingAKeyframeChangesItsTimeAsOneUndoStep() {
        expand("a");
        double row = y("a", POSITION);
        drag(tracks, x(0.5), row, x(1.004), row);
        assertEquals(1.0, time("a", POSITION, "p1"), 1e-9, "snapped to a frame");
        assertEquals(1.5, time("a", POSITION, "p2"), 1e-9);
        assertEquals("Move Keyframe", doc.getUndoName());
        run(doc::undo);
        assertEquals(0.5, time("a", POSITION, "p1"), 1e-9);
        assertEquals("Change Opacity", doc.getUndoName());
    }

    @Test
    void selectedKeyframesMoveTogetherAndStopAtZero() {
        expand("a");
        click(tracks, x(0.5), y("a", POSITION));
        click(tracks, x(1.0), y("a", OPACITY), Keys.SHIFT);
        drag(tracks, x(1.0), y("a", OPACITY), x(1.25), y("a", OPACITY));
        assertEquals(0.75, time("a", POSITION, "p1"), 1e-9);
        assertEquals(1.25, time("a", OPACITY, "o1"), 1e-9);
        assertEquals(1.5, time("a", POSITION, "p2"), 1e-9);
        assertEquals(Set.of(ref("a", POSITION, "p1"), ref("a", OPACITY, "o1")), selectedKeys());

        // far to the left: the block keeps its spacing and stops when its first keyframe is at zero
        drag(tracks, x(1.25), y("a", OPACITY), x(1.25) - 2000, y("a", OPACITY));
        assertEquals(0.0, time("a", POSITION, "p1"), 1e-9);
        assertEquals(0.5, time("a", OPACITY, "o1"), 1e-9);
    }

    @Test
    void passingOverAnotherKeyframeDuringADragDoesNotRemoveIt() {
        execute(Commands.setValueAt("a", OPACITY, 2.0, 1.0, false));
        expand("a");
        double row = y("a", OPACITY);
        // from 1.0 over the keyframe at 2.0, stopping exactly on it on the way, to 2.5
        press(tracks, x(1.0), row, Keys.NONE);
        dragTo(tracks, x(2.0), row, Keys.NONE);
        dragTo(tracks, x(2.5), row, Keys.NONE);
        release(tracks, x(2.5), row, Keys.NONE);
        assertEquals(2, keyframes("a", OPACITY).size());
        assertEquals(2.5, time("a", OPACITY, "o1"), 1e-9);
    }

    @Test
    void escapeCancelsAKeyframeDrag() {
        expand("a");
        double row = y("a", POSITION);
        press(tracks, x(0.5), row, Keys.NONE);
        dragTo(tracks, x(1.0), row, Keys.NONE);
        assertEquals(1.0, time("a", POSITION, "p1"), 1e-9);
        key(KeyCode.ESCAPE);
        assertEquals(0.5, time("a", POSITION, "p1"), 1e-9);
        release(tracks, x(1.0), row, Keys.NONE);
        assertEquals(0.5, time("a", POSITION, "p1"), 1e-9);
        assertEquals("Change Opacity", doc.getUndoName(), "no undo step is left behind");
    }

    @Test
    void altDragsWithoutSnapping() {
        expand("a");
        double row = y("a", POSITION);
        drag(tracks, x(0.5), row, x(0.5) + 7, row, Keys.ALT);
        double expected = 0.5 + fx(() -> timeline.getScale().toDuration(7));
        assertEquals(expected, time("a", POSITION, "p1"), 1e-6);
        assertNotEquals(TimeScale.snap(expected, 60), time("a", POSITION, "p1"), 1e-4);
    }

    @Test
    void aMarqueeSelectsKeyframesAcrossRows() {
        expand("a");
        double top = y("a", POSITION) - 8;
        double bottom = y("a", OPACITY) + 8;
        drag(tracks, x(0.3), top, x(1.2), bottom);
        assertEquals(Set.of(ref("a", POSITION, "p1"), ref("a", OPACITY, "o1")), selectedKeys());
        assertEquals(List.of("a"), selection());
        // Shift adds
        drag(tracks, x(1.4), top, x(1.7), top + 10, Keys.SHIFT);
        assertEquals(3, selectedKeys().size());
    }

    @Test
    void deleteRemovesKeyframesWhileTheTracksHaveTheFocusAndLayersOtherwise() {
        expand("a");
        click(tracks, x(0.5), y("a", POSITION));
        assertSame(tracks, focusOwner());
        key(KeyCode.DELETE);
        assertEquals(1, keyframes("a", POSITION).size());
        assertEquals("Delete Keyframe", doc.getUndoName());
        assertEquals(3, layerIds().size());
        // with nothing selected in the tracks, Delete there does not touch the layers
        key(KeyCode.BACK_SPACE);
        assertEquals(3, layerIds().size());

        click(layerCell("b"), 100, 10);
        assertSame(column, focusOwner());
        key(KeyCode.DELETE);
        assertEquals(List.of("a", "c"), layerIds());
    }

    @Test
    void deleteRemovesSelectedKeyframesAlsoWhileTheLayerColumnHasTheFocus() {
        expand("a");
        click(tracks, x(0.5), y("a", POSITION));
        run(column::requestFocus);
        assertSame(column, focusOwner());

        key(KeyCode.DELETE);

        assertEquals(1, keyframes("a", POSITION).size());
        assertEquals("Delete Keyframe", doc.getUndoName());
        assertEquals(3, layerIds().size());

        // the Edit menu follows the same rule
        click(tracks, x(1.5), y("a", POSITION));
        run(column::requestFocus);
        run(() -> window.menuItem("menu-edit-delete").fire());
        assertFalse(layer("a").base().transform().position().isAnimated());
        assertEquals(3, layerIds().size());
    }

    @Test
    void aLockedLayerCannotBeTrimmedOrKeyedInTheTimeline() {
        expand("a");
        execute(Commands.updateLayer("Lock", "a", l -> l.withBase(l.base().withLocked(true))));
        Animation locked = doc.getAnimation();
        double row = y("a", POSITION);

        // keyframes can still be selected, but neither moved nor deleted
        drag(tracks, x(0.5), row, x(1.0), row);
        assertEquals(Set.of(ref("a", POSITION, "p1")), selectedKeys());
        key(KeyCode.DELETE);
        run(() -> window.menuItem("menu-edit-cut").fire());
        // no new keyframes by double-click or with the navigator
        doubleClick(tracks, x(2.5), row);
        playhead(2.0);
        layout();
        run(() -> ((ButtonBase) propertyCell("a", POSITION).lookup(".keyframe-toggle")).fire());
        // the in/out bar stays as it is
        double bar = y("a", null);
        drag(tracks, x(0) + 1, bar, x(0.5), bar);
        drag(tracks, x(1.0), bar, x(1.5), bar);
        assertSame(locked, doc.getAnimation());

        // a selection that includes an unlocked layer moves only that layer's keyframes
        execute(Commands.setProperty("b", OPACITY, Animatable.keyframed(List.of(
                new Keyframe<>("b1", 1.0, 0.5, Easing.LINEAR)))));
        expand("a", "b");
        click(tracks, x(0.5), y("a", POSITION));
        click(tracks, x(1.0), y("b", OPACITY), Keys.SHIFT);
        drag(tracks, x(1.0), y("b", OPACITY), x(2.0), y("b", OPACITY));
        assertEquals(0.5, time("a", POSITION, "p1"), 1e-9);
        assertEquals(2.0, time("b", OPACITY, "b1"), 1e-9);

        // unlocked again, the layer is edited as before
        execute(Commands.updateLayer("Unlock", "a", l -> l.withBase(l.base().withLocked(false))));
        drag(tracks, x(0.5), y("a", POSITION), x(1.0), y("a", POSITION));
        assertEquals(1.0, time("a", POSITION, "p1"), 1e-9);
    }

    @Test
    void copyAndPasteWorkOnKeyframesWhileKeyframesAreSelected() {
        expand("a");
        click(tracks, x(0.5), y("a", POSITION));
        click(tracks, x(1.0), y("a", OPACITY), Keys.SHIFT);
        run(() -> window.menuItem("menu-edit-copy").fire());
        playhead(2.0);
        run(() -> window.menuItem("menu-edit-paste").fire());
        layout();
        assertEquals(3, layerIds().size(), "no layer was pasted");
        assertEquals(3, keyframes("a", POSITION).size());
        assertEquals(2.0, keyframes("a", POSITION).get(2).time(), 1e-9);
        assertEquals(new Vec2(150, 150), keyframes("a", POSITION).get(2).value());
        assertEquals(2, keyframes("a", OPACITY).size());
        assertEquals(2.5, keyframes("a", OPACITY).get(1).time(), 1e-9);
        assertEquals("Paste Keyframes", doc.getUndoName());
        // the pasted keyframes are selected
        assertEquals(Set.of(ref("a", POSITION, keyframes("a", POSITION).get(2).id()),
                ref("a", OPACITY, keyframes("a", OPACITY).get(1).id())), selectedKeys());
        run(doc::undo);
        assertEquals(2, keyframes("a", POSITION).size());
    }

    @Test
    void copyAndPasteWorkOnLayersWhenNoKeyframeIsSelected() {
        canvas.getActions().setClipboard(new com.dlsc.animatorfx.editor.canvas.LayerClipboard());
        select("b");
        run(() -> window.menuItem("menu-edit-copy").fire());
        run(() -> window.menuItem("menu-edit-paste").fire());
        assertEquals(4, layerIds().size());
    }

    @Test
    void aDoubleClickOnFreeTrackSpaceAddsAKeyframe() {
        expand("a");
        doubleClick(tracks, x(2.004), y("a", OPACITY));
        assertEquals(2, keyframes("a", OPACITY).size());
        assertEquals(2.0, keyframes("a", OPACITY).get(1).time(), 1e-9);
        assertEquals(0.5, keyframes("a", OPACITY).get(1).value());
        assertEquals(Set.of(ref("a", OPACITY, keyframes("a", OPACITY).get(1).id())), selectedKeys());
        assertEquals("Add Keyframe", doc.getUndoName());
    }

    @Test
    void theKeyframeMenuSetsTheEasingAndDeletes() {
        expand("a");
        click(tracks, x(0.5), y("a", POSITION));
        Menu easing = (Menu) fx(() -> tracks.getKeyframeMenu().getItems().get(0));
        assertEquals("Linear", easing.getItems().get(0).getText());
        assertEquals("Hold", easing.getItems().get(1).getText());
        assertEquals(3 + Easing.presets().size(), easing.getItems().size());
        MenuItem easeOut = easing.getItems().stream().filter(i -> "Ease Out".equals(i.getText())).findFirst().orElseThrow();
        run(easeOut::fire);
        assertEquals(Easing.presets().get("Ease Out"), keyframes("a", POSITION).get(0).easing());
        assertEquals(Easing.LINEAR, keyframes("a", POSITION).get(1).easing());
        assertEquals("Change Easing", doc.getUndoName());
        run(() -> easing.getItems().get(1).fire());
        assertEquals(Easing.HOLD, keyframes("a", POSITION).get(0).easing());
        run(() -> tracks.getKeyframeMenu().getItems().get(2).fire());
        assertEquals(1, keyframes("a", POSITION).size());
    }

    @Test
    void aContextMenuClickOnAKeyframeSelectsItAndOpensTheMenu() {
        expand("a");
        double kx = x(1.5);
        double ky = y("a", POSITION);
        run(() -> {
            var scene = tracks.localToScene(kx, ky);
            var screen = tracks.localToScreen(kx, ky);
            Event.fireEvent(tracks, new javafx.scene.input.ContextMenuEvent(
                    javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, scene.getX(), scene.getY(),
                    screen.getX(), screen.getY(), false, null));
        });
        assertEquals(Set.of(ref("a", POSITION, "p2")), selectedKeys());
        assertTrue(fx(() -> tracks.getKeyframeMenu().isShowing()));
        run(() -> tracks.getKeyframeMenu().hide());
        // free space has no menu
        run(() -> {
            var scene = tracks.localToScene(kx + 60, ky);
            Event.fireEvent(tracks, new javafx.scene.input.ContextMenuEvent(
                    javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, scene.getX(), scene.getY(), 0, 0, false,
                    null));
        });
        assertFalse(fx(() -> tracks.getKeyframeMenu().isShowing()));
    }

    // ------------------------------------------------------------------ bars

    @Test
    void draggingTheEndsOfABarTrimsTheLayerAndTheBodyMovesIt() {
        double row = y("b", null);
        drag(tracks, x(0) + 1, row, x(0.5), row);
        assertEquals(0.5, layer("b").base().inPoint(), 1e-9);
        assertEquals("Trim Layer", doc.getUndoName());
        drag(tracks, x(3) - 1, row, x(2.0), row);
        assertEquals(2.0, layer("b").base().outPoint(), 1e-9);
        drag(tracks, x(1.0), row, x(1.5), row);
        assertEquals(1.0, layer("b").base().inPoint(), 1e-9);
        assertEquals(2.5, layer("b").base().outPoint(), 1e-9);
        assertEquals("Move Layer Range", doc.getUndoName());
        assertEquals(List.of("b"), selection());
        // the range stays inside the duration and at least one frame long
        drag(tracks, x(1.5), row, x(1.5) + 3000, row);
        assertEquals(3.0, layer("b").base().outPoint(), 1e-9);
        assertEquals(1.5, layer("b").base().inPoint(), 1e-9);
        drag(tracks, x(1.5) + 1, row, x(1.5) + 3000, row);
        assertEquals(3 - 1 / 60.0, layer("b").base().inPoint(), 1e-9);
    }

    // ------------------------------------------------------------------ markers

    @Test
    void markersAreCreatedMovedResizedRenamedAndDeleted() {
        MarkerLane lane = timeline.getMarkerLane();
        playhead(1.0);
        fire("#" + LayerTreeColumn.ADD_MARKER_ID);
        assertEquals(List.of(new Marker("marker1", 1.0, 1.5)), doc.getAnimation().markers());
        assertEquals("marker1", timeline.getSelectedMarker());

        doubleClick(lane, x(2.0), 8);
        assertEquals(new Marker("marker2", 2.0, 2.5), doc.getAnimation().markers().get(1));

        // body
        drag(lane, x(1.25), 8, x(0.75), 8);
        assertEquals(new Marker("marker1", 0.5, 1.0), doc.getAnimation().markers().get(0));
        assertEquals("Move Marker", doc.getUndoName());
        // end
        drag(lane, x(1.0) - 1, 8, x(1.5), 8);
        assertEquals(new Marker("marker1", 0.5, 1.5), doc.getAnimation().markers().get(0));
        // start, as one undo step
        drag(lane, x(0.5) + 1, 8, x(0.25), 8);
        assertEquals(0.25, doc.getAnimation().markers().get(0).start(), 1e-9);
        run(doc::undo);
        assertEquals(new Marker("marker1", 0.5, 1.5), doc.getAnimation().markers().get(0));

        // rename: a taken name is refused inline
        doubleClick(lane, x(1.0), 8);
        TextField field = lookup("#" + MarkerLane.RENAME_FIELD_ID);
        Label error = lookup("#" + MarkerLane.RENAME_ERROR_ID);
        assertTrue(field.isVisible());
        run(() -> {
            field.setText("marker2");
            Event.fireEvent(field, new ActionEvent());
        });
        assertTrue(fx(error::isVisible));
        assertTrue(fx(field::isVisible));
        assertEquals("marker1", doc.getAnimation().markers().get(0).name());
        run(() -> {
            field.setText("intro");
            Event.fireEvent(field, new ActionEvent());
        });
        layout();
        assertFalse(field.isVisible());
        assertEquals("intro", doc.getAnimation().markers().get(0).name());
        assertEquals("Rename Marker", doc.getUndoName());
        com.dlsc.animatorfx.editor.doc.RoundTrip.of(doc.getAnimation());

        // delete the selected marker with the lane focused
        click(lane, x(2.25), 8);
        assertEquals("marker2", timeline.getSelectedMarker());
        key(KeyCode.DELETE);
        assertEquals(List.of(new Marker("intro", 0.5, 1.5)), doc.getAnimation().markers());
        assertEquals(3, layerIds().size());
    }

    // ------------------------------------------------------------------ zoom and scroll

    @Test
    void shortcutScrollZoomsAtThePointerAndFitShowsEverything() {
        double before = x(1.5);
        double pps = fx(() -> timeline.getScale().pixelsPerSecond());
        scroll(tracks, before, 20, 0, 120, Keys.SHORTCUT);
        assertTrue(doc.getViewState().getTimelineZoom() > 1.3);
        assertTrue(fx(() -> timeline.getScale().pixelsPerSecond()) > pps);
        assertEquals(before, x(1.5), 0.5, "the time under the pointer stays put");

        fire("#" + LayerTreeColumn.ZOOM_FIT_ID);
        assertEquals(1.0, doc.getViewState().getTimelineZoom(), 1e-9);
        assertEquals(0.0, doc.getViewState().getTimelineScroll(), 1e-9);

        fire("#" + LayerTreeColumn.ZOOM_IN_ID);
        fire("#" + LayerTreeColumn.ZOOM_IN_ID);
        assertEquals(2.25, doc.getViewState().getTimelineZoom(), 1e-9);
        // horizontal scrolling moves the content, and stops at its ends
        double left = x(1.0);
        scroll(tracks, 100, 20, -60, 0, Keys.NONE);
        assertEquals(left - 60, x(1.0), 0.5);
        scroll(tracks, 100, 20, 0, -100000, Keys.SHIFT);
        assertEquals(fx(() -> tracks.getWidth()) - TimeScale.END_SLACK, x(3.0), 0.5);
        fire("#" + LayerTreeColumn.ZOOM_OUT_ID);
        assertEquals(1.5, doc.getViewState().getTimelineZoom(), 1e-9);
    }

    @Test
    void theViewFollowsThePlayheadWhenItLeavesTheVisiblePart() {
        fire("#" + LayerTreeColumn.ZOOM_IN_ID);
        fire("#" + LayerTreeColumn.ZOOM_IN_ID);
        run(() -> doc.getViewState().setTimelineScroll(0));
        layout();
        double width = fx(() -> tracks.getWidth());
        assertTrue(x(2.5) > width, "2.5 s is out of view");
        playhead(2.5);
        assertTrue(x(2.5) >= 0 && x(2.5) <= width, "the view moved to the playhead");
        playhead(0.1);
        assertTrue(x(0.1) >= 0 && x(0.1) <= width);
        // a playhead that stays in view does not move the view
        double scroll = doc.getViewState().getTimelineScroll();
        playhead(0.2);
        assertEquals(scroll, doc.getViewState().getTimelineScroll(), 1e-9);
    }

    private void scroll(Node node, double x, double y, double dx, double dy, Keys keys) {
        run(() -> {
            var scene = node.localToScene(x, y);
            boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
            Event.fireEvent(node, new ScrollEvent(ScrollEvent.SCROLL, scene.getX(), scene.getY(), scene.getX(),
                    scene.getY(), keys.shift(), !mac && keys.shortcut(), keys.alt(), mac && keys.shortcut(), false,
                    false, dx, dy, dx, dy, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                    ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
        });
        layout();
    }

    // ------------------------------------------------------------------ colours

    @Test
    void thePaletteResolvesTheLookedUpColoursOfTheTheme() {
        Palette palette = (Palette) fx(() -> timeline.lookup(".palette"));
        run(() -> window.getTheme().setMode(com.dlsc.animatorfx.editor.ui.Theme.Mode.DARK));
        layout();
        assertEquals(Color.web("#ffb454"), fx(() -> palette.get(Palette.Tone.KEY)));
        assertEquals(Color.web("#ff5c5c"), fx(() -> palette.get(Palette.Tone.PLAYHEAD)));
        assertEquals(0.16, fx(() -> palette.get(Palette.Tone.ACCENT_SOFT)).getOpacity(), 0.01);
        run(() -> window.getTheme().setMode(com.dlsc.animatorfx.editor.ui.Theme.Mode.LIGHT));
        layout();
        assertEquals(Color.web("#d9730d"), fx(() -> palette.get(Palette.Tone.KEY)));
    }
}
