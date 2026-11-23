package com.dlsc.animatorfx.importer;

import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.VideoLayer;
import com.dlsc.animatorfx.model.Easing;
import com.dlsc.animatorfx.model.Effect;
import com.dlsc.animatorfx.model.Geometries;
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
import com.dlsc.animatorfx.model.PathMeasure;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.SlotLayer;
import com.dlsc.animatorfx.model.SoundCue;
import com.dlsc.animatorfx.model.Stroke;
import com.dlsc.animatorfx.model.TextLayer;
import com.dlsc.animatorfx.model.Transform;
import com.dlsc.animatorfx.model.Trim;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.io.AnimationFile;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds the Lottie JSON of a document, as the maps and lists {@code JsonWriter} writes. It is
 * the reverse of {@link LottieLayers}, {@link LottieShapes} and their helpers, and keeps their
 * conventions: where they divide by a factor, this multiplies by it.
 * <p>
 * How the layer tree is laid out in Lottie, which has no groups of layers:
 * <ul>
 *   <li>A group becomes a null layer that carries its transform, and its children are parented
 *       to it. Opacity, effects, in and out point and visibility of a group do not travel along
 *       a Lottie parent, so they are handed down to the layers inside.</li>
 *   <li>The model lists layers bottom-most first, Lottie top-most first.</li>
 *   <li>A mask becomes a track matte. The matte layer is written right above every layer it
 *       masks and is named by {@code tp} as well, so that both players that go by position and
 *       players that go by index find it. A group that serves as a mask has to be one layer for
 *       that, and becomes a precomposition.</li>
 * </ul>
 */
final class LottieDocumentWriter {

    private static final Animatable<Double> ONE = Animatable.of(1.0);
    private static final double THIRD = 1.0 / 3;

    /**
     * What the groups around hand down to a layer.
     *
     * @param parent  the index of the null layer of the innermost group with a transform, or {@code null}
     * @param matte   the mask of the layer or of a group around, or {@code null}
     * @param chain   the transforms of all groups around, outermost first
     * @param asMatte whether the layer is written as the matte of another one
     */
    private record Context(Integer parent, Animatable<Double> opacity, double in, double out, boolean visible,
                           List<Effect> effects, Matte matte, List<Map<String, Object>> chain, boolean asMatte) {

        Context withMatte(Matte matte) {
            return new Context(parent, opacity, in, out, visible, effects, matte, chain, asMatte);
        }

        /** For the mask layer, a sibling: its alpha alone counts. */
        Context forMatte() {
            return new Context(parent, ONE, in, out, true, List.of(), null, chain, true);
        }
    }

    /** A mask layer and where it lives. */
    private record Matte(Layer source, Context context, Mask.Mode mode) {
    }

    private record Motion(Animatable<Vec2> position, Animatable<Double> rotation) {
    }

    private final AnimationFile file;
    private final Animation animation;
    private final Warnings warnings;
    private final double frameRate;
    private final double duration;
    private final double endFrame;
    private final Map<String, Asset> assetsById = new HashMap<>();
    private final List<Map<String, Object>> assets = new ArrayList<>();
    private final Map<String, String> imageIds = new HashMap<>();
    private final Map<String, String> soundIds = new HashMap<>();
    private final Map<String, String> compositionIds = new HashMap<>();
    private final Map<String, Map<String, Object>> fonts = new LinkedHashMap<>();
    private int nextIndex = 1;

    LottieDocumentWriter(AnimationFile file, Warnings warnings) {
        this.file = file;
        this.animation = file.animation();
        this.warnings = warnings;
        this.frameRate = animation.frameRate() > 0 ? animation.frameRate() : 30;
        this.duration = Math.max(0, animation.duration());
        this.endFrame = Math.max(1, number(duration * frameRate));
        for (Asset asset : animation.assets()) {
            assetsById.put(asset.id(), asset);
        }
    }

    /** The background colour as {@code #rrggbb}, or {@code null} if the document has none. */
    String background() {
        Rgba color = animation.background();
        if (color == null || color.a() <= 0) {
            return null;
        }
        return String.format(Locale.ROOT, "#%02x%02x%02x", channel(color.r()), channel(color.g()),
                channel(color.b()));
    }

    private static int channel(double value) {
        return (int) Math.round(Math.clamp(value, 0.0, 1.0) * 255);
    }

    Map<String, Object> document() {
        List<Map<String, Object>> layers = new ArrayList<>();
        Context root = new Context(null, ONE, 0, duration, true, List.of(), null, List.of(), false);
        children(animation.layers(), root, layers);
        for (SoundCue cue : animation.cues()) {
            audio(AudioLayer.create(cue.name(), cue.assetId(), Math.clamp(cue.time(), 0.0, duration), duration)
                    .withVolume(Animatable.of(cue.volume())).withMuted(cue.muted()), root, layers);
        }
        Collections.reverse(layers);

        Map<String, Object> document = object("v", "5.7.0", "nm", animation.name(), "ddd", 0,
                "fr", number(frameRate), "ip", 0, "op", endFrame,
                "w", (int) Math.max(1, Math.round(animation.width())),
                "h", (int) Math.max(1, Math.round(animation.height())), "assets", assets);
        if (!fonts.isEmpty()) {
            document.put("fonts", object("list", new ArrayList<>(fonts.values())));
        }
        document.put("layers", layers);
        List<Object> markers = new ArrayList<>();
        for (Marker marker : animation.markers()) {
            markers.add(object("tm", frame(marker.start()), "cm", marker.name(),
                    "dr", frame(Math.max(0, marker.end() - marker.start()))));
        }
        if (!markers.isEmpty()) {
            document.put("markers", markers);
        }
        return document;
    }

    // ------------------------------------------------------------------ the layer tree

    /** The layers of one list, bottom-most first as the model has them. */
    private void children(List<Layer> layers, Context context, List<Map<String, Object>> out) {
        Map<String, Layer> byId = new HashMap<>();
        for (Layer layer : layers) {
            byId.put(layer.id(), layer);
        }
        Set<String> maskIds = new HashSet<>();
        for (Layer layer : layers) {
            Mask mask = layer.base().mask();
            if (mask != null && byId.containsKey(mask.layerId()) && !mask.layerId().equals(layer.id())) {
                maskIds.add(mask.layerId());
            }
        }
        for (Layer layer : layers) {
            if (maskIds.contains(layer.id())) {
                // not drawn itself: written with every layer it masks
                continue;
            }
            Mask mask = layer.base().mask();
            Layer source = mask == null ? null : byId.get(mask.layerId());
            Context own = context;
            if (source != null && source != layer) {
                if (context.matte() != null) {
                    warnings.add("A masked layer inside a masked group keeps its own mask only", "layer", "layers");
                }
                own = context.withMatte(new Matte(source, context.forMatte(), mask.mode()));
            }
            write(layer, own, out);
        }
    }

    private void write(Layer layer, Context context, List<Map<String, Object>> out) {
        switch (layer) {
            case GroupLayer group -> group(group, context, out);
            case ShapeLayer shape -> visual(shape, shape.base().transform(), 4, context, out)
                    .put("shapes", shapes(shape));
            case TextLayer text -> text(text, context, out);
            case ImageLayer image -> image(image, context, out);
            case VideoLayer video -> warnings.add("Video layer '" + video.name() + "' and its sound were omitted");
            case AudioLayer audio -> {
                if (!context.asMatte()) {
                    audio(audio, context, out);
                }
            }
            case SlotLayer slot -> warnings.add("Slots for JavaFX nodes have no counterpart in Lottie",
                    "layer skipped", "layers skipped");
        }
    }

    private void group(GroupLayer group, Context context, List<Map<String, Object>> out) {
        LayerBase base = group.base();
        Integer parent = context.parent();
        List<Map<String, Object>> chain = context.chain();
        if (!base.transform().equals(Transform.IDENTITY) || base.motionPath() != null) {
            Map<String, Object> ks = transform(base.transform(), ONE, base.motionPath());
            Map<String, Object> carrier = nullLayer(base.name(), parent, ks);
            out.add(carrier);
            parent = (Integer) carrier.get("ind");
            chain = new ArrayList<>(chain);
            chain.add(ks);
        }
        boolean several = leaves(group) > 1;
        if (several && !ONE.equals(base.opacity())) {
            warnings.add("The opacity of a group is applied to each of its layers; where they overlap, the"
                    + " lower one shows through", "group", "groups");
        }
        if (several && !base.effects().isEmpty()) {
            warnings.add("The effects of a group are applied to each of its layers", "group", "groups");
        }
        children(group.children(), new Context(parent, multiply(context.opacity(), base.opacity()),
                Math.max(context.in(), base.inPoint()), Math.min(context.out(), base.outPoint()),
                context.visible() && base.visible(), concat(context.effects(), base.effects()), context.matte(),
                chain, context.asMatte()), out);
    }

    private static int leaves(Layer layer) {
        if (!(layer instanceof GroupLayer group)) {
            return 1;
        }
        int count = 0;
        for (Layer child : group.children()) {
            count += leaves(child);
        }
        return count;
    }

    /**
     * A layer that is drawn: what all kinds have in common. The caller adds the content.
     *
     * @param transform the transform of the layer, which for text differs from the one of its base
     */
    private Map<String, Object> visual(Layer layer, Transform transform, int type, Context context,
                                       List<Map<String, Object>> out) {
        LayerBase base = layer.base();
        Map<String, Object> map = layer(type, base.name(), context, base.inPoint(), base.outPoint(),
                base.visible() || context.asMatte());
        map.put("ks", transform(transform, multiply(context.opacity(), base.opacity()), base.motionPath()));
        List<Effect> effects = concat(context.effects(), base.effects());
        if (!effects.isEmpty()) {
            List<Object> written = effects(effects);
            if (!written.isEmpty()) {
                map.put("ef", written);
            }
        }
        out.add(map);
        if (context.asMatte()) {
            map.put("td", 1);
        } else if (context.matte() != null) {
            Integer matte = matte(context.matte(), out);
            if (matte != null) {
                map.put("tt", 1);
                map.put("tp", matte);
            }
        }
        return map;
    }

    private Map<String, Object> layer(int type, String name, Context context, double in, double out,
                                      boolean visible) {
        Map<String, Object> map = object("ddd", 0, "ind", nextIndex++, "ty", type, "nm", name, "sr", 1, "ao", 0,
                "ip", frame(Math.max(context.in(), in)), "op", outFrame(Math.min(context.out(), out)), "st", 0,
                "bm", 0);
        if (context.parent() != null) {
            map.put("parent", context.parent());
        }
        if (!(context.visible() && visible)) {
            map.put("hd", true);
        }
        return map;
    }

    private Map<String, Object> nullLayer(String name, Integer parent, Map<String, Object> ks) {
        Map<String, Object> map = object("ddd", 0, "ind", nextIndex++, "ty", 3, "nm", name, "sr", 1, "ks", ks,
                "ao", 0, "ip", 0, "op", endFrame, "st", 0, "bm", 0);
        if (parent != null) {
            map.put("parent", parent);
        }
        return map;
    }

    /**
     * Writes the mask layer above the layer that was written last.
     *
     * @return the index of the matte layer, or {@code null} if the mask layer has nothing to show
     */
    private Integer matte(Matte matte, List<Map<String, Object>> out) {
        Layer source = matte.source();
        if (source.base().mask() != null) {
            warnings.add("The mask of a layer that is a mask itself is not supported", "mask ignored",
                    "masks ignored");
        }
        boolean clip = matte.mode() == Mask.Mode.CLIP;
        if (clip) {
            source = geometryOnly(source);
        }
        if (source instanceof GroupLayer group) {
            return precomposition(group, clip, matte.context(), out);
        }
        int before = out.size();
        write(source, matte.context(), out);
        return out.size() > before ? (Integer) out.get(out.size() - 1).get("ind") : null;
    }

    /**
     * A layer as a {@code CLIP} mask uses it: the outline alone clips, whatever it is painted
     * with. Shapes and text are filled and opaque, without stroke; nothing has an opacity or an
     * effect, all the way down a group.
     */
    private static Layer geometryOnly(Layer layer) {
        LayerBase base = layer.base().withOpacity(ONE).withEffects(List.of());
        Animatable<Paint> opaque = Animatable.of(new Paint.Solid(Rgba.WHITE));
        return switch (layer) {
            case ShapeLayer shape -> shape.withFill(opaque).withStroke(null).withTrim(null).withBase(base);
            case TextLayer text -> text.withFill(opaque).withStroke(null).withBase(base);
            case GroupLayer group -> {
                List<Layer> children = new ArrayList<>();
                for (Layer child : group.children()) {
                    children.add(geometryOnly(child));
                }
                yield group.withChildren(children).withBase(base);
            }
            default -> layer.withBase(base);
        };
    }

    /**
     * A group as one layer. The precomposition is as large as the animation and lies in its
     * coordinates, with the transforms of the groups around repeated inside, so that its bounds
     * cut nothing off that would be visible.
     *
     * @param clip whether the group was reduced to its outlines, which is another composition
     *             than the group as it is painted
     */
    private Integer precomposition(GroupLayer group, boolean clip, Context context,
                                   List<Map<String, Object>> out) {
        String key = group.id() + (clip ? ":clip" : ":alpha");
        String id = compositionIds.get(key);
        if (id == null) {
            id = "comp_" + compositionIds.size();
            compositionIds.put(key, id);
            List<Map<String, Object>> inner = new ArrayList<>();
            Integer parent = null;
            for (Map<String, Object> ks : context.chain()) {
                Map<String, Object> carrier = nullLayer("Parent", parent, ks);
                inner.add(carrier);
                parent = (Integer) carrier.get("ind");
            }
            write(group.withBase(group.base().withVisible(true).withMask(null)),
                    new Context(parent, ONE, 0, duration, true, List.of(), null, context.chain(), false), inner);
            Collections.reverse(inner);
            assets.add(object("id", id, "nm", group.base().name(), "fr", number(frameRate), "layers", inner));
        }
        Map<String, Object> map = object("ddd", 0, "ind", nextIndex++, "ty", 0, "nm", group.base().name(),
                "refId", id, "sr", 1, "ks", transform(Transform.IDENTITY, ONE, null), "ao", 0,
                "w", (int) Math.max(1, Math.round(animation.width())),
                "h", (int) Math.max(1, Math.round(animation.height())),
                "ip", 0, "op", endFrame, "st", 0, "bm", 0, "td", 1);
        out.add(map);
        return (Integer) map.get("ind");
    }

    // ------------------------------------------------------------------ transforms and effects

    private Map<String, Object> transform(Transform transform, Animatable<Double> opacity, MotionPath motion) {
        Animatable<Vec2> position = transform.position();
        Animatable<Double> rotation = transform.rotation();
        if (motion != null) {
            Motion baked = bake(transform, motion);
            position = baked.position();
            rotation = baked.rotation();
        }
        for (Vec2 skew : values(transform.skew())) {
            if (skew.y() != 0) {
                warnings.add("Skew along y is not supported; only the skew along x is exported", "transform",
                        "transforms");
                break;
            }
        }
        // a positive Lottie skew leans the other way
        return object("a", vector(transform.anchor(), 1), "p", vector(position, 1),
                "s", vector(transform.scale(), 100), "r", scalar(rotation, 1), "o", scalar(opacity, 100),
                "sk", scalar(Keyframes.map(transform.skew(), skew -> -skew.x() + 0.0), 1), "sa", constant(0));
    }

    /** Lottie has no motion paths: the position along the path becomes keyframes, one per frame. */
    private Motion bake(Transform transform, MotionPath motion) {
        PathMeasure measure = new PathMeasure(motion.path());
        Animatable<Double> progress = motion.progress();
        boolean orient = motion.orientToPath();
        if (progress.keyframes().size() < 2) {
            double at = progress.at(0);
            return new Motion(Animatable.of(measure.pointAt(at)),
                    orient ? Keyframes.map(transform.rotation(), r -> r + measure.angleAt(at)) : transform.rotation());
        }
        warnings.add("Motion paths are exported as one position keyframe per frame", "layer", "layers");
        double from = progress.keyframes().get(0).time();
        double to = progress.keyframes().get(progress.keyframes().size() - 1).time();
        if (orient) {
            for (Keyframe<Double> keyframe : transform.rotation().keyframes()) {
                from = Math.min(from, keyframe.time());
                to = Math.max(to, keyframe.time());
            }
        }
        long first = (long) Math.floor(from * frameRate);
        long last = Math.max(first + 1, (long) Math.ceil(to * frameRate));
        List<Keyframe<Vec2>> positions = new ArrayList<>();
        List<Keyframe<Double>> rotations = new ArrayList<>();
        double previous = Double.NaN;
        for (long f = first; f <= last; f++) {
            double time = f / frameRate;
            double at = progress.at(time);
            positions.add(Keyframes.key(time, measure.pointAt(at), Easing.LINEAR));
            if (orient) {
                // the direction is an angle in a circle; a keyframe must not turn the long way round
                double angle = measure.angleAt(at);
                while (angle - previous > 180) {
                    angle -= 360;
                }
                while (angle - previous < -180) {
                    angle += 360;
                }
                previous = angle;
                rotations.add(Keyframes.key(time, transform.rotation().at(time) + angle, Easing.LINEAR));
            }
        }
        return new Motion(Keyframes.of(positions), orient ? Keyframes.of(rotations) : transform.rotation());
    }

    private List<Object> effects(List<Effect> effects) {
        List<Object> out = new ArrayList<>();
        for (Effect effect : effects) {
            switch (effect) {
                case Effect.Blur blur -> out.add(object("ty", 29, "nm", "Gaussian Blur", "en", 1, "ef", List.of(
                        value(0, "Blurriness", scalar(blur.radius(), 1 / LottieEffects.BLURRINESS_TO_RADIUS)),
                        value(7, "Blur Dimensions", constant(1)),
                        value(7, "Repeat Edge Pixels", constant(0)))));
                case Effect.DropShadow shadow -> out.add(dropShadow(shadow));
                case Effect.Glow glow -> warnings.add("Glow has no counterpart in Lottie", "effect skipped",
                        "effects skipped");
            }
        }
        return out;
    }

    /** Values in order: colour, opacity (0..255), direction (degrees, 0 = up, clockwise), distance, softness. */
    private Map<String, Object> dropShadow(Effect.DropShadow shadow) {
        Animatable<Double> x = shadow.offsetX();
        Animatable<Double> y = shadow.offsetY();
        // Direction and distance do not move in a straight line when the offsets do: a shadow that
        // crosses the layer would swing around it instead. They are taken frame by frame.
        double[] previous = {Double.NaN};
        Animatable<Double> direction = perFrame(time -> {
            double dx = x.at(time);
            double dy = y.at(time);
            if (dx == 0 && dy == 0) {
                return Double.isNaN(previous[0]) ? 0.0 : previous[0];
            }
            double angle = Math.toDegrees(Math.atan2(dx, -dy)) + 0.0;
            // an angle in a circle: a keyframe must not turn the long way round
            while (angle - previous[0] > 180) {
                angle -= 360;
            }
            while (angle - previous[0] < -180) {
                angle += 360;
            }
            previous[0] = angle;
            return angle;
        }, x, y);
        Animatable<Double> distance = perFrame(time -> Math.hypot(x.at(time), y.at(time)), x, y);
        if (x.isAnimated() || y.isAnimated()) {
            warnings.add("Animated shadow offsets are exported as one keyframe per frame", "effect", "effects");
        }
        for (double spread : values(shadow.spread())) {
            if (spread != 0) {
                warnings.add("The spread of a drop shadow is not supported", "effect", "effects");
                break;
            }
        }
        return object("ty", 25, "nm", "Drop Shadow", "en", 1, "ef", List.of(
                value(2, "Shadow Color", color(shadow.color())),
                value(0, "Opacity", scalar(Keyframes.map(shadow.color(), Rgba::a), 255)),
                value(1, "Direction", scalar(direction, 1)),
                value(0, "Distance", scalar(distance, 1)),
                value(0, "Softness", scalar(shadow.radius(), 1 / LottieEffects.SOFTNESS_TO_RADIUS))));
    }

    private static Map<String, Object> value(int type, String name, Map<String, Object> value) {
        return object("ty", type, "nm", name, "v", value);
    }

    // ------------------------------------------------------------------ shapes

    /**
     * The content of a shape layer. In a Lottie group a style paints what is listed before it,
     * and what is listed first lies on top: the stroke comes before the fill. A trim cuts
     * everything that follows it, but the model trims the stroke only, so a trimmed shape with a
     * fill is written as two groups.
     */
    private List<Object> shapes(ShapeLayer layer) {
        String name = layer.base().name();
        List<Object> geometry = geometry(layer.geometry(), name);
        Map<String, Object> fill = paint(layer.fill(), null);
        Map<String, Object> stroke = layer.stroke() == null ? null : paint(layer.stroke().paint(), layer.stroke());
        Trim trim = stroke == null ? null : layer.trim();
        List<Object> shapes = new ArrayList<>();
        if (trim != null && fill != null) {
            shapes.add(shapeGroup("Stroke", geometry, trim(trim), stroke, null));
            shapes.add(shapeGroup("Fill", geometry, null, null, fill));
        } else {
            shapes.add(shapeGroup(name, geometry, trim == null ? null : trim(trim), stroke, fill));
        }
        return shapes;
    }

    private Map<String, Object> shapeGroup(String name, List<Object> geometry, Map<String, Object> trim,
                                           Map<String, Object> stroke, Map<String, Object> fill) {
        List<Object> items = new ArrayList<>(geometry);
        for (Map<String, Object> item : new ArrayList<>(java.util.Arrays.asList(trim, stroke, fill))) {
            if (item != null) {
                items.add(item);
            }
        }
        items.add(object("ty", "tr", "nm", "Transform", "a", constant(0, 0), "p", constant(0, 0),
                "s", constant(100, 100), "r", constant(0), "o", constant(100), "sk", constant(0),
                "sa", constant(0)));
        return object("ty", "gr", "nm", name, "it", items);
    }

    private Map<String, Object> trim(Trim trim) {
        return object("ty", "tm", "nm", "Trim", "s", scalar(trim.start(), 100), "e", scalar(trim.end(), 100),
                "o", scalar(trim.offset(), 360), "m", 1);
    }

    /**
     * The model's geometries start at their top left corner; Lottie places rectangles and
     * ellipses by their centre. Lines, polygons and stars are written as the path the player
     * draws, which keeps where their first point is.
     */
    private List<Object> geometry(Geometry geometry, String name) {
        return switch (geometry) {
            case Geometry.Rect rect -> List.of(object("ty", "rc", "nm", name, "d", 1,
                    "p", vector(Keyframes.map(rect.size(), size -> size.times(0.5)), 1),
                    "s", vector(rect.size(), 1), "r", scalar(rect.cornerRadius(), 1)));
            case Geometry.Ellipse ellipse -> List.of(object("ty", "el", "nm", name, "d", 1,
                    "p", vector(Keyframes.map(ellipse.size(), size -> size.times(0.5)), 1),
                    "s", vector(ellipse.size(), 1)));
            case Geometry.PathGeometry path -> paths(path.path(), name);
            case Geometry.Line line -> paths(outline(geometry, line.start(), line.end()), name);
            case Geometry.Polygon polygon -> paths(outline(geometry, polygon.radius()), name);
            case Geometry.Star star -> paths(outline(geometry, star.outerRadius(), star.innerRadius()), name);
        };
    }

    private Animatable<PathData> outline(Geometry geometry, Animatable<?>... inputs) {
        return Keyframes.combine(time -> Geometries.toPath(geometry, time), this::sampled, inputs);
    }

    /** One path item per contour: a Lottie path is a single contour. */
    private List<Object> paths(Animatable<PathData> path, String name) {
        List<PathData> all = values(path);
        PathData first = all.get(0);
        Animatable<PathData> source = path;
        for (PathData other : all) {
            if (!sameStructure(first, other)) {
                warnings.add("Paths whose number of points changes cannot be animated in Lottie; the first"
                        + " shape is exported", "path", "paths");
                source = Animatable.of(first);
                break;
            }
        }
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < first.contours().size(); i++) {
            int index = i;
            Animatable<PathData.Contour> contour = Keyframes.map(source, data -> data.contours().get(index));
            items.add(object("ty", "sh", "nm", name, "d", 1, "ks", property(contour,
                    LottieDocumentWriter::bezier, value -> List.of(bezier(value)))));
        }
        return items;
    }

    private static boolean sameStructure(PathData a, PathData b) {
        if (a.contours().size() != b.contours().size()) {
            return false;
        }
        for (int i = 0; i < a.contours().size(); i++) {
            if (a.contours().get(i).vertices().size() != b.contours().get(i).vertices().size()
                    || a.contours().get(i).closed() != b.contours().get(i).closed()) {
                return false;
            }
        }
        return true;
    }

    /** Tangents are relative to their vertex in both formats. */
    private static Map<String, Object> bezier(PathData.Contour contour) {
        List<Object> vertices = new ArrayList<>();
        List<Object> in = new ArrayList<>();
        List<Object> out = new ArrayList<>();
        for (PathData.Vertex vertex : contour.vertices()) {
            vertices.add(point(vertex.point()));
            in.add(point(vertex.in()));
            out.add(point(vertex.out()));
        }
        return object("c", contour.closed(), "v", vertices, "i", in, "o", out);
    }

    private static List<Object> point(Vec2 point) {
        return List.of(number(point.x()), number(point.y()));
    }

    /**
     * A fill, or with {@code stroke} a stroke. The alpha of a colour becomes the opacity of the
     * item, as players ignore the fourth component of a colour.
     *
     * @return {@code null} for no paint
     */
    private Map<String, Object> paint(Animatable<Paint> paint, Stroke stroke) {
        List<Paint> all = values(paint);
        Paint first = all.get(0);
        Animatable<Paint> source = paint;
        for (Paint other : all) {
            if (!sameKind(first, other)) {
                warnings.add("Paints that change their kind or their number of colours cannot be animated in"
                        + " Lottie; the first one is exported", "paint", "paints");
                source = Animatable.of(first);
                all = List.of(first);
                break;
            }
        }
        Map<String, Object> item;
        switch (first) {
            case Paint.None none -> {
                return null;
            }
            case Paint.Solid solid -> {
                Animatable<Rgba> color = Keyframes.map(source, value -> ((Paint.Solid) value).color());
                item = object("ty", stroke == null ? "fl" : "st", "nm", stroke == null ? "Fill" : "Stroke",
                        "c", color(color), "o", scalar(Keyframes.map(color, Rgba::a), 100));
            }
            case Paint.LinearGradient linear -> item = gradient(source, all, stroke != null, false);
            case Paint.RadialGradient radial -> item = gradient(source, all, stroke != null, true);
        }
        if (stroke == null) {
            item.put("r", 1);
            return item;
        }
        item.put("w", scalar(stroke.width(), 1));
        item.put("lc", switch (stroke.cap()) {
            case BUTT -> 1;
            case ROUND -> 2;
            case SQUARE -> 3;
        });
        item.put("lj", switch (stroke.join()) {
            case MITER -> 1;
            case ROUND -> 2;
            case BEVEL -> 3;
        });
        item.put("ml", (int) Math.max(1, Math.round(stroke.miterLimit())));
        if (!stroke.dashArray().isEmpty()) {
            List<Object> dashes = new ArrayList<>();
            for (int i = 0; i < stroke.dashArray().size(); i++) {
                dashes.add(object("n", i % 2 == 0 ? "d" : "g", "nm", i % 2 == 0 ? "dash" : "gap",
                        "v", constant(stroke.dashArray().get(i))));
            }
            dashes.add(object("n", "o", "nm", "offset", "v", scalar(stroke.dashOffset(), 1)));
            item.put("d", dashes);
        }
        return item;
    }

    private static boolean sameKind(Paint a, Paint b) {
        if (a.getClass() != b.getClass()) {
            return false;
        }
        return stops(a).size() == stops(b).size();
    }

    private static List<Paint.Stop> stops(Paint paint) {
        return switch (paint) {
            case Paint.LinearGradient linear -> linear.stops();
            case Paint.RadialGradient radial -> radial.stops();
            default -> List.of();
        };
    }

    /**
     * The points of a gradient are in the coordinates of the layer, which are those of the shape
     * group that is written. Lottie gives a radial gradient by its centre and a point on its
     * edge.
     */
    private Map<String, Object> gradient(Animatable<Paint> paint, List<Paint> all, boolean stroke, boolean radial) {
        boolean alpha = false;
        for (Paint value : all) {
            for (Paint.Stop stop : stops(value)) {
                alpha |= stop.color().a() < 1;
            }
        }
        boolean withAlpha = alpha;
        Animatable<Vec2> start = Keyframes.map(paint, value -> value instanceof Paint.RadialGradient r ? r.center()
                : ((Paint.LinearGradient) value).start());
        Animatable<Vec2> end = Keyframes.map(paint, value -> value instanceof Paint.RadialGradient r
                ? r.center().plus(new Vec2(r.radius(), 0)) : ((Paint.LinearGradient) value).end());
        Map<String, Object> item = object("ty", stroke ? "gs" : "gf", "nm", stroke ? "Gradient Stroke"
                        : "Gradient Fill", "o", constant(100), "s", vector(start, 1), "e", vector(end, 1),
                "t", radial ? 2 : 1,
                "g", object("p", stops(all.get(0)).size(), "k", property(paint, value -> colors(stops(value),
                        withAlpha), value -> colors(stops(value), withAlpha))));
        if (radial) {
            item.put("h", constant(0));
            item.put("a", constant(0));
        }
        return item;
    }

    /** Colour stops as {@code offset, r, g, b}, followed by alpha stops as {@code offset, alpha}. */
    private static List<Object> colors(List<Paint.Stop> stops, boolean alpha) {
        List<Object> values = new ArrayList<>();
        for (Paint.Stop stop : stops) {
            values.add(number(stop.offset()));
            values.add(number(stop.color().r()));
            values.add(number(stop.color().g()));
            values.add(number(stop.color().b()));
        }
        if (alpha) {
            for (Paint.Stop stop : stops) {
                values.add(number(stop.offset()));
                values.add(number(stop.color().a()));
            }
        }
        return values;
    }

    // ------------------------------------------------------------------ text, images, sound

    /**
     * Lottie places text by its baseline, the model by its top: the difference comes out of the
     * anchor, with the ascent estimated as the importer estimates it.
     */
    private void text(TextLayer layer, Context context, List<Map<String, Object>> out) {
        double size = layer.fontSize().at(0);
        if (layer.fontSize().keyframes().size() > 1) {
            warnings.add("Animated font sizes are not supported; the first size is exported", "text", "texts");
        }
        warnings.add("Text is placed approximately: the baseline is estimated without the font", "text", "texts");
        if (layer.fontAssetId() != null) {
            warnings.add("Font files are not exported; the font must be installed where the animation plays",
                    "text", "texts");
        }
        // Lottie text colours have no alpha. A colour that cannot be seen is left out; what alpha
        // remains becomes the opacity of the layer, which fill and stroke share.
        Rgba fill = visible(solid(layer.fill(), "text fill"));
        Rgba stroke = layer.stroke() == null || !(layer.stroke().width().at(0) > 0) ? null
                : visible(solid(layer.stroke().paint(), "text stroke"));
        // text without a colour would be black in a player: it is exported, but cannot be seen
        double alpha = fill != null ? fill.a() : stroke != null ? stroke.a() : 0;
        if (fill != null && stroke != null && Math.abs(fill.a() - stroke.a()) > 1e-9) {
            warnings.add("Text fill and stroke of different transparency are not supported; both get the one of"
                    + " the fill", "text", "texts");
        }
        if (alpha < 1) {
            LayerBase base = layer.base();
            layer = layer.withBase(base.withOpacity(multiply(base.opacity(), Animatable.of(alpha))));
        }
        Vec2 origin = new Vec2(0, LottieLayers.ASCENT * size);
        Transform transform = layer.base().transform();
        Map<String, Object> map = visual(layer,
                transform.withAnchor(Keyframes.map(transform.anchor(), anchor -> anchor.minus(origin))), 5,
                context, out);

        boolean bold = layer.fontWeight() == TextLayer.Weight.BOLD;
        String style = bold && layer.italic() ? "Bold Italic" : bold ? "Bold" : layer.italic() ? "Italic"
                : "Regular";
        String fontName = layer.fontFamily() + "-" + style.replace(" ", "");
        fonts.putIfAbsent(fontName, object("fName", fontName, "fFamily", layer.fontFamily(), "fStyle", style,
                "ascent", number(LottieLayers.ASCENT * 100)));

        Map<String, Object> document = object("s", number(size), "f", fontName,
                "t", layer.text().replace("\r\n", "\r").replace('\n', '\r'),
                "j", switch (layer.alignment()) {
                    case LEFT -> 0;
                    case RIGHT -> 1;
                    case CENTER -> 2;
                }, "tr", 0, "lh", number(size * 1.2), "ls", 0);
        if (fill != null) {
            document.put("fc", List.of(number(fill.r()), number(fill.g()), number(fill.b())));
        }
        if (stroke != null) {
            document.put("sc", List.of(number(stroke.r()), number(stroke.g()), number(stroke.b())));
            document.put("sw", number(layer.stroke().width().at(0)));
        }
        map.put("t", object("d", object("k", List.of(object("s", document, "t", 0))), "p", object(),
                "m", object("g", 1, "a", constant(0, 0)), "a", List.of()));
    }

    private static Rgba visible(Rgba color) {
        return color == null || color.a() <= 0 ? null : color;
    }

    /** The colour text is painted with: Lottie text has one plain colour that does not change. */
    private Rgba solid(Animatable<Paint> paint, String what) {
        List<Paint> all = values(paint);
        if (all.size() > 1) {
            warnings.add("An animated " + what + " is not supported; the first colour is exported", "text",
                    "texts");
        }
        return switch (all.get(0)) {
            case Paint.Solid solid -> solid.color();
            case Paint.None none -> null;
            default -> {
                warnings.add("A gradient as " + what + " is not supported; its first colour is exported", "text",
                        "texts");
                List<Paint.Stop> stops = stops(all.get(0));
                yield stops.isEmpty() ? null : stops.get(0).color();
            }
        };
    }

    /** The asset has the size the layer shows the picture at, which is the size players draw it. */
    private void image(ImageLayer layer, Context context, List<Map<String, Object>> out) {
        byte[] bytes = file.assets().get(layer.assetId());
        Asset asset = assetsById.get(layer.assetId());
        if (bytes == null || asset == null) {
            warnings.add("Images that are missing from the document were skipped", "layer", "layers");
            return;
        }
        int width = (int) Math.max(1, Math.round(layer.width()));
        int height = (int) Math.max(1, Math.round(layer.height()));
        String reference = imageIds.computeIfAbsent(layer.assetId() + ":" + width + "x" + height, key -> {
            String id = "image_" + imageIds.size();
            assets.add(object("id", id, "nm", asset.name(), "w", width, "h", height, "u", "",
                    "p", dataUri(imageType(asset.path()), bytes), "e", 1));
            return id;
        });
        visual(layer, layer.base().transform(), 2, context, out).put("refId", reference);
    }

    /**
     * An audio layer sounds from its in point to its out point; the sound itself starts at the
     * start time {@code st}, which lies the clip offset before the in point. The one volume of
     * the model becomes the level of both channels.
     */
    private void audio(AudioLayer layer, Context context, List<Map<String, Object>> out) {
        byte[] bytes = file.assets().get(layer.assetId());
        Asset asset = assetsById.get(layer.assetId());
        if (bytes == null || asset == null) {
            warnings.add("Sounds that are missing from the document were skipped", "layer", "layers");
            return;
        }
        if (layer.loop()) {
            warnings.add("Looping sounds are not supported; they play once", "layer", "layers");
        }
        if (layer.balance().isAnimated() || layer.balance().at(0) != 0) {
            warnings.add("The balance of a sound is not supported; both channels get the same level", "layer",
                    "layers");
        }
        String reference = soundIds.computeIfAbsent(layer.assetId(), key -> {
            String id = "audio_" + soundIds.size();
            assets.add(object("id", id, "nm", asset.name(), "u", "", "p", dataUri(soundType(asset.path()), bytes),
                    "e", 1));
            return id;
        });
        LayerBase base = layer.base();
        Map<String, Object> map = layer(6, base.name(), context, base.inPoint(), base.outPoint(),
                base.visible() && !layer.muted());
        map.put("refId", reference);
        map.put("st", frame(base.inPoint() - layer.clipOffset()));
        map.put("ks", transform(Transform.IDENTITY, ONE, null));
        map.put("au", object("lv", property(layer.volume(), LottieDocumentWriter::levels,
                LottieDocumentWriter::levels)));
        out.add(map);
    }

    private static List<Object> levels(double volume) {
        double percent = number(Math.clamp(volume, 0.0, 1.0) * 100);
        return List.of(percent, percent);
    }

    private static String dataUri(String type, byte[] bytes) {
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static String extension(String path) {
        int dot = path == null ? -1 : path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String imageType(String path) {
        return switch (extension(path)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            default -> "image/png";
        };
    }

    private static String soundType(String path) {
        return switch (extension(path)) {
            case "wav" -> "audio/wav";
            case "aiff", "aif" -> "audio/aiff";
            case "m4a", "mp4" -> "audio/mp4";
            case "aac" -> "audio/aac";
            default -> "audio/mpeg";
        };
    }

    // ------------------------------------------------------------------ properties and keyframes

    private Map<String, Object> scalar(Animatable<Double> value, double factor) {
        return property(value, v -> number(v * factor), v -> List.of(number(v * factor)));
    }

    private Map<String, Object> vector(Animatable<Vec2> value, double factor) {
        Function<Vec2, Object> list = v -> List.of(number(v.x() * factor), number(v.y() * factor));
        return property(value, list, list);
    }

    /** A colour as {@code [r, g, b, 1]}; its alpha is written as an opacity where there is one. */
    private Map<String, Object> color(Animatable<Rgba> value) {
        Function<Rgba, Object> list = c -> List.of(number(c.r()), number(c.g()), number(c.b()), 1);
        return property(value, list, list);
    }

    private static Map<String, Object> constant(double... values) {
        if (values.length == 1) {
            return object("a", 0, "k", number(values[0]));
        }
        List<Object> list = new ArrayList<>();
        for (double value : values) {
            list.add(number(value));
        }
        return object("a", 0, "k", list);
    }

    /**
     * A Lottie property. A keyframe of the model eases towards the next one, as a Lottie
     * keyframe does with its out handle {@code o} and the in handle {@code i} that belongs to
     * the arrival.
     *
     * @param fixed    the form of a value that does not change
     * @param keyframe the form of a value in a keyframe, which is always a list
     */
    private <T> Map<String, Object> property(Animatable<T> value, Function<T, Object> fixed,
                                             Function<T, Object> keyframe) {
        List<Keyframe<T>> keyframes = value.keyframes();
        if (keyframes.size() < 2) {
            return object("a", 0, "k", fixed.apply(keyframes.isEmpty() ? value.at(0) : keyframes.get(0).value()));
        }
        List<Object> keys = new ArrayList<>();
        for (int i = 0; i < keyframes.size(); i++) {
            Keyframe<T> source = keyframes.get(i);
            Map<String, Object> key = object("t", frame(source.time()), "s", keyframe.apply(source.value()));
            if (i < keyframes.size() - 1) {
                switch (source.easing()) {
                    case Easing.Hold hold -> key.put("h", 1);
                    case Easing.Linear linear -> {
                        key.put("o", handle(THIRD, THIRD));
                        key.put("i", handle(2 * THIRD, 2 * THIRD));
                    }
                    case Easing.CubicBezier curve -> {
                        key.put("o", handle(curve.x1(), curve.y1()));
                        key.put("i", handle(curve.x2(), curve.y2()));
                    }
                }
            }
            keys.add(key);
        }
        return object("a", 1, "k", keys);
    }

    private static Map<String, Object> handle(double x, double y) {
        return object("x", List.of(number(x)), "y", List.of(number(y)));
    }

    private static <T> List<T> values(Animatable<T> value) {
        if (value.keyframes().isEmpty()) {
            return List.of(value.at(0));
        }
        List<T> values = new ArrayList<>();
        for (Keyframe<T> keyframe : value.keyframes()) {
            values.add(keyframe.value());
        }
        return values;
    }

    private Animatable<Double> multiply(Animatable<Double> a, Animatable<Double> b) {
        if (ONE.equals(a)) {
            return b;
        }
        if (ONE.equals(b)) {
            return a;
        }
        if (!a.isAnimated()) {
            double factor = a.at(0);
            return Keyframes.map(b, value -> value * factor);
        }
        if (!b.isAnimated()) {
            double factor = b.at(0);
            return Keyframes.map(a, value -> value * factor);
        }
        // The product of two values that both move is no straight line between their keyframes:
        // two fades from 0 to 1 are at a quarter halfway, not at a half.
        warnings.add("The opacity of a layer and of a group around it that are both animated are exported as one"
                + " keyframe per frame", "layer", "layers");
        return perFrame(time -> a.at(time) * b.at(time), a, b);
    }

    /**
     * A value that depends on its inputs in a way keyframes cannot follow, taken at every frame
     * from the first to the last keyframe of any input and connected by straight lines.
     *
     * @param sampler the value at a time in seconds; it is asked in the order of time
     */
    private <T> Animatable<T> perFrame(java.util.function.DoubleFunction<T> sampler, Animatable<?>... inputs) {
        double from = Double.POSITIVE_INFINITY;
        double to = Double.NEGATIVE_INFINITY;
        for (Animatable<?> input : inputs) {
            if (input.isAnimated()) {
                for (Keyframe<?> keyframe : input.keyframes()) {
                    from = Math.min(from, keyframe.time());
                    to = Math.max(to, keyframe.time());
                }
            }
        }
        if (from > to) {
            return Animatable.of(sampler.apply(0));
        }
        long first = (long) Math.floor(from * frameRate);
        long last = Math.max(first + 1, (long) Math.ceil(to * frameRate));
        List<Keyframe<T>> keyframes = new ArrayList<>();
        for (long f = first; f <= last; f++) {
            double time = f / frameRate;
            keyframes.add(Keyframes.key(time, sampler.apply(time), Easing.LINEAR));
        }
        return Keyframes.of(keyframes);
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        List<T> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private void sampled() {
        warnings.add("Properties animated with different timing are combined by sampling at their key times",
                "value", "values");
    }

    private double frame(double time) {
        return number(time * frameRate);
    }

    /** A layer that lasts to the end of the document lasts to the end of the animation. */
    private double outFrame(double time) {
        return time >= duration - Animatable.TIME_EPSILON ? endFrame : frame(time);
    }

    /** Four decimals are more than any player resolves, and keep the file readable. */
    private static double number(double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return Math.rint(value * 10000) / 10000 + 0.0;
    }

    private static Map<String, Object> object(Object... members) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < members.length; i += 2) {
            map.put((String) members[i], members[i + 1]);
        }
        return map;
    }
}
