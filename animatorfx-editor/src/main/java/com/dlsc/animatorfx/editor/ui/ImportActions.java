package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.timeline.Cues;
import java.util.UUID;
import java.util.function.Consumer;
import com.dlsc.animatorfx.editor.audio.AudioImport;
import com.dlsc.animatorfx.editor.audio.AudioInfo;
import com.dlsc.animatorfx.editor.video.VideoImport;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import com.dlsc.animatorfx.editor.doc.Command;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.importer.FxmlImporter;
import com.dlsc.animatorfx.importer.ImportResult;
import com.dlsc.animatorfx.importer.LottieImporter;
import com.dlsc.animatorfx.importer.SvgImporter;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.io.AnimationFile;
import javafx.scene.image.Image;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * File ▸ Import: SVG, Lottie and FXML files become new untitled documents, an SVG can also
 * be placed into the active document, and sound files become audio layers of the active document. What an importer had to leave out is summed up in a
 * notice whose action lists the warnings, as often as wanted; a file that cannot be imported is reported in a notice.
 */
public final class ImportActions {

    /** The formats that can be imported as a document. */
    public enum Kind {
        SVG("SVG", "svg"),
        LOTTIE("Lottie", "json"),
        FXML("FXML", "fxml");

        private final String label;
        private final String extension;

        Kind(String label, String extension) {
            this.label = label;
            this.extension = extension;
        }

        /** The format a file is taken for by its extension. */
        public static Optional<Kind> of(Path file) {
            String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (name.endsWith("." + kind.extension)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    private final Workspace workspace;
    private final NoticeBar notices;
    private final Supplier<Window> owner;
    private BiFunction<Window, Kind, Optional<Path>> filePicker = this::chooseFile;
    private Function<Window, List<Path>> audioPicker = this::chooseAudioFiles;
    private Function<Window, List<Path>> videoPicker = this::chooseVideoFiles;
    private BiConsumer<String, List<String>> warningsViewer;
    private Path lastDirectory;
    /** Completes when the sound imports started so far are applied; they are applied in order. */
    private CompletableFuture<Void> audioImports = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> videoImports = CompletableFuture.completedFuture(null);

    ImportActions(Workspace workspace, NoticeBar notices, Dialogs dialogs, Supplier<Window> owner) {
        this.workspace = workspace;
        this.notices = notices;
        this.owner = owner;
        this.warningsViewer = dialogs::showWarnings;
    }

    /**
     * Replaces the file dialog; it returns the chosen file, or empty when cancelled.
     */
    public void setFilePicker(BiFunction<Window, Kind, Optional<Path>> picker) {
        filePicker = Objects.requireNonNull(picker, "picker");
    }

    /**
     * Replaces the dialog that lists the warnings of an import; it gets a title and the warnings.
     */
    public void setWarningsViewer(BiConsumer<String, List<String>> viewer) {
        warningsViewer = Objects.requireNonNull(viewer, "viewer");
    }

    /**
     * Replaces the file dialog of Import ▸ Audio…; it returns the chosen files, none when cancelled.
     */
    public void setAudioPicker(Function<Window, List<Path>> picker) {
        audioPicker = Objects.requireNonNull(picker, "picker");
    }

    /**
     * Asks for sound files and adds each to the active document as an audio asset with an audio
     * layer at the playhead.
     */
    public void chooseAndImportAudio() {
        EditorDocument document = workspace.getActiveDocument();
        if (document != null) {
            for (Path file : audioPicker.apply(owner.get())) {
                importAudio(document, file);
            }
        }
    }

    /**
     * Adds a sound file (MP3, WAV, AIFF, AAC) to the document as an audio asset together with an
     * audio layer named after the file, on top of the other layers. The layer starts at the
     * playhead and lasts as long as the sound, at most to the end of the document. Asset and
     * layer are one undo step; the layer is selected. A file that is larger than 50 MB or that
     * is no playable sound is refused with a notice and adds nothing.
     *
     * <p>The file is read, and its length found out, in the background, side by side with other
     * imports: the layer appears a moment later, after the layers of the imports that were
     * started before this one. A notice says so when it takes more than a blink.
     *
     * @return completes on the JavaFX application thread with the id of the new layer, or empty
     *         when the file was refused
     */
    public CompletableFuture<Optional<String>> importAudio(EditorDocument document, Path file) {
        return importAudioAt(document, file, null);
    }

    /** Imports audio at the captured time, even if the playhead moves while the file is read. */
    public CompletableFuture<Optional<String>> importAudio(EditorDocument document, Path file, double time) {
        return importAudioAt(document, file, time);
    }

    private CompletableFuture<Optional<String>> importAudioAt(EditorDocument document, Path file, Double time) {
        CompletableFuture<AudioImport.Sound> read = AudioImport.read(file, message -> notices.show(message, null, null));
        CompletableFuture<Optional<String>> done = new CompletableFuture<>();
        // applied in the order asked for, whichever file is read first
        CompletableFuture<Void> before = audioImports;
        audioImports = done.handle((layer, error) -> null);
        before.whenComplete((nothing, ignored) -> read.whenComplete((sound, error) -> {
            Optional<String> layer = Optional.empty();
            try {
                if (error != null) {
                    refused(file, error);
                } else if (workspace.getDocuments().contains(document)) {
                    if (time != null) {
                        Animation animation = document.getAnimation();
                        double at = animation.frameRate() > 0
                                ? Math.round(time * animation.frameRate()) / animation.frameRate() : time;
                        if (at >= animation.duration() - Animatable.TIME_EPSILON) {
                            throw new IllegalArgumentException("The chosen time is at the end of the animation. "
                                    + "Move the cursor earlier or choose Beginning.");
                        }
                    }
                    layer = Optional.of(time == null ? AudioImport.addLayer(document, sound)
                            : AudioImport.addLayer(document, sound, time));
                }
            } catch (RuntimeException e) {
                refused(file, e);
            } finally {
                // also after an Error: the imports behind this one wait for it
                done.complete(layer);
            }
        }));
        return done;
    }

    /**
     * Completes, on the JavaFX application thread, when every sound import started so far has
     * added its layer or cue, or has been refused.
     */
    public CompletableFuture<Void> whenAudioImported() {
        return audioImports;
    }

    /** Replaces the video file chooser; an empty list cancels the import. */
    public void setVideoPicker(Function<Window, List<Path>> picker) {
        videoPicker = Objects.requireNonNull(picker);
    }

    public void chooseAndImportVideo() {
        EditorDocument document = workspace.getActiveDocument();
        if (document != null) {
            for (Path file : videoPicker.apply(owner.get())) importVideo(document, file);
        }
    }

    /** Reads clips concurrently and applies successful video imports in request order. */
    public CompletableFuture<Optional<String>> importVideo(EditorDocument document, Path file) {
        CompletableFuture<VideoImport.Clip> read = VideoImport.read(file,
                message -> notices.show(message, null, null));
        CompletableFuture<Optional<String>> done = new CompletableFuture<>();
        CompletableFuture<Void> before = videoImports;
        videoImports = done.handle((layer, error) -> null);
        done.whenComplete((layer, error) -> {
            if (done.isCancelled()) read.cancel(true);
        });
        before.whenComplete((nothing, ignored) -> read.whenComplete((clip, error) -> {
            Optional<String> layer = Optional.empty();
            try {
                if (!done.isCancelled()) {
                    if (error != null) refused(file, error);
                    else if (workspace.getDocuments().contains(document)) {
                        layer = Optional.of(VideoImport.addLayer(document, clip));
                    }
                }
            } catch (RuntimeException e) {
                refused(file, e);
            } finally {
                done.complete(layer);
            }
        }));
        return done;
    }

    public CompletableFuture<Void> whenVideoImported() {
        return videoImports;
    }

    private List<Path> chooseVideoFiles(Window window) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Video");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("MP4 video", "*.mp4", "*.m4v"));
        if (lastDirectory != null && Files.isDirectory(lastDirectory)) chooser.setInitialDirectory(lastDirectory.toFile());
        List<File> files = chooser.showOpenMultipleDialog(window);
        if (files == null || files.isEmpty()) return List.of();
        lastDirectory = files.getFirst().toPath().toAbsolutePath().getParent();
        return files.stream().map(File::toPath).toList();
    }

    private void refused(Path file, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        notices.show("Could not import " + file.getFileName() + ": "
                + (cause instanceof AudioImport.Refused || cause instanceof IOException
                || cause instanceof IllegalArgumentException ? cause.getMessage() : "it cannot be read."));
    }

    /**
     * Asks for a sound file and adds it to the active document as an audio asset together with a
     * sound cue at the playhead, as one undo step: "Import Sound…" of the cue menu.
     *
     * @param onAdded receives the id of the new cue
     */
    public void chooseAndImportCue(Consumer<String> onAdded) {
        EditorDocument document = workspace.getActiveDocument();
        List<Path> files = document == null ? List.of() : audioPicker.apply(owner.get());
        if (files.isEmpty()) {
            return;
        }
        Path file = files.get(0);
        double time = document.getPlayhead();
        CompletableFuture<AudioImport.Sound> read = AudioImport.read(file, message -> notices.show(message, null, null));
        CompletableFuture<Void> done = new CompletableFuture<>();
        CompletableFuture<Void> before = audioImports;
        audioImports = done;
        before.whenComplete((nothing, ignored) -> read.whenComplete((sound, error) -> {
            try {
                if (error != null) {
                    refused(file, error);
                } else if (workspace.getDocuments().contains(document)) {
                    String cueId = UUID.randomUUID().toString();
                    AudioImport.addWithCue(document, sound, assetId -> Cues.add(cueId, assetId, time));
                    onAdded.accept(cueId);
                }
            } catch (RuntimeException e) {
                refused(file, e);
            } finally {
                done.complete(null);
            }
        }));
    }

    private List<Path> chooseAudioFiles(Window window) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Audio");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Audio files",
                AudioInfo.EXTENSIONS.stream().map(extension -> "*." + extension).toList()));
        if (lastDirectory != null && Files.isDirectory(lastDirectory)) {
            chooser.setInitialDirectory(lastDirectory.toFile());
        }
        List<File> files = chooser.showOpenMultipleDialog(window);
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        lastDirectory = files.get(0).toPath().toAbsolutePath().getParent();
        return files.stream().map(File::toPath).toList();
    }

    /**
     * Asks for a file of the kind and imports it as a new document.
     */
    public Optional<EditorDocument> chooseAndImport(Kind kind) {
        return filePicker.apply(owner.get(), kind).flatMap(file -> importFile(file, kind));
    }

    /**
     * Imports the file as a new untitled document named after it and makes it the active one.
     * The document is dirty, so it is recovered after a crash like other unsaved work. An
     * untouched untitled document that is the only open one makes way for it.
     *
     * @return the document; empty when the file could not be imported (a notice says why)
     */
    public Optional<EditorDocument> importFile(Path file, Kind kind) {
        EditorDocument document;
        List<String> warnings;
        try {
            String name = baseName(file);
            if (kind == Kind.FXML) {
                FxmlImporter.Result result = FxmlImporter.read(file.toUri().toURL());
                document = EditorDocument.imported(result.file());
                for (FxmlImporter.SlotPreview preview : result.previews()) {
                    Image image = new Image(new ByteArrayInputStream(preview.pngSnapshot()));
                    if (!image.isError()) {
                        document.getSlotPreviews().put(preview.slotName(), image);
                    }
                }
                warnings = result.warnings();
            } else {
                ImportResult result = read(file, kind, name);
                document = EditorDocument.imported(result.file());
                warnings = result.warnings();
            }
        } catch (IOException | RuntimeException | OutOfMemoryError | StackOverflowError e) {
            failed(file, e);
            return Optional.empty();
        }
        EditorDocument placeholder = workspace.getDocuments().size() == 1 ? workspace.getDocuments().get(0) : null;
        workspace.add(document);
        if (placeholder != null && FileActions.isUntouched(placeholder)) {
            workspace.remove(placeholder);
        }
        report(file, warnings);
        return Optional.of(document);
    }

    /**
     * Asks for an SVG file and places it into the active document.
     */
    public boolean chooseSvgIntoDocument() {
        EditorDocument document = workspace.getActiveDocument();
        return document != null && filePicker.apply(owner.get(), Kind.SVG)
                .map(file -> importSvgInto(document, file)).orElse(false);
    }

    /**
     * Adds the layers of an SVG file to the document as one group, named after the file, on top
     * of the other layers, together with the images it embeds. This is one undo step. The group
     * is selected.
     *
     * @return whether the file was imported; a notice says why when it was not
     */
    public boolean importSvgInto(EditorDocument document, Path file) {
        ImportResult result;
        try {
            result = read(file, Kind.SVG, baseName(file));
        } catch (IOException | RuntimeException | OutOfMemoryError | StackOverflowError e) {
            failed(file, e);
            return false;
        }
        AnimationFile content = result.file();
        Animation target = document.getAnimation();
        List<Layer> layers = new ArrayList<>();
        for (Layer layer : content.animation().layers()) {
            layers.add(lasting(layer, target.duration()));
        }
        GroupLayer group = new GroupLayer(LayerBase.create(content.animation().name(), target.duration()), layers);
        Map<Asset, byte[]> assets = new LinkedHashMap<>();
        for (Asset asset : content.animation().assets()) {
            assets.put(asset, content.assets().get(asset.id()));
        }
        Command command = Commands.addLayers("Import SVG", null, target.layers().size(), List.of(group));
        Optional<String> obstacle = Commands.nestingObstacle(command.apply(target));
        if (obstacle.isPresent()) {
            notices.show("Could not import " + file.getFileName() + ": " + obstacle.get());
            return false;
        }
        document.execute(command, assets);
        document.getSelection().layerIds().setAll(group.id());
        report(file, result.warnings());
        return true;
    }

    /** An SVG has no time of its own: its layers are shown for as long as the document runs. */
    private static Layer lasting(Layer layer, double duration) {
        Layer result = layer.withBase(layer.base().withInPoint(0).withOutPoint(duration));
        if (result instanceof GroupLayer group) {
            List<Layer> children = new ArrayList<>();
            for (Layer child : group.children()) {
                children.add(lasting(child, duration));
            }
            return group.withChildren(children);
        }
        return result;
    }

    private static ImportResult read(Path file, Kind kind, String name) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            return kind == Kind.SVG ? SvgImporter.read(in, name) : LottieImporter.read(in, name);
        }
    }

    /** Also for a file that exhausts memory or the stack: it must not take the editor down. */
    private void failed(Path file, Throwable e) {
        String reason = e instanceof Error ? "the file is too large or too deeply nested"
                : e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
        notices.show("Could not import " + file.getFileName() + ": " + reason);
    }

    private void report(Path file, List<String> warnings) {
        if (warnings.isEmpty()) {
            return;
        }
        String summary = "Imported " + file.getFileName() + " with " + warnings.size()
                + (warnings.size() == 1 ? " warning." : " warnings.");
        // the notice stays, so that the warnings can be looked at again
        notices.show(summary, "Show warnings", () -> warningsViewer.accept(summary, warnings), false);
    }

    private static String baseName(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.isBlank() ? EditorDocument.UNTITLED : base;
    }

    private Optional<Path> chooseFile(Window window, Kind kind) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import " + kind.label);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                kind.label + " files (*." + kind.extension + ")", "*." + kind.extension));
        if (lastDirectory != null && Files.isDirectory(lastDirectory)) {
            chooser.setInitialDirectory(lastDirectory.toFile());
        }
        File file = chooser.showOpenDialog(window);
        if (file == null) {
            return Optional.empty();
        }
        lastDirectory = file.toPath().toAbsolutePath().getParent();
        return Optional.of(file.toPath());
    }
}
