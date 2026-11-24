package com.dlsc.animatorfx.editor.canvas;

import com.dlsc.animatorfx.editor.canvas.PathOps.VertexRef;
import com.dlsc.animatorfx.editor.canvas.tools.MotionPathTool;
import com.dlsc.animatorfx.editor.canvas.tools.NewLayers;
import com.dlsc.animatorfx.editor.canvas.tools.PathEditTool;
import com.dlsc.animatorfx.editor.canvas.tools.PenTool;
import com.dlsc.animatorfx.editor.canvas.tools.Pointer;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.ui.ToolKind;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.Keyframe;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.Mask;
import com.dlsc.animatorfx.model.MotionPath;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.PathData;
import com.dlsc.animatorfx.model.PathData.Contour;
import com.dlsc.animatorfx.model.PathData.Vertex;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.Stroke;
import com.dlsc.animatorfx.model.Transform;
import com.dlsc.animatorfx.model.Vec2;
import javafx.geometry.Bounds;
import javafx.scene.Cursor;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pen, path editing, convert-to-path and motion paths on the canvas, driven by mouse and key
 * events under the headless toolkit.
 */
class PathToolsTest extends CanvasTestBase {

    private static final String PATH = "geometry.path";

    private static void assertVec(double x, double y, Vec2 actual) {
        assertEquals(x, actual.x(), 1e-6, "x of " + actual);
        assertEquals(y, actual.y(), 1e-6, "y of " + actual);
    }

    private static Vertex corner(double x, double y) {
        return new Vertex(new Vec2(x, y), Vec2.ZERO, Vec2.ZERO);
    }

    /** An open, stroked path layer whose local coordinates are the document's shifted by (200, 200). */
    private static ShapeLayer pathLayer(String id, Vertex... vertices) {
        LayerBase base = LayerBase.create(id, 3).withId(id)
                .withTransform(Transform.IDENTITY.withPosition(Animatable.of(new Vec2(200, 200))));
        Stroke stroke = new Stroke(Animatable.of(new Paint.Solid(Rgba.WHITE)), Animatable.of(2.0), Stroke.Cap.ROUND,
                Stroke.Join.ROUND, 10, List.of(), Animatable.of(0.0));
        return new ShapeLayer(base, new Geometry.PathGeometry(Animatable.of(
                new PathData(List.of(new Contour(List.of(vertices), false))))), Animatable.of(new Paint.None()), stroke, null);
    }

    private static ShapeLayer angle(String id) {
        return pathLayer(id, corner(0, 0), corner(100, 0), corner(100, 100));
    }

    private PathData path(String id) {
        return ((Geometry.PathGeometry) ((ShapeLayer) layer(id)).geometry()).path().at(doc.getPlayhead());
    }

    private List<Vertex> vertices(String id) {
        return path(id).contours().get(0).vertices();
    }

    private PenTool pen() {
        return (PenTool) fx(() -> canvas.getTool(ToolKind.PEN));
    }

    private PathEditTool editor() {
        return assertInstanceOf(PathEditTool.class, fx(canvas::getModalTool));
    }

    private void editPath(String id) {
        run(() -> assertTrue(canvas.editPath(id)));
        layout();
    }

    private void altClick(double x, double y) {
        click(x, y, Keys.ALT);
    }

    // ------------------------------------------------------------------ pen

    @Test
    void threePenClicksAndEnterCreateAPathLayerWithThreeVertices() {
        tool(ToolKind.PEN);
        assertEquals("Pen", fx(() -> canvas.getOverlay().getModeHint()));
        click(200, 220);
        assertEquals(1, layers().size(), "the layer exists from the first click");
        click(200, 100);
        click(100, 100);
        key(KeyCode.ENTER);

        assertEquals(1, layers().size());
        ShapeLayer shape = (ShapeLayer) layers().get(0);
        assertEquals("Path 1", shape.name());
        Contour contour = path(shape.id()).contours().get(0);
        assertFalse(contour.closed());
        // the coordinates of the finished path start at the top-left of its box, not at its first point
        assertEquals(List.of(corner(100, 120), corner(100, 0), corner(0, 0)), contour.vertices());
        // the anchor is in the middle of the box and the points are where they were clicked
        assertVec(50, 60, shape.base().transform().anchor().at(0));
        assertVec(150, 160, shape.base().transform().position().at(0));
        assertTrue(shape.fill().at(0) instanceof Paint.None);
        assertEquals(new Paint.Solid(NewLayers.SHAPE_COLOR), shape.stroke().paint().at(0));
        assertEquals(2, shape.stroke().width().at(0));
        assertEquals(List.of(shape.id()), selection());
        assertEquals(ToolKind.SELECT, canvas.getActiveTool());
        assertNull(fx(() -> canvas.getOverlay().getModeHint()));

        run(doc::undo);
        assertTrue(layers().isEmpty(), "drawing the whole path is one undo step");
        assertFalse(doc.isCanUndo());
    }

    @Test
    void pressAndDragMakesASmoothVertexAndAltBreaksTheSymmetry() {
        tool(ToolKind.PEN);
        click(100, 100);
        press(200, 100);
        drag(220, 115);
        drag(240, 130);
        release(240, 130);
        press(300, 200);
        drag(340, 200);
        dragView(view(350, 240), Keys.ALT);
        releaseView(view(350, 240), Keys.ALT);
        key(KeyCode.ENTER);

        List<Vertex> vertices = vertices(layers().get(0).id());
        assertVec(40, 30, vertices.get(1).out());
        assertVec(-40, -30, vertices.get(1).in());
        assertVec(50, 40, vertices.get(2).out());
        assertVec(-40, 0, vertices.get(2).in());
        assertTrue(PathOps.isCorner(vertices.get(0)));
    }

    @Test
    void shiftKeepsANewSegmentOn45DegreeSteps() {
        tool(ToolKind.PEN);
        click(100, 100);
        click(200, 108, Keys.SHIFT);
        click(290, 205, Keys.SHIFT);
        key(KeyCode.ENTER);

        ShapeLayer shape = (ShapeLayer) layers().get(0);
        List<Vertex> vertices = vertices(shape.id());
        Vec2 second = vertices.get(1).point().minus(vertices.get(0).point());
        Vec2 third = vertices.get(2).point().minus(vertices.get(1).point());
        assertEquals(0, second.y(), 1e-6);
        assertEquals(third.x(), third.y(), 1e-6);
    }

    @Test
    void aClickOnTheFirstVertexClosesThePathAndGivesItAFill() {
        tool(ToolKind.PEN);
        click(100, 100);
        click(200, 100);
        click(150, 180);
        move(250, 250);
        assertFalse(pen().getOverlay().isCloseTargetShown());
        move(103, 102);
        assertTrue(pen().getOverlay().isCloseTargetShown(), "the first vertex shows that a click closes");
        click(103, 102);

        ShapeLayer shape = (ShapeLayer) layers().get(0);
        Contour contour = path(shape.id()).contours().get(0);
        assertTrue(contour.closed());
        assertEquals(3, contour.vertices().size());
        assertEquals(new Paint.Solid(NewLayers.SHAPE_COLOR), shape.fill().at(0));
        assertNull(shape.stroke());
        assertEquals(ToolKind.SELECT, canvas.getActiveTool());
        assertFalse(pen().isDrawing());
        run(doc::undo);
        assertTrue(layers().isEmpty());
    }

    @Test
    void escapeDropsAPathOfOnePointAndFinishesALongerOne() {
        tool(ToolKind.PEN);
        click(100, 100);
        key(KeyCode.ESCAPE);

        assertTrue(layers().isEmpty());
        assertFalse(doc.isCanUndo());
        assertEquals(ToolKind.PEN, canvas.getActiveTool());

        click(100, 100);
        click(200, 150);
        key(KeyCode.ESCAPE);

        assertEquals(2, vertices(layers().get(0).id()).size());
        assertEquals(ToolKind.SELECT, canvas.getActiveTool());
    }

    @Test
    void backspaceRemovesTheLastVertexWhileDrawing() {
        tool(ToolKind.PEN);
        click(100, 100);
        click(200, 100);
        click(200, 220);
        key(KeyCode.BACK_SPACE);

        assertEquals(1, layers().size(), "Backspace takes a point, not the layer");
        assertEquals(2, pen().getVertexCount());
        click(260, 160);
        key(KeyCode.ENTER);

        Layer shape = layers().get(0);
        assertEquals(3, vertices(shape.id()).size());
        assertVec(260 - 100, 160 - 100, vertices(shape.id()).get(2).point());

        tool(ToolKind.PEN);
        click(500, 100);
        key(KeyCode.BACK_SPACE);
        assertEquals(1, layers().size(), "removing the only point drops the new path");
        assertFalse(pen().isDrawing());
    }

    @Test
    void aDoubleClickFinishesAnOpenPathAndTheRubberBandFollowsThePointer() {
        tool(ToolKind.PEN);
        click(100, 100);
        move(180, 140);
        assertEquals(1, pen().getOverlay().getVertexCount());
        assertNotNull(fx(() -> canvas.lookup(".path-rubber-band")));
        assertTrue(fx(() -> canvas.lookup(".path-rubber-band").isVisible()));
        doubleClick(200, 150);

        assertEquals(2, vertices(layers().get(0).id()).size());
        assertEquals(ToolKind.SELECT, canvas.getActiveTool());
        assertNull(fx(() -> canvas.lookup(".path-rubber-band")), "the drawing aids go with the path");
    }

    @Test
    void newVerticesSnapToTheArtboardUnlessControlIsHeld() {
        tool(ToolKind.PEN);
        click(100, 100);
        click(397, 200);
        click(500, 297, Keys.CONTROL);
        key(KeyCode.ENTER);

        ShapeLayer shape = (ShapeLayer) layers().get(0);
        double[] matrix = shape.base().transform().matrixAt(0);
        List<Vertex> vertices = vertices(shape.id());
        assertVec(400, 200, com.dlsc.animatorfx.editor.doc.Matrices.apply(matrix, vertices.get(1).point()));
        assertVec(500, 297, com.dlsc.animatorfx.editor.doc.Matrices.apply(matrix, vertices.get(2).point()));
    }

    @Test
    void choosingAnotherToolFinishesThePathAndKeepsThatTool() {
        tool(ToolKind.PEN);
        click(100, 100);
        click(200, 150);
        tool(ToolKind.RECTANGLE);

        assertEquals(ToolKind.RECTANGLE, canvas.getActiveTool());
        assertEquals(2, vertices(layers().get(0).id()).size());
        assertVec(50, 25, layers().get(0).base().transform().anchor().at(0));
    }

    // ------------------------------------------------------------------ path editing

    @Test
    void switchingTheDocumentWhileDrawingFinishesThePathInTheDocumentItWasDrawnIn() {
        EditorDocument first = doc;
        tool(ToolKind.PEN);
        click(200, 220);
        click(300, 100);

        EditorDocument second = fx(() -> window.getFileActions().newDocument());
        layout();

        assertEquals(second, canvas.getDocument());
        ShapeLayer path = (ShapeLayer) first.getAnimation().layers().get(0);
        // finished as Enter would: the anchor is in the middle of the box, and the path is selected
        assertVec(50, 60, path.base().transform().anchor().at(0));
        assertVec(250, 160, path.base().transform().position().at(0));
        assertEquals(List.of(path.id()), List.copyOf(first.getSelection().layerIds()));
        assertEquals(List.of(), second.getAnimation().layers());
        assertEquals(List.of(), List.copyOf(second.getSelection().layerIds()));
        assertFalse(second.isCanUndo());
    }

    @Test
    void switchingTheDocumentWhileDrawingAMotionPathLeavesTheOtherDocumentAlone() {
        EditorDocument first = doc;
        add(rect("a", 100, 100, 100, 100));
        run(() -> canvas.drawMotionPath("a"));
        click(200, 220);
        click(300, 100);

        EditorDocument second = fx(() -> window.getFileActions().newDocument());
        layout();

        assertEquals(2, first.getAnimation().layers().get(0).base().motionPath().path().contours().get(0).vertices().size());
        assertEquals("Add Motion Path", first.getUndoName());
        assertFalse(second.isCanUndo());
        assertNull(fx(canvas::getModalTool));
    }

    @Test
    void aDoubleClickOnAPathLayerEntersPathEditingAndEscapeLeavesIt() {
        add(angle("p"));
        // not the middle of an edge, where the selection has a scale handle
        doubleClick(230, 200);

        PathEditTool tool = editor();
        assertEquals(List.of("p"), selection());
        assertEquals(3, tool.getOverlay().getVertexCount());
        assertEquals(0, tool.getOverlay().getHandleCount());
        assertEquals("Path editing", fx(() -> canvas.getOverlay().getModeHint()));
        assertFalse(fx(() -> canvas.lookup(".overlay-readout").isVisible()), "the selection box makes way for the points");
        key(KeyCode.ESCAPE);
        assertNull(fx(canvas::getModalTool));
        assertEquals(List.of("p"), selection());
        assertNull(fx(() -> canvas.getOverlay().getModeHint()));
        assertTrue(fx(() -> canvas.lookup(".overlay-readout").isVisible()));

        key(KeyCode.ENTER);
        editor();
        key(KeyCode.ENTER);
        assertNull(fx(canvas::getModalTool));

        editPath("p");
        doubleClick(600, 500);
        assertNull(fx(canvas::getModalTool), "a double-click on empty canvas leaves");
        assertNull(fx(() -> canvas.lookup(".path-vertex")));
    }

    @Test
    void draggingAVertexUpdatesThePathAsOneUndoStep() {
        add(angle("p"));
        editPath("p");
        dragFromTo(300, 200, 330, 170);

        assertEquals(List.of(corner(0, 0), corner(130, -30), corner(100, 100)), vertices("p"));
        assertEquals(Set.of(new VertexRef(0, 1)), editor().getSelection());

        run(doc::undo);
        assertEquals(List.of(corner(0, 0), corner(100, 0), corner(100, 100)), vertices("p"));
        run(doc::undo);
        assertTrue(layers().isEmpty(), "the drag was a single step");
    }

    @Test
    void shiftClickAndMarqueeSelectSeveralVerticesWhichMoveTogether() {
        add(angle("p"));
        editPath("p");
        click(300, 200);
        click(300, 300, Keys.SHIFT);
        assertEquals(Set.of(new VertexRef(0, 1), new VertexRef(0, 2)), editor().getSelection());

        dragFromTo(300, 300, 320, 330);
        assertEquals(List.of(corner(0, 0), corner(120, 30), corner(120, 130)), vertices("p"));

        dragFromTo(120, 120, 180, 180);
        assertTrue(editor().getSelection().isEmpty(), "a marquee over nothing clears the selection");
        dragFromTo(180, 180, 340, 250);
        assertEquals(Set.of(new VertexRef(0, 0), new VertexRef(0, 1)), editor().getSelection());

        key(KeyCode.RIGHT);
        key(KeyCode.DOWN, true);
        assertEquals(List.of(corner(1, 10), corner(121, 40), corner(120, 130)), vertices("p"));
        run(doc::undo);
        assertEquals(corner(0, 0), vertices("p").get(0), "the nudges undo together");
    }

    @Test
    void aVertexSnapsToTheArtboardUnlessControlIsHeld() {
        add(angle("p"));
        editPath("p");
        dragFromTo(300, 200, 397, 240);
        assertVec(200, 40, vertices("p").get(1).point());
        assertFalse(fx(() -> canvas.getOverlay().getGuides()).size() > 0, "guides go away with the drag");

        dragFromTo(400, 240, 397, 250, Keys.CONTROL);
        assertVec(197, 50, vertices("p").get(1).point());
    }

    @Test
    void editingAtASecondTimeWithAutoKeyAddsAPathKeyframeAndTheShapeMorphs() {
        add(angle("p"));
        run(() -> doc.setAutoKey(true));
        editPath("p");
        dragFromTo(300, 200, 320, 200);
        run(() -> doc.setPlayhead(1));
        layout();
        editor();
        dragFromTo(320, 200, 360, 160);

        List<? extends Keyframe<?>> keyframes = ((Geometry.PathGeometry) ((ShapeLayer) layer("p")).geometry()).path().keyframes();
        assertEquals(2, keyframes.size());
        assertEquals(0, keyframes.get(0).time());
        assertEquals(1, keyframes.get(1).time());

        run(() -> doc.setPlayhead(0.5));
        layout();
        assertVec(140, -20, vertices("p").get(1).point());
        // the overlay follows the morph, and so does what is drawn
        assertEquals(3, editor().getOverlay().getVertexCount());
        Bounds drawn = fx(() -> canvas.getAnimationView().getLayerNode("p").getBoundsInLocal());
        assertEquals(140, drawn.getMaxX(), 1.5);
        assertEquals(-20, drawn.getMinY(), 1.5);

        run(doc::undo);
        assertEquals(1, ((Geometry.PathGeometry) ((ShapeLayer) layer("p")).geometry()).path().keyframes().size());
    }

    @Test
    void aClickOnASegmentInsertsAVertexAndDeleteRemovesTheSelectedOnes() {
        add(angle("p"));
        editPath("p");
        move(250, 201);
        assertTrue(editor().getOverlay().isInsertPointShown());
        assertEquals(Cursor.CROSSHAIR, fx(canvas::getCursor), "the cursor says that a click adds a point");
        click(250, 201);

        assertEquals(List.of(corner(0, 0), corner(50, 0), corner(100, 0), corner(100, 100)), vertices("p"));
        assertEquals(Set.of(new VertexRef(0, 1)), editor().getSelection());
        assertEquals(4, editor().getOverlay().getVertexCount());

        key(KeyCode.DELETE);
        assertEquals(List.of(corner(0, 0), corner(100, 0), corner(100, 100)), vertices("p"));
        assertEquals(1, layers().size(), "Delete removes points, not the layer");

        run(doc::undo);
        assertEquals(4, vertices("p").size());
        run(doc::undo);
        assertEquals(3, vertices("p").size());
    }

    @Test
    void anInsertedVertexCanBeDraggedOnInTheSameGesture() {
        add(angle("p"));
        editPath("p");
        dragFromTo(250, 200, 250, 170);

        assertEquals(List.of(corner(0, 0), corner(50, -30), corner(100, 0), corner(100, 100)), vertices("p"));
        run(doc::undo);
        assertEquals(3, vertices("p").size(), "inserting and dragging is one step");
    }

    private void keyWith(KeyCode code, boolean alt) {
        run(() -> javafx.event.Event.fireEvent(canvas, new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, false, false, alt, false)));
        layout();
    }

    @Test
    void deleteNeverRemovesTheLayerWhileItsPathIsEdited() {
        add(angle("p"));
        editPath("p");
        click(300, 200);
        keyWith(KeyCode.DELETE, true);
        assertEquals(1, layers().size(), "Alt held");

        press(300, 300);
        drag(320, 330);
        keyWith(KeyCode.BACK_SPACE, false);
        assertEquals(1, layers().size(), "in the middle of a drag");
        release(320, 330);
        assertEquals(1, layers().size());
        editor();
    }

    @Test
    void toolShortcutLettersAreIgnoredWhileAPathIsDrawnOrEdited() {
        tool(ToolKind.PEN);
        click(100, 100);
        click(200, 150);
        key(KeyCode.R);
        assertEquals(ToolKind.PEN, canvas.getActiveTool());
        assertTrue(pen().isDrawing());
        key(KeyCode.ENTER);
        assertEquals(ToolKind.SELECT, canvas.getActiveTool());

        String id = layers().get(0).id();
        editPath(id);
        key(KeyCode.P);
        key(KeyCode.S);
        editor();
        assertEquals(ToolKind.SELECT, canvas.getActiveTool());
        key(KeyCode.ESCAPE);
        key(KeyCode.R);
        assertEquals(ToolKind.RECTANGLE, canvas.getActiveTool(), "outside those modes the letters select tools");

        // the pen without a path in progress gives way as well
        tool(ToolKind.PEN);
        key(KeyCode.V);
        assertEquals(ToolKind.SELECT, canvas.getActiveTool());
    }

    @Test
    void backspaceAfterADoubleClickThatStartedNothingDoesNotThrow() {
        tool(ToolKind.PEN);
        pressView(view(100, 100), Keys.NONE);
        releaseView(view(100, 100), Keys.NONE);
        key(KeyCode.BACK_SPACE);
        assertTrue(layers().isEmpty());

        tool(ToolKind.PEN);
        run(() -> pen().pressed(new Pointer(view(300, 300), false, false, false, 2)));
        key(KeyCode.BACK_SPACE);
        key(KeyCode.ENTER);
        assertFalse(pen().isDrawing());
        assertTrue(layers().isEmpty());
    }

    @Test
    void theLastTwoPointsOfAPathCannotBeDeleted() {
        List<String> notices = new ArrayList<>();
        add(pathLayer("p", corner(0, 0), corner(100, 0)));
        run(() -> canvas.setNotifier(notices::add));
        editPath("p");
        click(300, 200);
        key(KeyCode.BACK_SPACE);

        assertEquals(2, vertices("p").size());
        assertEquals(1, notices.size());
    }

    @Test
    void altClickTogglesCornerAndSmoothAndTheHandlesDragSymmetricallyUnlessAltIsHeld() {
        add(angle("p"));
        editPath("p");
        altClick(300, 200);

        Vertex smooth = vertices("p").get(1);
        double third = 100 / 3.0 * Math.sqrt(0.5);
        assertVec(third, third, smooth.out());
        assertVec(-third, -third, smooth.in());
        assertEquals(2, editor().getOverlay().getHandleCount(), "the selected vertex shows its handles");

        dragFromTo(300 + third, 200 + third, 350, 260);
        assertVec(50, 60, vertices("p").get(1).out());
        assertVec(-50, -60, vertices("p").get(1).in());

        dragFromTo(350, 260, 340, 190, Keys.ALT);
        assertVec(40, -10, vertices("p").get(1).out());
        assertVec(-50, -60, vertices("p").get(1).in());

        altClick(300, 200);
        assertTrue(PathOps.isCorner(vertices("p").get(1)));
    }

    @Test
    void theContextMenuClosesAndOpensTheContour() {
        add(angle("p"));
        editPath("p");

        List<MenuItem> items = fx(() -> editor().menuItems(Pointer.at(view(300, 200).x(), view(300, 200).y())));
        List<String> ids = items.stream().map(MenuItem::getId).filter(java.util.Objects::nonNull).toList();
        assertEquals(List.of("path-menu-smooth", "path-menu-delete", "path-menu-close", "path-menu-done"), ids);
        MenuItem close = items.stream().filter(i -> "path-menu-close".equals(i.getId())).findFirst().orElseThrow();
        assertEquals("Close Path", close.getText());
        run(close::fire);
        assertTrue(path("p").contours().get(0).closed());

        items = fx(() -> editor().menuItems(Pointer.at(view(600, 500).x(), view(600, 500).y())));
        assertEquals("Open Path", items.get(0).getText());
        run(items.get(0)::fire);
        assertFalse(path("p").contours().get(0).closed());
    }

    @Test
    void pathEditingEndsWhenTheLayerGoesAway() {
        add(angle("p"));
        editPath("p");
        run(doc::undo);
        layout();
        WaitForAsyncUtils.waitForFxEvents();

        assertNull(fx(canvas::getModalTool));
    }

    @Test
    void pathEditingWorksInsideARotatedAndScaledLayer() {
        ShapeLayer layer = angle("p");
        Transform transform = new Transform(Animatable.of(Vec2.ZERO), Animatable.of(new Vec2(200, 200)),
                Animatable.of(new Vec2(2, 2)), Animatable.of(90.0), Animatable.of(Vec2.ZERO));
        add((ShapeLayer) layer.withBase(layer.base().withTransform(transform)));
        editPath("p");
        // local (100, 0) is at document (200, 400); 20 px down on screen are 10 along local x
        dragFromTo(200, 400, 200, 420, Keys.CONTROL);

        assertVec(110, 0, vertices("p").get(1).point());
    }

    // ------------------------------------------------------------------ convert to path

    @Test
    void convertToPathReplacesThePrimitiveAndADoubleClickDoesNotConvert() {
        add(rect("r", 100, 100, 80, 60));
        doubleClick(140, 130);
        assertNull(fx(canvas::getModalTool));
        assertTrue(((ShapeLayer) layer("r")).geometry() instanceof Geometry.Rect);

        List<String> ids = fx(() -> canvas.getTool(ToolKind.SELECT).menuItems(Pointer.at(view(140, 130).x(), view(140, 130).y())))
                .stream().map(MenuItem::getId).toList();
        assertTrue(ids.contains("canvas-menu-convert-to-path"));
        assertFalse(ids.contains("canvas-menu-edit-path"));
        run(() -> canvas.getActions().convertToPath());
        layout();

        assertEquals(4, vertices("r").size());
        assertVec(140, 130, position("r"));
        run(doc::undo);
        assertTrue(((ShapeLayer) layer("r")).geometry() instanceof Geometry.Rect);

        run(() -> canvas.getActions().convertToPath());
        layout();
        doubleClick(140, 130);
        editor();
        assertEquals(4, editor().getOverlay().getVertexCount());
    }

    @Test
    void theEditMenuConvertsToAPathAndStartsPathEditing() {
        List<String> notices = new ArrayList<>();
        add(rect("r", 100, 100, 80, 60));
        assertTrue(fx(() -> window.menuItem("menu-edit-convert-to-path").isDisable()), "nothing is selected");
        select("r");
        run(() -> {
            canvas.setNotifier(notices::add);
            window.menuItem("menu-edit-edit-path").fire();
        });
        assertNull(fx(canvas::getModalTool));
        assertEquals(1, notices.size(), "a rectangle has no points to edit yet");

        run(() -> window.menuItem("menu-edit-convert-to-path").fire());
        layout();
        assertEquals(4, vertices("r").size());
        run(() -> window.menuItem("menu-edit-edit-path").fire());
        layout();
        assertEquals(4, editor().getOverlay().getVertexCount());
    }

    @Test
    void convertToPathRefusesAnimatedGeometryWithANotice() {
        List<String> notices = new ArrayList<>();
        add(rect("r", 100, 100, 80, 60));
        run(() -> {
            canvas.setNotifier(notices::add);
            doc.execute(Commands.setValueAt("r", "geometry.size", 1, new Vec2(100, 100), true));
        });
        select("r");
        run(() -> canvas.getActions().convertToPath());

        assertTrue(((ShapeLayer) layer("r")).geometry() instanceof Geometry.Rect);
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).contains("keyframes"));
    }

    // ------------------------------------------------------------------ motion paths

    private MotionPath motion(String id) {
        return layer(id).base().motionPath();
    }

    @Test
    void drawingAMotionPathPutsTheLayerOnItAsOneUndoStep() {
        add(rect("a", 100, 100, 50, 50));
        run(() -> canvas.drawMotionPath("a"));
        assertInstanceOf(MotionPathTool.class, fx(canvas::getModalTool));
        assertEquals("Motion path", fx(() -> canvas.getOverlay().getModeHint()));
        click(160, 420);
        assertNull(motion("a"), "one point is not a path yet");
        press(500, 460);
        drag(540, 460);
        release(540, 460);
        key(KeyCode.ENTER);

        MotionPath motion = motion("a");
        assertNull(fx(canvas::getModalTool));
        assertEquals(List.of("a"), selection());
        assertEquals(List.of(corner(160, 420), new Vertex(new Vec2(500, 460), new Vec2(-40, 0), new Vec2(40, 0))),
                motion.path().contours().get(0).vertices());
        assertFalse(motion.path().contours().get(0).closed());
        assertEquals(2, motion.progress().keyframes().size());
        assertTrue(fx(() -> canvas.getOverlay().isMotionPathShown()), "the path of the selected layer is drawn");
        // at the start of the path
        Vec2 centre = fx(() -> canvas.getGeometry().quad("a").orElseThrow().center());
        assertVec(view(160, 420).x(), view(160, 420).y(), centre);

        run(doc::undo);
        layout();
        assertNull(motion("a"));
        assertFalse(fx(() -> canvas.getOverlay().isMotionPathShown()));
    }

    @Test
    void escapeWithOnePointCancelsTheMotionPath() {
        add(rect("a", 100, 100, 50, 50));
        run(() -> canvas.drawMotionPath("a"));
        click(160, 420);
        key(KeyCode.ESCAPE);

        assertNull(fx(canvas::getModalTool));
        assertNull(motion("a"));
    }

    private void addOnPath(String id) {
        Layer layer = rect(id, 100, 100, 50, 50);
        MotionPath motion = new MotionPath(new PathData(List.of(new Contour(List.of(corner(160, 420), corner(500, 460)), false))),
                Animatable.of(0.0), false);
        add(layer.withBase(layer.base().withMotionPath(motion)));
    }

    @Test
    void draggingALayerOnAMotionPathMovesTheWholePath() {
        addOnPath("a");
        dragFromTo(160, 420, 200, 440, Keys.CONTROL);

        assertEquals(List.of(corner(200, 440), corner(540, 480)), motion("a").path().contours().get(0).vertices());
        assertVec(125, 125, position("a"));
        run(doc::undo);
        assertEquals(corner(160, 420), motion("a").path().contours().get(0).vertices().get(0));

        select("a");
        key(KeyCode.RIGHT, true);
        assertEquals(corner(170, 420), motion("a").path().contours().get(0).vertices().get(0));
    }

    @Test
    void aLayerOnAnOrientedMotionPathScalesAndRotatesAboutWhereThePathPutsIt() {
        Layer layer = rect("a", 100, 100, 50, 50);
        // straight down: the path turns the layer by 90 degrees
        MotionPath motion = new MotionPath(new PathData(List.of(new Contour(List.of(corner(300, 100), corner(300, 400)), false))),
                Animatable.of(0.0), true);
        add(layer.withBase(layer.base().withMotionPath(motion)));
        select("a");
        // the layer's bottom-right corner (50, 50) is drawn at the bottom-left
        dragFromTo(275, 125, 250, 150, Keys.CONTROL);

        Vec2 scale = layer("a").base().transform().scale().at(0);
        assertVec(2, 2, scale);
        assertVec(125, 125, position("a"));

        run(doc::undo);
        select("a");
        // the rotate handle is above the top edge, which the path has turned to the right
        Vec2 handle = fx(() -> canvas.getGeometry().quad("a").orElseThrow().rotateHandle(Handles.ROTATE_DISTANCE));
        pressView(handle, Keys.NONE);
        dragView(new Vec2(view(300, 100).x(), handle.y() + 40), Keys.NONE);
        dragView(new Vec2(view(300, 147).x(), view(300, 147).y()), Keys.NONE);
        releaseView(new Vec2(view(300, 147).x(), view(300, 147).y()), Keys.NONE);
        assertEquals(90, layer("a").base().transform().rotation().at(0), 1e-6, "a quarter turn on top of the path's");
    }

    @Test
    void aSecondaryClickShowsTheMenuOfTheToolInCharge() {
        add(rect("r", 100, 100, 80, 60));
        run(() -> {
            javafx.geometry.Point2D scene = canvas.localToScene(view(140, 130).x(), view(140, 130).y());
            javafx.geometry.Point2D screen = canvas.localToScreen(view(140, 130).x(), view(140, 130).y());
            javafx.event.Event.fireEvent(canvas, new javafx.scene.input.ContextMenuEvent(
                    javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, scene.getX(), scene.getY(),
                    screen.getX(), screen.getY(), false, null));
        });
        layout();

        assertEquals(List.of("r"), selection(), "the layer under the pointer is selected first");
        assertTrue(fx(() -> canvas.getContextMenu().isShowing()));
        MenuItem convert = fx(() -> canvas.getContextMenu().getItems().get(0));
        assertEquals("Convert to Path", convert.getText());
        run(convert::fire);
        layout();
        assertEquals(4, vertices("r").size());
        run(() -> canvas.getContextMenu().hide());
    }

    @Test
    void playbackEndsAGestureButNotThePenOrPathEditing() {
        add(angle("p"));
        editPath("p");
        run(canvas::togglePlayback);
        run(canvas::pausePlayback);
        click(300, 200);
        layout();
        assertEquals(Set.of(new VertexRef(0, 1)), editor().getSelection());
        assertEquals(3, editor().getOverlay().getVertexCount(), "the points show again");
        assertEquals("Path editing", fx(() -> canvas.getOverlay().getModeHint()));
    }

    @Test
    void theMotionPathIsEditedWithThePathEditor() {
        addOnPath("a");
        run(() -> assertTrue(canvas.editMotionPath("a")));
        layout();
        assertEquals(2, editor().getOverlay().getVertexCount());
        dragFromTo(500, 460, 520, 380, Keys.CONTROL);

        assertEquals(List.of(corner(160, 420), corner(520, 380)), motion("a").path().contours().get(0).vertices());
        List<MenuItem> items = fx(() -> editor().menuItems(Pointer.at(view(700, 100).x(), view(700, 100).y())));
        assertTrue(items.stream().noneMatch(i -> "path-menu-close".equals(i.getId())), "a motion path stays open");
        key(KeyCode.ESCAPE);
        assertNull(fx(canvas::getModalTool));
    }

    // ------------------------------------------------------------------ masks

    @Test
    void aSelectedMaskLayerShowsItsOutlineAndCanBeDragged() {
        Layer masked = rect("a", 100, 100, 100, 100);
        add(rect("m", 120, 120, 50, 50), masked.withBase(masked.base().withMask(new Mask("m", Mask.Mode.CLIP))));
        click(140, 140);
        assertEquals(List.of("a"), selection(), "the mask layer is not drawn, so the click goes to the masked layer");

        select("m");
        assertTrue(fx(() -> canvas.getGeometry().quad("m")).isPresent());
        dragFromTo(140, 140, 180, 160, Keys.CONTROL);

        assertEquals(List.of("m"), selection());
        assertVec(185, 165, position("m"));
    }
}
