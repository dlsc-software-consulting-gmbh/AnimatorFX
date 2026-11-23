package com.dlsc.animatorfx.editor.inspector;

import com.dlsc.animatorfx.editor.audio.AudioImport;
import com.dlsc.animatorfx.editor.video.VideoImport;
import com.dlsc.animatorfx.model.VideoLayer;
import com.dlsc.animatorfx.editor.canvas.tools.NewLayers;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.ImageLayer;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.Layers;
import com.dlsc.animatorfx.model.SoundCue;
import com.dlsc.animatorfx.model.TextLayer;
import com.dlsc.animatorfx.model.Vec2;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import com.dlsc.animatorfx.editor.audio.Waveform;
import com.dlsc.animatorfx.editor.audio.WaveformCache;
import javafx.beans.InvalidationListener;
import javafx.beans.WeakInvalidationListener;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import org.kordamp.ikonli.material2.Material2MZ;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;

/**
 * The Assets tab: the images, fonts and sounds of the document, each with a thumbnail (an "Aa"
 * sample in the font, the waveform of a sound), its name, kind, size, for a sound its length,
 * and the number of layers and cues that use it. Add… takes PNG, JPEG, TTF and OTF files and
 * sound files; an image asset can be placed on the canvas as a new image layer and a sound on
 * the timeline as a new audio layer; an asset can be removed once nothing uses it. Adding, placing and removing are undo steps.
 */
public final class AssetsView extends VBox {

    public static final String ADD_ID = "assets-add";
    public static final String MESSAGE_ID = "assets-message";
    public static final String LIST_ID = "assets-list";

    private static final double THUMBNAIL = 36;

    private final ObjectProperty<EditorDocument> document = new SimpleObjectProperty<>(this, "document");
    private final VBox list = new VBox();
    private final Label message = new Label();
    private final Label empty = new Label("No assets yet. Add images or fonts, or drop image files on the canvas.");
    private final Map<String, Image> images = new HashMap<>();
    private final Map<String, Font> fonts = new HashMap<>();
    private final Map<String, Label> details = new HashMap<>();
    private List<Asset> listed;
    /** The problems of the files of the last Add; sounds report theirs a moment later. */
    private List<String> currentProblems = new ArrayList<>();
    private final List<String> reading = new ArrayList<>();
    private Function<Window, List<Path>> filePicker = AssetsView::chooseFiles;
    private final InvalidationListener update = obs -> update();
    private final InvalidationListener soundArrived = obs -> {
        if (listed != null && listed.stream().anyMatch(asset -> asset.kind() == Asset.Kind.AUDIO
                || asset.kind() == Asset.Kind.VIDEO)) {
            listed = null;
            update();
        }
    };

    public AssetsView() {
        getStyleClass().add("assets-view");
        Button add = new Button("Add…", new FontIcon(Material2AL.ADD));
        add.setId(ADD_ID);
        add.getStyleClass().add("section-button");
        add.setOnAction(e -> {
            Window owner = getScene() == null ? null : getScene().getWindow();
            addFiles(filePicker.apply(owner));
        });
        Label title = new Label("ASSETS");
        title.getStyleClass().add("section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(title, spacer, add);
        header.getStyleClass().add("panel-header");
        header.setAlignment(Pos.CENTER_LEFT);

        message.setId(MESSAGE_ID);
        message.getStyleClass().add("inline-message");
        message.setWrapText(true);
        show(message, false);
        empty.getStyleClass().add("hint");
        empty.setWrapText(true);
        list.setId(LIST_ID);
        list.getStyleClass().add("asset-list");
        VBox content = new VBox(message, empty, list);
        content.getStyleClass().add("panel-content");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("inspector-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(header, scroll);

        // the length and the waveform of a sound arrive after its row was made
        WaveformCache.shared().revisionProperty().addListener(new WeakInvalidationListener(soundArrived));
        VideoImport.revisionProperty().addListener(new WeakInvalidationListener(soundArrived));
        document.addListener((obs, oldDoc, newDoc) -> {
            if (oldDoc != null) {
                oldDoc.animationProperty().removeListener(update);
            }
            if (newDoc != null) {
                newDoc.animationProperty().addListener(update);
            }
            listed = null;
            images.clear();
            fonts.clear();
            setMessage(null);
            update();
        });
        update();
    }

    /**
     * The document whose assets the view lists.
     *
     * @return the document property
     */
    public final ObjectProperty<EditorDocument> documentProperty() {
        return document;
    }

    public final EditorDocument getDocument() {
        return document.get();
    }

    public final void setDocument(EditorDocument value) {
        document.set(value);
    }

    /** Replaces the file dialog of the Add button; it returns the chosen files, none when cancelled. */
    public void setFilePicker(Function<Window, List<Path>> picker) {
        filePicker = Objects.requireNonNull(picker, "picker");
    }

    /** The inline message: why an asset could not be added or removed; {@code null} when there is none. */
    public String getMessage() {
        return message.isVisible() ? message.getText() : null;
    }

    private void setMessage(String text) {
        message.setText(text == null ? "" : text);
        show(message, text != null);
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static List<Path> chooseFiles(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add Assets");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Images, fonts, sounds and videos", "*.png", "*.jpg", "*.jpeg", "*.ttf", "*.otf",
                        "*.mp3", "*.wav", "*.aif", "*.aiff", "*.m4a", "*.aac", "*.mp4", "*.m4v"),
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg"),
                new FileChooser.ExtensionFilter("Fonts", "*.ttf", "*.otf"),
                new FileChooser.ExtensionFilter("Sounds", "*.mp3", "*.wav", "*.aif", "*.aiff", "*.m4a", "*.aac"),
                new FileChooser.ExtensionFilter("Videos", "*.mp4", "*.m4v"));
        List<File> files = chooser.showOpenMultipleDialog(owner);
        return files == null ? List.of() : files.stream().map(File::toPath).toList();
    }

    // ------------------------------------------------------------------ actions

    /** Adds image and font files as assets, one undo step each; files that cannot be read are named in the message. */
    public void addFiles(List<Path> files) {
        EditorDocument doc = getDocument();
        if (doc == null) {
            return;
        }
        List<String> problems = new ArrayList<>();
        currentProblems = problems;
        for (Path file : files) {
            String name = file.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (VideoImport.isVideo(file)) {
                VideoImport.read(file, text -> {
                    reading.add(text);
                    showProblems(problems);
                    return () -> { reading.remove(text); showProblems(problems); };
                }).whenComplete((clip, error) -> {
                    if (error != null) {
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        problems.add("Could not add " + file.getFileName() + ": " + cause.getMessage());
                    } else if (getDocument() == doc) {
                        VideoImport.addAsset(doc, clip);
                    }
                    showProblems(problems);
                });
                continue;
            }
            if (AudioImport.isAudio(file)) {
                addSound(doc, file, problems);
                continue;
            }
            Asset.Kind kind = switch (extension) {
                case "png", "jpg", "jpeg" -> Asset.Kind.IMAGE;
                case "ttf", "otf" -> Asset.Kind.FONT;
                default -> null;
            };
            if (kind == null) {
                problems.add(name + " is not an image (PNG, JPEG), a font (TTF, OTF), a sound (MP3, WAV, AIFF, AAC) or a video (MP4, M4V).");
                continue;
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException e) {
                problems.add("Cannot read " + name + ": " + e.getMessage());
                continue;
            }
            boolean readable = kind == Asset.Kind.IMAGE ? !new Image(new ByteArrayInputStream(bytes)).isError()
                    : Font.loadFont(new ByteArrayInputStream(bytes), 12) != null;
            if (!readable) {
                problems.add(name + " cannot be read as " + (kind == Asset.Kind.IMAGE ? "an image." : "a font."));
                continue;
            }
            String id = UUID.randomUUID().toString();
            doc.addAsset(new Asset(id, kind, "assets/" + id + "." + extension, name), bytes);
        }
        showProblems(problems);
    }

    /** A sound is read and checked in the background; what is wrong with it joins the other problems. */
    private void addSound(EditorDocument doc, Path file, List<String> problems) {
        AudioImport.read(file, text -> {
            reading.add(text);
            showProblems(problems);
            return () -> {
                reading.remove(text);
                showProblems(problems);
            };
        }).whenComplete((sound, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                problems.add("Could not add " + file.getFileName() + ": "
                        + (cause instanceof AudioImport.Refused ? cause.getMessage() : "it cannot be read."));
            } else if (getDocument() == doc) {
                AudioImport.addAsset(doc, sound);
            }
            showProblems(problems);
        });
    }

    /** Shows what is being read and the problems of the files added last, unless a later Add replaced them. */
    private void showProblems(List<String> problems) {
        if (problems != currentProblems) {
            return;
        }
        List<String> lines = new ArrayList<>(reading);
        lines.addAll(problems);
        setMessage(lines.isEmpty() ? null : String.join("\n", lines));
    }

    /** Adds an audio layer for an audio asset at the playhead, on top, and selects it. */
    public Optional<String> placeAudio(String assetId) {
        EditorDocument doc = getDocument();
        return doc == null ? Optional.empty() : AudioImport.place(doc, assetId);
    }

    /** Places a stored video once its metadata is ready, without duplicating its bytes. */
    public java.util.concurrent.CompletableFuture<Optional<String>> placeVideo(String assetId) {
        EditorDocument doc = getDocument();
        Asset asset = doc == null ? null : doc.getAnimation().assets().stream()
                .filter(value -> value.kind() == Asset.Kind.VIDEO && value.id().equals(assetId)).findFirst().orElse(null);
        if (asset == null) return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
        byte[] source = doc.getFileContent().assets().get(assetId);
        return VideoImport.metadata(doc, asset).thenApply(info -> getDocument() == doc
                && doc.getFileContent().assets().get(assetId) == source
                ? VideoImport.place(doc, assetId) : Optional.<String>empty()).exceptionally(error -> {
                    if (getDocument() == doc) setMessage("Could not place " + asset.name() + ": video metadata is unavailable.");
                    return Optional.empty();
                });
    }

    /** Removes an asset as one undo step; refuses, with a message, while layers or cues use it. */
    public void remove(String assetId) {
        EditorDocument doc = getDocument();
        Asset asset = doc == null ? null
                : doc.getAnimation().assets().stream().filter(a -> a.id().equals(assetId)).findFirst().orElse(null);
        if (asset == null) {
            return;
        }
        int layers = layerUsage(doc.getAnimation(), assetId);
        int cues = cueUsage(doc.getAnimation(), assetId);
        if (layers + cues > 0) {
            String byLayers = layers == 0 ? "" : layers + (layers == 1 ? " layer" : " layers");
            String byCues = cues == 0 ? "" : cues + (cues == 1 ? " cue" : " cues");
            setMessage("“" + asset.name() + "” is used by " + byLayers + (layers > 0 && cues > 0 ? " and " : "") + byCues
                    + ". Delete or reassign " + (layers + cues == 1 ? "it" : "them") + " first.");
            return;
        }
        setMessage(null);
        doc.execute(Commands.setDocument("Remove Asset",
                a -> a.withAssets(a.assets().stream().filter(other -> !other.id().equals(assetId)).toList())));
    }

    /** Adds an image layer for an image asset in the middle of the artboard, on top, and selects it. */
    public Optional<String> place(String assetId) {
        EditorDocument doc = getDocument();
        Image image = doc == null ? null : image(doc, assetId);
        if (image == null || image.isError()) {
            return Optional.empty();
        }
        Animation animation = doc.getAnimation();
        double fit = Math.min(1, Math.min(animation.width() / image.getWidth(), animation.height() / image.getHeight()));
        ImageLayer layer = NewLayers.image(animation, assetId, new Vec2(animation.width() / 2, animation.height() / 2),
                new Vec2(image.getWidth() * fit, image.getHeight() * fit));
        doc.execute(Commands.batch("Add Image", List.of(Commands.addLayer(null, animation.layers().size(), layer))));
        doc.getSelection().layerIds().setAll(layer.id());
        return Optional.of(layer.id());
    }

    /** How many layers and sound cues use the asset. */
    public static int usage(Animation animation, String assetId) {
        return layerUsage(animation, assetId) + cueUsage(animation, assetId);
    }

    private static int layerUsage(Animation animation, String assetId) {
        int count = 0;
        for (Layer layer : Layers.flatten(animation)) {
            if (layer instanceof ImageLayer image && image.assetId().equals(assetId)
                    || layer instanceof TextLayer text && assetId.equals(text.fontAssetId())
                    || layer instanceof VideoLayer video && video.assetId().equals(assetId)
                    || layer instanceof AudioLayer audio && audio.assetId().equals(assetId)) {
                count++;
            }
        }
        return count;
    }

    private static int cueUsage(Animation animation, String assetId) {
        return (int) animation.cues().stream().filter(cue -> cue.assetId().equals(assetId)).count();
    }

    // ------------------------------------------------------------------ list

    private Image image(EditorDocument doc, String assetId) {
        return images.computeIfAbsent(assetId, id -> {
            byte[] bytes = doc.getFileContent().assets().get(id);
            return bytes == null ? null : new Image(new ByteArrayInputStream(bytes));
        });
    }

    private void update() {
        EditorDocument doc = getDocument();
        List<Asset> assets = doc == null ? List.of() : doc.getAnimation().assets();
        if (assets != listed) {
            listed = assets;
            details.clear();
            List<Node> rows = new ArrayList<>();
            for (Asset asset : assets) {
                rows.add(row(doc, asset));
            }
            list.getChildren().setAll(rows);
            show(empty, assets.isEmpty());
        }
        if (doc != null) {
            // the usage counts change with the layers; the rows stay
            for (Asset asset : assets) {
                Label detail = details.get(asset.id());
                if (detail != null) {
                    detail.setText(detail(doc, asset));
                }
            }
        }
    }

    private static String detail(EditorDocument doc, Asset asset) {
        byte[] bytes = doc.getFileContent().assets().get(asset.id());
        int users = usage(doc.getAnimation(), asset.id());
        String size = bytes == null ? "missing" : String.format(Locale.ROOT, "%.1f KB", bytes.length / 1024.0);
        String kind = switch (asset.kind()) {
            case IMAGE -> "Image";
            case FONT -> "Font";
            case AUDIO -> "Audio";
            case VIDEO -> "Video";
        };
        String usage = users == 0 ? "unused" : "used " + users + "×";
        if (asset.kind() == Asset.Kind.AUDIO) {
            // two lines: with the length there is more to say than the panel is wide
            return kind + AudioImport.info(doc, asset).map(info -> " · " + info.durationText()).orElse("")
                    + "\n" + size + " · " + usage;
        }
        if (asset.kind() == Asset.Kind.VIDEO) {
            return "Video" + VideoImport.info(doc, asset).map(info -> " · " + info.description()).orElse("")
                    + "\n" + size + " · " + usage;
        }
        return kind + " · " + size + " · " + usage;
    }

    private Node row(EditorDocument doc, Asset asset) {
        StackPane thumbnail = new StackPane();
        thumbnail.getStyleClass().add("asset-thumbnail");
        thumbnail.setMinSize(THUMBNAIL, THUMBNAIL);
        thumbnail.setMaxSize(THUMBNAIL, THUMBNAIL);
        if (asset.kind() == Asset.Kind.IMAGE) {
            Image image = image(doc, asset.id());
            if (image != null && !image.isError()) {
                ImageView view = new ImageView(image);
                view.setPreserveRatio(true);
                view.setFitWidth(THUMBNAIL - 4);
                view.setFitHeight(THUMBNAIL - 4);
                thumbnail.getChildren().add(view);
            }
        } else if (asset.kind() == Asset.Kind.FONT) {
            Label sample = new Label("Aa");
            sample.getStyleClass().add("font-sample");
            Font font = fonts.computeIfAbsent(asset.id(), id -> {
                byte[] bytes = doc.getFileContent().assets().get(id);
                return bytes == null ? null : Font.loadFont(new ByteArrayInputStream(bytes), 18);
            });
            if (font != null) {
                // the sample is in the asset's own font, which no stylesheet can name
                sample.setFont(font);
            }
            thumbnail.getChildren().add(sample);
        } else if (asset.kind() == Asset.Kind.VIDEO) {
            thumbnail.getChildren().add(new FontIcon(Material2MZ.VIDEOCAM));
        } else {
            Optional<Waveform> waveform = AudioImport.waveform(doc, asset);
            if (waveform.isPresent()) {
                thumbnail.getChildren().add(waveformShape(waveform.get()));
            } else {
                FontIcon speaker = new FontIcon(Material2MZ.VOLUME_UP);
                speaker.getStyleClass().add("asset-sound-icon");
                thumbnail.getChildren().add(speaker);
            }
        }

        Label name = new Label(asset.name());
        name.getStyleClass().add("asset-name");
        name.setMinWidth(0);
        Label detail = new Label(detail(doc, asset));
        detail.getStyleClass().addAll("asset-detail", "mono");
        detail.setMinWidth(0);
        details.put(asset.id(), detail);
        VBox text = new VBox(name, detail);
        text.setAlignment(Pos.CENTER_LEFT);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(thumbnail, text);
        row.getStyleClass().add("asset-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setUserData(asset.id());
        if (asset.kind() == Asset.Kind.IMAGE) {
            Button place = iconButton(Material2AL.ADD_PHOTO_ALTERNATE, "Place on the canvas as a new image layer");
            place.getStyleClass().add("asset-place");
            place.setOnAction(e -> place(asset.id()));
            row.getChildren().add(place);
        }
        if (asset.kind() == Asset.Kind.AUDIO) {
            Button place = iconButton(Material2MZ.PLAYLIST_ADD, "Place on the timeline as a new audio layer at the playhead");
            place.getStyleClass().add("asset-place");
            place.setOnAction(e -> placeAudio(asset.id()));
            row.getChildren().add(place);
        }
        if (asset.kind() == Asset.Kind.VIDEO) {
            Button place = iconButton(Material2MZ.VIDEO_LIBRARY, "Place as a video layer at the playhead");
            place.getStyleClass().add("asset-place");
            place.setOnAction(event -> placeVideo(asset.id()));
            row.getChildren().add(place);
        }
        Button remove = iconButton(Material2AL.DELETE_OUTLINE, "Remove the asset");
        remove.getStyleClass().add("asset-remove");
        remove.setOnAction(e -> remove(asset.id()));
        row.getChildren().add(remove);
        return row;
    }

    /** The whole sound as a small mirrored outline, in the colour of the stylesheet. */
    private static Node waveformShape(Waveform waveform) {
        double width = THUMBNAIL - 6;
        double reach = (THUMBNAIL - 10) / 2;
        int columns = (int) width;
        javafx.scene.shape.Path path = new javafx.scene.shape.Path();
        double[] heights = new double[columns];
        for (int i = 0; i < columns; i++) {
            double from = waveform.duration() * i / columns;
            double to = waveform.duration() * (i + 1) / columns;
            heights[i] = Math.max(0.5, waveform.peak(from, to) * reach);
        }
        path.getElements().add(new MoveTo(0, -heights[0]));
        for (int i = 0; i < columns; i++) {
            path.getElements().add(new LineTo(i, -heights[i]));
            path.getElements().add(new LineTo(i + 1, -heights[i]));
        }
        for (int i = columns - 1; i >= 0; i--) {
            path.getElements().add(new LineTo(i + 1, heights[i]));
            path.getElements().add(new LineTo(i, heights[i]));
        }
        path.getElements().add(new ClosePath());
        path.getStyleClass().add("asset-waveform");
        return path;
    }

    private static Button iconButton(org.kordamp.ikonli.Ikon icon, String tooltip) {
        Button button = new Button(null, new FontIcon(icon));
        button.getStyleClass().setAll("icon-button", "section-toggle");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }
}
