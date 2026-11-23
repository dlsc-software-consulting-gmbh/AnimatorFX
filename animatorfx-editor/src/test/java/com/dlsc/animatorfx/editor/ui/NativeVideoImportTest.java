package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.audio.EditorAudio;
import com.dlsc.animatorfx.editor.audio.TestSounds;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.video.VideoImport;
import com.dlsc.animatorfx.model.VideoLayer;
import javafx.scene.Parent;
import javafx.scene.image.WritableImage;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledIfSystemProperty(named = "animatorfx.nativeVideo", matches = "true")
class NativeVideoImportTest extends EditorUiTest {
    @AfterEach
    void restorePolicy() {
        System.setProperty(EditorAudio.PROPERTY, "off");
        VideoImport.setProbe(null);
    }

    @Test
    void importedVideoSurvivesReopeningAndPlaysInTheEditorCanvas() throws Exception {
        Path source = Path.of(System.getProperty("animatorfx.test.video"));
        assertTrue(Files.isRegularFile(source), "Pass the original red-blue.mp4 fixture");
        System.setProperty(EditorAudio.PROPERTY, "on");
        var imported = fx(() -> window.getImportActions().importVideo(doc, source));
        String id = imported.get(20, TimeUnit.SECONDS).orElseThrow();
        VideoLayer clip = (VideoLayer) layer(id);
        assertEquals(64, clip.width());
        assertEquals(36, clip.height());
        Path saved = dir.resolve("video.afx");
        EditorDocument reopened = fx(() -> {
            doc.saveAs(saved);
            window.getWorkspace().remove(doc);
            EditorDocument result = EditorDocument.open(saved);
            window.getWorkspace().add(result);
            return result;
        });
        assertArrayEquals(Files.readAllBytes(source), reopened.getFileContent().assets().get(clip.assetId()));
        var view = canvas.getAnimationView();
        run(() -> view.seek(0.25));
        await(() -> color(true), "Reopened video did not decode red");
        run(() -> view.seek(1.25));
        await(() -> color(false), "Reopened video did not seek to blue");
        run(() -> view.seek(0.25));
        await(() -> color(true), "Seek did not return to red");
        run(() -> { view.setVolume(0.25); view.play(); });
        await(() -> fx(() -> {
            MediaPlayer player = picture(view).getMediaPlayer();
            return player != null && player.getStatus() == MediaPlayer.Status.PLAYING && player.getVolume() > 0.24;
        }), "The editor did not start the synchronized video soundtrack");
        run(() -> view.setMuted(true));
        await(() -> fx(view::getCurrentTime) > 1.05 && color(false), "Mute froze the editor video");
        assertEquals(0, fx(() -> picture(view).getMediaPlayer().getVolume()));
        run(view::pause);
        double paused = fx(view::getCurrentTime);
        Thread.sleep(150);
        assertEquals(paused, fx(view::getCurrentTime), 0.001);
        MediaPlayer decoder = fx(() -> picture(view).getMediaPlayer());
        run(() -> window.getWorkspace().remove(reopened));
        assertEquals(MediaPlayer.Status.DISPOSED, fx(decoder::getStatus));
    }

    @Test
    void audioOnlyMp4IsRefusedWithoutPartialDocumentContent() throws Exception {
        Path sound = dir.resolve("audio-only.mp4");
        assumeTrue(TestSounds.aac(sound, 0.25), "Native AAC fixture generation needs macOS afconvert");
        var imported = fx(() -> window.getImportActions().importVideo(doc, sound));
        assertTrue(imported.get(20, TimeUnit.SECONDS).isEmpty());
        assertTrue(doc.getAnimation().assets().isEmpty());
        assertTrue(doc.getAnimation().layers().isEmpty());
        assertTrue(fx(() -> window.getNoticeBar().getMessages()).stream()
                .anyMatch(message -> message.contains("no video track")));
    }

    private boolean color(boolean red) {
        return fx(() -> {
            MediaView media = picture(canvas.getAnimationView());
            if (media == null || media.getMediaPlayer() == null) return false;
            WritableImage image = media.snapshot(null, null);
            Color color = image.getPixelReader().getColor((int) image.getWidth() / 2, (int) image.getHeight() / 2);
            return red ? color.getRed() > 0.7 && color.getBlue() < 0.3
                    : color.getBlue() > 0.7 && color.getRed() < 0.3;
        });
    }

    private static MediaView picture(Parent parent) {
        for (var child : parent.getChildrenUnmodifiable()) {
            if (child instanceof MediaView media) return media;
            if (child instanceof Parent nested) {
                MediaView media = picture(nested);
                if (media != null) return media;
            }
        }
        return null;
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        WaitForAsyncUtils.waitFor(12, TimeUnit.SECONDS, condition::getAsBoolean);
        assertTrue(condition.getAsBoolean(), message);
    }
}
