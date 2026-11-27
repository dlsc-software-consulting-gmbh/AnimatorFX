package com.dlsc.animatorfx.editor.timeline;

import com.dlsc.animatorfx.editor.audio.TestSounds;
import com.dlsc.animatorfx.editor.audio.Waveform;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.ui.EditorUiTest;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.io.AnimationIO;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audio layers in the timeline: their row, their bar gestures and their waveform. */
class AudioTimelineTest extends EditorUiTest {

    private TrackPane tracks;

    /** A one-second sound in a layer from 0.5 to 2.5, above a rectangle. */
    @BeforeEach
    void content() {
        tracks = timeline.getTrackPane();
        run(() -> doc.addAsset(new Asset("snd", Asset.Kind.AUDIO, "assets/snd.wav", "chimes.wav"), TestSounds.chimes(1)));
        add(rect("r", 100, 100, 100, 100));
        AudioLayer layer = AudioLayer.create("music", "snd", 0.5, 2.5);
        add(layer.withBase(layer.base().withId("a")));
    }

    private AudioLayer audio() {
        return (AudioLayer) layer("a");
    }

    private double x(double time) {
        return fx(() -> tracks.timeToX(time));
    }

    private double y() {
        return fx(() -> tracks.rowCenter(tracks.rowOf("a")));
    }

    private Node cell() {
        return fx(() -> timeline.getLayerColumn().layerCell("a").orElseThrow());
    }

    private void editVolume() {
        run(() -> tracks.getAudioMenu("a").getItems().getFirst().fire());
        layout();
    }

    private double volumeY(double value) {
        return fx(() -> timeline.rowTop(tracks.rowOf("a")) + 3.5 + (1 - value) * 14);
    }

    @Test
    void clickingTheVolumeGraphAddsAnUndoablePersistedEnvelopeWithoutMovingTheClip() throws Exception {
        editVolume();
        click(tracks, x(1), volumeY(.25));

        assertEquals(3, audio().volume().keyframes().size());
        assertEquals(1, audio().volume().at(.5), 1e-9);
        assertEquals(.25, audio().volume().at(1), 1e-9);
        assertEquals(.625, audio().volume().at(.75), 1e-9);
        assertEquals(1, audio().volume().at(2.5), 1e-9);
        assertEquals(.5, audio().base().inPoint());
        assertEquals(2.5, audio().base().outPoint());
        assertFalse(doc.isAutoKey(), "Graph editing must create points without requiring auto-key");
        java.nio.file.Path file = dir.resolve("volume.afx");
        run(() -> {
            try {
                AnimationIO.write(doc.getFileContent(), file);
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        });
        assertEquals(doc.getAnimation(), AnimationIO.read(file).animation());
        run(doc::undo);
        assertFalse(audio().volume().isAnimated());
        assertEquals(1, audio().volume().at(1), 1e-9);
    }

    @Test
    void volumePointsMoveInTimeAndValueAndCanBeDeletedWithoutChangingOtherPoints() {
        editVolume();
        click(tracks, x(1), volumeY(.25));
        String id = audio().volume().keyframes().get(1).id();
        drag(tracks, x(1), volumeY(.25), x(1.25), volumeY(.75));

        assertEquals(id, audio().volume().keyframes().get(1).id());
        assertEquals(.75, audio().volume().at(1.25), 1e-9);
        assertEquals(1, audio().volume().at(.5), 1e-9);
        run(doc::undo);
        assertEquals(.25, audio().volume().at(1), 1e-9);
        click(tracks, x(1), volumeY(.25));
        key(KeyCode.DELETE);
        assertEquals(2, audio().volume().keyframes().size());
        assertEquals(1, audio().volume().at(1), 1e-9);
    }

    @Test
    void escapingAVolumePointDragRestoresItsValueAndLockedTracksCannotBeEdited() {
        editVolume();
        click(tracks, x(1), volumeY(.25));
        var before = audio().volume();
        press(tracks, x(1), volumeY(.25), Keys.NONE);
        dragTo(tracks, x(1.25), volumeY(.75), Keys.NONE);
        key(KeyCode.ESCAPE);
        release(tracks, x(1.25), volumeY(.75), Keys.NONE);
        assertEquals(before, audio().volume());

        execute(Commands.updateLayer("Lock Layer", "a", l -> l.withBase(l.base().withLocked(true))));
        click(tracks, x(1.5), volumeY(.5));
        drag(tracks, x(1), volumeY(.25), x(1.25), volumeY(.75));
        assertEquals(before, audio().volume());
    }

    @Test
    void switchingDocumentsCancelsAVolumeDragInTheOriginalDocument() {
        editVolume();
        click(tracks, x(1), volumeY(.25));
        var before = audio().volume();
        press(tracks, x(1), volumeY(.25), Keys.NONE);
        dragTo(tracks, x(1.25), volumeY(.75), Keys.NONE);
        assertEquals(.75, audio().volume().at(1.25), 1e-9);

        run(() -> window.getFileActions().newDocument());
        layout();
        assertEquals(before, audio().volume());
        assertFalse(tracks.isEditingVolume("a"));
        assertTrue(window.getWorkspace().getActiveDocument().getAnimation().layers().isEmpty());
    }

    @Test
    void leavingVolumeEditModeRestoresTheNormalAudioBarGesture() {
        editVolume();
        click(tracks, x(1), volumeY(.25));
        var envelope = audio().volume();
        editVolume();

        drag(tracks, x(1.5), y(), x(1.75), y());
        assertEquals(.75, audio().base().inPoint(), 1e-9);
        assertEquals(2.75, audio().base().outPoint(), 1e-9);
        assertEquals(envelope, audio().volume());
    }

    @Test
    void switchingDocumentsDuringAMarqueeDoesNotCopyTheOldSelection() {
        select("r");
        press(tracks, x(2.8), y(), Keys.NONE);
        dragTo(tracks, x(.75), y(), Keys.NONE);
        run(() -> window.getFileActions().newDocument());
        layout();

        assertTrue(window.getWorkspace().getActiveDocument().getSelection().layerIds().isEmpty());
        assertEquals(java.util.List.of("r"), doc.getSelection().layerIds());
    }

    @Test
    void theRowOfAnAudioLayerHasANoteAndASpeakerThatMutes() {
        FontIcon type = (FontIcon) cell().lookup(".layer-icon");
        Button speaker = (Button) cell().lookup(".eye");
        assertEquals(Material2AL.AUDIOTRACK, type.getIconCode());
        assertEquals(Material2MZ.VOLUME_UP, ((FontIcon) speaker.getGraphic()).getIconCode());

        run(speaker::fire);
        layout();

        assertFalse(audio().base().visible());
        assertEquals(Material2MZ.VOLUME_OFF, ((FontIcon) ((Button) cell().lookup(".eye")).getGraphic()).getIconCode());
        assertEquals("Mute Layer", doc.getUndoName());
        // the rectangle keeps its eye
        Node other = fx(() -> timeline.getLayerColumn().layerCell("r").orElseThrow());
        assertEquals(Material2MZ.VISIBILITY, ((FontIcon) ((Button) other.lookup(".eye")).getGraphic()).getIconCode());
    }

    @Test
    void theSpeakerOfALayerThatIsMutedInTheInspectorIsOffAndSwitchesItOnAgain() {
        execute(Commands.updateLayer("Mute Layer", "a", l -> ((AudioLayer) l).withMuted(true)));
        layout();
        assertEquals(Material2MZ.VOLUME_OFF, ((FontIcon) ((Button) cell().lookup(".eye")).getGraphic()).getIconCode());

        run(() -> ((Button) cell().lookup(".eye")).fire());
        layout();

        assertFalse(audio().muted());
        assertTrue(audio().base().visible());
        assertEquals(Material2MZ.VOLUME_UP, ((FontIcon) ((Button) cell().lookup(".eye")).getGraphic()).getIconCode());
    }

    @Test
    void draggingTheBarMovesInAndOutTogetherAndLeavesTheClipOffset() {
        drag(tracks, x(1.5), y(), x(1.75), y());

        assertEquals(0.75, audio().base().inPoint(), 1e-9);
        assertEquals(2.75, audio().base().outPoint(), 1e-9);
        assertEquals(0, audio().clipOffset());
        run(doc::undo);
        assertEquals(0.5, audio().base().inPoint(), 1e-9);
    }

    @Test
    void altDraggingTheBarSlidesTheSoundUnderIt() {
        run(() -> doc.execute(Commands.setClipOffset("a", 0.5)));

        // to the left: a later part of the sound comes to the in-point
        drag(tracks, x(1.5), y(), x(1.25), y(), Keys.ALT);

        assertEquals(0.75, audio().clipOffset(), 1e-9);
        assertEquals(0.5, audio().base().inPoint(), 1e-9);
        assertEquals(2.5, audio().base().outPoint(), 1e-9);
        assertEquals("Slide Clip", doc.getUndoName());

        drag(tracks, x(1.5), y(), x(2.9), y(), Keys.ALT);
        assertEquals(0, audio().clipOffset(), "not before the start of the sound");

        run(doc::undo);
        run(doc::undo);
        assertEquals(0.5, audio().clipOffset(), 1e-9);
    }

    @Test
    void escapeCancelsASlide() {
        press(tracks, x(1.5), y(), Keys.ALT);
        dragTo(tracks, x(1.2), y(), Keys.ALT);
        assertEquals(0.3, audio().clipOffset(), 1e-9);

        key(KeyCode.ESCAPE);
        release(tracks, x(1.2), y(), Keys.ALT);

        assertEquals(0, audio().clipOffset());
        assertEquals("Add Layer", doc.getUndoName());
    }

    @Test
    void trimmingTheStartKeepsTheSoundInPlace() {
        drag(tracks, x(0.5), y(), x(0.9), y());

        assertEquals(0.9, audio().base().inPoint(), 1e-9);
        assertEquals(0.4, audio().clipOffset(), 1e-9);
        assertEquals(2.5, audio().base().outPoint(), 1e-9);

        // back out: not further than the start of the sound
        drag(tracks, x(0.9), y(), x(0.1), y());
        assertEquals(0.5, audio().base().inPoint(), 1e-9);
        assertEquals(0, audio().clipOffset(), 1e-9);
    }

    @Test
    void altTrimmingTheStartMovesTheSoundWithTheInPoint() {
        drag(tracks, x(0.5), y(), x(0.9), y(), Keys.ALT);

        assertEquals(0.9, audio().base().inPoint(), 1e-9);
        assertEquals(0, audio().clipOffset());
    }

    @Test
    void trimmingTheEndChangesOnlyTheOutPoint() {
        drag(tracks, x(2.5), y(), x(2.0), y());

        assertEquals(2.0, audio().base().outPoint(), 1e-9);
        assertEquals(0.5, audio().base().inPoint(), 1e-9);
        assertEquals(0, audio().clipOffset());
    }

    @Test
    void aLockedAudioLayerKeepsItsBarAndItsSound() {
        execute(Commands.updateLayer("Lock Layer", "a", l -> l.withBase(l.base().withLocked(true))));

        drag(tracks, x(1.5), y(), x(1.2), y(), Keys.ALT);
        drag(tracks, x(1.5), y(), x(1.8), y());
        drag(tracks, x(0.5), y(), x(0.9), y());

        assertEquals(0, audio().clipOffset());
        assertEquals(0.5, audio().base().inPoint(), 1e-9);
        assertEquals("Lock Layer", doc.getUndoName());
    }

    @Test
    void theWaveformArrivesAndIsDrawnInsideTheBar() throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> fx(() -> timeline.waveformOf("snd").isPresent()));
        Waveform waveform = fx(() -> timeline.waveformOf("snd").orElseThrow());
        assertEquals(1, waveform.duration(), 1e-6);
        WaitForAsyncUtils.waitForFxEvents();
        layout();

        // the first chime starts just after the start of the sound: tall there, and nothing past its end
        Color wave = fx(() -> timeline.getPalette().get(com.dlsc.animatorfx.editor.ui.Palette.Tone.WAVE));
        WritableImage image = fx(() -> tracks.snapshot(new SnapshotParameters(), null));
        double top = fx(() -> timeline.rowTop(tracks.rowOf("a")));
        assertTrue(waveColumn(image, x(0.5 + 0.03), top, wave) >= 8, "the attack of the first chime");
        assertEquals(0, waveColumn(image, x(2.0), top, wave), "no sound after the end of the clip");
    }

    @Test
    void aSoundWhoseWaveformIsNotThereYetStillShowsWhereItEndsAndSlidesWithinItsLength() throws Exception {
        java.nio.file.Path longSound = dir.resolve("long.m4a");
        java.nio.file.Path shortSound = dir.resolve("short.m4a");
        org.junit.jupiter.api.Assumptions.assumeTrue(TestSounds.aac(longSound, 9) && TestSounds.aac(shortSound, 0.5),
                "afconvert is not available");
        // the long sound keeps the one-at-a-time analysis busy: the short one has a length but no waveform
        run(() -> window.getImportActions().importAudio(doc, longSound));
        run(() -> {
            doc.setPlayhead(0.5);
            window.getImportActions().importAudio(doc, shortSound);
        });
        fx(() -> window.getImportActions().whenAudioImported()).get(30, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
        AudioLayer imported = (AudioLayer) doc.getAnimation().layers().get(doc.getAnimation().layers().size() - 1);
        assertEquals("short", imported.name());
        execute(Commands.updateLayer("Trim Layer", imported.id(), l -> l.withBase(l.base().withOutPoint(2.5))));
        layout();
        assertFalse(fx(() -> timeline.waveformOf(imported.assetId()).isPresent()), "no waveform yet");
        assertEquals(0.5, fx(() -> timeline.clipDurationOf(imported.assetId())), 0.12);

        // the bar from 0.5 to 2.5 has sound for half a second: after that it looks different
        WritableImage image = fx(() -> tracks.snapshot(new SnapshotParameters(), null));
        int row = fx(() -> tracks.rowOf(imported.id()));
        int middle = (int) (fx(() -> timeline.rowTop(row)) + TimelineView.ROW_HEIGHT / 2);
        Color sounding = image.getPixelReader().getColor((int) x(0.75), middle);
        Color empty = image.getPixelReader().getColor((int) x(2.0), middle - 3);
        assertFalse(sounding.equals(empty), "the part without sound is marked: " + sounding + " / " + empty);

        double rowY = fx(() -> tracks.rowCenter(row));
        drag(tracks, x(2.4), rowY, x(0.6), rowY, Keys.ALT);
        AudioLayer slid = (AudioLayer) layer(imported.id());
        assertTrue(slid.clipOffset() > 0.3 && slid.clipOffset() <= 0.62, "offset " + slid.clipOffset());
    }

    @Test
    void thePainterDrawsOneColumnPerPixelAsHighAsItsLoudestPeak() {
        float[] peaks = new float[200];
        for (int i = 0; i < 100; i++) {
            peaks[i] = 1;
        }
        peaks[150] = 0.5f;
        Waveform waveform = new Waveform(1, peaks);
        Color color = Color.rgb(10, 200, 30);
        WritableImage image = fx(() -> {
            Canvas canvas = new Canvas(220, 20);
            // 100 pixels per second, time 0 at x = 12: two repetitions of the sound from x = 12
            WaveformPainter.paint(canvas.getGraphicsContext2D(), waveform, new TimeScale(100, 0), 0, 0, true,
                    12, 212, 0, 20, 220, color);
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setFill(Color.WHITE);
            return canvas.snapshot(parameters, null);
        });

        assertEquals(0, waveColumn(image, 5, 0, color), "nothing before the bar");
        assertEquals(20, waveColumn(image, 30, 0, color), "full scale");
        assertEquals(1, waveColumn(image, 70, 0, color), "silence is a hairline");
        assertEquals(10, waveColumn(image, 87, 0, color), "a single peak is not lost between pixels");
        assertEquals(20, waveColumn(image, 130, 0, color), "the second repetition");
        assertEquals(0, waveColumn(image, 215, 0, color), "nothing after the bar");
    }

    /** How many pixels of a column, within one row height from the top, have the colour of the waveform. */
    private static int waveColumn(WritableImage image, double x, double top, Color color) {
        int count = 0;
        for (int y = (int) top; y < Math.min(image.getHeight(), top + TimelineView.ROW_HEIGHT); y++) {
            Color pixel = image.getPixelReader().getColor((int) x, y);
            if (Math.abs(pixel.getRed() - color.getRed()) < 0.02 && Math.abs(pixel.getGreen() - color.getGreen()) < 0.02
                    && Math.abs(pixel.getBlue() - color.getBlue()) < 0.02) {
                count++;
            }
        }
        return count;
    }
}
