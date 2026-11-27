package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.doc.Selection;
import com.dlsc.animatorfx.model.Keyframe;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.Layers;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.io.AnimationIO;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.transform.Transform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The files of {@code samples/animations} open in the editor. With
 * {@code -Danimatorfx.screenshots=DIR} the success sample is also rendered in both themes to
 * {@code final-editor-dark.png} and {@code final-editor-light.png}.
 */
class SamplesInEditorTest extends EditorUiTest {

    private static final Path SAMPLES = Path.of("..", "samples", "animations");

    private EditorDocument open(String name) {
        EditorDocument opened = fx(() -> window.getFileActions().open(SAMPLES.resolve(name + ".afx"))).orElseThrow();
        WaitForAsyncUtils.waitForFxEvents();
        layout();
        return opened;
    }

    @ParameterizedTest
    @ValueSource(strings = {"loader", "success", "morph", "card", "orbit", "attention"})
    void everySampleOpens(String name) throws IOException {
        EditorDocument opened = open(name);
        assertEquals(AnimationIO.read(SAMPLES.resolve(name + ".afx")).animation(), opened.getAnimation());
        assertEquals(opened, fx(() -> window.getWorkspace().getActiveDocument()));
        assertEquals(name + ".afx", opened.getTitle());
        assertFalse(opened.isDirty());
        assertEquals(List.of(), fx(() -> window.getNoticeBar().getMessages()));
        // one timeline row per top-level layer while nothing is expanded
        assertEquals(opened.getAnimation().layers().size(), fx(() -> timeline.getRows().size()));
    }

    @Test
    void stateMachineSampleOpensWithItsGraphReadyToInspect() {
        EditorDocument opened = open("state-machine");
        assertEquals("interaction", opened.getAnimation().stateMachines().getFirst().name());
        run(() -> window.menuItem("menu-view-state-machines").fire());
        layout();
        assertNotNull(fx(() -> window.getScene().lookup("#sm-node-idle")));
        assertNotNull(fx(() -> window.getScene().lookup("#sm-node-working")));
        assertNotNull(fx(() -> window.getScene().lookup("#sm-node-done")));
        assertNotNull(fx(() -> window.getScene().lookup("#sm-edge-2")));
    }

    @Test
    void downloadWorkflowSampleOpensWithSixEditableStates() {
        EditorDocument opened = open("download-workflow");
        assertEquals(6, opened.getAnimation().stateMachines().getFirst().states().size());
        run(() -> window.menuItem("menu-view-state-machines").fire());
        layout();
        assertNotNull(fx(() -> window.getScene().lookup("#sm-node-ready")));
        assertNotNull(fx(() -> window.getScene().lookup("#sm-node-downloading")));
        assertNotNull(fx(() -> window.getScene().lookup("#sm-node-failed")));
    }

    /** What a file argument does (AnimatorApp hands every argument to FileActions.open). */
    @Test
    void theBareJsonExampleOfTheReadmeOpensAsADocument() throws IOException {
        Matcher json = Pattern.compile("```json\\R(.*?)```", Pattern.DOTALL)
                .matcher(Files.readString(Path.of("..", "README.md")));
        assertTrue(json.find());
        Path file = dir.resolve("pulse.json");
        Files.writeString(file, json.group(1));

        EditorDocument opened = fx(() -> window.getFileActions().open(file)).orElseThrow();
        WaitForAsyncUtils.waitForFxEvents();
        layout();
        assertEquals("pulse", opened.getAnimation().name());
        assertEquals(List.of("Dot"), opened.getAnimation().layers().stream().map(Layer::name).toList());
        assertEquals(file, opened.getPath());
        assertFalse(opened.isDirty());
        assertEquals(opened, fx(() -> window.getWorkspace().getActiveDocument()));
        assertEquals(List.of(), fx(() -> window.getNoticeBar().getMessages()));
        assertEquals(1, fx(() -> timeline.getRows().size()));
    }

    @Test
    void aKeyframeOfTheSuccessSampleCanBeSelected() throws IOException {
        EditorDocument success = open("success");
        int collapsed = fx(() -> timeline.getRows().size());
        ShapeLayer check = (ShapeLayer) Layers.find(success.getAnimation(), "success-check").orElseThrow();
        Keyframe<Double> draw = check.trim().end().keyframes().get(1);
        Selection.KeyframeRef keyframe = new Selection.KeyframeRef("success-check", "trim.end", draw.id());
        run(() -> {
            success.getViewState().getExpandedLayerIds().addAll(List.of("success-badge", "success-check"));
            success.getSelection().layerIds().setAll("success-check");
            success.getSelection().keyframes().add(keyframe);
            success.setPlayhead(0.8);
        });
        WaitForAsyncUtils.waitForFxEvents();
        layout();

        assertTrue(fx(() -> timeline.getRows().size()) > collapsed + 2, "the group and the layer are expanded");
        assertEquals(Set.of(keyframe), Set.copyOf(success.getSelection().keyframes()));
        assertEquals(List.of("success-check"), List.copyOf(success.getSelection().layerIds()));

        String directory = System.getProperty("animatorfx.screenshots");
        if (directory != null && !directory.isBlank()) {
            for (Theme.Mode mode : new Theme.Mode[]{Theme.Mode.DARK, Theme.Mode.LIGHT}) {
                run(() -> window.getTheme().setMode(mode));
                WaitForAsyncUtils.waitForFxEvents();
                layout();
                WritableImage image = fx(() -> {
                    SnapshotParameters parameters = new SnapshotParameters();
                    parameters.setTransform(Transform.scale(2, 2));
                    return window.getScene().getRoot().snapshot(parameters, null);
                });
                Files.createDirectories(Path.of(directory));
                Files.write(Path.of(directory, "final-editor-" + mode.name().toLowerCase() + ".png"),
                        PngEncoder.encode(image));
            }
        }
    }
}
