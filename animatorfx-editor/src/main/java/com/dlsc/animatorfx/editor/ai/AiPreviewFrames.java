package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.model.Layers;
import com.dlsc.animatorfx.model.VideoLayer;
import com.dlsc.animatorfx.player.Evaluator;
import com.dlsc.animatorfx.player.render.Renderer;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Renders small snapshots without altering the editor's live document or playhead. */
final class AiPreviewFrames {
    private AiPreviewFrames() {}

    static CompletableFuture<List<byte[]>> render(AnimationFile file) {
        if (Layers.flatten(file.animation()).stream().anyMatch(VideoLayer.class::isInstance)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Visual review does not support video layers."));
        }
        CompletableFuture<List<byte[]>> result = new CompletableFuture<>();
        Runnable task = () -> {
            if (result.isDone()) return;
            Renderer renderer = new Renderer();
            try {
                var animation = file.animation();
                double scale = Math.min(1, 512 / Math.max(animation.width(), animation.height()));
                int width = Math.max(1, (int) Math.ceil(animation.width() * scale));
                int height = Math.max(1, (int) Math.ceil(animation.height() * scale));
                renderer.setDocument(file);
                SnapshotParameters parameters = new SnapshotParameters();
                parameters.setFill(Color.TRANSPARENT);
                parameters.setTransform(new Scale(scale, scale));
                parameters.setViewport(new Rectangle2D(0, 0, width, height));
                double last = Math.max(0, animation.duration() - 1.0 / animation.frameRate());
                List<byte[]> frames = new ArrayList<>();
                for (double time : new double[]{0, last / 2, last}) {
                    if (result.isDone()) return;
                    renderer.apply(Evaluator.sample(animation, time));
                    WritableImage image = renderer.getRoot().snapshot(parameters, null);
                    int[] pixels = new int[width * height];
                    image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(),
                            pixels, 0, width);
                    BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                    buffered.setRGB(0, 0, width, height, pixels, 0, width);
                    var output = new ByteArrayOutputStream();
                    if (!ImageIO.write(buffered, "png", output)) throw new IllegalStateException("PNG unavailable");
                    frames.add(output.toByteArray());
                }
                result.complete(List.copyOf(frames));
            } catch (Exception ex) {
                result.completeExceptionally(ex);
            } finally {
                renderer.dispose();
            }
        };
        try {
            if (Platform.isFxApplicationThread()) task.run();
            else Platform.runLater(task);
        } catch (RuntimeException ex) {
            result.completeExceptionally(ex);
        }
        return result;
    }
}
