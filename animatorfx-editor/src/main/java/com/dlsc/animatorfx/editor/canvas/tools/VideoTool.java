package com.dlsc.animatorfx.editor.canvas.tools;

import com.dlsc.animatorfx.editor.canvas.CanvasView;
import com.dlsc.animatorfx.editor.ui.ToolKind;
import javafx.application.Platform;

/**
 * Opens the video import dialog, then hands back to the select tool.
 */
public final class VideoTool implements Tool {
    private final CanvasView canvas;
    private final Runnable importer;

    public VideoTool(CanvasView canvas, Runnable importer) {
        this.canvas = canvas;
        this.importer = importer;
    }

    @Override
    public void activate() {
        Platform.runLater(() -> {
            if (canvas.getActiveTool() != ToolKind.VIDEO) {
                return;
            }
            if (canvas.getDocument() != null) {
                importer.run();
            }
            canvas.setActiveTool(ToolKind.SELECT);
        });
    }
}
