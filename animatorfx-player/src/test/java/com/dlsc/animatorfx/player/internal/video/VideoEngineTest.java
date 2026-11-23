package com.dlsc.animatorfx.player.internal.video;

import com.dlsc.animatorfx.model.*;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.player.Evaluator;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.media.MediaPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VideoEngineTest {
    private static AnimationFile file() {
        VideoLayer video = new VideoLayer(LayerBase.create("Video", 3).withId("v"), "clip",
                320, 180, 0, Animatable.of(0.5), false, false);
        return new AnimationFile(Animation.create("Test", 800, 600, 3).withLayers(List.of(video)),
                Map.of("clip", new byte[]{1, 2}));
    }

    @Test
    void reusesOutputAcrossVisualEditsAndStopsReacquisitionUntilResumed() {
        AtomicInteger created = new AtomicInteger();
        RecordingOutput output = new RecordingOutput();
        VideoEngine engine = new VideoEngine(() -> {
            created.incrementAndGet();
            return output;
        }, System.getLogger("video-test"));
        AnimationFile file = file();
        engine.setDocument(file);
        engine.sync(Evaluator.sample(file.animation(), 0.5), true, 1, false, false, 0.4);
        assertEquals(0.5, output.targets.getFirst().mediaTime(), 1e-9);
        assertEquals(0.2, output.targets.getFirst().volume(), 1e-9);
        engine.setDocument(new AnimationFile(file.animation().withName("Renamed"), file.assets()));
        engine.sync(Evaluator.sample(file.animation(), 1), false, 1, false, false, 0.4);
        assertEquals(1, created.get());
        assertFalse(output.targets.getFirst().playing());
        engine.dispose();
        assertTrue(output.disposed);
        engine.sync(Evaluator.sample(file.animation(), 2), true, 1, false, false, 1);
        assertEquals(1, created.get());
        engine.resume();
        assertEquals(2, created.get());
        engine.dispose();
    }

    @Test
    void videoFreeDocumentsDoNotAllocateAnOutput() {
        VideoEngine engine = new VideoEngine(() -> {
            fail("A document without video must not allocate media");
            return null;
        }, System.getLogger("video-test"));
        Animation animation = Animation.create("Empty", 100, 100, 1);
        engine.setDocument(new AnimationFile(animation, Map.of()));
        engine.sync(Evaluator.sample(animation, 0), true, 1, false, false, 1);
        engine.dispose();
    }

    private static final class RecordingOutput implements VideoOutput {
        List<VideoTarget> targets = List.of();
        boolean disposed;
        @Override public void setDocument(AnimationFile file) {}
        @Override public OptionalDouble duration(String assetId) { return OptionalDouble.of(3); }
        @Override public ReadOnlyObjectProperty<MediaPlayer> playerProperty(String layerId) {
            return new SimpleObjectProperty<>();
        }
        @Override public void apply(List<VideoTarget> targets) { this.targets = targets; }
        @Override public void dispose() { disposed = true; }
    }
}
