package com.dlsc.animatorfx.player.internal.video;

import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.io.AnimationFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class VideoFilesTest {
    private static AnimationFile file(byte[] bytes) {
        return new AnimationFile(Animation.create("Test", 100, 100, 2).withAssets(List.of(
                new Asset("clip", Asset.Kind.VIDEO, "assets/../../clip.mp4", "Clip"))), Map.of("clip", bytes));
    }

    @Test
    void extractsUnchangedBytesOnceAndReleasesReplacedSources() throws Exception {
        byte[] source = {1, 2, 3};
        VideoFiles files = new VideoFiles();
        files.setDocument(file(source));
        Path first = files.path("clip").get(5, TimeUnit.SECONDS);
        assertArrayEquals(source, Files.readAllBytes(first));
        assertTrue(first.getFileName().toString().endsWith(".mp4"));
        files.setDocument(file(source));
        assertEquals(first, files.path("clip").get(5, TimeUnit.SECONDS));
        files.setDocument(file(new byte[]{4, 5}));
        Path second = files.path("clip").get(5, TimeUnit.SECONDS);
        assertNotEquals(first, second);
        assertArrayEquals(new byte[]{4, 5}, Files.readAllBytes(second));
        files.dispose();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((Files.exists(first) || Files.exists(second)) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
    }
}
