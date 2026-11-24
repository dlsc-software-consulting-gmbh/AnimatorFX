package com.dlsc.animatorfx.editor.canvas;

import com.dlsc.animatorfx.editor.canvas.tools.NewLayers;
import com.dlsc.animatorfx.editor.canvas.tools.TextTool;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.doc.RoundTrip;
import com.dlsc.animatorfx.editor.doc.SaveableCommandsTest;
import com.dlsc.animatorfx.editor.ui.Formats;
import com.dlsc.animatorfx.editor.ui.PngEncoder;
import com.dlsc.animatorfx.editor.ui.ToolKind;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Easing;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.ImageLayer;
import com.dlsc.animatorfx.model.Keyframe;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.SlotLayer;
import com.dlsc.animatorfx.model.TextLayer;
import com.dlsc.animatorfx.model.Transform;
import com.dlsc.animatorfx.model.Vec2;
import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.ScrollEvent;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Edit and View commands of the canvas, playback, and their wiring into menus and toolbar.
 */
class CanvasActionsTest extends CanvasTestBase {

    private List<String> ids() {
        return layers().stream().map(Layer::id).toList();
    }

    private void fire(String menuItemId) {
        run(() -> window.menuItem(menuItemId).fire());
        layout();
    }

    private String zoomLabel() {
        return fx(() -> ((Label) window.getScene().lookup("#zoom")).getText());
    }

    private void contextMenu(double x, double y) {
        Vec2 at = view(x, y);
        run(() -> {
            var scene = canvas.localToScene(at.x(), at.y());
            var screen = canvas.localToScreen(at.x(), at.y());
            Event.fireEvent(canvas, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    scene.getX(), scene.getY(), screen.getX(), screen.getY(), false, null));
        });
        layout();
    }

    private MenuItem contextItem(String id) {
        return fx(() -> canvas.getContextMenu().getItems().stream()
                .filter(item -> id.equals(item.getId())).findFirst().orElseThrow());
    }

    private void fireContext(String id) {
        MenuItem item = contextItem(id);
        run(item::fire);
        layout();
    }

    @Test
    void canvasContextMenuGroupsAndUngroupsTheSelectedLayers() {
        add(rect("a", 100, 100, 100, 100), rect("b", 300, 100, 100, 100));
        select("a", "b");
        contextMenu(150, 150);
        assertEquals(List.of("a", "b"), selection());
        assertFalse(contextItem("canvas-menu-group").isDisable());
        assertTrue(contextItem("canvas-menu-ungroup").isDisable());
        fireContext("canvas-menu-group");
        GroupLayer group = (GroupLayer) layers().get(0);
        assertEquals(List.of("a", "b"), group.children().stream().map(Layer::id).toList());
        assertEquals("Group", doc.getUndoName());

        contextMenu(150, 150);
        fireContext("canvas-menu-ungroup");
        assertEquals(List.of("a", "b"), ids());
        assertEquals("Ungroup", doc.getUndoName());
        run(doc::undo);
        assertTrue(layers().get(0) instanceof GroupLayer);
    }

    @Test
    void canvasContextMenuClipboardAndOrderActionsUseTheExistingCommands() {
        add(rect("a", 100, 100, 100, 100), rect("b", 300, 100, 100, 100),
                rect("c", 500, 100, 100, 100));
        contextMenu(150, 150);
        assertEquals(List.of("a"), selection());
        assertFalse(contextItem("canvas-menu-copy").isDisable());
        assertTrue(contextItem("canvas-menu-paste").isDisable());
        fireContext("canvas-menu-bring-forward");
        assertEquals(List.of("b", "a", "c"), ids());
        fireContext("canvas-menu-bring-to-front");
        assertEquals(List.of("b", "c", "a"), ids());
        fireContext("canvas-menu-send-backward");
        assertEquals(List.of("b", "a", "c"), ids());
        fireContext("canvas-menu-send-to-back");
        assertEquals(List.of("a", "b", "c"), ids());

        fireContext("canvas-menu-copy");
        select();
        contextMenu(700, 500);
        assertFalse(contextItem("canvas-menu-paste").isDisable());
        assertTrue(contextItem("canvas-menu-cut").isDisable());
        fireContext("canvas-menu-paste");
        assertEquals(4, layers().size());
        contextMenu(700, 500);
        fireContext("canvas-menu-duplicate");
        assertEquals(5, layers().size());
        contextMenu(700, 500);
        fireContext("canvas-menu-cut");
        assertEquals(4, layers().size());
        assertEquals("Cut", doc.getUndoName());
    }

    // ------------------------------------------------------------------ edit menu

    @Test
    void theLayerItemsOfTheEditMenuFollowTheSelection() {
        add(rect("a", 100, 100, 100, 100));
        assertTrue(window.menuItem("menu-edit-delete").isDisable());
        assertTrue(window.menuItem("menu-edit-group").isDisable());

        select("a");

        for (String id : List.of("menu-edit-delete", "menu-edit-duplicate", "menu-edit-group", "menu-edit-ungroup",
                "menu-edit-bring-forward", "menu-edit-send-to-back")) {
            assertFalse(window.menuItem(id).isDisable(), id);
        }
    }

    @Test
    void deleteAndSelectAllFromTheMenu() {
        Layer locked = rect("locked", 500, 100, 100, 100);
        add(rect("a", 100, 100, 100, 100), rect("b", 300, 100, 100, 100), locked.withBase(locked.base().withLocked(true)));

        fire("menu-edit-select-all");
        assertEquals(List.of("a", "b"), selection());

        fire("menu-edit-delete");
        assertEquals(List.of("locked"), ids());
        assertEquals("Delete Layers", doc.getUndoName());
    }

    @Test
    void duplicateSelectsTheCopy() {
        add(rect("a", 100, 100, 100, 100), rect("b", 300, 100, 100, 100));
        select("a");

        fire("menu-edit-duplicate");

        assertEquals(3, layers().size());
        assertEquals("a", ids().get(0));
        assertEquals("a copy", layers().get(1).name());
        assertEquals(List.of(layers().get(1).id()), selection());
        assertEquals(new Vec2(160, 160), position(layers().get(1).id()));

        fire("menu-edit-duplicate");
        assertEquals(new Vec2(170, 170), position(selection().get(0)));
        run(doc::undo);
        assertEquals(3, layers().size());
    }

    @Test
    void duplicatedAndPastedSlotsCanBeSavedAndOpenedAgain() {
        add(NewLayers.slot(doc.getAnimation(), "card", new Vec2(300, 300), new Vec2(50, 50)));
        select(layers().get(0).id());

        fire("menu-edit-duplicate");
        fire("menu-edit-copy");
        fire("menu-edit-paste");
        fire("menu-edit-paste");
        fire("menu-edit-select-all");
        fire("menu-edit-group");
        fire("menu-edit-duplicate");

        List<String> names = com.dlsc.animatorfx.model.Layers.slots(doc.getAnimation()).stream()
                .map(SlotLayer::slotName).toList();
        assertEquals(8, names.size());
        assertEquals(8, new java.util.HashSet<>(names).size(), names.toString());
        RoundTrip.of(doc.getFileContent());
    }

    @Test
    void groupingAndPastingBeyondTheNestingLimitAreRefusedWithANotice() {
        add(SaveableCommandsTest.nested("g", 100), rect("a", 100, 100, 100, 100));
        String limit = "Layers cannot be nested deeper than 100 levels.";

        select("g-leaf");
        fire("menu-edit-group");
        assertEquals(List.of(limit), fx(() -> window.getNoticeBar().getMessages()));
        assertEquals("Add Layer", doc.getUndoName());
        assertEquals(List.of("g-leaf"), selection());

        // a rectangle fits into the innermost group, a group does not
        select("g1");
        fire("menu-edit-copy");
        run(() -> canvas.enterGroup("g99"));
        fire("menu-edit-paste");
        assertEquals(2, fx(() -> window.getNoticeBar().getMessages().size()));
        assertEquals("Add Layer", doc.getUndoName());

        select("a");
        fire("menu-edit-copy");
        fire("menu-edit-paste");
        assertEquals("Paste", doc.getUndoName());
        RoundTrip.of(doc.getFileContent());
    }

    @Test
    void groupWrapsTheSelectionAndUngroupRestoresIt() {
        add(rect("a", 100, 100, 100, 100), rect("b", 300, 100, 100, 100), rect("c", 500, 100, 100, 100));
        select("c", "a");

        fire("menu-edit-group");

        assertEquals(2, layers().size());
        GroupLayer group = (GroupLayer) layers().get(1);
        assertEquals("Group 1", group.name());
        assertEquals(List.of("a", "c"), group.children().stream().map(Layer::id).toList());
        assertEquals(List.of(group.id()), selection());
        Box box = fx(() -> canvas.getGeometry().viewBounds(group.id()).orElseThrow());
        assertEquals(500, box.width(), 1e-6);

        fire("menu-edit-ungroup");

        assertEquals(List.of("b", "a", "c"), ids());
        assertEquals(List.of("a", "c"), selection());
        assertEquals("Ungroup", doc.getUndoName());
    }

    @Test
    void ungroupOfAnAnimatedTransformedGroupIsRefusedWithANotice() {
        Animatable<Vec2> moving = Animatable.keyframed(List.of(
                new Keyframe<>("k1", 0, Vec2.ZERO, Easing.LINEAR), new Keyframe<>("k2", 1, new Vec2(50, 0), Easing.LINEAR)));
        GroupLayer group = new GroupLayer(LayerBase.create("Intro", 3).withId("g")
                .withTransform(Transform.IDENTITY.withPosition(moving)), List.of(rect("a", 100, 100, 100, 100)));
        add(group);
        select("g");

        fire("menu-edit-ungroup");

        assertEquals(List.of("g"), ids());
        List<String> notices = fx(() -> window.getNoticeBar().getMessages());
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).contains("Intro") && notices.get(0).contains("animated"), notices.get(0));
        assertEquals("Add Layer", doc.getUndoName());
    }

    @Test
    void ungroupFoldsTheGroupTransformIntoTheChildren() {
        GroupLayer group = new GroupLayer(LayerBase.create("g", 3).withId("g")
                .withTransform(Transform.at(400, 300).withRotation(Animatable.of(90.0))),
                List.of(rect("a", 0, 0, 100, 60)));
        add(group);
        Quad before = fx(() -> canvas.getGeometry().quad("a").orElseThrow());
        select("g");

        fire("menu-edit-ungroup");
        layout();

        Quad after = fx(() -> canvas.getGeometry().quad("a").orElseThrow());
        for (int i = 0; i < 4; i++) {
            assertEquals(before.corners().get(i).x(), after.corners().get(i).x(), 1e-6);
            assertEquals(before.corners().get(i).y(), after.corners().get(i).y(), 1e-6);
        }
        assertEquals(90, layer("a").base().transform().rotation().at(0), 1e-9);
    }

    @Test
    void theStackingOrderItemsMoveTheSelection() {
        add(rect("a", 100, 100, 100, 100), rect("b", 300, 100, 100, 100), rect("c", 500, 100, 100, 100));
        select("a");

        fire("menu-edit-bring-forward");
        assertEquals(List.of("b", "a", "c"), ids());
        fire("menu-edit-bring-to-front");
        assertEquals(List.of("b", "c", "a"), ids());
        fire("menu-edit-send-backward");
        assertEquals(List.of("b", "a", "c"), ids());
        fire("menu-edit-send-to-back");
        assertEquals(List.of("a", "b", "c"), ids());
        assertEquals(List.of("a"), selection());
    }

    // ------------------------------------------------------------------ clipboard

    @Test
    void copyAndPasteGiveNewIdsAndOffsetEachPasteBy10Pixels() {
        add(rect("a", 100, 100, 100, 100), rect("b", 300, 100, 100, 100));
        select("a");

        fire("menu-edit-copy");
        fire("menu-edit-paste");

        assertEquals(3, layers().size());
        Layer first = layers().get(2);
        assertNotEquals("a", first.id());
        assertEquals(new Vec2(160, 160), first.base().transform().position().at(0));
        assertEquals(List.of(first.id()), selection());
        assertEquals("Paste", doc.getUndoName());

        fire("menu-edit-paste");
        assertEquals(new Vec2(170, 170), layers().get(3).base().transform().position().at(0));
        assertEquals(new Vec2(150, 150), position("a"));
    }

    @Test
    void cutRemovesTheLayersAndPasteBringsThemBack() {
        add(rect("a", 100, 100, 100, 100), rect("b", 300, 100, 100, 100));
        select("a", "b");

        fire("menu-edit-cut");
        assertTrue(layers().isEmpty());
        assertEquals("Cut", doc.getUndoName());

        fire("menu-edit-paste");
        assertEquals(2, layers().size());
        assertEquals(List.of("a", "b"), layers().stream().map(Layer::name).toList());
        assertEquals(2, selection().size());
    }

    @Test
    void pasteIntoAnotherDocumentCopiesTheAssetsItNeeds() throws IOException {
        Path file = Files.write(dir.resolve("logo.png"), PngEncoder.solid(40, 20, 0xFFFF0000));
        String imageId = fx(() -> canvas.addImage(file)).orElseThrow();
        String assetId = ((ImageLayer) layer(imageId)).assetId();
        add(NewLayers.slot(doc.getAnimation(), "slot1", new Vec2(300, 300), new Vec2(50, 50)));
        run(() -> doc.getSelection().layerIds().setAll(imageId, layers().get(1).id()));
        fire("menu-edit-copy");

        EditorDocument other = fx(() -> window.getFileActions().newDocument());
        layout();
        assertEquals(other, canvas.getDocument());
        run(() -> other.execute(com.dlsc.animatorfx.editor.doc.Commands.addLayer(null, 0,
                NewLayers.slot(other.getAnimation(), "slot1", new Vec2(100, 100), new Vec2(50, 50)))));
        fire("menu-edit-paste");

        assertEquals(3, other.getAnimation().layers().size());
        assertEquals(1, other.getAnimation().assets().size());
        assertEquals(assetId, other.getAnimation().assets().get(0).id());
        assertEquals(Files.readAllBytes(file).length, other.getFileContent().assets().get(assetId).length);
        // the slot name is taken in the other document
        assertEquals("slot2", ((SlotLayer) other.getAnimation().layers().get(2)).slotName());
        RoundTrip.of(other.getFileContent());

        run(other::undo);
        assertTrue(other.getAnimation().assets().isEmpty());
        assertEquals(1, other.getAnimation().layers().size());
    }

    // ------------------------------------------------------------------ zoom and pan

    @Test
    void zoomKeepsThePointUnderTheAnchorAndStaysInRange() {
        Vec2 anchor = new Vec2(120, 90);
        Vec2 under = fx(() -> canvas.viewToDocument(anchor));

        run(() -> canvas.zoomTo(3, anchor));
        Vec2 after = fx(() -> canvas.documentToView(under));
        assertEquals(3, canvas.getZoom());
        assertEquals(anchor.x(), after.x(), 1);
        assertEquals(anchor.y(), after.y(), 1);
        assertEquals(Formats.zoom(3), zoomLabel());

        run(() -> canvas.zoomTo(1000, anchor));
        assertEquals(CanvasView.MAX_ZOOM, canvas.getZoom());
        run(() -> canvas.zoomTo(0.01, anchor));
        assertEquals(CanvasView.MIN_ZOOM, canvas.getZoom());
    }

    @Test
    void theViewMenuZoomsInStepsFitsAndReturnsToActualSize() {
        fire("menu-view-zoom-in");
        assertEquals(1.5, canvas.getZoom());
        fire("menu-view-zoom-in");
        assertEquals(2, canvas.getZoom());
        fire("menu-view-zoom-out");
        fire("menu-view-zoom-out");
        fire("menu-view-zoom-out");
        assertEquals(0.67, canvas.getZoom());

        fire("menu-view-zoom-fit");
        double fit = canvas.getZoom();
        Vec2 topLeft = view(0, 0);
        Vec2 bottomRight = view(800, 600);
        assertTrue(topLeft.x() >= 40 && topLeft.y() >= 40, "margin around the artboard: " + topLeft);
        assertTrue(bottomRight.x() <= canvas.getWidth() - 40 && bottomRight.y() <= canvas.getHeight() - 40);
        assertEquals(0, doc.getViewState().getCanvasPanX());
        assertEquals(Formats.zoom(fit), zoomLabel());

        fire("menu-view-actual-size");
        assertEquals(1, canvas.getZoom());
        // the middle of the canvas stayed where it was: the artboard is still centred
        assertEquals(canvas.getWidth() / 2, view(400, 300).x(), 1);
    }

    @Test
    void headerZoomButtonsChangeTheCanvasZoom() {
        run(() -> ((Button) window.getScene().lookup("#zoom-in")).fire());
        assertEquals(1.5, canvas.getZoom());
        run(() -> ((Button) window.getScene().lookup("#zoom-out")).fire());
        assertEquals(1, canvas.getZoom());
        run(() -> ((Button) window.getScene().lookup("#zoom-fit")).fire());
        assertTrue(view(0, 0).x() >= 40);
        assertTrue(view(800, 600).x() <= canvas.getWidth() - 40);
        run(() -> ((Button) window.getScene().lookup("#zoom-actual-size")).fire());
        assertEquals(1, canvas.getZoom());
    }

    @Test
    void zoomInMenuUsesShortcutPlus() {
        assertEquals(new KeyCodeCombination(KeyCode.PLUS, KeyCombination.SHORTCUT_DOWN),
                window.menuItem("menu-view-zoom-in").getAccelerator());
    }

    @Test
    void scrollingPansAndShortcutScrollingZoomsAtThePointer() {
        Vec2 before = view(0, 0);

        run(() -> Event.fireEvent(canvas, scroll(30, -20, false)));
        layout();
        Vec2 panned = view(0, 0);
        assertEquals(before.x() + 30, panned.x(), 1e-6);
        assertEquals(before.y() - 20, panned.y(), 1e-6);

        run(() -> Event.fireEvent(canvas, scroll(0, 100, true)));
        assertTrue(canvas.getZoom() > 1, "scrolling up with the shortcut key zooms in");
    }

    private ScrollEvent scroll(double dx, double dy, boolean shortcut) {
        javafx.geometry.Point2D at = canvas.localToScene(100, 100);
        return new ScrollEvent(ScrollEvent.SCROLL, at.getX(), at.getY(), at.getX(), at.getY(), false, shortcut, false,
                false, false, false, dx, dy, dx, dy, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null);
    }

    @Test
    void theArtboardIsDrawnAtTheZoomWithTheAnimationViewInside() {
        run(() -> canvas.zoomTo(2, new Vec2(0, 0)));
        layout();

        javafx.scene.layout.Region artboard = (javafx.scene.layout.Region) fx(() -> canvas.lookup(".artboard"));
        assertEquals(1600, artboard.getWidth(), 1);
        assertEquals(view(0, 0).x(), artboard.getLayoutX(), 1e-6);
        assertEquals(800, canvas.getAnimationView().getWidth(), 1e-6);
        javafx.geometry.Bounds inCanvas = fx(() -> canvas.sceneToLocal(
                canvas.getAnimationView().localToScene(canvas.getAnimationView().getBoundsInLocal())));
        assertEquals(1600, inCanvas.getWidth(), 1e-6);
        assertEquals(view(0, 0).x(), inCanvas.getMinX(), 1e-6);
    }

    // ------------------------------------------------------------------ playback

    @Test
    void thePlayButtonPlaysFromThePlayheadAndStopsAtTheEnd() throws Exception {
        add(rect("a", 100, 100, 100, 100));
        run(() -> doc.setPlayhead(2.6));
        Button play = (Button) fx(() -> window.getScene().lookup("#transport-play"));
        assertFalse(play.isDisable());

        run(play::fire);
        assertTrue(canvas.isPlaying());
        assertTrue(fx(() -> window.getToolBar().isPlaying()));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !canvas.isPlaying());

        assertEquals(3, doc.getPlayhead(), 1e-9);
        assertFalse(fx(() -> window.getToolBar().isPlaying()));
    }

    @Test
    void theSpaceBarTogglesPlaybackAndThePlayheadFollows() throws Exception {
        add(rect("a", 100, 100, 100, 100));
        run(() -> {
            canvas.requestFocus();
            doc.setPlayhead(0.5);
        });

        key(KeyCode.SPACE);
        assertTrue(canvas.isPlaying());
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> doc.getPlayhead() > 0.6);

        key(KeyCode.SPACE);
        assertFalse(canvas.isPlaying());
        double paused = doc.getPlayhead();
        WaitForAsyncUtils.sleep(150, TimeUnit.MILLISECONDS);
        assertEquals(paused, doc.getPlayhead(), 1e-9);
        assertEquals(paused, canvas.getTime(), 1e-9);
    }

    @Test
    void loopingKeepsPlayingPastTheEnd() throws Exception {
        add(rect("a", 100, 100, 100, 100));
        run(() -> {
            window.getToolBar().setLoop(true);
            doc.setPlayhead(2.8);
            canvas.togglePlayback();
        });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> doc.getPlayhead() < 2);

        assertTrue(canvas.isPlaying());
        run(canvas::togglePlayback);
        assertFalse(canvas.isPlaying());
    }

    @Test
    void spaceHeldWhileDraggingPansInsteadOfPlaying() {
        add(rect("a", 100, 100, 100, 100));
        Vec2 before = view(0, 0);

        run(() -> Event.fireEvent(canvas, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                " ", " ", KeyCode.SPACE, false, false, false, false)));
        pressView(new Vec2(200, 200), Keys.NONE);
        dragView(new Vec2(240, 230), Keys.NONE);
        releaseView(new Vec2(240, 230), Keys.NONE);
        run(() -> Event.fireEvent(canvas, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_RELEASED,
                " ", " ", KeyCode.SPACE, false, false, false, false)));
        layout();

        assertFalse(canvas.isPlaying());
        assertEquals(before.x() + 40, view(0, 0).x(), 1e-6);
        assertEquals(before.y() + 30, view(0, 0).y(), 1e-6);
        assertEquals(new Vec2(150, 150), position("a"));
        assertTrue(selection().isEmpty());
    }

    @Test
    void aPointerGestureDuringPlaybackPausesIt() {
        add(rect("a", 100, 100, 100, 100));
        run(canvas::togglePlayback);
        assertTrue(canvas.isPlaying());

        click(150, 150);

        assertFalse(canvas.isPlaying());
        assertEquals(List.of("a"), selection());
    }

    @Test
    void theCanvasFollowsTheActiveDocument() {
        add(rect("a", 100, 100, 100, 100));
        select("a");
        run(() -> canvas.enterGroup(null));

        EditorDocument other = fx(() -> window.getFileActions().newDocument());
        layout();

        assertEquals(other, canvas.getDocument());
        assertNull(canvas.getEnteredGroupId());
        assertFalse(fx(() -> canvas.lookup(".overlay-selection")).isVisible());
    }

    @Test
    void switchingTheDocumentCommitsTheTextBeingEditedToItsOwnDocument() {
        TextLayer text = NewLayers.text(doc.getAnimation(), new Vec2(200, 150), "Title");
        add(text);
        run(() -> ((TextTool) canvas.getTool(ToolKind.TEXT)).edit(text.id()));
        TextField editor = (TextField) fx(() -> canvas.lookup("#" + TextTool.EDITOR_ID));
        run(() -> editor.setText("Kept"));

        EditorDocument other = fx(() -> window.getFileActions().newDocument());
        layout();

        assertEquals("Kept", ((TextLayer) layer(text.id())).text());
        assertTrue(other.getAnimation().layers().isEmpty());
        assertNull(fx(() -> canvas.lookup("#" + TextTool.EDITOR_ID)));
    }
}
