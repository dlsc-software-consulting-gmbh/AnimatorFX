package com.dlsc.animatorfx.editor.timeline;

import com.dlsc.animatorfx.editor.doc.EditorDocument;
import javafx.animation.PauseTransition;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Run with Metal and {@code -Dprism.maxTextureSize=1024} to exercise HiDPI texture limits. */
class TimelineTextureTest {
    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ruler", "tracks", "markers", "cues"})
    void resizingTimelineDoesNotAllocateViewportSizedTextures(String kind) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        Stage stage = null;
        CompletableFuture<Void> rendered = new CompletableFuture<>();
        try (PrintStream captured = new PrintStream(errors)) {
            System.setErr(captured);
            stage = WaitForAsyncUtils.asyncFx(() -> {
                TimelineView timeline = new TimelineView();
                timeline.setDocument(EditorDocument.untitled());
                Surface surface = surface(timeline, kind);
                Region view = surface.view();
                view.setManaged(false);
                view.resize(400, 22);
                surface.draw().run();
                Stage window = new Stage();
                window.setScene(new Scene(new Pane(view), 400, 250));
                window.show();
                // Creating an initially oversized swap chain fails before Canvas rendering.
                PauseTransition resize = new PauseTransition(Duration.millis(250));
                resize.setOnFinished(event -> {
                    view.resize(2300, 22);
                    surface.draw().run();
                    window.setWidth(2300);
                    PauseTransition settle = new PauseTransition(Duration.millis(750));
                    settle.setOnFinished(done -> rendered.complete(null));
                    settle.play();
                });
                resize.play();
                return window;
            }).get();
            rendered.get(5, TimeUnit.SECONDS);
            assertFalse(errors.toString().contains("Illegal texture dimensions"), errors::toString);
        } finally {
            System.setErr(original);
            if (stage != null) {
                Stage window = stage;
                WaitForAsyncUtils.asyncFx(window::close).get();
            }
        }
    }

    private record Surface(Region view, Runnable draw) {
    }

    private static Surface surface(TimelineView timeline, String kind) {
        return switch (kind) {
            case "ruler" -> {
                TimeRuler view = new TimeRuler(timeline);
                yield new Surface(view, view::draw);
            }
            case "tracks" -> {
                TrackPane view = new TrackPane(timeline);
                yield new Surface(view, view::draw);
            }
            case "markers" -> {
                MarkerLane view = new MarkerLane(timeline);
                yield new Surface(view, view::draw);
            }
            case "cues" -> {
                CueLane view = new CueLane(timeline);
                yield new Surface(view, view::draw);
            }
            default -> throw new IllegalArgumentException(kind);
        };
    }
}
