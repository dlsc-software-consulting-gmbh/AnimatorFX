package com.dlsc.animatorfx.editor.canvas;

import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.model.Animation;
import javafx.event.Event;
import javafx.geometry.Orientation;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

/** Overlays scroll controls without resizing the viewport and shifting its zoom anchors. */
final class CanvasScrollBars extends Pane {
    private static final double BAR_SIZE = 12;
    private static final double EDGE_MARGIN = 32;

    private final CanvasView canvas;
    private final ScrollBar horizontal = new ScrollBar();
    private final ScrollBar vertical = new ScrollBar();
    private final Region corner = new Region();
    private ScrollBar dragging;
    private boolean syncing;

    CanvasScrollBars(CanvasView canvas) {
        this.canvas = canvas;
        setManaged(false);
        setPickOnBounds(false);
        setCursor(Cursor.DEFAULT);
        configure(horizontal, "canvas-h-scroll");
        configure(vertical, "canvas-v-scroll");
        vertical.setOrientation(Orientation.VERTICAL);
        corner.setManaged(false);
        corner.getStyleClass().add("canvas-scroll-corner");
        consumeChromeEvents(corner);
        getChildren().addAll(horizontal, vertical, corner);
        horizontal.valueProperty().addListener((obs, oldValue, value) -> scroll(true, value.doubleValue()));
        vertical.valueProperty().addListener((obs, oldValue, value) -> scroll(false, value.doubleValue()));
    }

    private void configure(ScrollBar bar, String id) {
        bar.setId(id);
        bar.getStyleClass().add("canvas-scroll-bar");
        bar.setManaged(false);
        bar.setFocusTraversable(false);
        bar.setUnitIncrement(32);
        bar.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> dragging = bar);
        bar.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            dragging = null;
            canvas.requestLayout();
        });
        consumeChromeEvents(bar);
    }

    private static void consumeChromeEvents(Node node) {
        node.addEventHandler(MouseEvent.ANY, Event::consume);
        node.addEventHandler(ScrollEvent.ANY, Event::consume);
        node.addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, Event::consume);
    }

    private void scroll(boolean xAxis, double value) {
        EditorDocument doc = canvas.getDocument();
        if (syncing || doc == null) {
            return;
        }
        Animation animation = doc.getAnimation();
        if (xAxis) {
            doc.getViewState().setCanvasPanX((animation.width() * canvas.getZoom() - canvas.getWidth()) / 2 - value);
        } else {
            doc.getViewState().setCanvasPanY((animation.height() * canvas.getZoom() - canvas.getHeight()) / 2 - value);
        }
    }

    void update() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        resizeRelocate(0, 0, width, height);
        EditorDocument doc = canvas.getDocument();
        if (doc == null || width <= 0 || height <= 0) {
            horizontal.setVisible(false);
            vertical.setVisible(false);
            corner.setVisible(false);
            return;
        }
        double contentWidth = doc.getAnimation().width() * canvas.getZoom();
        double contentHeight = doc.getAnimation().height() * canvas.getZoom();
        boolean h = contentWidth > width;
        boolean v = contentHeight > height;
        h |= contentWidth > width - (v ? BAR_SIZE : 0);
        v |= contentHeight > height - (h ? BAR_SIZE : 0);
        horizontal.setVisible(h);
        vertical.setVisible(v);
        corner.setVisible(h && v);
        double visibleWidth = Math.max(0, width - (v ? BAR_SIZE : 0));
        double visibleHeight = Math.max(0, height - (h ? BAR_SIZE : 0));
        syncing = true;
        try {
            if (h) {
                updateRange(horizontal, visibleWidth, contentWidth,
                        (contentWidth - width) / 2 - doc.getViewState().getCanvasPanX());
                horizontal.resizeRelocate(0, height - BAR_SIZE, visibleWidth, BAR_SIZE);
            }
            if (v) {
                updateRange(vertical, visibleHeight, contentHeight,
                        (contentHeight - height) / 2 - doc.getViewState().getCanvasPanY());
                vertical.resizeRelocate(width - BAR_SIZE, 0, BAR_SIZE, visibleHeight);
            }
            corner.resizeRelocate(width - BAR_SIZE, height - BAR_SIZE, BAR_SIZE, BAR_SIZE);
        } finally {
            syncing = false;
        }
    }

    private void updateRange(ScrollBar bar, double visible, double content, double value) {
        // Include free panning beyond the artboard, but freeze the range during a thumb drag.
        if (dragging != bar) {
            bar.setMin(Math.min(-EDGE_MARGIN, value));
            bar.setMax(Math.max(content + EDGE_MARGIN - visible, value));
            double range = bar.getMax() - bar.getMin();
            bar.setVisibleAmount(range * visible / (range + visible));
            bar.setBlockIncrement(visible * 0.9);
        }
        bar.setValue(value);
    }
}
