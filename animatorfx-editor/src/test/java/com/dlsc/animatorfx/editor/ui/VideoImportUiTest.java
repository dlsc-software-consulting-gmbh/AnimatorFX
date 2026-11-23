package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.video.EditorVideo;
import com.dlsc.animatorfx.editor.video.VideoImport;
import com.dlsc.animatorfx.editor.video.VideoInfo;
import com.dlsc.animatorfx.editor.inspector.AssetsView;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.VideoLayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class VideoImportUiTest extends EditorUiTest {
    @BeforeEach
    void replaceNativeBoundaries() {
        EditorVideo.setOutputFactory(() -> null);
        VideoImport.setProbe((bytes, extension) ->
                CompletableFuture.completedFuture(new VideoInfo(0.75, 320, 180, true)));
    }

    @AfterEach
    void restoreNativeBoundaries() {
        VideoImport.setProbe(null);
        EditorVideo.setOutputFactory(null);
    }

    private void settle() throws Exception {
        fx(() -> window.getImportActions().whenVideoImported()).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
        layout();
    }

    @Test
    void menuImportEmbedsAndSelectsTheClipAsOneUndoStep() throws Exception {
        Path source = dir.resolve("Title clip.mp4");
        Files.write(source, new byte[]{1, 2, 3, 4});
        run(() -> {
            doc.setPlayhead(1);
            window.getImportActions().setVideoPicker(owner -> List.of(source));
            window.menuItem("menu-file-import-video").fire();
        });
        settle();
        assertEquals(1, doc.getAnimation().assets().size());
        Asset asset = doc.getAnimation().assets().getFirst();
        assertEquals(Asset.Kind.VIDEO, asset.kind());
        VideoLayer video = (VideoLayer) doc.getAnimation().layers().getFirst();
        assertEquals("Title clip", video.name());
        assertEquals(1, video.base().inPoint());
        assertEquals(1.75, video.base().outPoint());
        assertEquals(320, video.width());
        assertEquals(List.of(video.id()), List.copyOf(doc.getSelection().layerIds()));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, doc.getFileContent().assets().get(asset.id()));
        run(doc::undo);
        assertTrue(doc.getAnimation().assets().isEmpty());
        assertTrue(doc.getAnimation().layers().isEmpty());
        run(doc::redo);
        assertEquals(1, doc.getAnimation().layers().size());
    }

    @Test
    void oversizedAndFailedProbesLeaveNoPartialImport() throws Exception {
        Path large = dir.resolve("large.mp4");
        try (RandomAccessFile file = new RandomAccessFile(large.toFile(), "rw")) {
            file.setLength(50L * 1024 * 1024 + 1);
        }
        run(() -> window.getImportActions().importVideo(doc, large));
        settle();
        assertTrue(doc.getAnimation().assets().isEmpty());
        assertTrue(fx(() -> window.getNoticeBar().getMessages()).stream().anyMatch(text -> text.contains("50 MiB")));
        Path bad = dir.resolve("bad.mp4");
        Files.write(bad, new byte[]{1});
        VideoImport.setProbe((bytes, extension) ->
                CompletableFuture.failedFuture(new IllegalArgumentException("No video track")));
        run(() -> window.getImportActions().importVideo(doc, bad));
        settle();
        assertTrue(doc.getAnimation().layers().isEmpty());
        assertTrue(doc.getAnimation().assets().isEmpty());
    }

    @Test
    void cancellationStopsTheProbeAndClosedDocumentsIgnoreLateResults() throws Exception {
        Path source = dir.resolve("pending.mp4");
        Files.write(source, new byte[]{1, 2});
        CompletableFuture<VideoInfo> pending = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean probing = new java.util.concurrent.atomic.AtomicBoolean();
        VideoImport.setProbe((bytes, extension) -> { probing.set(true); return pending; });
        var result = fx(() -> window.getImportActions().importVideo(doc, source));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, probing::get);
        run(() -> result.cancel(true));
        assertTrue(pending.isCancelled());
        assertTrue(doc.getAnimation().assets().isEmpty());
        CompletableFuture<VideoInfo> late = new CompletableFuture<>();
        probing.set(false);
        VideoImport.setProbe((bytes, extension) -> { probing.set(true); return late; });
        var closed = fx(() -> window.getImportActions().importVideo(doc, source));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, probing::get);
        run(() -> {
            window.getWorkspace().remove(doc);
            late.complete(new VideoInfo(1, 320, 180, true));
        });
        assertTrue(closed.get(5, TimeUnit.SECONDS).isEmpty());
        assertTrue(doc.getAnimation().assets().isEmpty());
        assertTrue(doc.getAnimation().layers().isEmpty());
    }

    @Test
    void assetsWaitForMetadataAndPlaceRepeatedlyWithoutCopyingBytes() throws Exception {
        CompletableFuture<VideoInfo> pending = new CompletableFuture<>();
        VideoImport.setProbe((bytes, extension) -> pending);
        AssetsView assets = fx(AssetsView::new);
        run(() -> {
            doc.addAsset(new Asset("clip", Asset.Kind.VIDEO, "assets/clip.mp4", "clip.mp4"), new byte[]{1, 2});
            assets.setDocument(doc);
        });
        byte[] source = doc.getFileContent().assets().get("clip");
        var first = fx(() -> assets.placeVideo("clip"));
        assertFalse(first.isDone(), "A single click must wait for metadata instead of doing nothing");
        run(() -> pending.complete(new VideoInfo(1, 320, 180, true)));
        assertTrue(first.get(5, TimeUnit.SECONDS).isPresent());
        var second = fx(() -> assets.placeVideo("clip"));
        assertTrue(second.get(5, TimeUnit.SECONDS).isPresent());
        assertEquals(2, doc.getAnimation().layers().size());
        assertEquals(1, doc.getAnimation().assets().size());
        assertSame(source, doc.getFileContent().assets().get("clip"));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({
            org.junit.jupiter.api.condition.OS.MAC, org.junit.jupiter.api.condition.OS.LINUX})
    void cancellationClosesABlockedSourceRead() throws Exception {
        Path pipe = dir.resolve("blocked.mp4");
        Process mkfifo = new ProcessBuilder("mkfifo", pipe.toString()).start();
        assertTrue(mkfifo.waitFor(5, TimeUnit.SECONDS) && mkfifo.exitValue() == 0);
        var read = fx(() -> VideoImport.read(pipe, message -> () -> {}));
        CompletableFuture<Void> connected = new CompletableFuture<>();
        CompletableFuture<Boolean> writer = CompletableFuture.supplyAsync(() -> {
            try (var out = Files.newOutputStream(pipe)) {
                connected.complete(null);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (System.nanoTime() < deadline) {
                    out.write(1);
                    Thread.sleep(25);
                }
                return false;
            } catch (java.io.IOException e) {
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        connected.get(5, TimeUnit.SECONDS);
        run(() -> read.cancel(true));
        assertTrue(writer.get(5, TimeUnit.SECONDS), "Cancellation must close the blocked reader, not just its result future");
        assertTrue(doc.getAnimation().assets().isEmpty());
    }
}
