package com.dlsc.animatorfx.editor.video;

import com.dlsc.animatorfx.editor.audio.AudioImport;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.model.Asset;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Bounded embedded-video imports and identity-keyed metadata shared by editor views. */
public final class VideoImport {
    public static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final Map<byte[], CompletableFuture<VideoInfo>> INFO = new WeakHashMap<>();
    private static final ReadOnlyLongWrapper revision = new ReadOnlyLongWrapper();
    private static BiFunction<byte[], String, CompletableFuture<VideoInfo>> prober = VideoProbe::probe;

    public record Clip(String fileName, String extension, byte[] bytes, VideoInfo info) {
        public String baseName() {
            return AudioImport.baseName(fileName);
        }
    }

    private VideoImport() {
    }

    /** Changes when asynchronous video metadata becomes available. */
    public static final ReadOnlyLongProperty revisionProperty() {
        return revision.getReadOnlyProperty();
    }

    /** For tooling: substitutes metadata probing; null restores JavaFX media. */
    public static void setProbe(BiFunction<byte[], String, CompletableFuture<VideoInfo>> probe) {
        prober = probe == null ? VideoProbe::probe : probe;
        INFO.clear();
    }

    public static boolean isVideo(Path file) {
        String extension = AudioImport.extension(file.getFileName().toString());
        return extension.equals("mp4") || extension.equals("m4v");
    }

    /** Call on the FX thread; completion and busy-message cleanup happen there too. */
    public static CompletableFuture<Clip> read(Path file, Function<String, Runnable> busy) {
        CompletableFuture<Clip> result = new CompletableFuture<>();
        String name = file.getFileName().toString();
        String extension = AudioImport.extension(name);
        Runnable[] dismiss = {null};
        Future<?>[] reading = {null};
        CompletableFuture<?>[] probing = {null};
        PauseTransition busyTimer = new PauseTransition(Duration.millis(200));
        busyTimer.setOnFinished(event -> dismiss[0] = busy.apply("Reading " + name + "..."));
        PauseTransition deadline = new PauseTransition(Duration.seconds(60));
        deadline.setOnFinished(event -> result.completeExceptionally(new IOException("Reading the video timed out")));
        result.whenComplete((clip, error) -> {
            Runnable finish = () -> {
                busyTimer.stop();
                deadline.stop();
                if (dismiss[0] != null) dismiss[0].run();
                if (reading[0] != null && !reading[0].isDone()) reading[0].cancel(true);
                if (probing[0] != null && !probing[0].isDone()) probing[0].cancel(true);
            };
            if (Platform.isFxApplicationThread()) finish.run();
            else Platform.runLater(finish);
        });
        busyTimer.play();
        deadline.play();
        reading[0] = VideoProbe.IO.submit(() -> {
            try {
                if (!isVideo(file)) throw new IOException("It is not an MP4 or M4V video");
                if (Files.size(file) > MAX_BYTES) throw new IOException("It is larger than 50 MiB");
                byte[] bytes;
                try (var in = Channels.newInputStream(FileChannel.open(file))) {
                    bytes = in.readNBytes((int) MAX_BYTES + 1);
                }
                if (bytes.length > MAX_BYTES) throw new IOException("It is larger than 50 MiB");
                Platform.runLater(() -> {
                    if (result.isDone()) return;
                    try {
                        CompletableFuture<VideoInfo> metadata = prober.apply(bytes, extension);
                        probing[0] = metadata;
                        metadata.whenComplete((info, error) -> {
                            Runnable finish = () -> {
                                if (error != null) result.completeExceptionally(error);
                                else {
                                    INFO.put(bytes, CompletableFuture.completedFuture(info));
                                    result.complete(new Clip(name, extension, bytes, info));
                                }
                            };
                            if (Platform.isFxApplicationThread()) finish.run();
                            else Platform.runLater(finish);
                        });
                    } catch (RuntimeException e) {
                        result.completeExceptionally(e);
                    }
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> result.completeExceptionally(e));
            }
        });
        return result;
    }

    private static Asset asset(Clip clip) {
        String id = UUID.randomUUID().toString();
        return new Asset(id, Asset.Kind.VIDEO, "assets/" + id + "." + clip.extension(), clip.fileName());
    }

    public static Asset addAsset(EditorDocument document, Clip clip) {
        Asset asset = asset(clip);
        document.addAsset(asset, clip.bytes());
        adopt(document, asset, clip);
        return asset;
    }

    public static String addLayer(EditorDocument document, Clip clip) {
        Asset asset = asset(clip);
        String layerId = UUID.randomUUID().toString();
        document.execute(Commands.addVideoLayer(layerId, clip.baseName(), asset.id(), document.getPlayhead(),
                clip.info().duration(), clip.info().width(), clip.info().height()), Map.of(asset, clip.bytes()));
        adopt(document, asset, clip);
        document.getSelection().layerIds().setAll(layerId);
        return layerId;
    }

    private static void adopt(EditorDocument document, Asset asset, Clip clip) {
        byte[] held = document.getFileContent().assets().get(asset.id());
        if (held != null) {
            CompletableFuture<VideoInfo> previous = INFO.put(held, CompletableFuture.completedFuture(clip.info()));
            if (previous != null && !previous.isDone()) previous.cancel(true);
            revision.set(revision.get() + 1);
        }
    }

    /** The shared probe for an existing embedded asset; completed on the FX thread. */
    public static CompletableFuture<VideoInfo> metadata(EditorDocument document, Asset asset) {
        byte[] bytes = document.getFileContent().assets().get(asset.id());
        if (bytes == null || asset.kind() != Asset.Kind.VIDEO) {
            return CompletableFuture.failedFuture(new IOException("Missing video asset"));
        }
        return INFO.computeIfAbsent(bytes, content -> {
            CompletableFuture<VideoInfo> future = prober.apply(content, AudioImport.extension(asset.path()));
            future.whenComplete((info, error) -> Platform.runLater(() -> revision.set(revision.get() + 1)));
            return future;
        });
    }

    public static Optional<VideoInfo> info(EditorDocument document, Asset asset) {
        CompletableFuture<VideoInfo> future = metadata(document, asset);
        return future.isCompletedExceptionally() || future.isCancelled() ? Optional.empty()
                : Optional.ofNullable(future.getNow(null));
    }

    public static Optional<String> place(EditorDocument document, String assetId) {
        Asset asset = document.getAnimation().assets().stream()
                .filter(value -> value.kind() == Asset.Kind.VIDEO && value.id().equals(assetId)).findFirst().orElse(null);
        if (asset == null) return Optional.empty();
        return info(document, asset).map(info -> {
            String layerId = UUID.randomUUID().toString();
            document.execute(Commands.addVideoLayer(layerId, AudioImport.baseName(asset.name()), asset.id(),
                    document.getPlayhead(), info.duration(), info.width(), info.height()));
            document.getSelection().layerIds().setAll(layerId);
            return layerId;
        });
    }
}
