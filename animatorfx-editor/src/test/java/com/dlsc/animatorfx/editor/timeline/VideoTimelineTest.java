package com.dlsc.animatorfx.editor.timeline;

import com.dlsc.animatorfx.editor.ui.EditorUiTest;
import com.dlsc.animatorfx.editor.video.EditorVideo;
import com.dlsc.animatorfx.editor.video.VideoImport;
import com.dlsc.animatorfx.editor.video.VideoInfo;
import com.dlsc.animatorfx.model.*;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class VideoTimelineTest extends EditorUiTest {
    private TrackPane tracks;

    @BeforeEach
    void content() {
        EditorVideo.setOutputFactory(() -> null);
        VideoImport.setProbe((bytes, extension) ->
                CompletableFuture.completedFuture(new VideoInfo(2, 320, 180, true)));
        tracks = timeline.getTrackPane();
        run(() -> doc.addAsset(new Asset("clip", Asset.Kind.VIDEO, "assets/clip.mp4", "Clip"), new byte[]{1, 2}));
        add(new VideoLayer(LayerBase.create("Video", 2.5).withId("v").withInPoint(0.5), "clip",
                320, 180, 0.25, Animatable.of(1.0), false, false));
    }

    @AfterEach
    void restore() {
        VideoImport.setProbe(null);
        EditorVideo.setOutputFactory(null);
    }

    private VideoLayer video() {
        return (VideoLayer) layer("v");
    }

    private double x(double time) { return fx(() -> tracks.timeToX(time)); }
    private double y() { return fx(() -> tracks.rowCenter(tracks.rowOf("v"))); }

    @Test
    void trimmingAdvancesTheSourceOffsetAndUndoRestoresBoth() {
        drag(tracks, x(0.5), y(), x(1), y());
        assertEquals(1, video().base().inPoint(), 1e-9);
        assertEquals(0.75, video().clipOffset(), 1e-9);
        run(doc::undo);
        assertEquals(0.5, video().base().inPoint(), 1e-9);
        assertEquals(0.25, video().clipOffset(), 1e-9);
        drag(tracks, x(0.5), y(), x(1), y(), Keys.ALT);
        assertEquals(1, video().base().inPoint(), 1e-9);
        assertEquals(0.25, video().clipOffset(), 1e-9);
    }

    @Test
    void slippingChangesOnlySourceOffsetAndEscapeCancelsIt() {
        drag(tracks, x(1.5), y(), x(1.6), y(), Keys.ALT);
        assertEquals(0.15, video().clipOffset(), 1e-9);
        assertEquals(0.5, video().base().inPoint(), 1e-9);
        assertEquals(2.5, video().base().outPoint(), 1e-9);
        press(tracks, x(1.5), y(), Keys.ALT);
        dragTo(tracks, x(1.4), y(), Keys.ALT);
        key(KeyCode.ESCAPE);
        release(tracks, x(1.4), y(), Keys.ALT);
        assertEquals(0.15, video().clipOffset(), 1e-9);
    }

    @Test
    void muteAndPictureVisibilityAreIndependent() {
        var cell = fx(() -> timeline.getLayerColumn().layerCell("v").orElseThrow());
        Button mute = fx(() -> (Button) cell.lookup(".video-mute"));
        assertNotNull(mute, "Video needs its own soundtrack mute, not a replacement eye");
        run(mute::fire);
        assertTrue(video().muted());
        assertTrue(video().base().visible());
        run(() -> ((Button) cell.lookup(".eye")).fire());
        assertFalse(video().base().visible());
        assertTrue(video().muted());
    }
}
