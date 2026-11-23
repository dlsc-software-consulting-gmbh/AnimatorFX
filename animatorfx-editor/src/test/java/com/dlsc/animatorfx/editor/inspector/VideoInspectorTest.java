package com.dlsc.animatorfx.editor.inspector;

import com.dlsc.animatorfx.editor.inspector.fields.NumberField;
import com.dlsc.animatorfx.editor.inspector.sections.VideoSection;
import com.dlsc.animatorfx.editor.ui.EditorUiTest;
import com.dlsc.animatorfx.editor.video.EditorVideo;
import com.dlsc.animatorfx.editor.video.VideoImport;
import com.dlsc.animatorfx.editor.video.VideoInfo;
import com.dlsc.animatorfx.model.*;
import javafx.event.ActionEvent;
import javafx.scene.control.CheckBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class VideoInspectorTest extends EditorUiTest {
    @BeforeEach
    void content() {
        EditorVideo.setOutputFactory(() -> null);
        VideoImport.setProbe((bytes, extension) ->
                CompletableFuture.completedFuture(new VideoInfo(2, 320, 180, true)));
        run(() -> doc.addAsset(new Asset("clip", Asset.Kind.VIDEO, "assets/clip.mp4", "Clip"), new byte[]{1, 2}));
        add(new VideoLayer(LayerBase.create("Clip", 3).withId("v"), "clip",
                320, 180, 0, Animatable.of(1.0), false, false));
        select("v");
    }

    @AfterEach
    void restore() {
        VideoImport.setProbe(null);
        EditorVideo.setOutputFactory(null);
    }

    private VideoLayer video() {
        return (VideoLayer) layer("v");
    }

    @Test
    void dimensionsOffsetAndMuteEditTheRealDocumentAndAreUndoable() {
        InspectorView inspector = window.getSidePanel().getInspector();
        assertTrue(fx(() -> inspector.getVisibleSections().stream().anyMatch(section -> section.title().equals("Video"))));
        NumberField width = fx(() -> (NumberField) inspector.lookup("#inspector-video-width"));
        NumberField offset = fx(() -> (NumberField) inspector.lookup("#inspector-video-offset"));
        run(() -> { width.setText("480"); width.fireEvent(new ActionEvent()); });
        assertEquals(480, video().width());
        run(doc::undo);
        assertEquals(320, video().width());
        run(() -> { offset.setText("0.25"); offset.fireEvent(new ActionEvent()); });
        assertEquals(0.25, video().clipOffset(), 1e-9);
        run(() -> ((CheckBox) inspector.lookup("#inspector-video-mute")).fire());
        assertTrue(video().muted());
        assertTrue(video().base().visible());
    }

    @Test
    void videoVolumeCanBeKeyframedAlongsideVisualProperties() {
        VideoSection section = window.getSidePanel().getInspector().section(VideoSection.class).orElseThrow();
        PropertyRows.NumberRow volume = (PropertyRows.NumberRow) section.propertyRow("volume").orElseThrow();
        playhead(1);
        run(() -> volume.toggle().fire());
        run(() -> { volume.field().setText("25"); volume.field().fireEvent(new ActionEvent()); });
        assertTrue(video().volume().isAnimated());
        assertEquals(0.25, video().volume().at(1), 1e-9);
        assertTrue(window.getSidePanel().getInspector().getVisibleSections().stream()
                .anyMatch(value -> value.title().equals("Transform")));
    }

    @Test
    void failedMetadataDoesNotRemainLabeledAsLoading() {
        VideoImport.setProbe((bytes, extension) ->
                CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported video")));
        playhead(0.1);
        var label = fx(() -> (javafx.scene.control.Label) window.getSidePanel().getInspector()
                .lookup("#inspector-video-info"));
        assertTrue(fx(label::getText).contains("unavailable"));
    }
}
