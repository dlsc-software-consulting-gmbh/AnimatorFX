package com.dlsc.animatorfx.player.internal.video;

import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.Mask;
import com.dlsc.animatorfx.model.VideoLayer;
import com.dlsc.animatorfx.player.Evaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VideoScheduleTest {

    private static VideoLayer clip() {
        return new VideoLayer(LayerBase.create("Clip", 2.5).withId("v").withInPoint(0.5),
                "clip", 320, 180, 0.2, Animatable.of(0.5), false, false);
    }

    private static VideoTarget target(Layer layer, double time, boolean playing, double rate,
                                      boolean reversing, boolean muted) {
        Animation animation = Animation.create("Video", 800, 600, 3).withLayers(List.of(layer));
        return VideoSchedule.targets(animation, Evaluator.sample(animation, time), playing, rate,
                reversing, muted, 0.4, Map.of("clip", 1.0)).getFirst();
    }

    @Test
    void evaluatesVolumeWithoutLosingVisualProperties() {
        VideoLayer video = new VideoLayer(LayerBase.create("Clip", 2.5).withId("v").withInPoint(0.5)
                .withOpacity(Animatable.of(0.4)), "clip", 320, 180, 0.2, Animatable.of(0.5), false, false);
        Animation animation = Animation.create("Video", 800, 600, 3).withLayers(List.of(video));
        var state = Evaluator.sample(animation, 0.8).get("v");
        assertEquals(0.5, state.volume());
        assertEquals(0.4, state.opacity());
    }

    @Test
    void positionsPictureAndMultipliesVolume() {
        VideoTarget target = target(clip(), 0.8, true, 1, false, false);
        assertTrue(target.visible());
        assertTrue(target.playing());
        assertEquals(0.5, target.mediaTime(), 1e-9);
        assertEquals(0.2, target.volume(), 1e-9);
        assertFalse(target(clip(), 0.499, true, 1, false, false).visible());
        assertTrue(target(clip(), 0.5, true, 1, false, false).visible());
        assertFalse(target(clip(), 2.5, true, 1, false, false).visible());
    }

    @Test
    void sourceEndHidesPictureUnlessLooping() {
        assertFalse(target(clip(), 1.5, true, 1, false, false).visible());
        VideoTarget wrapped = target(clip().withLoop(true), 1.8, true, 1, false, false);
        assertTrue(wrapped.visible());
        assertEquals(0.5, wrapped.mediaTime(), 1e-9);
    }

    @Test
    void mutedForwardPlaybackKeepsPictureMovingButPauseAndReverseSeekSilently() {
        for (VideoLayer layer : List.of(clip(), clip().withMuted(true))) {
            VideoTarget muted = target(layer, 0.8, true, 1, false, true);
            assertTrue(muted.playing());
            assertTrue(muted.visible());
            assertEquals(0, muted.volume());
        }
        for (double rate : new double[]{0, -1, 9}) {
            VideoTarget silent = target(clip(), 0.8, true, rate, false, false);
            assertTrue(silent.visible());
            assertFalse(silent.playing());
            assertEquals(0.5, silent.mediaTime(), 1e-9);
            assertEquals(0, silent.volume());
        }
        assertFalse(target(clip(), 0.8, false, 1, false, false).playing());
        assertFalse(target(clip(), 0.8, true, 1, true, false).playing());
    }

    @Test
    void hiddenOrInactiveGroupsHideAndSilenceChildrenButOpacityDoesNot() {
        GroupLayer hidden = new GroupLayer(LayerBase.create("Group", 3).withVisible(false), List.of(clip()));
        assertFalse(target(hidden, 0.8, true, 1, false, false).visible());
        GroupLayer late = hidden.withBase(hidden.base().withVisible(true).withInPoint(1));
        assertFalse(target(late, 0.8, true, 1, false, false).playing());
        VideoLayer transparent = clip().withBase(clip().base().withOpacity(Animatable.of(0.0)));
        assertEquals(0.2, target(transparent, 0.8, true, 1, false, false).volume(), 1e-9);
    }

    @Test
    void geometryOnlyMasksDoNotRequestAVideoDecoder() {
        Layer shape = com.dlsc.animatorfx.player.TestDocs.rect("shape", 100, 100);
        shape = shape.withBase(shape.base().withMask(new Mask("v", Mask.Mode.CLIP)));
        Animation animation = Animation.create("Video", 800, 600, 3).withLayers(List.of(clip(), shape));
        VideoTarget mask = VideoSchedule.targets(animation, Evaluator.sample(animation, 0.8), true, 1,
                false, false, 1, Map.of()).getFirst();
        assertFalse(mask.visible());
        assertFalse(mask.playing());
        assertEquals(0, mask.volume());
    }
}
