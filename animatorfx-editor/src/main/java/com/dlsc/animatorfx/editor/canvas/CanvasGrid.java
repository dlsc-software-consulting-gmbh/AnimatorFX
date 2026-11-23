package com.dlsc.animatorfx.editor.canvas;

import com.dlsc.animatorfx.model.Vec2;
import javafx.scene.Group;
import javafx.scene.shape.Line;

/**
 * The grid behind the artboard. Its lines sit on document coordinates and move with the pan, but
 * they are drawn in view pixels, one pixel wide at any zoom, and the spacing adapts so the lines
 * neither crowd nor thin out. Every fifth line is a major line.
 */
final class CanvasGrid extends Group {

    private static final double[] STEPS = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000};
    private static final double MIN_SPACING = 16;
    private static final int MAJOR_EVERY = 5;

    private final Group minor = new Group();
    private final Group major = new Group();
    private double lastWidth = -1;
    private double lastHeight = -1;
    private double lastZoom = -1;
    private Vec2 lastOrigin;

    CanvasGrid() {
        setManaged(false);
        setMouseTransparent(true);
        getChildren().addAll(minor, major);
    }

    /** The distance between two lines in document pixels at the zoom. */
    static double step(double zoom) {
        for (double step : STEPS) {
            if (step * zoom >= MIN_SPACING) {
                return step;
            }
        }
        return STEPS[STEPS.length - 1];
    }

    void update(double width, double height, Vec2 origin, double zoom) {
        if (width == lastWidth && height == lastHeight && zoom == lastZoom && origin.equals(lastOrigin)) {
            return;
        }
        lastWidth = width;
        lastHeight = height;
        lastZoom = zoom;
        lastOrigin = origin;
        int minorCount = 0;
        int majorCount = 0;
        double spacing = step(zoom) * zoom;
        long first = (long) Math.ceil(-origin.x() / spacing);
        for (long i = first; origin.x() + i * spacing <= width; i++) {
            double x = Math.rint(origin.x() + i * spacing) + 0.5;
            boolean isMajor = Math.floorMod(i, MAJOR_EVERY) == 0;
            line(isMajor ? major : minor, isMajor ? majorCount++ : minorCount++, x, 0, x, height);
        }
        first = (long) Math.ceil(-origin.y() / spacing);
        for (long i = first; origin.y() + i * spacing <= height; i++) {
            double y = Math.rint(origin.y() + i * spacing) + 0.5;
            boolean isMajor = Math.floorMod(i, MAJOR_EVERY) == 0;
            line(isMajor ? major : minor, isMajor ? majorCount++ : minorCount++, 0, y, width, y);
        }
        minor.getChildren().remove(minorCount, minor.getChildren().size());
        major.getChildren().remove(majorCount, major.getChildren().size());
    }

    private void line(Group group, int index, double x1, double y1, double x2, double y2) {
        // Line primitives avoid the viewport-sized rasterization masks required by a Path.
        Line line;
        if (index < group.getChildren().size()) {
            line = (Line) group.getChildren().get(index);
        } else {
            line = new Line();
            line.getStyleClass().add("canvas-grid");
            if (group == major) line.getStyleClass().add("canvas-grid-major");
            group.getChildren().add(line);
        }
        line.setStartX(x1);
        line.setStartY(y1);
        line.setEndX(x2);
        line.setEndY(y2);
    }
}
