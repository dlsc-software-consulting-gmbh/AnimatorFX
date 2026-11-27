package com.dlsc.animatorfx.model.io;

import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.Easing;
import com.dlsc.animatorfx.model.Effect;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.ImageLayer;
import com.dlsc.animatorfx.model.Keyframe;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.Marker;
import com.dlsc.animatorfx.model.Mask;
import com.dlsc.animatorfx.model.MotionPath;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.PathData;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.SlotLayer;
import com.dlsc.animatorfx.model.SoundCue;
import com.dlsc.animatorfx.model.StateMachine;
import com.dlsc.animatorfx.model.Stroke;
import com.dlsc.animatorfx.model.TextLayer;
import com.dlsc.animatorfx.model.Transform;
import com.dlsc.animatorfx.model.Trim;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.VideoLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds a document from its JSON tree (current format) and validates what the model does not.
 * One instance reads one document.
 */
final class AnimationJsonReader {

    @FunctionalInterface
    private interface ValueReader<T> {
        T read(Node node) throws AnimationFormatException;
    }

    private final Map<String, Asset.Kind> assetKinds = new HashMap<>();
    private final Set<String> layerIds = new HashSet<>();
    private final Set<String> slotNames = new HashSet<>();
    private final Set<String> exportNames = new HashSet<>();
    private double duration;

    Animation document(Node root) throws AnimationFormatException {
        String name = root.get("name").string(Defaults.DOCUMENT_NAME);
        double width = root.required("width").number();
        double height = root.required("height").number();
        double frameRate = root.get("frameRate").number(Defaults.FRAME_RATE);
        duration = root.required("duration").number();
        Rgba background = root.get("background").isMissing() ? Defaults.BACKGROUND : color(root.get("background"));
        List<Marker> markers = markers(root.elements("markers"));
        List<Asset> assets = assets(root.elements("assets"));
        List<Layer> layers = layers(root.elements("layers"), 1);
        List<SoundCue> cues = cues(root.elements("cues"));
        List<StateMachine> stateMachines = stateMachines(root.elements("stateMachines"));
        return new Animation(Animation.CURRENT_FORMAT, name, width, height, frameRate, duration, background, markers,
                assets, layers, cues, stateMachines);
    }

    private List<StateMachine> stateMachines(List<Node> nodes) throws AnimationFormatException {
        List<StateMachine> machines = new ArrayList<>();
        for (Node node : nodes) {
            try {
                List<StateMachine.State> states = new ArrayList<>();
                for (Node state : node.elements("states")) {
                    states.add(new StateMachine.State(state.required("name").string(),
                            state.required("marker").string(), state.get("loop").bool(false),
                            state.get("x").number(0), state.get("y").number(0)));
                }
                List<StateMachine.Transition> transitions = new ArrayList<>();
                for (Node transition : node.elements("transitions")) {
                    transitions.add(new StateMachine.Transition(transition.required("source").string(),
                            transition.required("target").string(), transition.get("event").string(null)));
                }
                machines.add(new StateMachine(node.required("name").string(),
                        node.required("initialState").string(), states, transitions));
            } catch (IllegalArgumentException e) {
                throw node.error(e.getMessage(), e);
            }
        }
        return machines;
    }

    private List<SoundCue> cues(List<Node> nodes) throws AnimationFormatException {
        Set<String> ids = new HashSet<>();
        List<SoundCue> cues = new ArrayList<>();
        for (Node node : nodes) {
            Node idNode = node.get("id");
            String id = idNode.isMissing() ? UUID.randomUUID().toString() : idNode.string();
            if (!ids.add(id)) {
                throw idNode.error("Duplicate cue id: " + id);
            }
            Node volume = node.get("volume");
            cues.add(new SoundCue(id,
                    node.get("name").string(""),
                    notNegative(node.required("t")),
                    assetId(node.required("asset"), Asset.Kind.AUDIO),
                    volume.isMissing() ? Defaults.CUE_VOLUME : within(volume, 0, 1),
                    node.get("muted").bool(false)));
        }
        return cues;
    }

    private List<Marker> markers(List<Node> nodes) throws AnimationFormatException {
        Set<String> names = new HashSet<>();
        List<Marker> markers = new ArrayList<>();
        for (Node node : nodes) {
            Node name = node.required("name");
            if (!names.add(name.string())) {
                throw name.error("Duplicate marker name: " + name.string());
            }
            markers.add(new Marker(name.string(), node.required("start").number(), node.required("end").number()));
        }
        return markers;
    }

    private List<Asset> assets(List<Node> nodes) throws AnimationFormatException {
        List<Asset> assets = new ArrayList<>();
        for (Node node : nodes) {
            Node id = node.required("id");
            Asset.Kind kind = constant(node.required("kind"), Asset.Kind.class);
            if (assetKinds.put(id.string(), kind) != null) {
                throw id.error("Duplicate asset id: " + id.string());
            }
            assets.add(new Asset(id.string(), kind, node.required("path").string(), node.get("name").string("")));
        }
        return assets;
    }

    /**
     * @param depth the nesting of the layers; 1 for the top-level layers
     */
    private List<Layer> layers(List<Node> nodes, int depth) throws AnimationFormatException {
        if (depth > AnimationJson.MAX_LAYER_DEPTH && !nodes.isEmpty()) {
            throw nodes.get(0).error("Layers are nested deeper than " + AnimationJson.MAX_LAYER_DEPTH + " levels");
        }
        List<Layer> layers = new ArrayList<>();
        Map<String, Layer> byId = new HashMap<>();
        for (Node node : nodes) {
            Layer layer = layer(node, depth);
            layers.add(layer);
            byId.put(layer.id(), layer);
        }
        // A mask is another layer of the same parent, which may come later in the list.
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            Mask mask = layer.base().mask();
            if (mask == null) {
                continue;
            }
            Node reference = nodes.get(i).get("mask").get("layer");
            if (mask.layerId().equals(layer.id())) {
                throw reference.error("A layer cannot mask itself");
            }
            Layer maskLayer = byId.get(mask.layerId());
            if (maskLayer == null) {
                throw reference.error("No layer with id " + mask.layerId() + " in the same parent");
            }
            if (maskLayer instanceof AudioLayer) {
                throw reference.error("An audio layer cannot be used as a mask: " + mask.layerId());
            }
        }
        return layers;
    }

    private Layer layer(Node node, int depth) throws AnimationFormatException {
        Node type = node.required("type");
        LayerBase base = base(node);
        return switch (type.string()) {
            case "shape" -> new ShapeLayer(base, geometry(node.required("geometry")), fill(node.get("fill")),
                    optional(node.get("stroke"), this::stroke), optional(node.get("trim"), this::trim));
            case "text" -> text(node, base);
            case "image" -> new ImageLayer(base, assetId(node.required("asset"), Asset.Kind.IMAGE),
                    node.required("width").number(), node.required("height").number());
            case "group" -> new GroupLayer(base, layers(node.elements("children"), depth + 1));
            case "slot" -> slot(node, base);
            case "audio" -> audio(node, base);
            case "video" -> video(node, base);
            default -> throw type.error("Unknown layer type: " + type.string());
        };
    }

    private LayerBase base(Node node) throws AnimationFormatException {
        Node idNode = node.get("id");
        String id = idNode.isMissing() ? UUID.randomUUID().toString() : idNode.string();
        if (!layerIds.add(id)) {
            throw idNode.error("Duplicate layer id: " + id);
        }
        Node exportNode = node.get("exportName");
        String exportName = exportNode.string("").strip();
        if (!exportName.isEmpty() && !exportNames.add(exportName)) {
            throw exportNode.error("Duplicate export name: " + exportName);
        }
        return new LayerBase(id,
                node.get("name").string(""),
                node.get("visible").bool(true),
                node.get("locked").bool(false),
                node.get("inPoint").number(0),
                node.get("outPoint").number(duration),
                transform(node.get("transform")),
                number(node.get("opacity"), Defaults.OPACITY),
                optional(node.get("mask"), this::mask),
                effects(node.elements("effects")),
                optional(node.get("motionPath"), this::motionPath), exportName);
    }

    private TextLayer text(Node node, LayerBase base) throws AnimationFormatException {
        Node fontAsset = node.get("fontAsset");
        return new TextLayer(base,
                node.required("text").string(),
                node.get("fontFamily").string(Defaults.FONT_FAMILY),
                fontAsset.isMissing() ? null : assetId(fontAsset, Asset.Kind.FONT),
                number(node.get("fontSize"), Defaults.FONT_SIZE),
                constant(node.get("fontWeight"), Defaults.FONT_WEIGHT),
                node.get("italic").bool(false),
                constant(node.get("alignment"), Defaults.ALIGNMENT),
                fill(node.get("fill")),
                optional(node.get("stroke"), this::stroke));
    }

    private SlotLayer slot(Node node, LayerBase base) throws AnimationFormatException {
        Node name = node.required("slotName");
        if (!slotNames.add(name.string())) {
            throw name.error("Duplicate slot name: " + name.string());
        }
        Node rest = node.get("restTransform");
        return new SlotLayer(base, name.string(), node.required("width").number(), node.required("height").number(),
                rest.isMissing() ? base.transform() : transform(rest));
    }

    private AudioLayer audio(Node node, LayerBase base) throws AnimationFormatException {
        Node clipOffset = node.get("clipOffset");
        Node volume = node.get("volume");
        Node balance = node.get("balance");
        return new AudioLayer(base,
                assetId(node.required("asset"), Asset.Kind.AUDIO),
                clipOffset.isMissing() ? 0 : notNegative(clipOffset),
                volume.isMissing() ? Defaults.VOLUME : animatable(volume, v -> within(v, 0, 1)),
                balance.isMissing() ? Defaults.ZERO : animatable(balance, v -> within(v, -1, 1)),
                node.get("loop").bool(false),
                node.get("muted").bool(false));
    }

    private VideoLayer video(Node node, LayerBase base) throws AnimationFormatException {
        Node clipOffset = node.get("clipOffset");
        Node volume = node.get("volume");
        return new VideoLayer(base, assetId(node.required("asset"), Asset.Kind.VIDEO),
                positive(node.required("width")), positive(node.required("height")),
                clipOffset.isMissing() ? 0 : notNegative(clipOffset),
                volume.isMissing() ? Defaults.VOLUME : animatable(volume, v -> within(v, 0, 1)),
                node.get("loop").bool(false), node.get("muted").bool(false));
    }

    private static double positive(Node node) throws AnimationFormatException {
        double value = node.number();
        if (value <= 0) {
            throw node.error("Must be positive but is " + value);
        }
        return value;
    }

    private static double notNegative(Node node) throws AnimationFormatException {
        double value = node.number();
        if (value < 0) {
            throw node.error("Must not be negative but is " + value);
        }
        return value;
    }

    private static double within(Node node, double min, double max) throws AnimationFormatException {
        double value = node.number();
        if (value < min || value > max) {
            throw node.error("Must be within " + min + ".." + max + " but is " + value);
        }
        return value;
    }

    private String assetId(Node node, Asset.Kind expected) throws AnimationFormatException {
        Asset.Kind kind = assetKinds.get(node.string());
        if (kind == null) {
            throw node.error("No asset with id " + node.string());
        }
        if (kind != expected) {
            throw node.error("Asset " + node.string() + " is not " + switch (expected) {
                case IMAGE -> "an image";
                case FONT -> "a font";
                case AUDIO -> "an audio file";
                case VIDEO -> "a video file";
            });
        }
        return node.string();
    }

    private Transform transform(Node node) throws AnimationFormatException {
        if (node.isMissing()) {
            return Transform.IDENTITY;
        }
        Transform identity = Transform.IDENTITY;
        return new Transform(
                vec2(node.get("anchor"), identity.anchor()),
                vec2(node.get("position"), identity.position()),
                vec2(node.get("scale"), identity.scale()),
                number(node.get("rotation"), identity.rotation()),
                vec2(node.get("skew"), identity.skew()));
    }

    private Mask mask(Node node) throws AnimationFormatException {
        return new Mask(node.required("layer").string(), constant(node.required("mode"), Mask.Mode.class));
    }

    private List<Effect> effects(List<Node> nodes) throws AnimationFormatException {
        List<Effect> effects = new ArrayList<>();
        for (Node node : nodes) {
            Node type = node.required("type");
            effects.add(switch (type.string()) {
                case "blur" -> new Effect.Blur(number(node.required("radius")));
                case "dropShadow" -> new Effect.DropShadow(
                        node.get("color").isMissing() ? Defaults.SHADOW_COLOR : animatable(node.get("color"), this::color),
                        number(node.required("radius")),
                        number(node.get("offsetX"), Defaults.ZERO),
                        number(node.get("offsetY"), Defaults.ZERO),
                        number(node.get("spread"), Defaults.ZERO));
                case "glow" -> new Effect.Glow(number(node.required("level")));
                default -> throw type.error("Unknown effect type: " + type.string());
            });
        }
        return effects;
    }

    private MotionPath motionPath(Node node) throws AnimationFormatException {
        return new MotionPath(path(node.required("path")), number(node.required("progress")),
                node.get("orientToPath").bool(false));
    }

    private Geometry geometry(Node node) throws AnimationFormatException {
        Node type = node.required("type");
        return switch (type.string()) {
            case "rect" -> new Geometry.Rect(vec2(node.required("size")),
                    number(node.get("cornerRadius"), Defaults.ZERO));
            case "ellipse" -> new Geometry.Ellipse(vec2(node.required("size")));
            case "line" -> new Geometry.Line(vec2(node.required("start")), vec2(node.required("end")));
            case "polygon" -> new Geometry.Polygon(atLeast(node.required("sides"), 3),
                    number(node.required("radius")));
            case "star" -> new Geometry.Star(atLeast(node.required("points"), 2),
                    number(node.required("outerRadius")), number(node.required("innerRadius")));
            case "path" -> new Geometry.PathGeometry(animatable(node.required("path"), this::path));
            default -> throw type.error("Unknown geometry type: " + type.string());
        };
    }

    private static int atLeast(Node node, int minimum) throws AnimationFormatException {
        int value = node.integer();
        if (value < minimum) {
            throw node.error("Must be at least " + minimum + " but is " + value);
        }
        return value;
    }

    private Animatable<Paint> fill(Node node) throws AnimationFormatException {
        return node.isMissing() ? Defaults.FILL : animatable(node, this::paint);
    }

    private Stroke stroke(Node node) throws AnimationFormatException {
        Node paint = node.get("paint");
        List<Double> dashes = new ArrayList<>();
        for (Node dash : node.elements("dashArray")) {
            dashes.add(dash.number());
        }
        return new Stroke(
                paint.isMissing() ? Defaults.STROKE_PAINT : animatable(paint, this::paint),
                number(node.get("width"), Defaults.STROKE_WIDTH),
                constant(node.get("cap"), Defaults.CAP),
                constant(node.get("join"), Defaults.JOIN),
                node.get("miterLimit").number(Defaults.MITER_LIMIT),
                dashes,
                number(node.get("dashOffset"), Defaults.ZERO));
    }

    private Trim trim(Node node) throws AnimationFormatException {
        return new Trim(number(node.get("start"), Defaults.ZERO), number(node.get("end"), Defaults.TRIM_END),
                number(node.get("offset"), Defaults.ZERO));
    }

    private Paint paint(Node node) throws AnimationFormatException {
        Node type = node.required("type");
        return switch (type.string()) {
            case "none" -> new Paint.None();
            case "solid" -> new Paint.Solid(color(node.required("color")));
            case "linear" -> new Paint.LinearGradient(vec2Value(node.required("start")),
                    vec2Value(node.required("end")), stops(node.required("stops")));
            case "radial" -> new Paint.RadialGradient(vec2Value(node.required("center")),
                    node.required("radius").number(), stops(node.required("stops")));
            default -> throw type.error("Unknown paint type: " + type.string());
        };
    }

    private List<Paint.Stop> stops(Node node) throws AnimationFormatException {
        List<Paint.Stop> stops = new ArrayList<>();
        for (Node stop : node.elements()) {
            stops.add(new Paint.Stop(stop.required("offset").number(), color(stop.required("color"))));
        }
        return stops;
    }

    private PathData path(Node node) throws AnimationFormatException {
        List<PathData.Contour> contours = new ArrayList<>();
        for (Node contour : node.required("contours").elements()) {
            List<PathData.Vertex> vertices = new ArrayList<>();
            for (Node vertex : contour.elements("v")) {
                List<Node> n = vertex.elements();
                if (n.size() == 2) {
                    vertices.add(new PathData.Vertex(new Vec2(n.get(0).number(), n.get(1).number()), Vec2.ZERO,
                            Vec2.ZERO));
                } else if (n.size() == 6) {
                    vertices.add(new PathData.Vertex(new Vec2(n.get(0).number(), n.get(1).number()),
                            new Vec2(n.get(2).number(), n.get(3).number()),
                            new Vec2(n.get(4).number(), n.get(5).number())));
                } else {
                    throw vertex.error("Expected [x, y] or [x, y, inX, inY, outX, outY] but found " + n.size()
                            + " elements");
                }
            }
            contours.add(new PathData.Contour(vertices, contour.get("closed").bool(false)));
        }
        return new PathData(contours);
    }

    private Rgba color(Node node) throws AnimationFormatException {
        String hex = node.string();
        try {
            return Rgba.parse(hex);
        } catch (IllegalArgumentException e) {
            throw node.error("Expected a colour as #RRGGBB or #RRGGBBAA but found: " + hex, e);
        }
    }

    private Vec2 vec2Value(Node node) throws AnimationFormatException {
        List<Node> n = node.elements();
        if (n.size() != 2) {
            throw node.error("Expected [x, y] but found " + n.size() + " elements");
        }
        return new Vec2(n.get(0).number(), n.get(1).number());
    }

    private Animatable<Double> number(Node node) throws AnimationFormatException {
        return animatable(node, Node::number);
    }

    private Animatable<Double> number(Node node, Animatable<Double> fallback) throws AnimationFormatException {
        return node.isMissing() ? fallback : number(node);
    }

    private Animatable<Vec2> vec2(Node node) throws AnimationFormatException {
        return animatable(node, this::vec2Value);
    }

    private Animatable<Vec2> vec2(Node node, Animatable<Vec2> fallback) throws AnimationFormatException {
        return node.isMissing() ? fallback : vec2(node);
    }

    /** A plain value is static; an object with a {@code keyframes} member is animated. */
    private <T> Animatable<T> animatable(Node node, ValueReader<T> valueReader) throws AnimationFormatException {
        if (!node.has("keyframes")) {
            return Animatable.of(valueReader.read(node));
        }
        Node list = node.required("keyframes");
        List<Node> nodes = list.elements();
        if (nodes.isEmpty()) {
            throw list.error("An animated value needs at least one keyframe");
        }
        Set<String> ids = new HashSet<>();
        List<Keyframe<T>> keyframes = new ArrayList<>();
        for (Node keyframe : nodes) {
            Node idNode = keyframe.get("id");
            String id = idNode.isMissing() ? UUID.randomUUID().toString() : idNode.string();
            if (!ids.add(id)) {
                throw idNode.error("Duplicate keyframe id: " + id);
            }
            Node time = keyframe.required("t");
            if (time.number() < 0) {
                throw time.error("A keyframe time must not be negative but is " + time.number());
            }
            keyframes.add(new Keyframe<>(id, time.number(), valueReader.read(keyframe.required("v")),
                    easing(keyframe.get("e"))));
        }
        return Animatable.keyframed(keyframes);
    }

    private Easing easing(Node node) throws AnimationFormatException {
        if (node.isMissing()) {
            return Easing.LINEAR;
        }
        if (node.isString()) {
            return switch (node.string()) {
                case "linear" -> Easing.LINEAR;
                case "hold" -> Easing.HOLD;
                default -> throw node.error("Expected \"linear\", \"hold\" or [x1, y1, x2, y2] but found: "
                        + node.string());
            };
        }
        List<Node> n;
        try {
            n = node.elements();
        } catch (AnimationFormatException e) {
            throw node.error("Expected \"linear\", \"hold\" or [x1, y1, x2, y2]", e);
        }
        if (n.size() != 4) {
            throw node.error("Expected [x1, y1, x2, y2] but found " + n.size() + " elements");
        }
        return new Easing.CubicBezier(unit(n.get(0)), n.get(1).number(), unit(n.get(2)), n.get(3).number());
    }

    private static double unit(Node node) throws AnimationFormatException {
        double value = node.number();
        if (value < 0 || value > 1) {
            throw node.error("The x of an easing control point must be within 0..1 but is " + value);
        }
        return value;
    }

    private static <T> T optional(Node node, ValueReader<T> reader) throws AnimationFormatException {
        return node.isMissing() ? null : reader.read(node);
    }

    private static <E extends Enum<E>> E constant(Node node, E fallback) throws AnimationFormatException {
        return node.isMissing() ? fallback : constant(node, fallback.getDeclaringClass());
    }

    /** Enum constants are written in lower case. */
    private static <E extends Enum<E>> E constant(Node node, Class<E> type) throws AnimationFormatException {
        String name = node.string();
        List<String> allowed = new ArrayList<>();
        for (E constant : type.getEnumConstants()) {
            String lower = constant.name().toLowerCase(Locale.ROOT);
            if (lower.equals(name)) {
                return constant;
            }
            allowed.add(lower);
        }
        throw node.error("Expected one of " + String.join(", ", allowed) + " but found: " + name);
    }
}
