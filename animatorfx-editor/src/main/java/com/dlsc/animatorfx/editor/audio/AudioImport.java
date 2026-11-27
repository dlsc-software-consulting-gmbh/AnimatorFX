package com.dlsc.animatorfx.editor.audio;

import com.dlsc.animatorfx.editor.doc.Command;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.model.Asset;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

/**
 * Brings sound files into documents: reads and checks a file, and adds it as an audio asset,
 * with or without an audio layer at the playhead. Used by File ▸ Import ▸ Audio…, by files
 * dropped on the window and by the Assets tab.
 */
public final class AudioImport {

    /**
     * Larger files are refused: a document is held in memory with its undo history and is read
     * back whenever it is saved.
     */
    public static final long MAX_BYTES = 50L * 1024 * 1024;

    private static final Duration BUSY_AFTER = Duration.millis(200);
    /** How long the media stack has to answer once it has been asked; it answers within 4 s by itself. */
    private static final Duration GIVE_UP_AFTER = Duration.seconds(5);
    /** How long reading a file of up to 50 MB and writing its temporary copy may take. */
    private static final Duration READ_LIMIT = Duration.seconds(60);

    /** Why a file was not taken; the message completes "Could not import x: …". */
    public static final class Refused extends Exception {

        public Refused(String message) {
            super(message);
        }
    }

    /**
     * A sound file that was read and found playable.
     *
     * @param info what is known about the sound; its length is known
     */
    public record Sound(String fileName, String extension, byte[] bytes, AudioInfo info) {

        /** The file name without its extension: the name of a layer or cue made from the sound. */
        public String baseName() {
            return AudioImport.baseName(fileName);
        }
    }

    private AudioImport() {
    }

    /** Whether the file is taken for a sound by its extension. */
    public static boolean isAudio(Path file) {
        return AudioInfo.EXTENSIONS.contains(extension(file.getFileName() == null ? "" : file.getFileName().toString()));
    }

    public static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.isBlank() ? "Sound" : base;
    }

    /**
     * Reads a sound file and finds out how long it is. The file is read on a background thread;
     * WAV and AIFF files say their length in their header, for other formats the media stack is
     * asked, which takes a moment. Call on the JavaFX application thread; the result arrives
     * there, never inside this call. Fails with {@link Refused} for a file that is too large,
     * cannot be read or is no sound the media stack plays, and, as a last resort, when the media
     * stack has not answered 5 seconds after it was asked (the time to read the file does not
     * count; that has a minute): a file that was not checked is not taken.
     *
     * @param busy shows a message when the answer takes longer than a blink, and returns what
     *             takes the message away again
     */
    public static CompletableFuture<Sound> read(Path file, Function<String, Runnable> busy) {
        CompletableFuture<Sound> result = new CompletableFuture<>();
        String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        String extension = extension(name);

        Runnable[] dismiss = {null};
        PauseTransition busyTimer = new PauseTransition(BUSY_AFTER);
        busyTimer.setOnFinished(e -> dismiss[0] = busy.apply("Reading " + name + "…"));
        // two clocks: a generous one for reading the file and writing the copy the media stack
        // opens, and then a short one for the media stack to answer
        PauseTransition readLimit = new PauseTransition(READ_LIMIT);
        PauseTransition giveUp = new PauseTransition(GIVE_UP_AFTER);
        // every outcome comes through here, on the JavaFX application thread
        BiConsumer<Sound, Refused> finish = (sound, refused) -> {
            if (result.isDone()) {
                return;
            }
            busyTimer.stop();
            readLimit.stop();
            giveUp.stop();
            if (dismiss[0] != null) {
                dismiss[0].run();
            }
            if (refused != null) {
                result.completeExceptionally(refused);
            } else {
                result.complete(sound);
            }
        };
        giveUp.setOnFinished(e -> finish.accept(null, new Refused("the sound could not be read in time.")));
        readLimit.setOnFinished(e -> finish.accept(null, new Refused("reading the file took too long.")));
        busyTimer.play();
        readLimit.play();

        AudioProbe.IO.execute(() -> {
            byte[] bytes;
            try {
                if (!AudioInfo.EXTENSIONS.contains(extension)) {
                    throw new Refused("it is not an MP3, WAV, AIFF or AAC file.");
                }
                if (Files.size(file) > MAX_BYTES) {
                    throw new Refused("it is larger than 50 MB.");
                }
                bytes = Files.readAllBytes(file);
            } catch (Refused e) {
                Platform.runLater(() -> finish.accept(null, e));
                return;
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                Platform.runLater(() -> finish.accept(null, new Refused("it cannot be read.")));
                return;
            }
            Platform.runLater(() -> {
                if (result.isDone()) {
                    return;
                }
                // the cache probes the sound by itself, apart from the analysis of waveforms
                WaveformCache.shared().info(bytes, extension, () -> {
                    if (!result.isDone()) {
                        readLimit.stop();
                        giveUp.playFromStart();
                    }
                }).whenComplete((info, error) -> finish.accept(
                        error != null || !info.hasDuration() ? null : new Sound(name, extension, bytes, info),
                        error != null || !info.hasDuration() ? new Refused("it is not a sound the editor can play.") : null));
            });
        });
        return result;
    }

    private static Asset asset(Sound sound) {
        String id = UUID.randomUUID().toString();
        return new Asset(id, Asset.Kind.AUDIO, "assets/" + id + "." + sound.extension(), sound.fileName());
    }

    /** Adds the sound as an audio asset, as one undo step. */
    public static Asset addAsset(EditorDocument document, Sound sound) {
        Asset asset = asset(sound);
        document.addAsset(asset, sound.bytes());
        adopt(document, asset, sound);
        return asset;
    }

    /**
     * Adds the sound as an audio asset together with an audio layer at the playhead, as one undo
     * step, and selects the layer.
     *
     * @return the id of the new layer
     */
    public static String addLayer(EditorDocument document, Sound sound) {
        return addLayer(document, sound, document.getPlayhead());
    }

    /** Adds the sound and its audio layer at a captured timeline position, as one undo step. */
    public static String addLayer(EditorDocument document, Sound sound, double time) {
        Asset asset = asset(sound);
        String layerId = UUID.randomUUID().toString();
        double length = sound.info().duration();
        document.execute(Commands.addAudioLayer(layerId, sound.baseName(), asset.id(), time, length),
                Map.of(asset, sound.bytes()));
        adopt(document, asset, sound);
        document.getSelection().layerIds().setAll(layerId);
        return layerId;
    }

    /**
     * Adds the sound as an audio asset together with a sound cue, as one undo step.
     *
     * @param cueCommand makes the command that adds the cue for the id of the new asset
     * @return the id of the new asset
     */
    public static String addWithCue(EditorDocument document, Sound sound, Function<String, Command> cueCommand) {
        Asset asset = asset(sound);
        document.execute(cueCommand.apply(asset.id()), Map.of(asset, sound.bytes()));
        adopt(document, asset, sound);
        return asset.id();
    }

    /**
     * Adds an audio layer at the playhead for an audio asset the document has, as one undo step,
     * and selects it. It lasts as long as the sound when that is known by now.
     *
     * @return the id of the new layer; empty when the document has no such audio asset
     */
    public static Optional<String> place(EditorDocument document, String assetId) {
        Asset asset = document.getAnimation().assets().stream()
                .filter(a -> a.kind() == Asset.Kind.AUDIO && a.id().equals(assetId)).findFirst().orElse(null);
        if (asset == null) {
            return Optional.empty();
        }
        String layerId = UUID.randomUUID().toString();
        double length = info(document, asset).map(AudioInfo::duration).orElse(Double.NaN);
        document.execute(Commands.addAudioLayer(layerId, baseName(asset.name()), assetId, document.getPlayhead(), length));
        document.getSelection().layerIds().setAll(layerId);
        return Optional.of(layerId);
    }

    /** What is known by now about the sound of an audio asset of a document. */
    public static Optional<AudioInfo> info(EditorDocument document, Asset asset) {
        byte[] bytes = document.getFileContent().assets().get(asset.id());
        return bytes == null ? Optional.empty() : WaveformCache.shared().knownInfo(bytes, extension(asset.path()));
    }

    /** The waveform of an audio asset of a document, once it is there. */
    public static Optional<Waveform> waveform(EditorDocument document, Asset asset) {
        byte[] bytes = document.getFileContent().assets().get(asset.id());
        return bytes == null ? Optional.empty() : WaveformCache.shared().waveform(bytes, extension(asset.path()));
    }

    /** The document keeps its own copy of the content: what is known about the sound goes with it. */
    private static void adopt(EditorDocument document, Asset asset, Sound sound) {
        byte[] held = document.getFileContent().assets().get(asset.id());
        if (held != null) {
            WaveformCache.shared().transfer(sound.bytes(), held);
        }
    }
}
