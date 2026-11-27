package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.audio.TestSounds;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.Layer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sounds come into a document through File ▸ Import ▸ Audio…, a drop on the window and the Assets tab. */
class AudioImportUiTest extends EditorUiTest {

    private final List<Path> picked = new ArrayList<>();
    private CompletableFuture<Dialogs.AudioPlacement> placement;
    private final List<Double> placementTimes = new ArrayList<>();

    @Override
    protected CompletableFuture<Dialogs.AudioPlacement> chooseAudioInsertion(double time) {
        placementTimes.add(time);
        return placement != null ? placement
                : CompletableFuture.completedFuture(Dialogs.AudioPlacement.CURSOR);
    }

    @Test
    void aDroppedBatchWaitsForOnePlacementChoiceAndCanStartAtTheBeginning() throws Exception {
        placement = new CompletableFuture<>();
        Path first = sound("first.wav", .5);
        Path second = sound("second.wav", .75);
        run(() -> doc.setPlayhead(1.5));
        assertTrue(fx(() -> window.openDropped(List.of(first.toFile(), second.toFile()))));
        assertTrue(doc.getAnimation().assets().isEmpty());
        assertEquals(List.of(1.5), placementTimes);
        run(() -> {
            doc.setPlayhead(2);
            placement.complete(Dialogs.AudioPlacement.BEGINNING);
        });
        settle();
        assertEquals(2, doc.getAnimation().layers().size());
        for (Layer layer : doc.getAnimation().layers()) {
            assertEquals(0, layer.base().inPoint());
        }
    }

    @Test
    void aDroppedAudioUsesTheCapturedCursorAndDocumentEvenIfBothChangeBeforeTheAnswer() throws Exception {
        placement = new CompletableFuture<>();
        Path file = sound("cursor.wav", .5);
        run(() -> {
            doc.setPlayhead(1);
            window.openDropped(List.of(file.toFile()));
            doc.setPlayhead(2);
            window.getFileActions().newDocument();
            placement.complete(Dialogs.AudioPlacement.CURSOR);
            doc.setPlayhead(2.5);
        });
        settle();
        assertEquals(1, doc.getAnimation().layers().getFirst().base().inPoint());
        assertTrue(window.getWorkspace().getActiveDocument().getAnimation().layers().isEmpty());
    }

    @Test
    void cancellingThePlacementOrClosingItsDocumentAddsNoAssetsOrLayers() throws Exception {
        Path file = sound("cancel.wav", .5);
        placement = new CompletableFuture<>();
        run(() -> {
            window.openDropped(List.of(file.toFile()));
            placement.complete(Dialogs.AudioPlacement.CANCEL);
        });
        settle();
        assertTrue(doc.getAnimation().assets().isEmpty());

        placement = new CompletableFuture<>();
        run(() -> {
            window.openDropped(List.of(file.toFile()));
            window.getWorkspace().remove(doc);
            placement.complete(Dialogs.AudioPlacement.BEGINNING);
        });
        settle();
        assertTrue(doc.getAnimation().assets().isEmpty());
        assertTrue(doc.getAnimation().layers().isEmpty());
    }

    @Test
    void cursorPlacementAtTheAnimationEndDoesNotSilentlyInsertAtZero() throws Exception {
        Path file = sound("end.wav", .5);
        run(() -> {
            doc.setPlayhead(doc.getAnimation().duration());
            window.openDropped(List.of(file.toFile()));
        });
        settle();
        assertTrue(doc.getAnimation().assets().isEmpty());
        assertTrue(doc.getAnimation().layers().isEmpty());
    }

    @BeforeEach
    void stubThePicker() {
        window.getImportActions().setAudioPicker(owner -> {
            List<Path> files = List.copyOf(picked);
            picked.clear();
            return files;
        });
    }

    private Path sound(String name, double seconds) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, TestSounds.tone(seconds, 0.5));
        return file;
    }

    private void importAudio() {
        run(() -> window.menuItem("menu-file-import-audio").fire());
        settle();
    }

    /** Files are read and probed in the background: waits until every import asked for so far is applied. */
    private void settle() {
        try {
            fx(() -> window.getImportActions().whenAudioImported()).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        WaitForAsyncUtils.waitForFxEvents();
        layout();
    }

    private List<String> notices() {
        return fx(() -> window.getNoticeBar().getMessages());
    }

    @Test
    void importingAWavAddsOneAssetAndOneAudioLayerAtThePlayheadInOneUndoStep() throws Exception {
        picked.add(sound("door chime.wav", 0.75));
        run(() -> doc.setPlayhead(1.0));

        importAudio();

        assertEquals(1, doc.getAnimation().assets().size());
        Asset asset = doc.getAnimation().assets().get(0);
        assertEquals(Asset.Kind.AUDIO, asset.kind());
        assertEquals("door chime.wav", asset.name());
        assertEquals("assets/" + asset.id() + ".wav", asset.path());
        assertEquals(1, doc.getAnimation().layers().size());
        AudioLayer layer = (AudioLayer) doc.getAnimation().layers().get(0);
        assertEquals("door chime", layer.name());
        assertEquals(asset.id(), layer.assetId());
        assertEquals(1.0, layer.base().inPoint(), 1e-9);
        assertEquals(1.75, layer.base().outPoint(), 1e-9);
        assertEquals(0, layer.clipOffset());
        assertEquals(List.of(layer.id()), List.copyOf(doc.getSelection().layerIds()));
        assertEquals(List.of(), notices());
        assertEquals("Add Audio", doc.getUndoName());

        run(doc::undo);
        assertTrue(doc.getAnimation().assets().isEmpty());
        assertTrue(doc.getAnimation().layers().isEmpty());
        assertFalse(doc.isCanUndo());
    }

    @Test
    void aSoundImportedAtTheEndOfTheDocumentStartsAtZeroAndIsCutAtTheEnd() throws Exception {
        picked.add(sound("long.wav", 5));
        run(() -> doc.setPlayhead(3));

        importAudio();

        Layer layer = doc.getAnimation().layers().get(0);
        assertEquals(0, layer.base().inPoint());
        assertEquals(3, layer.base().outPoint(), 1e-9);
    }

    @Test
    void severalSoundsAreImportedOneAboveTheOther() throws Exception {
        picked.add(sound("a.wav", 0.5));
        picked.add(sound("b.aiff", 0.5));
        Files.write(dir.resolve("b.aiff"), TestSounds.wav(TestSounds.sine(0.5, 8000, 440, 0.5), 8000, 1, 16, true));

        importAudio();

        assertEquals(List.of("a", "b"), doc.getAnimation().layers().stream().map(Layer::name).toList());
        assertEquals(2, doc.getAnimation().assets().size());
    }

    @Test
    void aCompressedSoundIsImportedOnceTheMediaStackKnowsItsLength() throws Exception {
        Path m4a = dir.resolve("voice.m4a");
        Assumptions.assumeTrue(TestSounds.aac(m4a, 0.8), "afconvert is not available");
        picked.add(m4a);
        run(() -> doc.setPlayhead(1.0));

        importAudio();

        AudioLayer layer = (AudioLayer) doc.getAnimation().layers().get(0);
        assertEquals("voice", layer.name());
        assertEquals("assets/" + layer.assetId() + ".m4a", doc.getAnimation().assets().get(0).path());
        assertEquals(1.0, layer.base().inPoint(), 1e-9);
        assertEquals(1.8, layer.base().outPoint(), 0.12);
        assertEquals(List.of(), notices());
        run(doc::undo);
        assertTrue(doc.getAnimation().assets().isEmpty());
        assertFalse(doc.isCanUndo());
    }

    @Test
    void compressedSoundsImportedTogetherEachGetTheirLength() throws Exception {
        Path first = dir.resolve("one.m4a");
        Path second = dir.resolve("two.m4a");
        Path third = dir.resolve("three.m4a");
        Assumptions.assumeTrue(TestSounds.aac(first, 0.8) && TestSounds.aac(second, 0.5) && TestSounds.aac(third, 1.2),
                "afconvert is not available");
        picked.addAll(List.of(first, second, third));
        run(() -> doc.setPlayhead(1.0));

        importAudio();

        assertEquals(List.of(), notices());
        assertEquals(List.of("one", "two", "three"), doc.getAnimation().layers().stream().map(Layer::name).toList());
        assertEquals(1.8, doc.getAnimation().layers().get(0).base().outPoint(), 0.12);
        assertEquals(1.5, doc.getAnimation().layers().get(1).base().outPoint(), 0.12);
        assertEquals(2.2, doc.getAnimation().layers().get(2).base().outPoint(), 0.12);
    }

    @Test
    void whileALongSoundIsStillAnalysedAnotherOneIsImportedPromptlyAndJunkIsStillRefused() throws Exception {
        Path longSound = dir.resolve("long.m4a");
        Path shortSound = dir.resolve("short.m4a");
        Assumptions.assumeTrue(TestSounds.aac(longSound, 9) && TestSounds.aac(shortSound, 0.5),
                "afconvert is not available");
        Path junk = dir.resolve("junk.mp3");
        Files.write(junk, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        run(() -> doc.execute(com.dlsc.animatorfx.editor.doc.Commands.setDocument("Set Duration", a -> a.withDuration(20))));
        picked.add(longSound);
        importAudio();
        assertEquals(9, doc.getAnimation().layers().get(0).base().outPoint(), 0.12);

        // the analysis of the long sound takes as long as the sound; these do not wait for it
        long started = System.nanoTime();
        picked.addAll(List.of(shortSound, junk));
        importAudio();
        double seconds = (System.nanoTime() - started) / 1e9;

        assertEquals(List.of("long", "short"), doc.getAnimation().layers().stream().map(Layer::name).toList());
        assertEquals(0.5, doc.getAnimation().layers().get(1).base().outPoint(), 0.12);
        assertEquals(List.of("Could not import junk.mp3: it is not a sound the editor can play."), notices());
        assertEquals(2, doc.getAnimation().assets().size(), "the junk file is no asset");
        assertTrue(seconds < 7, "took " + seconds + " s");
    }

    @Test
    void aFileThatIsNoSoundIsRefusedWithANotice() throws Exception {
        Path file = dir.resolve("noise.mp3");
        Files.write(file, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        picked.add(file);

        importAudio();

        assertEquals(List.of("Could not import noise.mp3: it is not a sound the editor can play."), notices());
        assertTrue(doc.getAnimation().assets().isEmpty());
        assertTrue(doc.getAnimation().layers().isEmpty());
        assertFalse(doc.isCanUndo());
    }

    @Test
    void aFileOfMoreThan50MegabytesIsRefused() throws Exception {
        Path file = dir.resolve("huge.wav");
        try (RandomAccessFile sparse = new RandomAccessFile(file.toFile(), "rw")) {
            sparse.setLength(50L * 1024 * 1024 + 1);
        }
        picked.add(file);

        importAudio();

        assertEquals(List.of("Could not import huge.wav: it is larger than 50 MB."), notices());
        assertTrue(doc.getAnimation().assets().isEmpty());
    }

    @Test
    void theFileIsReadOffTheJavaFxThread() throws Exception {
        Path file = sound("big.wav", 2);
        CompletableFuture<Boolean> doneAtOnce = new CompletableFuture<>();

        run(() -> doneAtOnce.complete(window.getImportActions().importAudio(doc, file).isDone()));

        assertFalse(doneAtOnce.get(), "the import must not finish inside the call that starts it");
        settle();
        assertEquals(1, doc.getAnimation().layers().size());
        assertEquals("Add Audio", doc.getUndoName());
        run(doc::undo);
        assertFalse(doc.isCanUndo(), "still one undo step");
    }

    @Test
    void severalProblemsOfOneAddInTheAssetsTabAreAllShown() throws Exception {
        Path junk = dir.resolve("junk.mp3");
        Files.write(junk, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        Path text = dir.resolve("notes.txt");
        Files.writeString(text, "hello");
        Path good = sound("good.wav", 0.2);
        var assets = window.getSidePanel().getAssets();

        run(() -> assets.addFiles(List.of(junk, text, good)));
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS, () -> fx(() -> assets.getMessage() != null
                && assets.getMessage().contains("junk.mp3") && !assets.getMessage().contains("Reading")));

        String message = fx(assets::getMessage);
        assertTrue(message.contains("notes.txt is not"), message);
        assertTrue(message.contains("Could not add junk.mp3: it is not a sound the editor can play."), message);
        assertEquals(1, doc.getAnimation().assets().size());
    }

    @Test
    void anImportThatBreaksDoesNotHoldUpTheNextOne() throws Exception {
        Path broken = sound("broken.wav", 0.5);
        Path good = sound("good.wav", 0.5);
        // adding the first layer runs into an error: its document refuses with one
        var listener = new javafx.beans.value.ChangeListener<com.dlsc.animatorfx.model.Animation>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends com.dlsc.animatorfx.model.Animation> o,
                                com.dlsc.animatorfx.model.Animation before, com.dlsc.animatorfx.model.Animation after) {
                doc.animationProperty().removeListener(this);
                throw new StackOverflowError("test");
            }
        };
        run(() -> {
            doc.animationProperty().addListener(listener);
            window.getImportActions().importAudio(doc, broken);
            window.getImportActions().importAudio(doc, good);
        });

        settle();

        assertTrue(doc.getAnimation().layers().stream().anyMatch(l -> l.name().equals("good")),
                doc.getAnimation().layers().toString());
    }

    @Test
    void aSoundDroppedOnTheWindowBecomesAnAudioLayer() throws Exception {
        Path file = sound("drop.wav", 0.5);

        assertTrue(window.isDroppable(file.toFile()));
        assertTrue(fx(() -> window.openDropped(List.of(file.toFile()))));
        settle();

        assertEquals(1, window.getWorkspace().getDocuments().size(), "no new document");
        assertEquals(List.of("drop"), doc.getAnimation().layers().stream().map(Layer::name).toList());
    }

    @Test
    void theAssetsTabAddsASoundWithoutALayerAndPlacesItOnTheTimeline() throws Exception {
        Path file = sound("tick.wav", 0.25);
        var assets = window.getSidePanel().getAssets();

        run(() -> assets.addFiles(List.of(file)));
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !doc.getAnimation().assets().isEmpty());

        assertEquals(1, doc.getAnimation().assets().size());
        assertTrue(doc.getAnimation().layers().isEmpty());
        String assetId = doc.getAnimation().assets().get(0).id();

        run(() -> doc.setPlayhead(0.5));
        run(() -> assets.placeAudio(assetId));

        AudioLayer layer = (AudioLayer) doc.getAnimation().layers().get(0);
        assertEquals("tick", layer.name());
        assertEquals(0.5, layer.base().inPoint(), 1e-9);
        assertEquals(0.75, layer.base().outPoint(), 1e-9);
        assertEquals(List.of(layer.id()), List.copyOf(doc.getSelection().layerIds()));
    }
}
