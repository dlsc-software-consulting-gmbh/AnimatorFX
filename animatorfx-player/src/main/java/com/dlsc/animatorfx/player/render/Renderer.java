package com.dlsc.animatorfx.player.render;

import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.ImageLayer;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.Mask;
import com.dlsc.animatorfx.model.SlotLayer;
import com.dlsc.animatorfx.model.TextLayer;
import com.dlsc.animatorfx.model.VideoLayer;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.player.Frame;
import com.dlsc.animatorfx.player.LayerState;
import com.dlsc.animatorfx.player.internal.video.VideoEngine;
import com.dlsc.animatorfx.player.internal.video.MediaVideoOutput;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.Rectangle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shows a document as a JavaFX node tree. The tree is built once per document structure;
 * afterwards {@link #apply(Frame)} only writes the values that differ from the frame before.
 * <p>
 * The tree is in document pixels. {@link #getRoot()} is clipped to the document rectangle and
 * holds a background rectangle followed by the top-level layers, bottom to top. Every layer is
 * one node that carries the layer's transform (a single {@code Affine}), opacity, visibility,
 * effects and mask:
 * <ul>
 * <li>group: a {@code Group} of its children</li>
 * <li>shape: a {@code Group} with the outline as a {@code Path} and, if the layer has a trim, a
 * second {@code Path} for the trimmed stroke</li>
 * <li>text: a {@code Text} whose top-left corner is the layer origin</li>
 * <li>image: an {@code ImageView} at the layer origin</li>
 * <li>slot: an empty {@code Group} for the node bound to the slot</li>
 * </ul>
 * A layer that masks a sibling is not part of the visible tree: it is the clip of the masked
 * layer's node, once for every layer it masks. The mask applies no matter whether the mask layer
 * itself is visible.
 * <p>
 * Use on the JavaFX application thread only.
 */
public final class Renderer {

    private static final System.Logger LOG = System.getLogger(Renderer.class.getName());

    /** A masked layer and the copy of its mask layer that clips it. */
    private static final class MaskLink {

        private final LayerNode masked;
        private final LayerNode mask;
        private double[] maskedMatrix;
        private double[] maskMatrix;

        MaskLink(LayerNode masked, LayerNode mask) {
            this.masked = masked;
            this.mask = mask;
        }

        /**
         * A clip lives in the coordinates of the node it clips, but the mask layer is a sibling
         * of the masked layer: place the clip at inverse(masked) · mask.
         */
        void update() {
            double[] a = masked.matrix();
            double[] b = mask.matrix();
            if (a == null || b == null || (a == maskedMatrix && b == maskMatrix)) {
                return;
            }
            maskedMatrix = a;
            maskMatrix = b;
            double determinant = a[0] * a[4] - a[1] * a[3];
            if (Math.abs(determinant) < 1e-12) {
                // the masked layer is collapsed to nothing and shows nothing
                return;
            }
            double ixx = a[4] / determinant;
            double ixy = -a[1] / determinant;
            double iyx = -a[3] / determinant;
            double iyy = a[0] / determinant;
            double itx = -(ixx * a[2] + ixy * a[5]);
            double ity = -(iyx * a[2] + iyy * a[5]);
            mask.placeAt(
                    ixx * b[0] + ixy * b[3], ixx * b[1] + ixy * b[4], ixx * b[2] + ixy * b[5] + itx,
                    iyx * b[0] + iyy * b[3], iyx * b[1] + iyy * b[4], iyx * b[2] + iyy * b[5] + ity);
        }
    }

    private final Group root = new Group();
    private final Rectangle background = new Rectangle();
    private final Rectangle bounds = new Rectangle();
    private final Assets assets = new Assets();

    /** Every layer node including the copies made for masks, parents before children. */
    private final List<LayerNode> nodes = new ArrayList<>();
    private final List<MaskLink> masks = new ArrayList<>();
    private final Map<String, LayerNode> nodesById = new HashMap<>();
    private final Map<String, Group> slots = new HashMap<>();
    private List<String> structure;
    private final VideoEngine video;
    private final boolean ownsVideo;

    public Renderer() {
        this(new VideoEngine(MediaVideoOutput::new, LOG), true);
    }

    public Renderer(VideoEngine video) {
        this(video, false);
    }

    private Renderer(VideoEngine video, boolean ownsVideo) {
        this.video = video;
        this.ownsVideo = ownsVideo;
        root.setClip(bounds);
    }

    /**
     * The node tree in document pixels.
     */
    public Group getRoot() {
        return root;
    }

    /**
     * Shows a document, or nothing for {@code null}. The nodes are kept when the document has the
     * structure of the one shown before, that is the same layers (id and type) in the same order
     * and nesting with the same masks, asset references and slot names; all other differences are
     * written to the existing nodes. Follow up with {@link #apply(Frame)}: sampled values appear
     * only then.
     */
    public void setDocument(AnimationFile file) {
        if (ownsVideo) {
            video.setDocument(file);
            video.resume();
        }
        if (file == null) {
            clear();
            root.getChildren().clear();
            structure = null;
            return;
        }
        Animation animation = file.animation();
        List<String> newStructure = new ArrayList<>();
        describe(animation.layers(), newStructure);
        boolean rebuild = !newStructure.equals(structure);
        if (rebuild) {
            clear();
            List<Node> children = new ArrayList<>();
            children.add(background);
            buildSiblings(animation.layers(), children, false, new HashSet<>());
            root.getChildren().setAll(children);
            structure = newStructure;
        }

        background.setWidth(animation.width());
        background.setHeight(animation.height());
        background.setFill(PaintConverter.toFx(animation.background()));
        bounds.setWidth(animation.width());
        bounds.setHeight(animation.height());

        boolean force = assets.use(file.assets()) || rebuild;
        Map<String, Layer> layers = new HashMap<>();
        index(animation.layers(), layers);
        for (LayerNode node : nodes) {
            node.setLayer(layers.get(node.id), assets, force);
        }
    }

    /**
     * Writes a frame of the current document to the nodes. Only values that differ from the
     * frame applied before are written. Layers the frame does not know stay as they are.
     */
    public void apply(Frame frame) {
        if (ownsVideo) {
            video.sync(frame, false, 1, false, true, 0);
        }
        for (int i = 0; i < nodes.size(); i++) {
            LayerNode node = nodes.get(i);
            LayerState state = frame.get(node.id);
            if (state != null) {
                node.apply(state);
            }
        }
        for (int i = 0; i < masks.size(); i++) {
            masks.get(i).update();
        }
    }

    /**
     * The node of a layer, or {@code null} if the document has no such layer. For a layer that
     * masks another one this is the clip node, which is not part of the visible tree.
     */
    public Node getLayerNode(String layerId) {
        LayerNode node = nodesById.get(layerId);
        return node == null ? null : node.node;
    }

    /**
     * The container for the node bound to a slot, or {@code null} if the document has no slot of
     * that name. It is the slot layer's node. Its children are not resized by the container.
     */
    public Group getSlotContainer(String slotName) {
        return slots.get(slotName);
    }

    private void clear() {
        nodes.forEach(LayerNode::dispose);
        nodes.clear();
        masks.clear();
        nodesById.clear();
        slots.clear();
    }

    /** Releases node bindings and any media session owned by this renderer. */
    public void dispose() {
        clear();
        root.getChildren().clear();
        structure = null;
        if (ownsVideo) video.dispose();
    }

    /** Lists everything that decides which nodes exist and how they nest. */
    private static void describe(List<Layer> layers, List<String> out) {
        for (Layer layer : layers) {
            out.add(layer.getClass().getSimpleName());
            out.add(layer.id());
            Mask mask = layer.base().mask();
            out.add(mask == null ? "" : mask.mode().name());
            out.add(mask == null ? "" : mask.layerId());
            switch (layer) {
                case ImageLayer image -> out.add(image.assetId());
                case VideoLayer movie -> out.add(movie.assetId());
                case TextLayer text -> out.add(text.fontAssetId() == null ? "" : text.fontAssetId());
                case SlotLayer slot -> out.add(slot.slotName());
                case GroupLayer group -> {
                    out.add("{");
                    describe(group.children(), out);
                    out.add("}");
                }
                default -> out.add("");
            }
        }
    }

    private static void index(List<Layer> layers, Map<String, Layer> out) {
        for (Layer layer : layers) {
            out.putIfAbsent(layer.id(), layer);
            if (layer instanceof GroupLayer group) {
                index(group.children(), out);
            }
        }
    }

    /**
     * Builds the nodes of the layers that are rendered themselves, bottom to top.
     *
     * @param building the ids of the layers whose node is under construction, to stop mask cycles
     */
    private void buildSiblings(List<Layer> siblings, List<Node> out, boolean geometryOnly, Set<String> building) {
        Map<String, Layer> byId = new HashMap<>();
        for (Layer layer : siblings) {
            byId.putIfAbsent(layer.id(), layer);
        }
        Set<String> maskIds = new HashSet<>();
        for (Layer layer : siblings) {
            Layer mask = maskOf(layer, byId);
            if (mask != null) {
                maskIds.add(mask.id());
            } else if (layer.base().mask() != null && !(layer instanceof AudioLayer)) {
                LOG.log(System.Logger.Level.WARNING, "Layer ''{0}'' is masked by ''{1}'', which is not a visual"
                        + " sibling layer; the mask is ignored", layer.id(), layer.base().mask().layerId());
            }
        }
        for (Layer layer : siblings) {
            if (!maskIds.contains(layer.id())) {
                out.add(build(layer, byId, geometryOnly, false, building).node);
            }
        }
    }

    private static Layer maskOf(Layer layer, Map<String, Layer> siblings) {
        Mask mask = layer.base().mask();
        if (mask == null || mask.layerId().equals(layer.id()) || layer instanceof AudioLayer) {
            return null;
        }
        // an audio layer has nothing to mask with
        return siblings.get(mask.layerId()) instanceof AudioLayer ? null : siblings.get(mask.layerId());
    }

    private LayerNode build(Layer layer, Map<String, Layer> siblings, boolean geometryOnly, boolean clip,
                            Set<String> building) {
        LayerNode node = LayerNode.create(layer, geometryOnly, clip, video);
        nodes.add(node);
        nodesById.putIfAbsent(layer.id(), node);
        building.add(layer.id());
        if (layer instanceof GroupLayer group) {
            List<Node> children = new ArrayList<>();
            buildSiblings(group.children(), children, geometryOnly, building);
            ((Group) node.node).getChildren().setAll(children);
        } else if (layer instanceof SlotLayer slot) {
            Group container = (Group) node.node;
            // the bound node keeps the size the binding gives it
            container.setAutoSizeChildren(false);
            slots.putIfAbsent(slot.slotName(), container);
        }
        Layer mask = maskOf(layer, siblings);
        if (mask != null && !building.contains(mask.id())) {
            boolean geometry = geometryOnly || layer.base().mask().mode() == Mask.Mode.CLIP;
            LayerNode maskNode = build(mask, siblings, geometry, true, building);
            node.node.setClip(maskNode.node);
            masks.add(new MaskLink(node, maskNode));
        }
        building.remove(layer.id());
        return node;
    }
}
