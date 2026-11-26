package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.ai.AiAttachment;
import com.dlsc.animatorfx.editor.ai.AiChatAttachment;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.io.AnimationFile;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AiChatAttachmentsViewTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void enforcesSharedLimitAndRemovalClearsTheError() throws Exception {
        fx(() -> {
            AiChatAttachmentsView view = new AiChatAttachmentsView();
            for (int i = 0; i < 5; i++) view.addAttachment(attachment("image-" + i));

            view.addAttachment(attachment("sixth"));

            assertEquals(5, view.getAttachments().size());
            assertEquals("At most 5 attachments.", view.getError());
            assertTrue(((Label) view.lookup("#ai-chat-attachment-error")).isVisible());
            assertThrows(UnsupportedOperationException.class, () -> view.getAttachments().clear());

            ((Button) view.lookupAll(".ai-attachment-remove").iterator().next()).fire();
            assertEquals(4, view.getAttachments().size());
            assertEquals("", view.getError());
            assertFalse(((Label) view.lookup("#ai-chat-attachment-error")).isVisible());
            return null;
        });
    }

    @Test
    void externalFilesUseExistingValidationAndBecomeChatChips() throws Exception {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 6; i++) files.add(png("image" + i + ".png"));
        Path unsupported = directory.resolve("photo.webp");
        Files.write(unsupported, new byte[] {1, 2, 3});

        fx(() -> {
            AiChatAttachmentsView view = new AiChatAttachmentsView();
            view.addFiles(files);

            assertEquals(5, view.getAttachments().size());
            assertEquals("At most 5 attachments.", view.getError());
            assertEquals(5, view.lookupAll(".ai-attachment-chip").size());
            assertTrue(view.getAttachments().stream().allMatch(item -> item.external() != null));

            view.clear();
            view.addFiles(List.of(unsupported));
            assertTrue(view.getAttachments().isEmpty());
            assertEquals("photo.webp: WebP is not supported.", view.getError());
            return null;
        });
    }

    @Test
    void projectPickerDefersInvalidImageDecodingUntilExplicitSelection() throws Exception {
        Asset image = new Asset("photo", Asset.Kind.IMAGE, "assets/photo.png", "Photo");
        Asset unreadable = new Asset("broken", Asset.Kind.IMAGE, "assets/broken.png", "Broken");
        Asset font = new Asset("font", Asset.Kind.FONT, "assets/font.ttf", "Font");
        Asset audio = new Asset("audio", Asset.Kind.AUDIO, "assets/audio.wav", "Audio");
        byte[] imageBytes = pngBytes();
        AnimationFile file = new AnimationFile(
                Animation.create("Project", 320, 200, 2).withAssets(List.of(image, unreadable, font, audio)),
                Map.of(image.id(), imageBytes, unreadable.id(), new byte[] {1, 2, 3},
                        font.id(), new byte[] {4}, audio.id(), new byte[] {5}));

        fx(() -> {
            AiChatAttachmentsView view = new AiChatAttachmentsView();
            view.setProject(file);

            @SuppressWarnings("unchecked")
            ComboBox<Asset> picker = (ComboBox<Asset>) view.lookup("#ai-chat-project-images");
            assertEquals(List.of(image, unreadable), picker.getItems());
            assertTrue(view.getAttachments().isEmpty());
            assertEquals("", view.getError());

            picker.setValue(unreadable);
            assertTrue(view.getAttachments().isEmpty());
            assertEquals("assets/broken.png: not a readable image.", view.getError());

            picker.setValue(image);

            assertEquals(1, view.getAttachments().size());
            AiChatAttachment selected = view.getAttachments().getFirst();
            assertEquals(image, selected.projectAsset());
            assertTrue(selected.preview().length > 0);
            assertNull(selected.external());
            assertEquals(List.of(image, unreadable), picker.getItems());
            return null;
        });
    }

    @Test
    void selectedProjectPreviewTracksChangedMetadataAndBytes() throws Exception {
        Asset original = new Asset("photo", Asset.Kind.IMAGE, "assets/photo.png", "Original");
        Asset updated = new Asset("photo", Asset.Kind.IMAGE, "assets/photo.png", "Updated");
        byte[] originalBytes = pngBytes();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB), "png", output);
        byte[] updatedBytes = output.toByteArray();
        Animation animation = Animation.create("Project", 320, 200, 2);
        fx(() -> {
            AiChatAttachmentsView view = new AiChatAttachmentsView();
            view.setProject(new AnimationFile(animation.withAssets(List.of(original)),
                    Map.of(original.id(), originalBytes)));
            view.addProjectImage(original, originalBytes);
            byte[] oldPreview = view.getAttachments().getFirst().preview();

            view.setProject(new AnimationFile(animation.withAssets(List.of(updated)),
                    Map.of(updated.id(), updatedBytes)));

            AiChatAttachment selected = view.getAttachments().getFirst();
            assertEquals(updated, selected.projectAsset());
            assertEquals("Updated", selected.name());
            assertEquals(80, selected.width());
            assertEquals(60, selected.height());
            assertFalse(java.util.Arrays.equals(oldPreview, selected.preview()));
            assertEquals("", view.getError());
            return null;
        });
    }

    @Test
    void validProjectSvgIsSelectableWithPngPreviewAndKeepsAssetIdentity() throws Exception {
        Asset svg = new Asset("vector", Asset.Kind.IMAGE, "assets/vector.svg", "Vector");
        byte[] svgBytes = """
                <svg xmlns="http://www.w3.org/2000/svg" width="64" height="32" viewBox="0 0 64 32">
                  <rect width="64" height="32" fill="#f00"/>
                </svg>
                """.getBytes(StandardCharsets.UTF_8);
        AnimationFile file = new AnimationFile(
                Animation.create("Project", 320, 200, 2).withAssets(List.of(svg)),
                Map.of(svg.id(), svgBytes));

        fx(() -> {
            AiChatAttachmentsView view = new AiChatAttachmentsView();
            view.setProject(file);

            @SuppressWarnings("unchecked")
            ComboBox<Asset> picker = (ComboBox<Asset>) view.lookup("#ai-chat-project-images");
            assertEquals(List.of(svg), picker.getItems());

            picker.setValue(svg);

            assertEquals(1, view.getAttachments().size());
            AiChatAttachment selected = view.getAttachments().getFirst();
            assertSame(svg, selected.projectAsset());
            assertNull(selected.external());
            assertEquals(AiAttachment.Kind.VECTOR, selected.kind());
            assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'},
                    java.util.Arrays.copyOf(selected.preview(), 8));
            return null;
        });
    }

    @Test
    void clearRemovesAttachmentsAndInlineErrors() throws Exception {
        Path unsupported = directory.resolve("broken.webp");
        Files.write(unsupported, new byte[] {1});

        fx(() -> {
            AiChatAttachmentsView view = new AiChatAttachmentsView();
            view.addAttachment(attachment("image"));
            view.addFiles(List.of(unsupported));
            assertEquals(1, view.getAttachments().size());
            assertFalse(view.getError().isEmpty());

            view.clear();

            assertTrue(view.getAttachments().isEmpty());
            assertEquals("", view.getError());
            assertFalse(((Label) view.lookup("#ai-chat-attachment-error")).isVisible());
            assertTrue(view.lookupAll(".ai-attachment-chip").isEmpty());
            return null;
        });
    }

    private AiChatAttachment attachment(String name) {
        Asset asset = new Asset(name, Asset.Kind.IMAGE, "assets/" + name + ".png", name);
        return new AiChatAttachment(name, AiAttachment.Kind.RASTER, 10, 10,
                new byte[] {(byte) name.length()}, asset, null);
    }

    private Path png(String name) throws Exception {
        Path file = directory.resolve(name);
        Files.write(file, pngBytes());
        return file;
    }

    private static byte[] pngBytes() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    private static <T> T fx(Callable<T> callable) throws Exception {
        return WaitForAsyncUtils.asyncFx(callable).get(10, TimeUnit.SECONDS);
    }
}
