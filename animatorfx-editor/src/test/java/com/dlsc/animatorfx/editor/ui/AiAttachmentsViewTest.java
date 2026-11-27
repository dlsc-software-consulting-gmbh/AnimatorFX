package com.dlsc.animatorfx.editor.ui;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AiAttachmentsViewTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void addedFilesBecomeRemovableChips() throws Exception {
        Path photo = png("photo.png");
        Path logo = directory.resolve("logo.svg");
        Files.writeString(logo, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"20\" height=\"10\">"
                + "<rect width=\"20\" height=\"10\"/></svg>");

        fx(() -> {
            AiAttachmentsView view = new AiAttachmentsView();
            view.add(List.of(photo, logo));
            assertEquals(List.of("photo.png", "logo.svg"),
                    view.getAttachments().stream().map(a -> a.name()).toList());
            assertEquals(2, view.lookupAll(".ai-attachment-chip").size());
            assertEquals("", view.getError());

            ((Button) view.lookupAll(".ai-attachment-remove").iterator().next()).fire();
            assertEquals(List.of("logo.svg"), view.getAttachments().stream().map(a -> a.name()).toList());
            assertEquals(1, view.lookupAll(".ai-attachment-chip").size());
            assertThrows(UnsupportedOperationException.class, () -> view.getAttachments().clear());
            return null;
        });
    }

    @Test
    void rejectsFilesBeyondTheLimitAndUnsupportedFiles() throws Exception {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 6; i++) files.add(png("image" + i + ".png"));
        Path webp = directory.resolve("photo.webp");
        Files.write(webp, new byte[] {1, 2, 3});

        fx(() -> {
            AiAttachmentsView view = new AiAttachmentsView();
            view.add(files);
            assertEquals(5, view.getAttachments().size());
            assertEquals("At most 5 attachments.", view.getError());
            Label error = (Label) view.lookup("#ai-attachment-error");
            assertTrue(error.isVisible());
            return null;
        });

        fx(() -> {
            AiAttachmentsView view = new AiAttachmentsView();
            view.add(List.of(webp));
            assertTrue(view.getAttachments().isEmpty());
            assertEquals("photo.webp: WebP is not supported.", view.getError());
            view.add(List.of(files.getFirst()));
            assertEquals("", view.getError());
            assertFalse(view.lookup("#ai-attachment-error").isVisible());
            Node add = view.lookup("#ai-add-attachments");
            assertNotNull(add);
            return null;
        });
    }

    private Path png(String name) throws Exception {
        Path file = directory.resolve(name);
        ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }

    private static <T> T fx(Callable<T> callable) throws Exception {
        return WaitForAsyncUtils.asyncFx(callable).get(10, TimeUnit.SECONDS);
    }
}
