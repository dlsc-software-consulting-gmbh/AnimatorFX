package com.dlsc.animatorfx.player;

import com.dlsc.animatorfx.model.*;
import com.dlsc.animatorfx.model.io.AnimationFile;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testfx.api.FxToolkit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "animatorfx.nativeVideo", matches = "true")
class NativeVideoPlaybackTest {
    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void decodesPausedSeeksAndMutedForwardPlayback() throws Exception {
        byte[] bytes;
        try (var in = getClass().getResourceAsStream("video/red-blue.mp4")) {
            assertNotNull(in);
            bytes = in.readAllBytes();
        }
        VideoLayer clip = new VideoLayer(LayerBase.create("Clip", 2).withId("v"), "clip",
                320, 180, 0, Animatable.of(0.5), false, false);
        Animation animation = Animation.create("Video", 320, 180, 2).withLayers(List.of(clip))
                .withAssets(List.of(new Asset("clip", Asset.Kind.VIDEO, "assets/clip.mp4", "Clip")));
        AnimationFile file = new AnimationFile(animation, Map.of("clip", bytes));
        AnimationView view = fx(AnimationView::new);
        Stage stage = fx(() -> {
            Stage window = new Stage();
            view.getPlayer().setAudioOutputFactory(ignored -> {
                fail("Video must not allocate a separate audio decoder");
                return null;
            });
            view.setAnimation(file);
            view.getPlayer().setVolume(0.8);
            window.setScene(new Scene(view, 320, 180));
            window.show();
            view.seek(0.25);
            return window;
        });
        try {
            assertNotNull(fx(() -> mediaView(view)), "Video layers must render a MediaView");
            await(() -> color(view, true), "Paused seek did not decode the red frame");
            fx(() -> { view.seek(1.25); return null; });
            await(() -> color(view, false), "Paused seek did not decode the blue frame");
            fx(() -> { view.seek(0.25); return null; });
            await(() -> color(view, true), "Restart seek did not return to red");
            fx(() -> { view.play(); return null; });
            await(() -> fxUnchecked(() -> mediaView(view).getMediaPlayer() != null
                    && mediaView(view).getMediaPlayer().getStatus() == javafx.scene.media.MediaPlayer.Status.PLAYING
                    && mediaView(view).getMediaPlayer().getVolume() > 0.39), "Video soundtrack did not start");
            fx(() -> { view.getPlayer().setMuted(true); return null; });
            await(() -> fxUnchecked(() -> view.getCurrentTime() > 1.05) && color(view, false),
                    "Muting froze the video picture");
            assertEquals(0, fx(() -> mediaView(view).getMediaPlayer().getVolume()), 1e-9);
            fx(() -> { view.pause(); return null; });
            double paused = fx(view::getCurrentTime);
            Thread.sleep(150);
            assertEquals(paused, fx(view::getCurrentTime), 0.001);
        } finally {
            fx(() -> { view.dispose(); stage.close(); return null; });
        }
    }

    @Test
    void instancesAreIndependentAndVisualEditsReuseTheirDecoders() throws Exception {
        byte[] bytes;
        try (var in = getClass().getResourceAsStream("video/red-blue.mp4")) {
            assertNotNull(in);
            bytes = in.readAllBytes();
        }
        VideoLayer first = new VideoLayer(LayerBase.create("First", 2).withId("a"), "clip",
                320, 180, 0, Animatable.of(1.0), false, false);
        VideoLayer second = first.withBase(first.base().withId("b")).withClipOffset(0.5);
        Animation animation = Animation.create("Video", 640, 180, 2).withLayers(List.of(first, second))
                .withAssets(List.of(new Asset("clip", Asset.Kind.VIDEO, "assets/clip.mp4", "Clip")));
        AnimationPlayer player = fx(() -> new AnimationPlayer(new AnimationFile(animation, Map.of("clip", bytes))));
        try {
            await(() -> fxUnchecked(() -> player.video().playerProperty("a").get() != null
                    && player.video().playerProperty("b").get() != null), "Independent video decoders were not ready");
            var a = fx(() -> player.video().playerProperty("a").get());
            var b = fx(() -> player.video().playerProperty("b").get());
            var extracted = java.nio.file.Path.of(java.net.URI.create(fx(() -> a.getMedia().getSource())));
            assertNotSame(a, b);
            fx(() -> {
                player.setAnimation(new AnimationFile(animation.withLayers(List.of(first.withWidth(160), second)),
                        Map.of("clip", bytes)));
                return null;
            });
            assertSame(a, fx(() -> player.video().playerProperty("a").get()));
            assertSame(b, fx(() -> player.video().playerProperty("b").get()));
            fx(() -> {
                player.setAnimation(new AnimationFile(animation.withLayers(List.of(second)), Map.of("clip", bytes)));
                return null;
            });
            assertEquals(javafx.scene.media.MediaPlayer.Status.DISPOSED, fx(a::getStatus));
            assertSame(b, fx(() -> player.video().playerProperty("b").get()));
            fx(() -> {
                player.setAnimation(new AnimationFile(animation.withLayers(List.of()), Map.of("clip", bytes)));
                return null;
            });
            assertEquals(javafx.scene.media.MediaPlayer.Status.DISPOSED, fx(b::getStatus));
            await(() -> !java.nio.file.Files.exists(extracted),
                    "Removing the last video layer must release its extracted file, even if the asset remains");
        } finally {
            fx(() -> { player.dispose(); return null; });
        }
    }

    @Test
    void sourceEndLoopingDetachAndSkinReplacementRespectMediaOwnership() throws Exception {
        byte[] bytes;
        try (var in = getClass().getResourceAsStream("video/red-blue.mp4")) {
            assertNotNull(in);
            bytes = in.readAllBytes();
        }
        VideoLayer clip = new VideoLayer(LayerBase.create("Clip", 3).withId("v"), "clip",
                320, 180, 0, Animatable.of(1.0), false, false);
        Animation animation = Animation.create("Video", 320, 180, 3).withLayers(List.of(clip))
                .withAssets(List.of(new Asset("clip", Asset.Kind.VIDEO, "assets/clip.mp4", "Clip")));
        AnimationView view = fx(() -> new AnimationView(new AnimationFile(animation, Map.of("clip", bytes))));
        Stage stage = fx(() -> {
            Stage window = new Stage();
            window.setScene(new Scene(view, 320, 180));
            window.show();
            view.seek(0.25);
            return window;
        });
        try {
            await(() -> color(view, true), "Initial frame was not red");
            fx(() -> { view.seek(2.1); return null; });
            assertNull(fx(() -> mediaView(view).getMediaPlayer()), "Nonlooping source end must hide the picture");
            fx(() -> {
                view.setAnimation(new AnimationFile(animation.withLayers(List.of(clip.withLoop(true))), Map.of("clip", bytes)));
                view.seek(2.25);
                return null;
            });
            await(() -> color(view, true), "Loop did not wrap to red");
            var old = fx(() -> mediaView(view).getMediaPlayer());
            fx(() -> { stage.getScene().setRoot(new javafx.scene.layout.Pane()); return null; });
            assertEquals(javafx.scene.media.MediaPlayer.Status.DISPOSED, fx(old::getStatus));
            fx(() -> { stage.getScene().setRoot(view); return null; });
            await(() -> color(view, true), "Reattaching the view did not recreate video");
            var beforeSkin = fx(() -> mediaView(view).getMediaPlayer());
            fx(() -> { view.setSkin(new AnimationViewSkin(view)); return null; });
            assertEquals(javafx.scene.media.MediaPlayer.Status.DISPOSED, fx(beforeSkin::getStatus));
            await(() -> color(view, true), "Replacing the skin did not reconnect video");
            assertNotSame(beforeSkin, fx(() -> mediaView(view).getMediaPlayer()));
        } finally {
            fx(() -> { view.dispose(); stage.close(); return null; });
        }
    }

    private static MediaView mediaView(Parent root) {
        for (var child : root.getChildrenUnmodifiable()) {
            if (child instanceof MediaView media) return media;
            if (child instanceof Parent parent) {
                MediaView media = mediaView(parent);
                if (media != null) return media;
            }
        }
        return null;
    }

    private static boolean color(AnimationView view, boolean red) {
        return fxUnchecked(() -> {
            WritableImage image = view.snapshot(null, null);
            Color color = image.getPixelReader().getColor((int) image.getWidth() / 2, (int) image.getHeight() / 2);
            return red ? color.getRed() > 0.7 && color.getBlue() < 0.3
                    : color.getBlue() > 0.7 && color.getRed() < 0.3;
        });
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail(message);
    }

    private static <T> T fxUnchecked(Callable<T> work) {
        try {
            return fx(work);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
}
