package com.dlsc.animatorfx.editor.canvas;

import com.dlsc.animatorfx.editor.canvas.tools.NewLayers;
import com.dlsc.animatorfx.editor.doc.Command;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.VideoLayer;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.ImageLayer;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.Layers;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.TextLayer;
import com.dlsc.animatorfx.model.Vec2;
import javafx.collections.ObservableList;
import javafx.scene.image.Image;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The commands of the Edit menu on the layer selection of the canvas document: delete,
 * duplicate, select all, group and ungroup, stacking order, the layer clipboard, and adding
 * images. Each is one undo step and does nothing without a document or a selection to work on.
 */
public final class CanvasActions {

    private static final double PASTE_OFFSET = 10;

    private final CanvasView canvas;
    private LayerClipboard clipboard = LayerClipboard.shared();
    private Function<Window, Optional<Path>> imagePicker = CanvasActions::chooseImageFile;

    CanvasActions(CanvasView canvas) {
        this.canvas = canvas;
    }

    private EditorDocument doc() {
        return canvas.getDocument();
    }

    private List<String> selected() {
        return doc() == null ? List.of() : List.copyOf(doc().getSelection().layerIds());
    }

    /** Whether the command would nest layers deeper than a file can hold; says so in a notice. */
    private boolean nestsTooDeep(Command command) {
        Optional<String> obstacle = Commands.nestingObstacle(command.apply(doc().getAnimation()));
        obstacle.ifPresent(canvas::notice);
        return obstacle.isPresent();
    }

    private static Set<String> ids(Animation animation) {
        Set<String> ids = new HashSet<>();
        for (Layer layer : Layers.flatten(animation)) {
            ids.add(layer.id());
        }
        return ids;
    }

    /** The clipboard for cut, copy and paste; the application-wide one unless replaced. */
    public LayerClipboard getClipboard() {
        return clipboard;
    }

    public void setClipboard(LayerClipboard clipboard) {
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
    }

    /**
     * Replaces the file dialog of {@link #chooseImage()}; it returns the chosen file, or empty
     * when cancelled.
     */
    public void setImagePicker(Function<Window, Optional<Path>> picker) {
        imagePicker = Objects.requireNonNull(picker, "picker");
    }

    // ------------------------------------------------------------------ selection

    public void delete() {
        // sound cues are selected instead of layers
        if (doc() != null && !doc().getSelection().cueIds().isEmpty()) {
            doc().execute(Commands.removeCues(List.copyOf(doc().getSelection().cueIds())));
            return;
        }
        List<String> ids = selected();
        if (!ids.isEmpty()) {
            doc().execute(Commands.removeLayers(ids));
        }
    }

    /** Copies the selected layers directly above the originals and selects the copies. */
    public void duplicate() {
        List<String> ids = selected();
        if (ids.isEmpty()) {
            return;
        }
        Set<String> before = ids(doc().getAnimation());
        doc().execute(Commands.duplicateLayers(ids));
        selectAdded(before);
    }

    /** Selects the layers that were not there before, leaving out children of new groups. */
    private void selectAdded(Set<String> before) {
        Animation animation = doc().getAnimation();
        List<String> added = new ArrayList<>();
        for (Layer layer : Layers.flatten(animation)) {
            boolean parentIsNew = Layers.parentOf(animation, layer.id()).filter(p -> !before.contains(p.id())).isPresent();
            if (!before.contains(layer.id()) && !parentIsNew) {
                added.add(layer.id());
            }
        }
        if (!added.isEmpty()) {
            doc().getSelection().layerIds().setAll(added);
        }
    }

    /** Selects every layer of the current level that can be picked on the canvas. */
    public void selectAll() {
        if (doc() == null) {
            return;
        }
        LayerGeometry geometry = canvas.getGeometry();
        List<String> ids = geometry.scope().stream().filter(geometry::isSelectable).map(Layer::id).toList();
        doc().getSelection().layerIds().setAll(ids);
    }

    // ------------------------------------------------------------------ paths

    /** Whether a selected layer is a rectangle, ellipse, line, polygon or star, which can become a path. */
    public boolean canConvertToPath() {
        return doc() != null && canConvertToPath(doc().getAnimation(), selected());
    }

    /** Whether one of the layers is a rectangle, ellipse, line, polygon or star. */
    public static boolean canConvertToPath(Animation animation, List<String> layerIds) {
        return layerIds.stream().anyMatch(id -> Layers.find(animation, id)
                .filter(layer -> layer instanceof ShapeLayer shape && !(shape.geometry() instanceof Geometry.PathGeometry))
                .isPresent());
    }

    /**
     * Turns the selected primitive shapes into path layers with the same outline, as one undo
     * step. Refuses with a notice when one of them has geometry keyframes, which would be lost.
     */
    public void convertToPath() {
        List<String> ids = selected();
        if (ids.isEmpty()) {
            return;
        }
        Optional<String> obstacle = Commands.convertObstacle(doc().getAnimation(), ids);
        if (obstacle.isPresent()) {
            canvas.notice(obstacle.get());
            return;
        }
        doc().execute(Commands.convertToPath(ids, canvas.getTime()));
    }

    // ------------------------------------------------------------------ structure

    public void group() {
        List<String> ids = selected();
        if (ids.isEmpty()) {
            return;
        }
        Animation before = doc().getAnimation();
        String groupId = UUID.randomUUID().toString();
        Command command = Commands.groupLayers(ids, groupId, LayerNames.next(before, "Group"));
        if (nestsTooDeep(command)) {
            return;
        }
        doc().execute(command);
        if (doc().getAnimation() == before) {
            canvas.notice("Only layers of the same group can be grouped, and a mask has to stay with its layer.");
        } else {
            doc().getSelection().layerIds().setAll(groupId);
        }
    }

    public void ungroup() {
        Animation before = doc() == null ? null : doc().getAnimation();
        List<String> groups = selected().stream()
                .filter(id -> Layers.find(before, id).orElse(null) instanceof GroupLayer).toList();
        if (groups.isEmpty()) {
            return;
        }
        Commands.ungroupObstacle(before, groups).ifPresent(canvas::notice);
        List<String> children = new ArrayList<>();
        for (String id : groups) {
            ((GroupLayer) Layers.find(before, id).orElseThrow()).children().forEach(c -> children.add(c.id()));
        }
        if (groups.contains(canvas.getEnteredGroupId())) {
            canvas.enterGroup(null);
        }
        doc().execute(Commands.ungroupLayers(groups));
        Animation after = doc().getAnimation();
        if (after != before) {
            Set<String> present = ids(after);
            List<String> selection = new ArrayList<>(selected());
            for (String child : children) {
                // the children of a group that was left alone are still inside it
                if (present.contains(child) && Layers.parentOf(after, child).filter(p -> groups.contains(p.id())).isEmpty()) {
                    selection.add(child);
                }
            }
            doc().getSelection().layerIds().setAll(selection);
        }
    }

    public void reorder(Commands.Order order) {
        List<String> ids = selected();
        if (!ids.isEmpty()) {
            doc().execute(Commands.reorderLayers(ids, order));
        }
    }

    // ------------------------------------------------------------------ clipboard

    public void copy() {
        if (doc() == null) {
            return;
        }
        Animation animation = doc().getAnimation();
        Set<String> wanted = new HashSet<>(selected());
        List<Layer> layers = new ArrayList<>();
        collectTopMost(animation.layers(), wanted, layers);
        if (layers.isEmpty()) {
            return;
        }
        Set<String> assetIds = new HashSet<>();
        for (Layer top : layers) {
            for (Layer layer : subtree(top)) {
                if (layer instanceof ImageLayer image) {
                    assetIds.add(image.assetId());
                } else if (layer instanceof AudioLayer audio) {
                    assetIds.add(audio.assetId());
                } else if (layer instanceof VideoLayer video) {
                    assetIds.add(video.assetId());
                } else if (layer instanceof TextLayer text && text.fontAssetId() != null) {
                    assetIds.add(text.fontAssetId());
                }
            }
        }
        Map<Asset, byte[]> assets = new LinkedHashMap<>();
        Map<String, byte[]> content = doc().getFileContent().assets();
        for (Asset asset : animation.assets()) {
            if (assetIds.contains(asset.id()) && content.containsKey(asset.id())) {
                assets.put(asset, content.get(asset.id()));
            }
        }
        clipboard.set(layers, assets);
    }

    public void cut() {
        List<String> ids = selected();
        if (ids.isEmpty()) {
            return;
        }
        copy();
        doc().execute(Commands.all("Cut", Commands.removeLayers(ids)));
    }

    /**
     * Pastes copies of the clipboard layers, with new ids, on top of the current level, each
     * paste 10 px further down and right, together with the assets they need, and selects them.
     * A pasted slot whose name is taken gets the next free slot name. Refuses with a notice
     * when the layers would end up nested deeper than a file can hold.
     */
    public void paste() {
        if (doc() == null || clipboard.isEmpty()) {
            return;
        }
        Animation animation = doc().getAnimation();
        double offset = PASTE_OFFSET * clipboard.nextPaste();
        List<Layer> copies = new ArrayList<>();
        Set<String> slotNames = Commands.slotNames(animation);
        for (Layer copy : Commands.freshCopies(clipboard.getLayers())) {
            copies.add(Commands.uniqueSlots(Commands.translated(copy, new Vec2(offset, offset)), slotNames));
        }
        String parent = canvas.getEnteredGroupId();
        Command command = Commands.addLayers("Paste", parent, canvas.getGeometry().scope().size(), copies);
        if (nestsTooDeep(command)) {
            return;
        }
        doc().execute(command, clipboard.getAssets());
        doc().getSelection().layerIds().setAll(copies.stream().map(Layer::id).toList());
    }

    private static void collectTopMost(List<Layer> layers, Set<String> wanted, List<Layer> out) {
        for (Layer layer : layers) {
            if (wanted.contains(layer.id())) {
                out.add(layer);
            } else if (layer instanceof GroupLayer group) {
                collectTopMost(group.children(), wanted, out);
            }
        }
    }

    private static List<Layer> subtree(Layer layer) {
        List<Layer> out = new ArrayList<>();
        out.add(layer);
        if (layer instanceof GroupLayer group) {
            group.children().forEach(child -> out.addAll(subtree(child)));
        }
        return out;
    }

    // ------------------------------------------------------------------ images

    private static Optional<Path> chooseImageFile(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add Image");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg"));
        return Optional.ofNullable(chooser.showOpenDialog(owner)).map(java.io.File::toPath);
    }

    /**
     * Asks for an image file and adds it in the middle of the artboard.
     *
     * @return the id of the new layer; empty when cancelled or the file is no image
     */
    public Optional<String> chooseImage() {
        if (doc() == null) {
            return Optional.empty();
        }
        Window owner = canvas.getScene() == null ? null : canvas.getScene().getWindow();
        return imagePicker.apply(owner).flatMap(file -> addImage(file, null));
    }

    /**
     * Adds an image file as an asset plus an image layer at its natural size, scaled down to fit
     * the artboard when it is larger. Asset and layer are one undo step.
     *
     * @param viewPointOrNull where the middle of the image goes, in view pixels; {@code null}
     *                        for the middle of the artboard
     * @return the id of the new layer; empty when the file could not be read as an image
     */
    public Optional<String> addImage(Path file, Vec2 viewPointOrNull) {
        if (doc() == null) {
            return Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            canvas.notice("Cannot read " + file.getFileName() + ": " + e.getMessage());
            return Optional.empty();
        }
        Image image = new Image(new ByteArrayInputStream(bytes));
        if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) {
            canvas.notice(file.getFileName() + " is not an image that can be shown (PNG or JPEG).");
            return Optional.empty();
        }
        Animation animation = doc().getAnimation();
        double fit = Math.min(1, Math.min(animation.width() / image.getWidth(), animation.height() / image.getHeight()));
        Vec2 size = new Vec2(image.getWidth() * fit, image.getHeight() * fit);
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "png" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String assetId = UUID.randomUUID().toString();
        Asset asset = new Asset(assetId, Asset.Kind.IMAGE, "assets/" + assetId + "." + extension, name);

        LayerGeometry geometry = canvas.getGeometry();
        String parent = canvas.getEnteredGroupId();
        Vec2 viewPoint = viewPointOrNull != null ? viewPointOrNull
                : canvas.documentToView(new Vec2(animation.width() / 2, animation.height() / 2));
        ImageLayer layer = NewLayers.image(animation, assetId, geometry.viewToGroup(parent, viewPoint), size);
        doc().execute(Commands.all("Add Image", Commands.addLayer(parent, geometry.scope().size(), layer)),
                Map.of(asset, bytes));
        doc().getSelection().layerIds().setAll(layer.id());
        return Optional.of(layer.id());
    }
}
