package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Easing;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.Keyframe;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.PathData;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.Stroke;
import com.dlsc.animatorfx.model.TextLayer;
import com.dlsc.animatorfx.model.Transform;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.model.io.AnimationFormatException;
import com.dlsc.animatorfx.model.io.Json;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Provider-independent, asset-free AI vector document format, version 1.
 * The schema is a nonrecursive graph of definitions suitable for strict structured output.
 */
public final class AiAnimation {

    private static final int MAX_JSON_LENGTH = 1_000_000;
    private static final int MAX_GROUP_DEPTH = 16;
    private static final int MAX_KEYFRAMES = 4096;
    private static final int MAX_VERTICES = 8192;
    private static final Set<String> LOCAL_CONSTRAINTS =
            Set.of("minimum", "maximum", "minLength", "maxLength", "maxItems");
    private static final Map<String, Object> VALIDATION_SCHEMA = createSchema();
    private static final Map<String, Object> SCHEMA = object(portableSchema(VALIDATION_SCHEMA));

    private AiAnimation() {
    }

    /**
     * Returns the JSON Schema itself, not a provider-specific response-format wrapper.
     * Every object is closed and every declared property is required; nullable fields
     * explicitly accept JSON null. Bounds unsupported by Anthropic are described in prose
     * and enforced locally by {@link #parse(String)}. The returned schema is deeply unmodifiable.
     */
    public static Map<String, Object> schema() {
        return SCHEMA;
    }

    /**
     * Validates syntax, schema, relationships and resource limits before creating an editable document.
     *
     * @throws AnimationFormatException with the offending JSON path, or syntax location, on invalid input
     */
    public static AnimationFile parse(String json) throws AnimationFormatException {
        if (json == null || json.length() > MAX_JSON_LENGTH) {
            throw error("$", "Expected JSON of at most " + MAX_JSON_LENGTH + " characters");
        }
        checkTokens(json);
        Object parsed = Json.parse(json);
        validate(parsed, VALIDATION_SCHEMA, "$");
        return new Conversion(object(parsed)).convert();
    }

    private static Object portableSchema(Object value) {
        if (value instanceof Map<?, ?>) {
            var input = object(value);
            Map<String, Object> output = new LinkedHashMap<>();
            StringBuilder constraints = new StringBuilder();
            for (var entry : input.entrySet()) {
                if (LOCAL_CONSTRAINTS.contains(entry.getKey())) {
                    constraints.append(' ').append(entry.getKey()).append('=').append(entry.getValue()).append(';');
                } else {
                    output.put(entry.getKey(), portableSchema(entry.getValue()));
                }
            }
            if (!constraints.isEmpty()) {
                output.put("description", input.get("description") + " Enforced locally:" + constraints);
            }
            return Map.copyOf(output);
        }
        if (value instanceof List<?>) {
            return list(value).stream().map(AiAnimation::portableSchema).toList();
        }
        return value;
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> defs = new LinkedHashMap<>();
        defs.put("point", vector(-32768, 32768, "Layer-local or parent-space pixel coordinates; x right, y down."));
        defs.put("scale", vector(-100, 100, "Scale multipliers, 1 = unchanged, negative mirrors, 0 collapses."));
        defs.put("skew", vector(-80, 80, "Skew angles in degrees along x and y; 0 = no skew."));
        defs.put("size", vector(0.01, 8192, "Positive local width x and height y in pixels."));
        defs.put("color", obj("RGBA color, each component normalized to 0..1; alpha 0 is transparent.",
                "r", number(0, 1, "Red channel."),
                "g", number(0, 1, "Green channel."),
                "b", number(0, 1, "Blue channel."),
                "a", number(0, 1, "Alpha channel.")));
        defs.put("pointTrack", track(ref("point"), "Pixel coordinates."));
        defs.put("scaleTrack", track(ref("scale"), "Scale multipliers."));
        defs.put("skewTrack", track(ref("skew"), "Skew in degrees."));
        defs.put("rotationTrack", track(number(-36000, 36000, "Clockwise rotation in degrees."), "Rotation."));
        defs.put("opacityTrack", track(number(0, 1, "Opacity, 0 transparent and 1 opaque."), "Opacity."));
        defs.put("colorTrack", track(ref("color"), "Solid RGBA color; alpha 0 means no visible paint."));
        defs.put("widthTrack", track(number(0, 256, "Stroke width in local pixels; 0 hides the stroke."), "Stroke width."));
        defs.put("fontTrack", track(number(1, 512, "Font size in local pixels."), "Font size."));
        defs.put("stroke", obj("Solid stroke with round caps and joins. Null stroke means no outline.",
                "color", ref("colorTrack"), "width", ref("widthTrack")));
        defs.put("transform", obj(
                "Maps layer-local pixels to its parent (artboard for roots): translate(position), clockwise "
                        + "rotate, skew, scale, translate(-anchor). The anchor lands at position.",
                "anchor", ref("pointTrack"), "position", ref("pointTrack"), "scale", ref("scaleTrack"),
                "rotation", ref("rotationTrack"), "skew", ref("skewTrack")));
        defs.put("vertex", obj("Cubic Bezier vertex; incoming/outgoing handles are offsets relative to point. "
                        + "Both handles (0,0) make a corner.",
                "point", ref("point"), "in", ref("point"), "out", ref("point")));
        defs.put("contour", obj("Consecutive vertices join by cubic Beziers: outgoing handle of the first "
                        + "to incoming handle of the next. Closed also joins last to first.",
                "closed", bool("Whether to close this contour."),
                "vertices", array(ref("vertex"), 1, 256, "Ordered local-space vertices, at most 256.")));
        defs.put("content", Map.of(
                "description", "Editable vector content. Choose exactly one supported kind; no assets or URLs.",
                "anyOf", List.of(
                        obj("Rectangle spans (0,0) to size in local pixels.",
                                "kind", enumeration("Content kind.", "rectangle"),
                                "size", ref("size"), "cornerRadius", number(0, 4096, "Corner radius in local pixels."),
                                "fill", ref("colorTrack"), "stroke", nullable(ref("stroke"))),
                        obj("Ellipse inscribed in the local rectangle (0,0) to size.",
                                "kind", enumeration("Content kind.", "ellipse"), "size", ref("size"),
                                "fill", ref("colorTrack"), "stroke", nullable(ref("stroke"))),
                        obj("Editable cubic path with nonzero fill rule; at most 16 contours.",
                                "kind", enumeration("Content kind.", "path"),
                                "contours", array(ref("contour"), 1, 16, "Contours; at most 8192 vertices in the entire document."),
                                "fill", ref("colorTrack"), "stroke", nullable(ref("stroke"))),
                        obj("Editable text whose top is y=0. Alignment positions it about local x=0. "
                                        + "Uses an installed platform font, never an external font asset.",
                                "kind", enumeration("Content kind.", "text"),
                                "text", string(0, 4096, "Literal text, not markup."),
                                "fontFamily", string(1, 128, "Installed font family; use System for a portable default."),
                                "fontSize", ref("fontTrack"), "bold", bool("Bold weight."),
                                "italic", bool("Italic text."),
                                "alignment", enumeration("Alignment about local origin.", "left", "center", "right"),
                                "fill", ref("colorTrack"), "stroke", nullable(ref("stroke"))),
                        obj("Group applies its transform and opacity to children identified by parentId. "
                                        + "Do not nest layers in JSON.",
                                "kind", enumeration("Content kind.", "group")))));
        defs.put("layer", obj("A visible, unlocked editable layer. IDs are unique. All time values are absolute "
                        + "document seconds, including child layers. Require 0 <= inPoint < outPoint <= duration.",
                "id", string(1, 64, "Unique nonblank layer ID."),
                "parentId", nullable(string(1, 64, "ID of a group in this document; null for an artboard root. "
                        + "Forward references allowed. No cycles; at most 16 containing groups.")),
                "name", string(1, 128, "Nonblank display name."),
                "inPoint", number(0, 60, "Visibility start in document seconds."),
                "outPoint", number(0, 60, "Visibility end in document seconds."),
                "transform", ref("transform"), "opacity", ref("opacityTrack"), "content", ref("content")));
        Map<String, Object> root = new LinkedHashMap<>(obj(
                "AnimatorFX AI vector animation version 1. Pixel coordinates: origin top-left, x right, y down. "
                        + "Root layers use artboard coordinates; children use parent-local coordinates. "
                        + "Layers and siblings render bottom to top in array order. No external assets. "
                        + "Limits: 1,000,000 JSON characters, 128 layers, 16 containing groups, 4096 total keyframes, "
                        + "8192 total path vertices. No scripts, expressions, media, masks or effects.",
                "version", Map.of("type", "integer", "enum", List.of(1), "description", "Schema version, exactly 1."),
                "name", string(1, 128, "Nonblank animation name."),
                "width", number(1, 4096, "Artboard width in pixels."),
                "height", number(1, 4096, "Artboard height in pixels."),
                "frameRate", number(1, 120, "Frames per second for snapping/display; keyframe times remain seconds."),
                "duration", number(0.01, 60, "Document duration in seconds."),
                "background", ref("color"),
                "layers", array(ref("layer"), 0, 128, "Flat list of all layers, bottom to top within each parent.")));
        root.put("$defs", Map.copyOf(defs));
        return Map.copyOf(root);
    }

    private static Map<String, Object> track(Map<String, Object> value, String description) {
        return obj(description + " Empty keyframes uses value. Otherwise value is ignored and keyframes "
                        + "drive the property, clamping before the first and after the last key. "
                        + "Keyframes must be increasing, at least 0.000001 seconds apart, within 0..duration.",
                "value", value, "keyframes", array(obj(
                                "A property value at an absolute document time. Easing controls travel to the next key.",
                                "time", number(0, 60, "Absolute document time in seconds."),
                                "value", value,
                                "easing", enumeration("linear interpolates; hold keeps this value until the next key.",
                                        "linear", "hold")),
                        0, 128, "At most 128 keys per property, 4096 in the document; chronological order required."));
    }

    private static Map<String, Object> obj(String description, Object... fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) {
            properties.put((String) fields[i], fields[i + 1]);
        }
        return Map.of("type", "object", "description", description, "properties", Map.copyOf(properties),
                "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }

    private static Map<String, Object> vector(double min, double max, String description) {
        return obj(description, "x", number(min, max, "Horizontal component."),
                "y", number(min, max, "Vertical component."));
    }

    private static Map<String, Object> number(double min, double max, String description) {
        return Map.of("type", "number", "minimum", min, "maximum", max, "description", description);
    }

    private static Map<String, Object> string(int min, int max, String description) {
        return Map.of("type", "string", "minLength", min, "maxLength", max, "description", description);
    }

    private static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> enumeration(String description, String... values) {
        return Map.of("type", "string", "enum", List.of(values), "description", description);
    }

    private static Map<String, Object> array(Map<String, Object> items, int min, int max, String description) {
        return Map.of("type", "array", "items", items, "minItems", min, "maxItems", max, "description", description);
    }

    private static Map<String, Object> nullable(Map<String, Object> schema) {
        return Map.of("description", "Null means absent; otherwise use the specified value.",
                "anyOf", List.of(schema, Map.of("type", "null", "description", "Absent.")));
    }

    private static Map<String, Object> ref(String name) {
        return Map.of("$ref", "#/$defs/" + name);
    }

    private static void validate(Object value, Map<String, Object> schema, String path)
            throws AnimationFormatException {
        if (schema.containsKey("$ref")) {
            String name = ((String) schema.get("$ref")).substring("#/$defs/".length());
            validate(value, object(object(VALIDATION_SCHEMA.get("$defs")).get(name)), path);
            return;
        }
        if (schema.containsKey("anyOf")) {
            var alternatives = list(schema.get("anyOf"));
            if (value instanceof Map<?, ?> data && alternatives.stream().allMatch(branch ->
                    object(branch).get("properties") instanceof Map<?, ?> properties && properties.containsKey("kind"))) {
                for (Object branch : alternatives) {
                    var kinds = list(object(object(object(branch).get("properties")).get("kind")).get("enum"));
                    if (data.get("kind") instanceof String kind && kinds.contains(kind)) {
                        validate(value, object(branch), path);
                        return;
                    }
                }
                throw error(path + ".kind", "Unsupported or missing vector content kind");
            }
            AnimationFormatException mostSpecific = null;
            for (Object branch : alternatives) {
                try {
                    validate(value, object(branch), path);
                    return;
                } catch (AnimationFormatException ex) {
                    if (mostSpecific == null || ex.jsonPath().length() > mostSpecific.jsonPath().length()) {
                        mostSpecific = ex;
                    }
                }
            }
            throw mostSpecific;
        }
        String type = (String) schema.get("type");
        boolean matches = switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "number", "integer" -> value instanceof Number n && Double.isFinite(n.doubleValue())
                    && (!type.equals("integer") || n.doubleValue() == Math.rint(n.doubleValue()));
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> false;
        };
        if (!matches) {
            throw error(path, "Expected " + type);
        }
        if (schema.containsKey("enum")) {
            boolean allowed = list(schema.get("enum")).stream().anyMatch(option ->
                    option instanceof Number n && value instanceof Number actual
                            ? n.doubleValue() == actual.doubleValue() : option.equals(value));
            if (!allowed) {
                throw error(path, "Expected one of " + schema.get("enum"));
            }
        }
        switch (type) {
            case "object" -> {
                var data = object(value);
                var properties = object(schema.get("properties"));
                for (String key : data.keySet()) {
                    if (!properties.containsKey(key)) {
                        throw error(path + "." + key, "Unsupported property");
                    }
                }
                for (var entry : properties.entrySet()) {
                    String child = path + "." + entry.getKey();
                    if (!data.containsKey(entry.getKey())) {
                        throw error(child, "Required property is missing");
                    }
                    validate(data.get(entry.getKey()), object(entry.getValue()), child);
                }
            }
            case "array" -> {
                var data = list(value);
                bounds(data.size(), schema, "minItems", "maxItems", path);
                for (int i = 0; i < data.size(); i++) {
                    validate(data.get(i), object(schema.get("items")), path + "[" + i + "]");
                }
            }
            case "number", "integer" -> bounds(((Number) value).doubleValue(), schema, "minimum", "maximum", path);
            case "string" -> {
                String text = (String) value;
                bounds(text.codePointCount(0, text.length()), schema, "minLength", "maxLength", path);
                if (schema.containsKey("minLength") && ((Number) schema.get("minLength")).intValue() > 0
                        && text.isBlank()) {
                    throw error(path, "Must not be blank");
                }
            }
            default -> {
            }
        }
    }

    private static void bounds(double value, Map<String, Object> schema, String min, String max, String path)
            throws AnimationFormatException {
        if (schema.containsKey(min) && value < ((Number) schema.get(min)).doubleValue()
                || schema.containsKey(max) && value > ((Number) schema.get(max)).doubleValue()) {
            throw error(path, "Value outside allowed range " + schema.get(min) + ".." + schema.get(max));
        }
    }

    /*
     * Json.parse deliberately accepts duplicate names. Check them (including escaped aliases)
     * before parsing, and bound lexical nesting before the parser allocates the value tree.
     * Syntax itself remains the strict JSON parser's responsibility.
     */
    static void checkTokens(String text) throws AnimationFormatException {
        var objects = new ArrayDeque<Set<String>>();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                int start = i++;
                while (i < text.length() && text.charAt(i) != '"') {
                    if (text.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                int next = i + 1;
                while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
                    next++;
                }
                if (next < text.length() && text.charAt(next) == ':' && !objects.isEmpty()) {
                    String key = (String) Json.parse(text.substring(start, i + 1));
                    if (!objects.peek().add(key)) {
                        throw error("$", "Duplicate object property: " + key);
                    }
                }
            } else if (c == '{' || c == '[') {
                if (++depth > 32) {
                    throw error("$", "JSON nesting exceeds 32 levels");
                }
                if (c == '{') {
                    objects.push(new HashSet<>());
                }
            } else if (c == '}' || c == ']') {
                depth--;
                if (c == '}' && !objects.isEmpty()) {
                    objects.pop();
                }
            }
        }
    }

    private static final class Conversion {

        private final Map<String, Object> document;
        private final Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        private final Map<String, String> paths = new LinkedHashMap<>();
        private final Map<String, List<String>> children = new LinkedHashMap<>();
        private final double duration;
        private int keyframeCount;
        private int vertexCount;

        private Conversion(Map<String, Object> document) {
            this.document = document;
            duration = number(document, "duration");
        }

        private AnimationFile convert() throws AnimationFormatException {
            List<Object> layers = list(document.get("layers"));
            for (int i = 0; i < layers.size(); i++) {
                var layer = object(layers.get(i));
                String id = (String) layer.get("id");
                String path = "$.layers[" + i + "]";
                if (byId.putIfAbsent(id, layer) != null) {
                    throw error(path + ".id", "Duplicate layer ID: " + id);
                }
                paths.put(id, path);
                if (number(layer, "inPoint") >= number(layer, "outPoint")
                        || number(layer, "outPoint") > duration) {
                    throw error(path + ".outPoint", "Require 0 <= inPoint < outPoint <= duration");
                }
                children.computeIfAbsent((String) layer.get("parentId"), _ -> new ArrayList<>()).add(id);
            }
            for (String id : byId.keySet()) {
                Set<String> seen = new HashSet<>();
                String current = id;
                int depth = 0;
                while (current != null) {
                    if (!seen.add(current)) {
                        throw error(paths.get(id) + ".parentId", "Cyclic parent reference");
                    }
                    String parent = (String) byId.get(current).get("parentId");
                    if (parent != null) {
                        if (!byId.containsKey(parent)) {
                            throw error(paths.get(current) + ".parentId", "Missing parent group: " + parent);
                        }
                        if (!"group".equals(object(byId.get(parent).get("content")).get("kind"))) {
                            throw error(paths.get(current) + ".parentId", "Parent must be a group: " + parent);
                        }
                        if (++depth > MAX_GROUP_DEPTH) {
                            throw error(paths.get(id) + ".parentId", "More than " + MAX_GROUP_DEPTH + " containing groups");
                        }
                    }
                    current = parent;
                }
            }
            var animation = new Animation(Animation.CURRENT_FORMAT, (String) document.get("name"),
                    number(document, "width"), number(document, "height"), number(document, "frameRate"),
                    duration, color(document.get("background")), List.of(), List.of(), convertChildren(null));
            return new AnimationFile(animation, Map.of());
        }

        private List<Layer> convertChildren(String parent) throws AnimationFormatException {
            List<Layer> result = new ArrayList<>();
            for (String id : children.getOrDefault(parent, List.of())) {
                result.add(convertLayer(id));
            }
            return result;
        }

        private Layer convertLayer(String id) throws AnimationFormatException {
            var layer = byId.get(id);
            String path = paths.get(id);
            var transform = object(layer.get("transform"));
            String tp = path + ".transform";
            Transform tx = new Transform(
                    track(transform.get("anchor"), AiAnimation::vector, tp + ".anchor"),
                    track(transform.get("position"), AiAnimation::vector, tp + ".position"),
                    track(transform.get("scale"), AiAnimation::vector, tp + ".scale"),
                    track(transform.get("rotation"), AiAnimation::scalar, tp + ".rotation"),
                    track(transform.get("skew"), AiAnimation::vector, tp + ".skew"));
            LayerBase base = new LayerBase(id, (String) layer.get("name"), true, false,
                    number(layer, "inPoint"), number(layer, "outPoint"), tx,
                    track(layer.get("opacity"), AiAnimation::scalar, path + ".opacity"), null, List.of(), null);
            var content = object(layer.get("content"));
            String cp = path + ".content";
            String kind = (String) content.get("kind");
            if (kind.equals("group")) {
                return new GroupLayer(base, convertChildren(id));
            }
            Animatable<Paint> fill = track(content.get("fill"), AiAnimation::paint, cp + ".fill");
            Stroke stroke = null;
            if (content.get("stroke") != null) {
                var outline = object(content.get("stroke"));
                stroke = new Stroke(track(outline.get("color"), AiAnimation::paint, cp + ".stroke.color"),
                        track(outline.get("width"), AiAnimation::scalar, cp + ".stroke.width"),
                        Stroke.Cap.ROUND, Stroke.Join.ROUND, 4, List.of(), Animatable.of(0.0));
            }
            if (kind.equals("text")) {
                return new TextLayer(base, (String) content.get("text"), (String) content.get("fontFamily"), null,
                        track(content.get("fontSize"), AiAnimation::scalar, cp + ".fontSize"),
                        (Boolean) content.get("bold") ? TextLayer.Weight.BOLD : TextLayer.Weight.NORMAL,
                        (Boolean) content.get("italic"),
                        TextLayer.Align.valueOf(((String) content.get("alignment")).toUpperCase(Locale.ROOT)), fill, stroke);
            }
            Geometry geometry = switch (kind) {
                case "rectangle" -> new Geometry.Rect(Animatable.of(vector(content.get("size"))),
                        Animatable.of(number(content, "cornerRadius")));
                case "ellipse" -> new Geometry.Ellipse(Animatable.of(vector(content.get("size"))));
                case "path" -> new Geometry.PathGeometry(Animatable.of(path(content, cp)));
                default -> throw error(cp + ".kind", "Unsupported vector content");
            };
            return new ShapeLayer(base, geometry, fill, stroke, null);
        }

        private PathData path(Map<String, Object> content, String path) throws AnimationFormatException {
            var contours = new ArrayList<PathData.Contour>();
            for (Object raw : list(content.get("contours"))) {
                var contour = object(raw);
                var vertices = new ArrayList<PathData.Vertex>();
                for (Object rawVertex : list(contour.get("vertices"))) {
                    if (++vertexCount > MAX_VERTICES) {
                        throw error(path + ".contours", "More than " + MAX_VERTICES + " path vertices in document");
                    }
                    var vertex = object(rawVertex);
                    vertices.add(new PathData.Vertex(vector(vertex.get("point")), vector(vertex.get("in")),
                            vector(vertex.get("out"))));
                }
                contours.add(new PathData.Contour(vertices, (Boolean) contour.get("closed")));
            }
            return new PathData(contours);
        }

        private <T> Animatable<T> track(Object raw, Function<Object, T> convert, String path)
                throws AnimationFormatException {
            var data = object(raw);
            var keys = list(data.get("keyframes"));
            if (keys.isEmpty()) {
                return Animatable.of(convert.apply(data.get("value")));
            }
            var frames = new ArrayList<Keyframe<T>>();
            double previous = -1;
            for (int i = 0; i < keys.size(); i++) {
                String kp = path + ".keyframes[" + i + "]";
                var key = object(keys.get(i));
                double time = number(key, "time");
                if (time > duration || time - previous < Animatable.TIME_EPSILON) {
                    throw error(kp + ".time", "Times must increase by at least 0.000001 seconds within 0..duration");
                }
                if (++keyframeCount > MAX_KEYFRAMES) {
                    throw error(kp, "More than " + MAX_KEYFRAMES + " keyframes in document");
                }
                frames.add(new Keyframe<>(kp, time, convert.apply(key.get("value")),
                        key.get("easing").equals("hold") ? Easing.HOLD : Easing.LINEAR));
                previous = time;
            }
            return Animatable.keyframed(frames);
        }
    }

    private static Paint paint(Object value) {
        return new Paint.Solid(color(value));
    }

    private static Rgba color(Object value) {
        var data = object(value);
        return new Rgba(number(data, "r"), number(data, "g"), number(data, "b"), number(data, "a"));
    }

    private static Vec2 vector(Object value) {
        var data = object(value);
        return new Vec2(number(data, "x"), number(data, "y"));
    }

    private static double scalar(Object value) {
        return ((Number) value).doubleValue();
    }

    private static double number(Map<String, Object> object, String name) {
        return scalar(object.get(name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static AnimationFormatException error(String path, String message) {
        return new AnimationFormatException(message, path);
    }
}
