package com.dlsc.animatorfx.editor.canvas;

import com.dlsc.animatorfx.editor.doc.Matrices;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.ImageLayer;
import com.dlsc.animatorfx.model.VideoLayer;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.Layers;
import com.dlsc.animatorfx.model.Mask;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.PathData;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.SlotLayer;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.player.Frame;
import com.dlsc.animatorfx.player.LayerState;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.shape.Shape;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where the layers of the canvas document are on screen at the current frame: matrices, bounds
 * and hit testing. Selection boxes, snapping and the tools all ask here.
 *
 * <p>Matrices use the layout of {@link com.dlsc.animatorfx.model.Transform#matrixAt}. They are
 * built from the sampled frame the renderer applies to its nodes, so they equal the nodes'
 * local-to-scene transforms, including rotation and group nesting, and are also there for layers
 * without a node in the scene. Bounds come from the rendered node.
 */
public final class LayerGeometry {

    /** How far around the pointer a thin stroke is still hit, in view pixels. */
    private static final double HIT_TOLERANCE = 3;
    private static final double[][] PROBES = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {0.7, 0.7}, {-0.7, 0.7}, {0.7, -0.7}, {-0.7, -0.7}};

    private final CanvasView canvas;
    private Animation maskSource;
    private Set<String> maskIds = Set.of();

    LayerGeometry(CanvasView canvas) {
        this.canvas = canvas;
    }

    private Animation animation() {
        return canvas.getDocument() == null ? null : canvas.getDocument().getAnimation();
    }

    private LayerState state(String id) {
        Frame frame = canvas.getAnimationView().getPlayer().getFrame();
        return frame == null ? null : frame.get(id);
    }

    public Optional<Layer> layer(String id) {
        Animation animation = animation();
        return animation == null || id == null ? Optional.empty() : Layers.find(animation, id);
    }

    /**
     * The layer's local-to-parent matrix at the current frame; a motion path is applied.
     */
    public double[] matrix(Layer layer) {
        LayerState state = state(layer.id());
        return state != null ? state.matrix() : layer.base().transform().matrixAt(canvas.getTime());
    }

    /**
     * The matrix from the coordinates in which the children of a group live to document
     * coordinates; the identity for {@code null}, the top level.
     */
    public double[] groupToDocument(String groupIdOrNull) {
        double[] matrix = Matrices.identity();
        Animation animation = animation();
        String current = groupIdOrNull;
        while (current != null && animation != null) {
            Optional<Layer> group = Layers.find(animation, current);
            if (group.isEmpty()) {
                break;
            }
            matrix = Matrices.multiply(matrix(group.get()), matrix);
            current = Layers.parentOf(animation, current).map(Layer::id).orElse(null);
        }
        return matrix;
    }

    public double[] groupToView(String groupIdOrNull) {
        return Matrices.multiply(canvas.documentToView(), groupToDocument(groupIdOrNull));
    }

    /**
     * The matrix from the layer's parent space, where its position lives, to view pixels.
     */
    public double[] parentToView(String layerId) {
        Animation animation = animation();
        String parent = animation == null ? null : Layers.parentOf(animation, layerId).map(Layer::id).orElse(null);
        return groupToView(parent);
    }

    public double[] localToView(Layer layer) {
        return Matrices.multiply(parentToView(layer.id()), matrix(layer));
    }

    /**
     * A view point in the coordinates of the children of a group ({@code null}: the document).
     */
    public Vec2 viewToGroup(String groupIdOrNull, Vec2 viewPoint) {
        double[] inverse = Matrices.invert(groupToView(groupIdOrNull));
        return inverse == null ? canvas.viewToDocument(viewPoint) : Matrices.apply(inverse, viewPoint);
    }

    /**
     * The bounds of the layer in its own coordinates: those of the rendered node, or, where the
     * node has none (a shape without fill and stroke, a slot, an empty group), those of the
     * sampled geometry or the reference size.
     */
    public Box localBox(Layer layer) {
        Node node = canvas.getAnimationView().getLayerNode(layer.id());
        if (node != null && !(layer instanceof SlotLayer)) {
            Bounds bounds = node.getBoundsInLocal();
            if (!bounds.isEmpty()) {
                return new Box(bounds.getMinX(), bounds.getMinY(), bounds.getMaxX(), bounds.getMaxY());
            }
        }
        return switch (layer) {
            case SlotLayer slot -> new Box(0, 0, slot.width(), slot.height());
            case ImageLayer image -> new Box(0, 0, image.width(), image.height());
            case VideoLayer video -> new Box(0, 0, video.width(), video.height());
            case ShapeLayer shape -> {
                LayerState state = state(shape.id());
                PathData path = state != null && state.fillPath() != null ? state.fillPath()
                        : com.dlsc.animatorfx.model.Geometries.toPath(shape.geometry(), canvas.getTime());
                yield pathBox(path);
            }
            case GroupLayer group -> {
                Box box = null;
                for (Layer child : group.children()) {
                    Box childBox = Quad.of(localBox(child), matrix(child)).bounds();
                    box = box == null ? childBox : box.union(childBox);
                }
                yield box == null ? new Box(0, 0, 0, 0) : box;
            }
            default -> new Box(0, 0, 0, 0);
        };
    }

    private static Box pathBox(PathData path) {
        List<Vec2> points = new ArrayList<>();
        for (PathData.Contour contour : path.contours()) {
            for (PathData.Vertex vertex : contour.vertices()) {
                points.add(vertex.point());
                points.add(vertex.point().plus(vertex.in()));
                points.add(vertex.point().plus(vertex.out()));
            }
        }
        Box box = Box.around(points);
        return box == null ? new Box(0, 0, 0, 0) : box;
    }

    /**
     * Whether the layer is drawn at the current frame: switched on, inside its time range, and
     * the same for every group around it.
     */
    public boolean isShown(Layer layer) {
        Animation animation = animation();
        Layer current = layer;
        while (current != null) {
            LayerState state = state(current.id());
            if (!current.base().visible() || (state != null && !state.visible())) {
                return false;
            }
            current = Layers.parentOf(animation, current.id()).orElse(null);
        }
        return true;
    }

    /**
     * The layer's bounds in view pixels as the four corners of its (rotated) box; empty for an
     * unknown layer, for one that is not drawn at the current frame, and for an audio layer,
     * which is never drawn.
     */
    public Optional<Quad> quad(String layerId) {
        return layer(layerId).filter(l -> !(l instanceof AudioLayer)).filter(this::isShown)
                .map(l -> Quad.of(localBox(l), localToView(l)));
    }

    /** The axis-aligned box around {@link #quad} in view pixels. */
    public Optional<Box> viewBounds(String layerId) {
        return quad(layerId).map(Quad::bounds);
    }

    /**
     * The layers the pointer works on: the children of the group that has been entered, or the
     * top level; bottom to top.
     */
    public List<Layer> scope() {
        Animation animation = animation();
        if (animation == null) {
            return List.of();
        }
        if (layer(canvas.getEnteredGroupId()).orElse(null) instanceof GroupLayer group) {
            return group.children();
        }
        return animation.layers();
    }

    /**
     * The top-most layer of the {@link #scope} at the view point. A group is hit through its
     * children and is what gets returned. Locked and hidden layers, layers outside their time
     * range and layers used as a mask are never hit.
     */
    public Optional<String> layerAt(Vec2 viewPoint) {
        List<Layer> scope = scope();
        for (int i = scope.size() - 1; i >= 0; i--) {
            Layer layer = scope.get(i);
            if (hits(layer, viewPoint, groupToView(canvas.getEnteredGroupId()))) {
                return Optional.of(layer.id());
            }
        }
        return Optional.empty();
    }

    /**
     * Whether the layer can be picked on the canvas at the current frame. An audio layer never can.
     */
    public boolean isSelectable(Layer layer) {
        LayerState state = state(layer.id());
        return !(layer instanceof AudioLayer) && layer.base().visible() && !layer.base().locked() && (state == null || state.visible())
                && !maskIds().contains(layer.id());
    }

    private boolean hits(Layer layer, Vec2 viewPoint, double[] parentToView) {
        if (!isSelectable(layer)) {
            return false;
        }
        double[] toView = Matrices.multiply(parentToView, matrix(layer));
        if (Matrices.invert(toView) == null) {
            // scaled to nothing at this frame: there is nothing to hit, here or among the children
            return false;
        }
        if (layer instanceof GroupLayer group) {
            for (int i = group.children().size() - 1; i >= 0; i--) {
                if (hits(group.children().get(i), viewPoint, toView)) {
                    return true;
                }
            }
            return false;
        }
        if (layer instanceof ShapeLayer shape && isPainted(shape)) {
            return hitsShape(canvas.getAnimationView().getLayerNode(shape.id()), viewPoint, toView);
        }
        return Quad.of(localBox(layer), toView).contains(viewPoint);
    }

    private boolean isPainted(ShapeLayer shape) {
        LayerState state = state(shape.id());
        if (state == null) {
            return false;
        }
        boolean filled = state.fill() != null && !(state.fill() instanceof Paint.None);
        boolean stroked = state.strokePaint() != null && !(state.strokePaint() instanceof Paint.None)
                && state.strokeWidth() > 0;
        return filled || stroked;
    }

    /** Precise test against the rendered outlines, at the point and in a small ring around it. */
    private static boolean hitsShape(Node node, Vec2 viewPoint, double[] toView) {
        double[] inverse = Matrices.invert(toView);
        if (node == null || inverse == null) {
            return false;
        }
        List<Shape> shapes = new ArrayList<>();
        collectShapes(node, shapes);
        for (double[] probe : PROBES) {
            Vec2 local = Matrices.apply(inverse, new Vec2(viewPoint.x() + probe[0] * HIT_TOLERANCE,
                    viewPoint.y() + probe[1] * HIT_TOLERANCE));
            for (Shape shape : shapes) {
                if (shape.contains(local.x(), local.y())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void collectShapes(Node node, List<Shape> out) {
        if (node instanceof Shape shape) {
            if (shape.isVisible()) {
                out.add(shape);
            }
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectShapes(child, out);
            }
        }
    }

    /** Whether another layer uses this one as its mask, so that it is not drawn itself. */
    public boolean isMask(String layerId) {
        return maskIds().contains(layerId);
    }

    private Set<String> maskIds() {
        Animation animation = animation();
        if (animation != maskSource) {
            Set<String> ids = new HashSet<>();
            if (animation != null) {
                for (Layer layer : Layers.flatten(animation)) {
                    Mask mask = layer.base().mask();
                    if (mask != null) {
                        ids.add(mask.layerId());
                    }
                }
            }
            maskIds = ids;
            maskSource = animation;
        }
        return maskIds;
    }

    /**
     * What a moving layer snaps to, in view pixels: the artboard and the other layers of the
     * scope that are drawn.
     *
     * @param excludedIds the layers that move
     */
    public List<Box> snapTargets(Collection<String> excludedIds) {
        List<Box> targets = new ArrayList<>();
        Animation animation = animation();
        if (animation == null) {
            return targets;
        }
        targets.add(Quad.of(new Box(0, 0, animation.width(), animation.height()), canvas.documentToView()).bounds());
        for (Layer layer : scope()) {
            if (!excludedIds.contains(layer.id()) && !maskIds().contains(layer.id())) {
                viewBounds(layer.id()).ifPresent(targets::add);
            }
        }
        return targets;
    }
}
