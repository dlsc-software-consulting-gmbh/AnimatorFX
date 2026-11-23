package com.dlsc.animatorfx.editor.doc;

import com.dlsc.animatorfx.model.*;
import com.dlsc.animatorfx.model.io.AnimationFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VideoCommandsTest {
    private static Animation document() {
        return Animation.create("Video", 800, 600, 3).withFrameRate(10).withAssets(List.of(
                new Asset("clip", Asset.Kind.VIDEO, "assets/clip.mp4", "Clip")));
    }

    @Test
    void placesAtSnappedPlayheadAndKeepsNaturalDimensions() {
        Animation after = Commands.addVideoLayer("v", "Clip", "clip", 1.04, 0.75, 320, 180).apply(document());
        VideoLayer video = (VideoLayer) Layers.find(after, "v").orElseThrow();
        assertEquals(1, video.base().inPoint(), 1e-9);
        assertEquals(1.75, video.base().outPoint(), 1e-9);
        assertEquals(320, video.width());
        assertEquals(180, video.height());
        assertEquals(after, RoundTrip.of(new AnimationFile(after, Map.of("clip", new byte[]{1, 2}))).animation());
        VideoLayer atEnd = (VideoLayer) Commands.addVideoLayer("v", "Clip", "clip", 3, 5, 320, 180)
                .apply(document()).layers().getFirst();
        assertEquals(0, atEnd.base().inPoint());
        assertEquals(3, atEnd.base().outPoint());
    }

    @Test
    void videoVolumeAndOffsetStayInTheSaveableRange() {
        Animation before = Commands.addVideoLayer("v", "Clip", "clip", 0, 2, 320, 180).apply(document());
        Animation changed = Commands.setClipOffset("v", -2).apply(before);
        changed = Commands.setValueAt("v", "volume", 0.5, 3.0, true).apply(changed);
        VideoLayer video = (VideoLayer) changed.layers().getFirst();
        assertEquals(0, video.clipOffset());
        assertEquals(1, video.volume().at(0.5));
        Animation shifted = Commands.setClipOffset("v", 0.5).apply(changed);
        assertEquals(0.5, ((VideoLayer) shifted.layers().getFirst()).clipOffset());
        RoundTrip.of(new AnimationFile(shifted, Map.of("clip", new byte[]{1, 2})));
    }

    @Test
    void missingOrNonvideoAssetsAreNotPlaced() {
        Animation before = document();
        assertSame(before, Commands.addVideoLayer("v", "Clip", "missing", 0, 2, 320, 180).apply(before));
        Animation image = before.withAssets(List.of(new Asset("clip", Asset.Kind.IMAGE, "assets/c.png", "Image")));
        assertSame(image, Commands.addVideoLayer("v", "Clip", "clip", 0, 2, 320, 180).apply(image));
    }
}
