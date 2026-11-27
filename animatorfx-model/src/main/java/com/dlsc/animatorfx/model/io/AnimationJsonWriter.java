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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Turns a document into its JSON tree. Members equal to the defaults of {@link Defaults} are left out.
 */
final class AnimationJsonWriter {

    private AnimationJsonWriter() {
    }

    static Map<String, Object> document(Animation a) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("formatVersion", (double) Animation.CURRENT_FORMAT);
        put(json, "name", a.name(), Defaults.DOCUMENT_NAME);
        json.put("width", a.width());
        json.put("height", a.height());
        put(json, "frameRate", a.frameRate(), Defaults.FRAME_RATE);
        json.put("duration", a.duration());
        put(json, "background", a.background(), Defaults.BACKGROUND, Rgba::toHex);
        if (!a.markers().isEmpty()) {
            json.put("markers", list(a.markers(), AnimationJsonWriter::marker));
        }
        if (!a.assets().isEmpty()) {
            json.put("assets", list(a.assets(), AnimationJsonWriter::asset));
        }
        json.put("layers", list(a.layers(), layer -> layer(layer, a.duration(), 1)));
        if (!a.cues().isEmpty()) {
            json.put("cues", list(a.cues(), AnimationJsonWriter::cue));
        }
        if (!a.stateMachines().isEmpty()) {
            json.put("stateMachines", list(a.stateMachines(), AnimationJsonWriter::stateMachine));
        }
        return json;
    }

    private static Map<String, Object> stateMachine(StateMachine machine) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", machine.name());
        json.put("initialState", machine.initialState());
        json.put("states", list(machine.states(), state -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", state.name());
            value.put("marker", state.marker());
            put(value, "loop", state.loop(), false);
            value.put("x", state.x());
            value.put("y", state.y());
            return value;
        }));
        json.put("transitions", list(machine.transitions(), transition -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("source", transition.source());
            value.put("target", transition.target());
            if (transition.event() != null) {
                value.put("event", transition.event());
            }
            return value;
        }));
        return json;
    }

    private static Map<String, Object> marker(Marker marker) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", marker.name());
        json.put("start", marker.start());
        json.put("end", marker.end());
        return json;
    }

    private static Map<String, Object> cue(SoundCue cue) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", cue.id());
        put(json, "name", cue.name(), "");
        json.put("t", cue.time());
        json.put("asset", cue.assetId());
        put(json, "volume", cue.volume(), Defaults.CUE_VOLUME);
        put(json, "muted", cue.muted(), false);
        return json;
    }

    private static Map<String, Object> asset(Asset asset) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", asset.id());
        json.put("kind", name(asset.kind()));
        json.put("path", asset.path());
        put(json, "name", asset.name(), "");
        return json;
    }

    /**
     * @param depth the nesting of the layer; 1 for a top-level layer
     * @throws IllegalArgumentException if the layer is nested deeper than the format allows
     */
    private static Map<String, Object> layer(Layer layer, double duration, int depth) {
        if (depth > AnimationJson.MAX_LAYER_DEPTH) {
            throw new IllegalArgumentException("Layers are nested deeper than " + AnimationJson.MAX_LAYER_DEPTH
                    + " levels: " + layer.name());
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("type", switch (layer) {
            case ShapeLayer s -> "shape";
            case TextLayer t -> "text";
            case ImageLayer i -> "image";
            case GroupLayer g -> "group";
            case SlotLayer s -> "slot";
            case AudioLayer a -> "audio";
            case VideoLayer v -> "video";
        });
        base(json, layer.base(), duration);
        switch (layer) {
            case ShapeLayer shape -> {
                json.put("geometry", geometry(shape.geometry()));
                put(json, "fill", shape.fill(), Defaults.FILL, fill -> animatable(fill, AnimationJsonWriter::paint));
                if (shape.stroke() != null) {
                    json.put("stroke", stroke(shape.stroke()));
                }
                if (shape.trim() != null) {
                    json.put("trim", trim(shape.trim()));
                }
            }
            case TextLayer text -> {
                json.put("text", text.text());
                put(json, "fontFamily", text.fontFamily(), Defaults.FONT_FAMILY);
                put(json, "fontAsset", text.fontAssetId(), null);
                put(json, "fontSize", text.fontSize(), Defaults.FONT_SIZE, AnimationJsonWriter::number);
                put(json, "fontWeight", text.fontWeight(), Defaults.FONT_WEIGHT, AnimationJsonWriter::name);
                put(json, "italic", text.italic(), false);
                put(json, "alignment", text.alignment(), Defaults.ALIGNMENT, AnimationJsonWriter::name);
                put(json, "fill", text.fill(), Defaults.FILL, fill -> animatable(fill, AnimationJsonWriter::paint));
                if (text.stroke() != null) {
                    json.put("stroke", stroke(text.stroke()));
                }
            }
            case ImageLayer image -> {
                json.put("asset", image.assetId());
                json.put("width", image.width());
                json.put("height", image.height());
            }
            case VideoLayer video -> {
                json.put("asset", video.assetId());
                json.put("width", video.width());
                json.put("height", video.height());
                put(json, "clipOffset", video.clipOffset(), 0.0);
                put(json, "volume", video.volume(), Defaults.VOLUME, AnimationJsonWriter::number);
                put(json, "loop", video.loop(), false);
                put(json, "muted", video.muted(), false);
            }
            case GroupLayer group -> json.put("children", list(group.children(), child -> layer(child, duration, depth + 1)));
            case SlotLayer slot -> {
                json.put("slotName", slot.slotName());
                json.put("width", slot.width());
                json.put("height", slot.height());
                put(json, "restTransform", slot.restTransform(), slot.base().transform(),
                        AnimationJsonWriter::transform);
            }
            case AudioLayer audio -> {
                json.put("asset", audio.assetId());
                put(json, "clipOffset", audio.clipOffset(), 0.0);
                put(json, "volume", audio.volume(), Defaults.VOLUME, AnimationJsonWriter::number);
                put(json, "balance", audio.balance(), Defaults.ZERO, AnimationJsonWriter::number);
                put(json, "loop", audio.loop(), false);
                put(json, "muted", audio.muted(), false);
            }
        }
        return json;
    }

    private static void base(Map<String, Object> json, LayerBase base, double duration) {
        json.put("id", base.id());
        put(json, "name", base.name(), "");
        put(json, "exportName", base.exportName(), "");
        put(json, "visible", base.visible(), true);
        put(json, "locked", base.locked(), false);
        put(json, "inPoint", base.inPoint(), 0.0);
        put(json, "outPoint", base.outPoint(), duration);
        put(json, "transform", base.transform(), Transform.IDENTITY, AnimationJsonWriter::transform);
        put(json, "opacity", base.opacity(), Defaults.OPACITY, AnimationJsonWriter::number);
        if (base.mask() != null) {
            Map<String, Object> mask = new LinkedHashMap<>();
            mask.put("layer", base.mask().layerId());
            mask.put("mode", name(base.mask().mode()));
            json.put("mask", mask);
        }
        if (!base.effects().isEmpty()) {
            json.put("effects", list(base.effects(), AnimationJsonWriter::effect));
        }
        if (base.motionPath() != null) {
            json.put("motionPath", motionPath(base.motionPath()));
        }
    }

    private static Map<String, Object> transform(Transform transform) {
        Transform identity = Transform.IDENTITY;
        Map<String, Object> json = new LinkedHashMap<>();
        put(json, "anchor", transform.anchor(), identity.anchor(), AnimationJsonWriter::vec2);
        put(json, "position", transform.position(), identity.position(), AnimationJsonWriter::vec2);
        put(json, "scale", transform.scale(), identity.scale(), AnimationJsonWriter::vec2);
        put(json, "rotation", transform.rotation(), identity.rotation(), AnimationJsonWriter::number);
        put(json, "skew", transform.skew(), identity.skew(), AnimationJsonWriter::vec2);
        return json;
    }

    private static Map<String, Object> effect(Effect effect) {
        Map<String, Object> json = new LinkedHashMap<>();
        switch (effect) {
            case Effect.Blur blur -> {
                json.put("type", "blur");
                json.put("radius", number(blur.radius()));
            }
            case Effect.DropShadow shadow -> {
                json.put("type", "dropShadow");
                put(json, "color", shadow.color(), Defaults.SHADOW_COLOR, AnimationJsonWriter::color);
                json.put("radius", number(shadow.radius()));
                put(json, "offsetX", shadow.offsetX(), Defaults.ZERO, AnimationJsonWriter::number);
                put(json, "offsetY", shadow.offsetY(), Defaults.ZERO, AnimationJsonWriter::number);
                put(json, "spread", shadow.spread(), Defaults.ZERO, AnimationJsonWriter::number);
            }
            case Effect.Glow glow -> {
                json.put("type", "glow");
                json.put("level", number(glow.level()));
            }
        }
        return json;
    }

    private static Map<String, Object> motionPath(MotionPath motionPath) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("path", path(motionPath.path()));
        json.put("progress", number(motionPath.progress()));
        put(json, "orientToPath", motionPath.orientToPath(), false);
        return json;
    }

    private static Map<String, Object> geometry(Geometry geometry) {
        Map<String, Object> json = new LinkedHashMap<>();
        switch (geometry) {
            case Geometry.Rect rect -> {
                json.put("type", "rect");
                json.put("size", vec2(rect.size()));
                put(json, "cornerRadius", rect.cornerRadius(), Defaults.ZERO, AnimationJsonWriter::number);
            }
            case Geometry.Ellipse ellipse -> {
                json.put("type", "ellipse");
                json.put("size", vec2(ellipse.size()));
            }
            case Geometry.Line line -> {
                json.put("type", "line");
                json.put("start", vec2(line.start()));
                json.put("end", vec2(line.end()));
            }
            case Geometry.Polygon polygon -> {
                json.put("type", "polygon");
                json.put("sides", (double) polygon.sides());
                json.put("radius", number(polygon.radius()));
            }
            case Geometry.Star star -> {
                json.put("type", "star");
                json.put("points", (double) star.points());
                json.put("outerRadius", number(star.outerRadius()));
                json.put("innerRadius", number(star.innerRadius()));
            }
            case Geometry.PathGeometry path -> {
                json.put("type", "path");
                json.put("path", animatable(path.path(), AnimationJsonWriter::path));
            }
        }
        return json;
    }

    private static Map<String, Object> stroke(Stroke stroke) {
        Map<String, Object> json = new LinkedHashMap<>();
        put(json, "paint", stroke.paint(), Defaults.STROKE_PAINT, paint -> animatable(paint, AnimationJsonWriter::paint));
        put(json, "width", stroke.width(), Defaults.STROKE_WIDTH, AnimationJsonWriter::number);
        put(json, "cap", stroke.cap(), Defaults.CAP, AnimationJsonWriter::name);
        put(json, "join", stroke.join(), Defaults.JOIN, AnimationJsonWriter::name);
        put(json, "miterLimit", stroke.miterLimit(), Defaults.MITER_LIMIT);
        if (!stroke.dashArray().isEmpty()) {
            json.put("dashArray", new ArrayList<Object>(stroke.dashArray()));
        }
        put(json, "dashOffset", stroke.dashOffset(), Defaults.ZERO, AnimationJsonWriter::number);
        return json;
    }

    private static Map<String, Object> trim(Trim trim) {
        Map<String, Object> json = new LinkedHashMap<>();
        put(json, "start", trim.start(), Defaults.ZERO, AnimationJsonWriter::number);
        put(json, "end", trim.end(), Defaults.TRIM_END, AnimationJsonWriter::number);
        put(json, "offset", trim.offset(), Defaults.ZERO, AnimationJsonWriter::number);
        return json;
    }

    private static Map<String, Object> paint(Paint paint) {
        Map<String, Object> json = new LinkedHashMap<>();
        switch (paint) {
            case Paint.None none -> json.put("type", "none");
            case Paint.Solid solid -> {
                json.put("type", "solid");
                json.put("color", solid.color().toHex());
            }
            case Paint.LinearGradient linear -> {
                json.put("type", "linear");
                json.put("start", vec2(linear.start()));
                json.put("end", vec2(linear.end()));
                json.put("stops", list(linear.stops(), AnimationJsonWriter::stop));
            }
            case Paint.RadialGradient radial -> {
                json.put("type", "radial");
                json.put("center", vec2(radial.center()));
                json.put("radius", radial.radius());
                json.put("stops", list(radial.stops(), AnimationJsonWriter::stop));
            }
        }
        return json;
    }

    private static Map<String, Object> stop(Paint.Stop stop) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("offset", stop.offset());
        json.put("color", stop.color().toHex());
        return json;
    }

    private static Map<String, Object> path(PathData path) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("contours", list(path.contours(), contour -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("closed", contour.closed());
            c.put("v", list(contour.vertices(), v -> List.<Object>of(v.point().x(), v.point().y(), v.in().x(),
                    v.in().y(), v.out().x(), v.out().y())));
            return c;
        }));
        return json;
    }

    private static <T> Object animatable(Animatable<T> animatable, Function<T, Object> valueWriter) {
        if (animatable instanceof Animatable.Static<T> fixed) {
            return valueWriter.apply(fixed.value());
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("keyframes", list(animatable.keyframes(), keyframe -> keyframe(keyframe, valueWriter)));
        return json;
    }

    private static <T> Map<String, Object> keyframe(Keyframe<T> keyframe, Function<T, Object> valueWriter) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", keyframe.id());
        json.put("t", keyframe.time());
        json.put("v", valueWriter.apply(keyframe.value()));
        switch (keyframe.easing()) {
            case Easing.Linear linear -> {
            }
            case Easing.Hold hold -> json.put("e", "hold");
            case Easing.CubicBezier c -> json.put("e", List.<Object>of(c.x1(), c.y1(), c.x2(), c.y2()));
        }
        return json;
    }

    private static Object number(Animatable<Double> animatable) {
        return animatable(animatable, value -> value);
    }

    private static Object vec2(Animatable<Vec2> animatable) {
        return animatable(animatable, AnimationJsonWriter::vec2);
    }

    private static Object color(Animatable<Rgba> animatable) {
        return animatable(animatable, Rgba::toHex);
    }

    private static Object vec2(Vec2 v) {
        return List.<Object>of(v.x(), v.y());
    }

    private static String name(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }

    private static <T> List<Object> list(List<T> items, Function<T, Object> writer) {
        List<Object> list = new ArrayList<>(items.size());
        for (T item : items) {
            list.add(writer.apply(item));
        }
        return list;
    }

    /** Adds the member unless it has its default value. */
    private static void put(Map<String, Object> json, String key, Object value, Object fallback) {
        put(json, key, value, fallback, v -> v);
    }

    private static <T> void put(Map<String, Object> json, String key, T value, T fallback, Function<T, Object> writer) {
        if (value != null && !value.equals(fallback)) {
            json.put(key, writer.apply(value));
        }
    }
}
