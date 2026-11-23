package com.dlsc.animatorfx.player.internal.video;

import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.io.AnimationFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lazy extraction off the FX thread. Archive paths are never used as filesystem paths. */
public final class VideoFiles {
    private final Map<String, Entry> entries = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "animatorfx-video-files");
        thread.setDaemon(true);
        return thread;
    });
    private AnimationFile file;
    private boolean disposed;

    private static final class Entry {
        final byte[] bytes;
        volatile boolean removed;
        volatile Path path;
        CompletableFuture<Path> future;

        Entry(byte[] bytes) {
            this.bytes = bytes;
        }
    }

    public void setDocument(AnimationFile file) {
        this.file = file;
        entries.entrySet().removeIf(item -> {
            Entry entry = item.getValue();
            if (file != null && file.assets().get(item.getKey()) == entry.bytes) {
                return false;
            }
            remove(entry);
            return true;
        });
    }

    public CompletableFuture<Path> path(String assetId) {
        if (disposed || file == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Video files are closed"));
        }
        Entry existing = entries.get(assetId);
        if (existing != null) {
            return existing.future;
        }
        Asset asset = file.animation().assets().stream()
                .filter(value -> value.id().equals(assetId) && value.kind() == Asset.Kind.VIDEO)
                .findFirst().orElse(null);
        byte[] bytes = file.assets().get(assetId);
        if (asset == null || bytes == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Missing video asset: " + assetId));
        }
        String extension = asset.path().toLowerCase(java.util.Locale.ROOT).endsWith(".m4v") ? ".m4v" : ".mp4";
        Entry entry = new Entry(bytes);
        entries.put(assetId, entry);
        entry.future = CompletableFuture.supplyAsync(() -> extract(entry, extension), worker);
        return entry.future;
    }

    public void retain(Set<String> assetIds) {
        entries.entrySet().removeIf(item -> {
            if (assetIds.contains(item.getKey())) return false;
            remove(item.getValue());
            return true;
        });
    }

    private static Path extract(Entry entry, String extension) {
        Path directory = null;
        Path path = null;
        try {
            directory = Files.createTempDirectory("animatorfx-video-");
            path = directory.resolve("clip" + extension);
            Files.write(path, entry.bytes);
            entry.path = path;
            if (entry.removed) {
                delete(path);
                throw new IllegalStateException("Video extraction was cancelled");
            }
            return path;
        } catch (IOException e) {
            if (path != null) delete(path);
            else if (directory != null) deleteDirectory(directory);
            throw new UncheckedIOException(e);
        }
    }

    private void remove(Entry entry) {
        entry.removed = true;
        entry.future.cancel(false);
        worker.execute(() -> delete(entry.path));
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        entries.values().forEach(this::remove);
        entries.clear();
        file = null;
        worker.shutdown();
    }

    private static void delete(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        } catch (IOException e) {
            System.getLogger(VideoFiles.class.getName()).log(System.Logger.Level.WARNING,
                    "Could not remove temporary video file: " + path, e);
        }
    }

    private static void deleteDirectory(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            System.getLogger(VideoFiles.class.getName()).log(System.Logger.Level.WARNING,
                    "Could not remove temporary video directory: " + path, e);
        }
    }
}
