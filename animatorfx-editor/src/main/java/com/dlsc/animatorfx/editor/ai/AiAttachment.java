package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.importer.SvgImporter;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.player.Evaluator;
import com.dlsc.animatorfx.player.render.Renderer;
import javafx.geometry.Rectangle2D;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A file the user attached to an AI generation. The AI sees {@code preview}; when it places the
 * attachment, {@code bytes} (raster) or {@code vector} (SVG) become part of the document.
 *
 * @param name      the file name
 * @param kind      whether the file is a raster image or an SVG
 * @param extension the lower-case file extension
 * @param bytes     the original file content
 * @param width     the intrinsic width in pixels
 * @param height    the intrinsic height in pixels
 * @param preview   a PNG of at most 768 pixels on its longest side, sent to the AI
 * @param vector    the imported SVG document, or {@code null} for raster images
 */
public record AiAttachment(String name, Kind kind, String extension, byte[] bytes, double width, double height,
                           byte[] preview, AnimationFile vector) {

    public enum Kind {RASTER, VECTOR}

    public static final int MAX_COUNT = 5;
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    private static final Set<String> RASTER = Set.of("png", "jpg", "jpeg", "gif");
    private static final int[] PREVIEW_SIZES = {768, 512, 384, 256};
    private static final int MAX_PREVIEW_BYTES = 200_000;

    public AiAttachment {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(extension, "extension");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(preview, "preview");
        if (kind == Kind.VECTOR) Objects.requireNonNull(vector, "vector");
    }

    /**
     * The id the AI uses to reference the attachment at the given position.
     *
     * @param index the zero-based position in the attachment list
     * @return {@code asset1} for the first attachment, and so on
     */
    public static String id(int index) {
        return "asset" + (index + 1);
    }

    /**
     * Reads and checks a file. Must be called on the JavaFX application thread.
     *
     * @param file the file to attach
     * @return the attachment
     * @throws IOException with a user-facing message if the file is unsupported, too large or unreadable
     */
    public static AiAttachment read(Path file) throws IOException {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (extension.equals("webp")) throw new IOException(name + ": WebP is not supported.");
        if (!RASTER.contains(extension) && !extension.equals("svg")) {
            throw new IOException(name + ": unsupported file type. Use PNG, JPEG, GIF or SVG.");
        }
        if (Files.size(file) > MAX_BYTES) throw new IOException(name + ": larger than 5 MB.");
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_BYTES) throw new IOException(name + ": larger than 5 MB.");
        return extension.equals("svg") ? vector(name, bytes) : raster(name, extension, bytes);
    }

    private static AiAttachment raster(String name, String extension, byte[] bytes) throws IOException {
        Image image = new Image(new ByteArrayInputStream(bytes));
        if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) {
            throw new IOException(name + ": not a readable image.");
        }
        double longest = Math.max(image.getWidth(), image.getHeight());
        byte[] preview = null;
        for (int size : PREVIEW_SIZES) {
            double target = Math.min(size, longest);
            preview = AiPreviewFrames.png(new Image(new ByteArrayInputStream(bytes), target, target, true, true));
            if (preview.length <= MAX_PREVIEW_BYTES) break;
        }
        return new AiAttachment(name, Kind.RASTER, extension, bytes, image.getWidth(), image.getHeight(),
                preview, null);
    }

    private static AiAttachment vector(String name, byte[] bytes) throws IOException {
        AnimationFile file;
        try {
            file = SvgImporter.read(new ByteArrayInputStream(bytes), name).file();
        } catch (IOException | RuntimeException ex) {
            throw new IOException(name + ": not a readable SVG.", ex);
        }
        Animation animation = file.animation();
        Renderer renderer = new Renderer();
        try {
            renderer.setDocument(file);
            renderer.apply(Evaluator.sample(animation, 0));
            byte[] preview = null;
            double longest = Math.max(animation.width(), animation.height());
            for (int size : PREVIEW_SIZES) {
                double scale = size / longest;
                SnapshotParameters parameters = new SnapshotParameters();
                parameters.setFill(Color.TRANSPARENT);
                parameters.setTransform(new Scale(scale, scale));
                parameters.setViewport(new Rectangle2D(0, 0, Math.max(1, Math.ceil(animation.width() * scale)),
                        Math.max(1, Math.ceil(animation.height() * scale))));
                preview = AiPreviewFrames.png(renderer.getRoot().snapshot(parameters, null));
                if (preview.length <= MAX_PREVIEW_BYTES) break;
            }
            return new AiAttachment(name, Kind.VECTOR, "svg", bytes, animation.width(), animation.height(),
                    preview, file);
        } finally {
            renderer.dispose();
        }
    }
}
