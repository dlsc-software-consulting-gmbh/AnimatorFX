package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.audio.RecordingAudioOutput;
import com.dlsc.animatorfx.editor.audio.TestSounds;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.session.RecoveryStore;
import com.dlsc.animatorfx.editor.session.SessionState;
import com.dlsc.animatorfx.editor.session.SessionStore;
import com.dlsc.animatorfx.editor.timeline.TimeRuler;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.io.Json;
import com.dlsc.animatorfx.player.internal.audio.VoiceTarget;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sound in the running editor: playback, the mute toggle and scrubbing. Nothing is audible: the output records. */
class AudioPlaybackUiTest extends EditorUiTest {

    private static final double RULER_Y = 10;
    private List<RecordingAudioOutput> outputs;
    private TimeRuler ruler;

    @BeforeEach
    void content() {
        outputs = RecordingAudioOutput.install();
        ruler = timeline.getRuler();
        run(() -> doc.addAsset(new Asset("snd", Asset.Kind.AUDIO, "assets/snd.wav", "chime.wav"), TestSounds.tone(1, 0.5)));
        AudioLayer layer = AudioLayer.create("music", "snd", 0, 3);
        add(rect("r", 100, 100, 100, 100), layer.withBase(layer.base().withId("a")));
    }

    @AfterEach
    void restore() {
        run(() -> canvas.pausePlayback());
        RecordingAudioOutput.uninstall();
    }

    private double x(double time) {
        return fx(() -> timeline.getScale().toX(time));
    }

    private ToggleButton muteButton() {
        return lookup("#mute");
    }

    private boolean playerMuted() {
        return fx(() -> canvas.getAnimationView().isMuted());
    }

    private long snippets() {
        return outputs.stream().mapToLong(o -> o.count("snippet")).sum();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    // ------------------------------------------------------------------ mute

    @Test
    void theToolbarToggleMutesThePlayerOfTheEditor() {
        assertFalse(playerMuted());

        run(() -> muteButton().fire());
        assertTrue(playerMuted());
        assertTrue(fx(() -> ((CheckMenuItem) window.menuItem("menu-view-mute")).isSelected()));

        run(() -> window.menuItem("menu-view-mute").fire());
        assertFalse(playerMuted());
        assertFalse(fx(() -> muteButton().isSelected()));
    }

    @Test
    void theKeyMTogglesMuteUnlessTextIsBeingTyped() {
        run(() -> canvas.requestFocus());
        key(KeyCode.M);
        assertTrue(playerMuted());
        key(KeyCode.M);
        assertFalse(playerMuted());

        select("r");
        TextField name = lookup("#inspector-layer-name");
        run(name::requestFocus);
        assertEquals(name, focusOwner());
        key(KeyCode.M);
        assertFalse(playerMuted());
    }

    @Test
    void muteIsStoredInTheSessionAndRestored() {
        run(() -> muteButton().fire());
        run(() -> window.getSession().saveNow());

        MainWindow second = fx(() -> new MainWindow(new Stage(), new SessionStore(dir.resolve("config")),
                new RecoveryStore(dir.resolve("config").resolve("recovery")), window.getDialogs()));
        try {
            assertTrue(fx(() -> second.getToolBar().isMuted()));
            assertTrue(fx(() -> second.getCanvasView().getAnimationView().isMuted()));
        } finally {
            run(second::shutdown);
        }
    }

    @Test
    void aSessionFromBeforeTheMuteFlagIsNotMuted() throws Exception {
        SessionState state = SessionState.fromJson(Json.parse("{\"version\": 1, \"theme\": \"DARK\"}"));

        assertFalse(state.audioMuted());
        assertEquals(Boolean.TRUE, SessionState.fromJson(Json.parse("{\"audioMuted\": true}")).audioMuted());
        assertEquals(Boolean.TRUE, SessionState.defaults().withAudioMuted(true).toJson().get("audioMuted"));
    }

    // ------------------------------------------------------------------ scrubbing

    @Test
    void draggingThePlayheadInTheRulerScrubsTheSound() {
        press(ruler, x(0.5), RULER_Y, Keys.NONE);

        assertEquals(1, snippets());
        VoiceTarget target = outputs.get(0).snippets.get(0).get(0);
        assertEquals("snd", target.assetId());
        assertEquals(0.5, target.mediaTime(), 1e-9);

        // a fast drag does not queue up snippets
        dragTo(ruler, x(0.6), RULER_Y, Keys.NONE);
        dragTo(ruler, x(0.7), RULER_Y, Keys.NONE);
        dragTo(ruler, x(0.8), RULER_Y, Keys.NONE);
        assertTrue(snippets() <= 2, "snippets: " + snippets());

        pause(80);
        dragTo(ruler, x(0.9), RULER_Y, Keys.NONE);
        release(ruler, x(0.9), RULER_Y, Keys.NONE);
        assertTrue(snippets() >= 2);
        assertEquals(0.9, doc.getPlayhead(), 1e-9);
        List<List<VoiceTarget>> all = outputs.get(outputs.size() - 1).snippets;
        assertEquals(0.9, all.get(all.size() - 1).get(0).mediaTime(), 1e-9);
    }

    @Test
    void scrubbingIsSilentWhenMuted() {
        run(() -> muteButton().fire());

        press(ruler, x(0.5), RULER_Y, Keys.NONE);
        release(ruler, x(0.5), RULER_Y, Keys.NONE);

        assertEquals(0, snippets());
        assertEquals(0.5, doc.getPlayhead(), 1e-9);
    }

    @Test
    void scrubbingIsSilentWhilePlaying() {
        run(() -> canvas.togglePlayback());
        pause(120);

        press(ruler, x(0.5), RULER_Y, Keys.NONE);
        release(ruler, x(0.5), RULER_Y, Keys.NONE);

        assertEquals(0, snippets());
    }

    // ------------------------------------------------------------------ playback

    @Test
    void playbackSoundsThroughThePlayerOfTheCanvasAndPauseSilencesIt() throws Exception {
        run(() -> canvas.togglePlayback());
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !outputs.isEmpty() && outputs.get(0).count("apply") >= 3);

        assertEquals(1, outputs.size(), "one player makes the sound");
        VoiceTarget target = outputs.get(0).applied.get(0).get(0);
        assertTrue(target.audible());
        assertEquals("a", target.layerId());

        run(() -> canvas.togglePlayback());
        pause(50);
        assertEquals("silence", outputs.get(0).calls.get(outputs.get(0).calls.size() - 1));
    }

    @Test
    void anEditDuringPlaybackKeepsTheSoundGoing() throws Exception {
        run(() -> canvas.togglePlayback());
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !outputs.isEmpty() && outputs.get(0).count("apply") >= 2);

        execute(Commands.setValueAt("a", "volume", 0, 0.25, false));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> fx(() -> {
            List<List<VoiceTarget>> applied = outputs.get(0).applied;
            return Math.abs(applied.get(applied.size() - 1).get(0).volume() - 0.25) < 1e-9;
        }));

        assertTrue(fx(() -> canvas.isPlaying()));
        assertEquals(1, outputs.size(), "the output was not made anew");
        assertFalse(outputs.get(0).disposed);
        assertEquals(0, outputs.get(0).count("silence"), "and never fell silent");
        assertEquals(0, outputs.get(0).cuesStopped, "a cue that is sounding rings on");
    }

    @Test
    void pausingPlaybackCutsTheCuesThatAreSounding() throws Exception {
        run(() -> canvas.togglePlayback());
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !outputs.isEmpty() && outputs.get(0).count("apply") >= 2);
        assertEquals(0, outputs.get(0).cuesStopped);

        run(() -> canvas.togglePlayback());

        assertEquals(1, outputs.get(0).cuesStopped);
        assertFalse(outputs.get(0).disposed);
    }

    @Test
    void goingToTheStartCutsTheCuesThatAreSounding() throws Exception {
        run(() -> canvas.togglePlayback());
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !outputs.isEmpty() && outputs.get(0).count("apply") >= 2);

        run(() -> ((javafx.scene.control.Button) window.getScene().lookup("#transport-start")).fire());

        assertEquals(1, outputs.get(0).cuesStopped);
        assertTrue(fx(() -> canvas.isPlaying()), "playback goes on from the start");
    }

    @Test
    void playbackRunningIntoTheEndLetsACueRingOut() throws Exception {
        playhead(2.9);
        run(() -> {
            window.getToolBar().setLoop(false);
            canvas.togglePlayback();
        });
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !outputs.isEmpty() && !fx(() -> canvas.isPlaying()));

        assertEquals(0, outputs.get(0).cuesStopped);
    }

    @Test
    void closingTheDocumentReleasesTheOutputForGood() throws Exception {
        run(() -> canvas.togglePlayback());
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !outputs.isEmpty() && outputs.get(0).count("apply") >= 2);

        run(() -> window.getFileActions().close(doc));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(outputs.get(0).disposed);
        assertEquals(1, outputs.size());
    }

    @Test
    void closingTheWindowReleasesTheOutputForGood() throws Exception {
        run(() -> canvas.togglePlayback());
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !outputs.isEmpty() && outputs.get(0).count("apply") >= 2);

        run(() -> window.shutdown());
        assertTrue(outputs.get(0).disposed);

        // a player that is disposed makes no sound again, whatever is asked of it
        run(() -> canvas.scrub(0.5));
        assertEquals(1, outputs.size());
    }

    // ------------------------------------------------------------------ dialogs

    @Test
    void whileADialogShowsNeitherTheKeyNorTheRulerMakesASound() {
        MainWindow real = fx(() -> {
            MainWindow w = new MainWindow(new Stage(), new SessionStore(dir.resolve("other")),
                    new RecoveryStore(dir.resolve("other").resolve("recovery")));
            w.getStage().setWidth(1240);
            w.getStage().setHeight(780);
            w.show();
            return w;
        });
        try {
            WaitForAsyncUtils.waitForFxEvents();
            run(() -> {
                var document = real.getWorkspace().getActiveDocument();
                document.addAsset(new Asset("snd", Asset.Kind.AUDIO, "assets/snd.wav", "chime.wav"), TestSounds.tone(1, 0.5));
                document.execute(Commands.addAudioLayer("a", "music", "snd", 0, 1));
                real.getScene().getRoot().applyCss();
                real.getScene().getRoot().layout();
                real.getDialogs().showAbout();
            });
            WaitForAsyncUtils.waitForFxEvents();
            assertTrue(fx(real::isDialogShowing));
            int before = outputs.size();

            run(() -> {
                javafx.event.Event.fireEvent(real.getScene().getFocusOwner() == null ? real.getScene().getRoot()
                        : real.getScene().getFocusOwner(), new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", KeyCode.M, false, false, false, false));
                real.getTimelineView().getRuler().fireEvent(new javafx.scene.input.MouseEvent(
                        javafx.scene.input.MouseEvent.MOUSE_PRESSED, 100, 10, 100, 10,
                        javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, true, false, false,
                        false, false, false, null));
                real.menuItem("menu-view-mute").fire();
            });

            assertFalse(fx(() -> real.getToolBar().isMuted()));
            assertFalse(fx(() -> ((CheckMenuItem) real.menuItem("menu-view-mute")).isSelected()));
            assertEquals(before, outputs.size(), "no output was asked for");
            assertEquals(0, snippets());
        } finally {
            run(() -> {
                real.shutdown();
                real.getStage().hide();
            });
        }
    }
}
