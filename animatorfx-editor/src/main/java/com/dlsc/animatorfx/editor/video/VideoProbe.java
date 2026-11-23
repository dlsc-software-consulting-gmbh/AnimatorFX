package com.dlsc.animatorfx.editor.video;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.media.AudioTrack;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.VideoTrack;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Asynchronous metadata probing; the temporary decoder is always muted and never played. */
public final class VideoProbe {
    static final ExecutorService IO = Executors.newCachedThreadPool(action -> {
        Thread thread = new Thread(action, "animatorfx-video-import");
        thread.setDaemon(true);
        return thread;
    });

    private VideoProbe() {
    }

    public static CompletableFuture<VideoInfo> probe(byte[] bytes, String extension) {
        Probe probe = new Probe();
        probe.result.whenComplete((info, error) -> Platform.runLater(probe::release));
        IO.execute(() -> {
            Path file = null;
            try {
                file = Files.createTempFile("animatorfx-video-probe-", "m4v".equals(extension) ? ".m4v" : ".mp4");
                Files.write(file, bytes);
                Path written = file;
                Platform.runLater(() -> probe.open(written));
            } catch (IOException | RuntimeException e) {
                delete(file);
                Platform.runLater(() -> probe.result.completeExceptionally(e));
            }
        });
        return probe.result;
    }

    private static final class Probe {
        final CompletableFuture<VideoInfo> result = new CompletableFuture<>();
        MediaPlayer player;
        Path file;
        PauseTransition limit;

        void open(Path file) {
            this.file = file;
            if (result.isDone()) {
                release();
                return;
            }
            limit = new PauseTransition(Duration.seconds(4));
            limit.setOnFinished(event -> result.completeExceptionally(new IOException("Video readiness timed out")));
            limit.play();
            try {
                Media media = new Media(file.toUri().toString());
                media.setOnError(() -> result.completeExceptionally(media.getError()));
                player = new MediaPlayer(media);
                player.setMute(true);
                player.setVolume(0);
                player.setOnError(() -> result.completeExceptionally(player.getError()));
                player.setOnHalted(() -> result.completeExceptionally(new IOException("Video decoder halted")));
                player.setOnReady(() -> {
                    if (result.isDone()) return;
                    if (media.getTracks().stream().noneMatch(VideoTrack.class::isInstance)) {
                        result.completeExceptionally(new IOException("The file contains no video track"));
                        return;
                    }
                    try {
                        result.complete(new VideoInfo(media.getDuration().toSeconds(), media.getWidth(),
                                media.getHeight(), media.getTracks().stream().anyMatch(AudioTrack.class::isInstance)));
                    } catch (IllegalArgumentException e) {
                        result.completeExceptionally(e);
                    }
                });
            } catch (RuntimeException | LinkageError e) {
                result.completeExceptionally(e);
            }
        }

        void release() {
            if (limit != null) limit.stop();
            if (player != null) {
                player.setOnReady(null);
                player.setOnError(null);
                player.setOnHalted(null);
                player.getMedia().setOnError(null);
                player.dispose();
                player = null;
            }
            Path old = file;
            file = null;
            if (old != null) IO.execute(() -> delete(old));
        }
    }

    private static void delete(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.getLogger(VideoProbe.class.getName()).log(System.Logger.Level.WARNING,
                    "Could not delete temporary video probe: " + file, e);
        }
    }
}
