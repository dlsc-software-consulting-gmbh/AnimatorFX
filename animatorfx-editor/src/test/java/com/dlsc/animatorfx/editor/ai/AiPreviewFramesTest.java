package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.Easing;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.VideoLayer;
import com.dlsc.animatorfx.model.io.AnimationFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AiPreviewFramesTest {
    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void rendersBoundedPngFramesWithTheActualScene() throws Exception {
        var red = new ShapeLayer(LayerBase.create("Red", 3),
                new Geometry.Rect(Animatable.of(new Vec2(100, 100)), Animatable.of(0.0)),
                Animatable.of(new Paint.Solid(new Rgba(1, 0, 0, 1))), null, null);
        Animation animation = Animation.create("Scene", 1000, 500, 3).withLayers(List.of(red));
        var frames = AiPreviewFrames.render(new AnimationFile(animation, Map.of())).get(10, TimeUnit.SECONDS);
        assertEquals(3, frames.size());
        var image = ImageIO.read(new ByteArrayInputStream(frames.getFirst()));
        assertNotNull(image);
        assertEquals(512, image.getWidth());
        assertEquals(256, image.getHeight());
        assertEquals(0xFFFF0000, image.getRGB(10, 10));
    }

    @Test
    void refusesToReviewInaccurateSnapshotsOfVideoLayers() {
        var video = new VideoLayer(LayerBase.create("Video", 3), "video", 100, 100,
                0, Animatable.of(1.0), false, false);
        var file = new AnimationFile(Animation.create("Video", 100, 100, 3).withLayers(List.of(video)), Map.of());
        assertThrows(java.util.concurrent.CompletionException.class, () -> AiPreviewFrames.render(file).join());
    }

    @Test
    void samplesTheMotionAtDifferentTimes() throws Exception {
        var base = LayerBase.create("Moving", 3);
        var position = Animatable.of(new Vec2(0, 0)).withKeyframe(0, new Vec2(0, 0), Easing.LINEAR)
                .withKeyframe(2, new Vec2(200, 0), Easing.LINEAR);
        base = base.withTransform(base.transform().withPosition(position));
        var shape = new ShapeLayer(base, new Geometry.Rect(Animatable.of(new Vec2(100, 100)), Animatable.of(0.0)),
                Animatable.of(new Paint.Solid(new Rgba(1, 0, 0, 1))), null, null);
        var file = new AnimationFile(Animation.create("Motion", 1000, 500, 3).withLayers(List.of(shape)), Map.of());
        var frames = AiPreviewFrames.render(file).get(10, TimeUnit.SECONDS);
        var first = ImageIO.read(new ByteArrayInputStream(frames.getFirst()));
        var last = ImageIO.read(new ByteArrayInputStream(frames.getLast()));
        assertEquals(0xFFFF0000, first.getRGB(10, 10));
        assertEquals(0, last.getRGB(10, 10) >>> 24);
        assertEquals(0xFFFF0000, last.getRGB(110, 10));
    }
}
