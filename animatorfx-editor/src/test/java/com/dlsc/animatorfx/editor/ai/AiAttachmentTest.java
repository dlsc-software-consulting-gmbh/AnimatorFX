package com.dlsc.animatorfx.editor.ai;

import javafx.scene.image.Image;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AiAttachmentTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void rasterAttachmentKeepsTheOriginalAndGetsASmallPngPreview() throws Exception {
        Path file = directory.resolve("photo.png");
        ImageIO.write(new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB), "png", file.toFile());

        AiAttachment attachment = read(file);

        assertEquals("photo.png", attachment.name());
        assertEquals(AiAttachment.Kind.RASTER, attachment.kind());
        assertEquals("png", attachment.extension());
        assertArrayEquals(Files.readAllBytes(file), attachment.bytes());
        assertEquals(2000, attachment.width());
        assertEquals(1000, attachment.height());
        assertNull(attachment.vector());
        Image preview = new Image(new ByteArrayInputStream(attachment.preview()));
        assertFalse(preview.isError());
        assertTrue(Math.max(preview.getWidth(), preview.getHeight()) <= 768);
        assertTrue(attachment.preview().length <= 200_000);
    }

    @Test
    void gifAndJpegAreAccepted() throws Exception {
        Path gif = directory.resolve("icon.gif");
        ImageIO.write(new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB), "gif", gif.toFile());
        Path jpeg = directory.resolve("shot.JPEG");
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg.toFile());

        assertEquals(32, read(gif).width());
        assertEquals("jpeg", read(jpeg).extension());
    }

    @Test
    void svgAttachmentIsImportedAndRasterizedForThePreview() throws Exception {
        Path file = directory.resolve("logo.svg");
        Files.writeString(file, """
                <svg xmlns="http://www.w3.org/2000/svg" width="120" height="40">
                  <rect width="120" height="40" fill="#ff0000"/>
                </svg>""");

        AiAttachment attachment = read(file);

        assertEquals(AiAttachment.Kind.VECTOR, attachment.kind());
        assertEquals(120, attachment.width());
        assertEquals(40, attachment.height());
        assertNotNull(attachment.vector());
        Image preview = new Image(new ByteArrayInputStream(attachment.preview()));
        assertFalse(preview.isError());
        assertEquals(3.0, preview.getWidth() / preview.getHeight(), 0.1);
        assertEquals(0xffff0000, preview.getPixelReader().getArgb((int) preview.getWidth() / 2,
                (int) preview.getHeight() / 2));
    }

    @Test
    void unsupportedOversizedAndBrokenFilesAreRejectedWithAReadableReason() throws Exception {
        Path webp = Files.write(directory.resolve("logo.webp"), new byte[]{1});
        Path text = Files.writeString(directory.resolve("notes.txt"), "x");
        Path broken = Files.writeString(directory.resolve("bad.png"), "not an image");
        Path big = directory.resolve("big.png");
        try (RandomAccessFile out = new RandomAccessFile(big.toFile(), "rw")) {
            out.setLength(AiAttachment.MAX_BYTES + 1L);
        }

        assertEquals("logo.webp: WebP is not supported.", failure(webp));
        assertEquals("notes.txt: unsupported file type. Use PNG, JPEG, GIF or SVG.", failure(text));
        assertEquals("bad.png: not a readable image.", failure(broken));
        assertEquals("big.png: larger than 5 MB.", failure(big));
    }

    @Test
    void idsFollowTheListOrder() {
        assertEquals("asset1", AiAttachment.id(0));
        assertEquals("asset5", AiAttachment.id(4));
    }

    private static AiAttachment read(Path file) throws Exception {
        return WaitForAsyncUtils.asyncFx(() -> AiAttachment.read(file)).get(10, TimeUnit.SECONDS);
    }

    private static String failure(Path file) throws Exception {
        ExecutionException error = assertThrows(ExecutionException.class, () -> read(file));
        assertInstanceOf(IOException.class, error.getCause());
        return error.getCause().getMessage();
    }
}
