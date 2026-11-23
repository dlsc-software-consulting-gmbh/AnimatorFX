package com.dlsc.animatorfx.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Uniform access to the animatable properties of a layer by key, for the timeline, the inspector
 * and keyframe commands. A property exists on a layer only when the member that carries it does:
 * {@code stroke.width} needs a stroke, {@code effects[1].radius} a second effect with a radius.
 *
 * <p>Keys: {@code transform.anchor|position|scale|rotation|skew}, {@code opacity},
 * {@code geometry.size|cornerRadius|start|end|radius|outerRadius|innerRadius|path},
 * {@code fontSize}, {@code fill}, {@code stroke.paint|width|dashOffset},
 * {@code trim.start|end|offset}, {@code motionPath.progress} and
 * {@code effects[i].radius|color|offsetX|offsetY|spread|level}. An audio layer has only
 * {@code volume} and {@code balance}: the visual members of its base have no effect.
 */
public final class LayerProperties {

    private LayerProperties() {
    }

    /**
     * The properties that exist on the layer, in display order: transform, opacity, the
     * properties of the layer type, stroke, trim, motion path, effects. For an audio layer:
     * volume, balance.
     */
    public static List<PropertyRef> list(Layer layer) {
        return entries(layer).stream().map(Entry::ref).toList();
    }

    /**
     * @throws IllegalArgumentException if the layer has no property with that key
     */
    public static Animatable<?> get(Layer layer, String key) {
        return entry(layer, key).ref().value();
    }

    /**
     * A copy of the layer, of the same type, with the property replaced.
     *
     * @throws IllegalArgumentException if the layer has no property with that key, or if the value
     *                                  is not of the property's type
     */
    public static Layer set(Layer layer, String key, Animatable<?> value) {
        Entry entry = entry(layer, key);
        Class<?> expected = valueClass(entry.ref().type());
        if (value instanceof Animatable.Static<?> s) {
            requireType(key, expected, s.value());
        } else {
            for (Keyframe<?> keyframe : value.keyframes()) {
                requireType(key, expected, keyframe.value());
            }
        }
        return entry.setter().apply(value);
    }

    private record Entry(PropertyRef ref, Function<Animatable<?>, Layer> setter) {
    }

    private static Entry entry(Layer layer, String key) {
        for (Entry entry : entries(layer)) {
            if (entry.ref().key().equals(key)) {
                return entry;
            }
        }
        throw new IllegalArgumentException("Layer '" + layer.name() + "' has no property '" + key + "'");
    }

    private static Class<?> valueClass(PropertyRef.ValueType type) {
        return switch (type) {
            case NUMBER -> Double.class;
            case VEC2 -> Vec2.class;
            case COLOR -> Rgba.class;
            case PAINT -> Paint.class;
            case PATH -> PathData.class;
        };
    }

    private static void requireType(String key, Class<?> expected, Object value) {
        if (!expected.isInstance(value)) {
            throw new IllegalArgumentException("Property '" + key + "' takes " + expected.getSimpleName()
                    + " values but got " + value.getClass().getSimpleName());
        }
    }

    private static List<Entry> entries(Layer layer) {
        Entries out = new Entries();
        if (layer instanceof AudioLayer audio) {
            out.number("volume", "Volume", audio.volume(), audio::withVolume);
            out.number("balance", "Balance", audio.balance(), audio::withBalance);
            return out.list;
        }
        LayerBase base = layer.base();
        Transform t = base.transform();
        Function<Transform, Layer> withTransform = v -> layer.withBase(base.withTransform(v));
        out.vec2("transform.anchor", "Anchor", t.anchor(), v -> withTransform.apply(t.withAnchor(v)));
        out.vec2("transform.position", "Position", t.position(), v -> withTransform.apply(t.withPosition(v)));
        out.vec2("transform.scale", "Scale", t.scale(), v -> withTransform.apply(t.withScale(v)));
        out.number("transform.rotation", "Rotation", t.rotation(), v -> withTransform.apply(t.withRotation(v)));
        out.vec2("transform.skew", "Skew", t.skew(), v -> withTransform.apply(t.withSkew(v)));
        out.number("opacity", "Opacity", base.opacity(), v -> layer.withBase(base.withOpacity(v)));

        switch (layer) {
            case ShapeLayer shape -> {
                geometry(out, shape);
                out.paint("fill", "Fill", shape.fill(), shape::withFill);
                stroke(out, shape.stroke(), shape::withStroke);
                Trim trim = shape.trim();
                if (trim != null) {
                    out.number("trim.start", "Trim Start", trim.start(), v -> shape.withTrim(trim.withStart(v)));
                    out.number("trim.end", "Trim End", trim.end(), v -> shape.withTrim(trim.withEnd(v)));
                    out.number("trim.offset", "Trim Offset", trim.offset(), v -> shape.withTrim(trim.withOffset(v)));
                }
            }
            case TextLayer text -> {
                out.number("fontSize", "Font Size", text.fontSize(), text::withFontSize);
                out.paint("fill", "Fill", text.fill(), text::withFill);
                stroke(out, text.stroke(), text::withStroke);
            }
            case ImageLayer image -> {
            }
            case VideoLayer video -> out.number("volume", "Volume", video.volume(), video::withVolume);
            case GroupLayer group -> {
            }
            case SlotLayer slot -> {
            }
            case AudioLayer audio -> throw new AssertionError(audio);
        }

        MotionPath motionPath = base.motionPath();
        if (motionPath != null) {
            out.number("motionPath.progress", "Motion Path Progress", motionPath.progress(),
                    v -> layer.withBase(base.withMotionPath(motionPath.withProgress(v))));
        }
        for (int i = 0; i < base.effects().size(); i++) {
            effect(out, layer, i);
        }
        return out.list;
    }

    private static void geometry(Entries out, ShapeLayer shape) {
        switch (shape.geometry()) {
            case Geometry.Rect g -> {
                out.vec2("geometry.size", "Size", g.size(),
                        v -> shape.withGeometry(new Geometry.Rect(v, g.cornerRadius())));
                out.number("geometry.cornerRadius", "Corner Radius", g.cornerRadius(),
                        v -> shape.withGeometry(new Geometry.Rect(g.size(), v)));
            }
            case Geometry.Ellipse g -> out.vec2("geometry.size", "Size", g.size(),
                    v -> shape.withGeometry(new Geometry.Ellipse(v)));
            case Geometry.Line g -> {
                out.vec2("geometry.start", "Start Point", g.start(),
                        v -> shape.withGeometry(new Geometry.Line(v, g.end())));
                out.vec2("geometry.end", "End Point", g.end(),
                        v -> shape.withGeometry(new Geometry.Line(g.start(), v)));
            }
            case Geometry.Polygon g -> out.number("geometry.radius", "Radius", g.radius(),
                    v -> shape.withGeometry(new Geometry.Polygon(g.sides(), v)));
            case Geometry.Star g -> {
                out.number("geometry.outerRadius", "Outer Radius", g.outerRadius(),
                        v -> shape.withGeometry(new Geometry.Star(g.points(), v, g.innerRadius())));
                out.number("geometry.innerRadius", "Inner Radius", g.innerRadius(),
                        v -> shape.withGeometry(new Geometry.Star(g.points(), g.outerRadius(), v)));
            }
            case Geometry.PathGeometry g -> out.add("geometry.path", "Path", PropertyRef.ValueType.PATH, g.path(),
                    v -> shape.withGeometry(new Geometry.PathGeometry(v)));
        }
    }

    private static void stroke(Entries out, Stroke stroke, Function<Stroke, Layer> withStroke) {
        if (stroke == null) {
            return;
        }
        out.paint("stroke.paint", "Stroke", stroke.paint(), v -> withStroke.apply(stroke.withPaint(v)));
        out.number("stroke.width", "Stroke Width", stroke.width(), v -> withStroke.apply(stroke.withWidth(v)));
        out.number("stroke.dashOffset", "Stroke Dash Offset", stroke.dashOffset(),
                v -> withStroke.apply(stroke.withDashOffset(v)));
    }

    private static void effect(Entries out, Layer layer, int index) {
        LayerBase base = layer.base();
        String prefix = "effects[" + index + "].";
        Function<Effect, Layer> withEffect = effect -> {
            List<Effect> effects = new ArrayList<>(base.effects());
            effects.set(index, effect);
            return layer.withBase(base.withEffects(effects));
        };
        switch (base.effects().get(index)) {
            case Effect.Blur e -> out.number(prefix + "radius", "Blur Radius", e.radius(),
                    v -> withEffect.apply(new Effect.Blur(v)));
            case Effect.DropShadow e -> {
                out.add(prefix + "color", "Shadow Color", PropertyRef.ValueType.COLOR, e.color(),
                        v -> withEffect.apply(
                                new Effect.DropShadow(v, e.radius(), e.offsetX(), e.offsetY(), e.spread())));
                out.number(prefix + "radius", "Shadow Radius", e.radius(), v -> withEffect.apply(
                        new Effect.DropShadow(e.color(), v, e.offsetX(), e.offsetY(), e.spread())));
                out.number(prefix + "offsetX", "Shadow Offset X", e.offsetX(), v -> withEffect.apply(
                        new Effect.DropShadow(e.color(), e.radius(), v, e.offsetY(), e.spread())));
                out.number(prefix + "offsetY", "Shadow Offset Y", e.offsetY(), v -> withEffect.apply(
                        new Effect.DropShadow(e.color(), e.radius(), e.offsetX(), v, e.spread())));
                out.number(prefix + "spread", "Shadow Spread", e.spread(), v -> withEffect.apply(
                        new Effect.DropShadow(e.color(), e.radius(), e.offsetX(), e.offsetY(), v)));
            }
            case Effect.Glow e -> out.number(prefix + "level", "Glow Level", e.level(),
                    v -> withEffect.apply(new Effect.Glow(v)));
        }
    }

    private static final class Entries {

        private final List<Entry> list = new ArrayList<>();

        // The cast is safe because set() checks the values against the entry's type first.
        @SuppressWarnings("unchecked")
        <T> void add(String key, String label, PropertyRef.ValueType type, Animatable<T> value,
                     Function<Animatable<T>, Layer> setter) {
            list.add(new Entry(new PropertyRef(key, label, type, value), v -> setter.apply((Animatable<T>) v)));
        }

        void number(String key, String label, Animatable<Double> value, Function<Animatable<Double>, Layer> setter) {
            add(key, label, PropertyRef.ValueType.NUMBER, value, setter);
        }

        void vec2(String key, String label, Animatable<Vec2> value, Function<Animatable<Vec2>, Layer> setter) {
            add(key, label, PropertyRef.ValueType.VEC2, value, setter);
        }

        void paint(String key, String label, Animatable<Paint> value, Function<Animatable<Paint>, Layer> setter) {
            add(key, label, PropertyRef.ValueType.PAINT, value, setter);
        }
    }
}
