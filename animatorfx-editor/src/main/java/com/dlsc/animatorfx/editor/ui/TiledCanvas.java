package com.dlsc.animatorfx.editor.ui;

import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;

import java.util.function.Consumer;

/**
 * A drawing surface backed by small canvases, so wide HiDPI viewports do not require a
 * single texture larger than Prism's limit.
 */
public final class TiledCanvas extends Group {
    private static final double TILE_SIZE = 512;

    public TiledCanvas() {
        setManaged(false);
    }

    /** Clears and paints the surface, invoking the painter per tile in surface coordinates. */
    public void draw(double width, double height, Consumer<GraphicsContext> painter) {
        int used = 0;
        for (double y = 0; y < height; y += TILE_SIZE) {
            for (double x = 0; x < width; x += TILE_SIZE) {
                if (used == getChildren().size()) {
                    getChildren().add(new Canvas());
                }
                Canvas tile = (Canvas) getChildren().get(used++);
                tile.setWidth(Math.min(TILE_SIZE, width - x));
                tile.setHeight(Math.min(TILE_SIZE, height - y));
                tile.relocate(x, y);
                GraphicsContext g = tile.getGraphicsContext2D();
                g.clearRect(0, 0, tile.getWidth(), tile.getHeight());
                g.save();
                try {
                    g.translate(-x, -y);
                    painter.accept(g);
                } finally {
                    g.restore();
                }
            }
        }
        getChildren().remove(used, getChildren().size());
    }
}
