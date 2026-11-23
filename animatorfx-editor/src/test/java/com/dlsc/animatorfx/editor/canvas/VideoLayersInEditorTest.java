package com.dlsc.animatorfx.editor.canvas;

import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.doc.RoundTrip;
import com.dlsc.animatorfx.editor.inspector.AssetsView;
import com.dlsc.animatorfx.editor.video.EditorVideo;
import com.dlsc.animatorfx.editor.video.VideoImport;
import com.dlsc.animatorfx.editor.video.VideoInfo;
import com.dlsc.animatorfx.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class VideoLayersInEditorTest extends CanvasTestBase {
    @BeforeEach
    void content() {
        EditorVideo.setOutputFactory(() -> null);
        VideoImport.setProbe((bytes, extension) ->
                CompletableFuture.completedFuture(new VideoInfo(2, 320, 180, true)));
        run(() -> doc.addAsset(new Asset("clip", Asset.Kind.VIDEO, "assets/clip.mp4", "Clip"), new byte[]{1, 2, 3}));
        add(new VideoLayer(LayerBase.create("Clip", 3).withId("v").withTransform(Transform.at(100, 100)),
                "clip", 320, 180, 0, Animatable.of(1.0), false, false));
    }

    @AfterEach
    void restore() {
        VideoImport.setProbe(null);
        EditorVideo.setOutputFactory(null);
    }

    @Test
    void videosCanBeSelectedResizedAndUndoneLikeOtherVisualLayers() {
        click(150, 150);
        assertEquals(List.of("v"), selection());
        dragFromTo(420, 280, 580, 370);
        var box = fx(() -> canvas.getGeometry().localBox(layer("v")));
        assertEquals(320, box.width(), 0.1);
        assertEquals(1.5, layer("v").base().transform().scale().at(0).x(), 0.05);
        run(doc::undo);
        assertEquals(1, layer("v").base().transform().scale().at(0).x(), 1e-9);
    }

    @Test
    void copyingIntoAnotherDocumentIncludesItsEmbeddedBytes() {
        select("v");
        run(() -> window.menuItem("menu-edit-copy").fire());
        EditorDocument other = fx(() -> window.getFileActions().newDocument());
        layout();
        run(() -> window.menuItem("menu-edit-paste").fire());
        assertEquals(1, other.getAnimation().layers().size());
        assertInstanceOf(VideoLayer.class, other.getAnimation().layers().getFirst());
        assertArrayEquals(new byte[]{1, 2, 3}, other.getFileContent().assets().get("clip"));
        RoundTrip.of(other.getFileContent());
    }

    @Test
    void assetRemovalIsRefusedWhileVideoLayersUseIt() {
        assertEquals(1, AssetsView.usage(doc.getAnimation(), "clip"));
        AssetsView assets = fx(() -> new AssetsView());
        run(() -> { assets.setDocument(doc); assets.remove("clip"); });
        assertEquals(1, doc.getAnimation().assets().size());
    }
}
