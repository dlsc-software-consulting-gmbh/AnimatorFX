package com.dlsc.animatorfx.editor.canvas;

import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.ui.ToolKind;
import com.dlsc.animatorfx.model.Vec2;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CanvasScrollingTest extends CanvasTestBase {
    private ScrollBar bar(String axis) {
        ScrollBar bar = fx(() -> (ScrollBar) canvas.lookup("#canvas-" + axis + "-scroll"));
        assertNotNull(bar, "The canvas must provide " + axis + " scrolling");
        return bar;
    }

    private void zoomIn() {
        run(() -> canvas.zoomTo(4, new Vec2(canvas.getWidth() / 2, canvas.getHeight() / 2)));
        layout();
    }

    @Test
    void scrollbarsReachAllArtboardEdgesWithoutEditingTheDocument() {
        zoomIn();
        ScrollBar horizontal = bar("h");
        ScrollBar vertical = bar("v");
        assertTrue(horizontal.isVisible());
        assertTrue(vertical.isVisible());
        run(() -> {
            horizontal.setValue(horizontal.getMin());
            vertical.setValue(vertical.getMin());
        });
        layout();
        Vec2 topLeft = fx(() -> canvas.documentToView(Vec2.ZERO));
        assertTrue(topLeft.x() >= 0);
        assertTrue(topLeft.y() >= 0);

        run(() -> {
            horizontal.setValue(horizontal.getMax());
            vertical.setValue(vertical.getMax());
        });
        layout();
        Vec2 bottomRight = fx(() -> canvas.documentToView(new Vec2(800, 600)));
        assertTrue(bottomRight.x() > 0 && bottomRight.x() <= canvas.getWidth() - vertical.getWidth());
        assertTrue(bottomRight.y() > 0 && bottomRight.y() <= canvas.getHeight() - horizontal.getHeight());
        assertFalse(doc.isCanUndo());
    }

    @Test
    void zoomingShowsScrollbarsWithoutMovingThePointUnderThePointerAndFitHidesThem() {
        run(canvas::zoomToFit);
        layout();
        assertFalse(bar("h").isVisible());
        assertFalse(bar("v").isVisible());
        Vec2 anchor = new Vec2(123, 87);
        Vec2 fixed = fx(() -> canvas.viewToDocument(anchor));
        run(() -> canvas.zoomTo(4, anchor));
        layout();
        Vec2 after = fx(() -> canvas.viewToDocument(anchor));
        assertEquals(fixed.x(), after.x(), 0.5);
        assertEquals(fixed.y(), after.y(), 0.5);
        assertTrue(bar("h").isVisible());
        assertTrue(bar("v").isVisible());
        run(canvas::zoomToFit);
        layout();
        assertFalse(bar("h").isVisible());
        assertFalse(bar("v").isVisible());
    }

    @Test
    void resizingShowsOnlyTheAxesThatOverflow() {
        run(() -> {
            canvas.setManaged(false);
            canvas.resize(500, 400);
            canvas.zoomTo(1, Vec2.ZERO);
        });
        layout();
        assertTrue(bar("h").isVisible());
        assertTrue(bar("v").isVisible());
        run(() -> canvas.resize(1000, 400));
        layout();
        assertFalse(bar("h").isVisible());
        assertTrue(bar("v").isVisible());
        run(() -> canvas.resize(1000, 800));
        layout();
        assertFalse(bar("h").isVisible());
        assertFalse(bar("v").isVisible());
    }

    @Test
    void scrollbarsFollowFreePanningAndTheActiveDocument() {
        zoomIn();
        ScrollBar horizontal = bar("h");
        ScrollBar vertical = bar("v");
        double x = horizontal.getValue();
        double y = vertical.getValue();
        double panX = doc.getViewState().getCanvasPanX();
        double panY = doc.getViewState().getCanvasPanY();
        run(() -> canvas.panBy(10000, -9000));
        layout();
        assertEquals(x - 10000, horizontal.getValue(), 1e-6);
        assertEquals(y + 9000, vertical.getValue(), 1e-6);
        assertEquals(panX + 10000, doc.getViewState().getCanvasPanX(), 1e-6);
        assertEquals(panY - 9000, doc.getViewState().getCanvasPanY(), 1e-6);
        run(() -> window.getWorkspace().add(EditorDocument.untitled()));
        layout();
        assertFalse(horizontal.isVisible());
        assertFalse(vertical.isVisible());
        run(() -> window.getWorkspace().setActiveDocument(doc));
        layout();
        assertTrue(horizontal.isVisible());
        assertTrue(vertical.isVisible());
        assertEquals(x - 10000, horizontal.getValue(), 1e-6);
        assertEquals(y + 9000, vertical.getValue(), 1e-6);
    }

    @Test
    void draggingAScrollbarPansWithoutActivatingTheDrawingTool() {
        zoomIn();
        tool(ToolKind.RECTANGLE);
        ScrollBar horizontal = bar("h");
        double before = fx(() -> canvas.documentToView(Vec2.ZERO).x());
        run(() -> {
            Node thumb = horizontal.lookup(".thumb");
            assertNotNull(thumb);
            Point2D start = thumb.localToScene(thumb.getBoundsInLocal().getWidth() / 2, 6);
            mouse(thumb, MouseEvent.MOUSE_PRESSED, start, true);
            mouse(thumb, MouseEvent.MOUSE_DRAGGED, start.add(60, 0), true);
            mouse(thumb, MouseEvent.MOUSE_RELEASED, start.add(60, 0), false);
        });
        layout();
        assertTrue(fx(() -> canvas.documentToView(Vec2.ZERO).x()) < before);
        assertTrue(doc.getAnimation().layers().isEmpty());
        assertFalse(doc.isCanUndo());
    }

    private static void mouse(Node target, EventType<MouseEvent> type, Point2D point, boolean down) {
        Event.fireEvent(target, new MouseEvent(type, point.getX(), point.getY(), point.getX(), point.getY(),
                MouseButton.PRIMARY, 1, false, false, false, false, down, false, false,
                false, false, false, null));
    }
}
